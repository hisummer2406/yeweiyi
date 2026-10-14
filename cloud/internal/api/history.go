package api

import (
	"errors"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// History limits.
//
// historyMaxBuckets mirrors ThingsBoard's own DATABASE_TS_MAX_INTERVALS (default 700), which rejects
// an aggregated read with more intervals than that ("Number of intervals is to high"; measured on CE
// 4.2.1.1: 1728 and 1980 both 400). Refusing here gives the caller a message in our terms instead of
// a platform error, and means a deployment that lowered the platform limit fails loudly in testing
// rather than in production. It also bounds the response: 700 buckets × 9 fields ≈ 25 KB.
// In practice: 1m ≤ 11h40m, 5m ≤ 58h20m, 15m and 1h cover the full 7 days.
const historyMaxBuckets = 700

// defaultTelemetryRetention is how long ThingsBoard keeps raw telemetry when Deps.TelemetryRetention
// is unset (issue #23).
const defaultTelemetryRetention = 7 * 24 * time.Hour

// historyHourMs is the one interval the hourly rollup (issue #30) can answer.
const historyHourMs = int64(time.Hour / time.Millisecond)

// historyIntervals are the bucket sizes offered: minute-level and hour-level, per issue #3. A fixed
// set rather than a free-form duration so the client cannot ask for 1ms buckets, and so the
// bucket-count bound above is the only sizing rule to reason about.
var historyIntervals = map[string]time.Duration{
	"1m":  time.Minute,
	"5m":  5 * time.Minute,
	"15m": 15 * time.Minute,
	"1h":  time.Hour,
}

// historyFields are the numeric per-tank telemetry fields that can be aggregated. oilType and
// alarmState are strings and probeConnected a bool: averaging them is meaningless, and ThingsBoard
// would return nothing or garbage rather than an error.
var historyFields = func() map[string]bool {
	m := make(map[string]bool, len(protocol.AggregatableTankFields))
	for _, f := range protocol.AggregatableTankFields {
		m[f] = true
	}
	return m
}()

var historyAggs = map[string]string{"avg": "AVG", "min": "MIN", "max": "MAX"}

type historyPoint struct {
	// Time is the START of the bucket. ThingsBoard reports the midpoint; shifting it back means a
	// client can plot or join buckets without knowing that quirk.
	Time  time.Time `json:"time"`
	Value float64   `json:"value"`
}

// telemetryHistoryHandler is GET /api/devices/:device/telemetry/history
// ?tank=1&fields=oilLevelMm,tempC&interval=1m|5m|15m|1h&agg=avg|min|max&beginTime=&endTime=
//
// Within the raw retention (Deps.TelemetryRetention, default 7 days) aggregation happens inside
// ThingsBoard at query time and nothing is copied into the business database (CLAUDE.md constraint
// 5). Older hours are read from the hourly rollup table `telemetry_hourly` (issue #30), which
// internal/rollup fills from ThingsBoard's own aggregation, so the two agree on the hours they share.
// A window crossing the boundary is stitched: the older part from the table, the rest from
// ThingsBoard. Raw data is gone beyond the retention, so a sub-hour interval cannot be answered
// there: the request is refused with the earliest instant it can be, instead of a 200 with an empty
// series that looks like "no data". Buckets with no samples are absent from a series, not zero —
// a device that was offline must not read as a flat line at 0.
func telemetryHistoryHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		// Authorization before parameter validation, so an unauthorized caller gets the same 403
		// whatever they put in the query string.
		// authAt is the DATABASE clock at the decision (see the cap on endMs below).
		authAt, ok := requireDeviceOwnershipAt(c, deps, device)
		if !ok {
			return
		}
		bad := func(msg string) { c.JSON(http.StatusBadRequest, gin.H{"error": msg}) }

		tank, err := strconv.Atoi(c.Query("tank"))
		if err != nil || tank < 1 || tank > protocol.MaxTankCount {
			bad("tank must be an integer between 1 and " + strconv.Itoa(protocol.MaxTankCount))
			return
		}

		// An explicitly requested tank the caller may not view is refused, whether or not the device has
		// such a tank (cloud#63): the answer is the same either way.
		if !requireTankAccess(c, deps, device, tank) {
			return
		}

		var fields []string
		seen := map[string]bool{}
		for _, f := range strings.Split(c.Query("fields"), ",") {
			f = strings.TrimSpace(f)
			if f == "" || seen[f] {
				continue
			}
			if !historyFields[f] {
				bad("unsupported field " + strconv.Quote(f) + "; numeric tank fields only: " + historyFieldList())
				return
			}
			seen[f] = true
			fields = append(fields, f)
		}
		if len(fields) == 0 {
			bad("fields is required: " + historyFieldList())
			return
		}

		interval, ok := historyIntervals[c.Query("interval")]
		if !ok {
			bad("interval must be one of 1m, 5m, 15m, 1h")
			return
		}
		aggName := c.DefaultQuery("agg", "avg")
		agg, ok := historyAggs[aggName]
		if !ok {
			bad("agg must be one of avg, min, max")
			return
		}

		end := time.Now().UTC()
		if v := c.Query("endTime"); v != "" {
			if end, err = time.Parse(time.RFC3339, v); err != nil {
				bad("endTime must be RFC3339")
				return
			}
		}
		begin := end.Add(-24 * time.Hour)
		if v := c.Query("beginTime"); v != "" {
			if begin, err = time.Parse(time.RFC3339, v); err != nil {
				bad("beginTime must be RFC3339")
				return
			}
		}
		if !begin.Before(end) {
			bad("beginTime must be before endTime")
			return
		}
		// Align to bucket boundaries so the same instant lands in the same bucket however the
		// request was phrased (ThingsBoard anchors buckets at startTs). Widening, never narrowing:
		// the caller gets at least what they asked for.
		step := interval.Milliseconds()
		startMs := floorTo(begin.UnixMilli(), step)
		endMs := ceilTo(end.UnixMilli(), step)
		reqStartMs, reqEndMs := startMs, endMs
		if (endMs-startMs)/step > historyMaxBuckets {
			bad("too many buckets (" + strconv.FormatInt((endMs-startMs)/step, 10) + " > " +
				strconv.Itoa(historyMaxBuckets) + "); use a larger interval or a shorter window")
			return
		}

		ctx := c.Request.Context()

		// ThingsBoard telemetry and the hourly rollups carry no customer, so after a device moves to
		// another customer the new one would otherwise read the previous customer's curves. Clip to the
		// moment the device joined its current customer (devices.customer_assigned_at, maintained by a
		// trigger). It applies to platform admins too, so the answer for a given device does not depend
		// on who asks. Rounded UP to a bucket boundary (plus one bucket, below): a bucket straddling the
		// move would mix both owners' data.
		// A device provisioned straight in ThingsBoard (tbdevicesim provision) has no business row;
		// a platform admin may still query it, and there is no assignment to clip to.
		row, err := deps.Queries.GetDevice(ctx, device)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if err == nil && row.CustomerAssignedAt.Valid {
			// +step: customer_assigned_at is stamped at the UPDATE, but the transfer only becomes
			// visible at COMMIT, after the bindings are cleared and the audit rows written. Without
			// the extra bucket, the first bucket the new owner may read could hold samples taken while
			// the old owner was still authorized. assignDeviceHandler bounds that transaction to
			// assignTxTimeout, which is below the smallest bucket, so the allowance is sound; the price
			// is one more bucket of history hidden from the new owner.
			if from := ceilTo(row.CustomerAssignedAt.Time.UnixMilli(), step) + step; from > startMs {
				startMs = from
			}
		}
		// The ownership decision, the cutoff above and the reads below are separate steps. If the
		// device is reassigned between them, this caller was authorized as the OLD owner yet could
		// read data the NEW owner produced. Cap the window at the last bucket that ended before the
		// authorization instant: everything in it was produced before that instant, hence before any
		// later reassignment, so no timing of the reassignment can leak. Rounding UP instead would
		// leave the rest of the current bucket queryable, and the new owner's samples in it would be
		// aggregated into the former owner's answer. The cost is that the bucket in progress is not
		// returned (up to one interval of the most recent data); the live value is served by the
		// telemetry endpoint. Both authAt and the cutoff are stamped by the database clock, so
		// API-host clock skew cannot defeat this.
		if capMs := floorTo(authAt.UnixMilli(), step); endMs > capMs {
			endMs = capMs
		}

		// Resolve the ThingsBoard device first: a device missing there must be a 404 whatever window
		// was asked for — one that predates the assignment or the raw retention included — not a 200
		// with no data. (The device record outlives its telemetry; only the points expire.)
		if deps.TB == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "ThingsBoard is not configured on this deployment"})
			return
		}
		dev, err := deps.TB.GetDeviceByName(ctx, device)
		if err != nil {
			writeThingsBoardError(c, err, device)
			return
		}

		series := make(map[string][]historyPoint, len(fields))
		for _, f := range fields {
			series[f] = []historyPoint{}
		}
		if startMs >= endMs {
			// The whole window predates the assignment: nothing this caller may see. Echo the
			// requested aligned window, not the clipped one, so beginTime never exceeds endTime.
			writeHistory(c, device, tank, aggName, reqStartMs, reqEndMs, series)
			return
		}

		// Where raw data stops. Rounded UP to a whole hour so the two sources meet on a bucket
		// boundary for every interval (all divide an hour) and no hour is split between them.
		retention := deps.TelemetryRetention
		if retention <= 0 {
			retention = defaultTelemetryRetention
		}
		rawFromMs := ceilTo(authAt.Add(-retention).UnixMilli(), historyHourMs)

		if startMs < rawFromMs && step != historyHourMs {
			bad("interval " + c.Query("interval") + " is only available from " +
				time.UnixMilli(rawFromMs).UTC().Format(time.RFC3339) +
				" (raw telemetry is kept for " + retention.String() + "); use interval=1h for older data")
			return
		}

		// Older part: the hourly rollups.
		if startMs < rawFromMs {
			rollEnd := min(endMs, rawFromMs)
			rows, err := deps.Queries.ListTelemetryHourly(ctx, db.ListTelemetryHourlyParams{
				DeviceName: device, Tank: int32(tank), Fields: fields,
				BeginTime: pgtype.Timestamptz{Time: time.UnixMilli(startMs).UTC(), Valid: true},
				EndTime:   pgtype.Timestamptz{Time: time.UnixMilli(rollEnd).UTC(), Valid: true},
			})
			if err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
				return
			}
			for _, r := range rows {
				v := r.AvgValue
				switch aggName {
				case "min":
					v = r.MinValue
				case "max":
					v = r.MaxValue
				}
				series[r.Field] = append(series[r.Field], historyPoint{Time: time.UnixMilli(r.HourMs).UTC(), Value: v})
			}
		}

		// Newer part: ThingsBoard.
		if rawStart := max(startMs, rawFromMs); rawStart < endMs {
			keys := make([]string, len(fields))
			for i, f := range fields {
				keys[i] = protocol.TankKey(tank, f)
			}
			raw, err := deps.TB.Timeseries(ctx, dev.ID.ID, tbclient.TimeseriesQuery{
				Keys: keys, StartMs: rawStart, EndMs: endMs, Agg: agg, IntervalMs: step,
			})
			if err != nil {
				writeThingsBoardError(c, err, device)
				return
			}
			for i, f := range fields {
				points := make([]historyPoint, 0, len(raw[keys[i]]))
				for _, p := range raw[keys[i]] {
					v, ok := chartableValue(p.Value)
					if !ok {
						continue // an uncharted value: skipping beats a 500 for the whole window
					}
					points = append(points, historyPoint{Time: time.UnixMilli(p.TS - step/2).UTC(), Value: v})
				}
				series[f] = append(series[f], points...)
			}
		}

		// ThingsBoard returns aggregated buckets ascending and the table is ordered, but the two
		// halves are joined here; sorting is cheap insurance, because a chart drawn from unordered
		// points is wrong without looking wrong.
		for _, f := range fields {
			pts := series[f]
			sort.Slice(pts, func(a, b int) bool { return pts[a].Time.Before(pts[b].Time) })
		}

		writeHistory(c, device, tank, aggName, startMs, endMs, series)
	}
}

func writeHistory(c *gin.Context, device string, tank int, aggName string, startMs, endMs int64, series map[string][]historyPoint) {
	c.JSON(http.StatusOK, gin.H{
		"device":    device,
		"tank":      tank,
		"interval":  c.Query("interval"),
		"agg":       aggName,
		"beginTime": time.UnixMilli(startMs).UTC(),
		"endTime":   time.UnixMilli(endMs).UTC(),
		"series":    series,
	})
}

// chartableValue parses a ThingsBoard value for charting. ParseFloat accepts "NaN"/"Inf", which
// encoding/json cannot marshal: the 200 would already be written and the body truncated, so they
// are rejected along with non-numeric strings.
func chartableValue(s string) (float64, bool) {
	v, err := strconv.ParseFloat(s, 64)
	if err != nil || math.IsNaN(v) || math.IsInf(v, 0) {
		return 0, false
	}
	return v, true
}

func floorTo(ms, step int64) int64 { return ms - ((ms%step)+step)%step }

func ceilTo(ms, step int64) int64 {
	f := floorTo(ms, step)
	if f == ms {
		return ms
	}
	return f + step
}

func historyFieldList() string {
	names := make([]string, 0, len(historyFields))
	for f := range historyFields {
		names = append(names, f)
	}
	sort.Strings(names)
	return strings.Join(names, ", ")
}
