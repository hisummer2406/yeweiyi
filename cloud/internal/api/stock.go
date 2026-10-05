package api

import (
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// GET /api/oil-in-out — 出入库 (stock in/out) history, read through to ThingsBoard.
//
// Same pattern and same reasoning as /api/alarms: the device publishes each completed movement as a
// `stockEvent` telemetry key the moment the record closes, ThingsBoard stores it, and this reads it
// back on request. Nothing is copied into the business database, which would recreate the duplicate
// store issue #10 forbids.
//
// **Breaking change from the legacy endpoint.** The old one served rows from the `oil_in_out` table
// that only the retired Alink ingestion ever wrote, with the device's original Java field names
// (`beginHeigth`/`endHeigth`/`beginVolum`). Those are gone along with the payloads they came from.
//
// **Depends on a device-side change that is not merged yet** (zhongchengyeweiyi#39): the device
// already publishes `stockEvent` on every movement, but currently as `DetailInoutRecord.toString()`
// — a Java debug string, not JSON. Until that becomes `gson.toJson(...)` with the field names in
// docs/protocol-v1-stock-event.md, real devices produce values this endpoint cannot parse.
//
// **What happens then is a 502, deliberately.** The original behaviour was to skip unparseable
// points and serve 200 with whatever was left. For a device on the current firmware that means 200
// and `[]` — an empty stock-movement history, which is a well-formed, confident, *wrong* answer to
// "what oil moved through this tank?". Nothing in that response separates "no deliveries this week"
// from "every delivery this week is sitting in ThingsBoard in a format this deployment cannot
// read", and 出入库 records are precisely what gets argued over with suppliers. It is also the exact
// failure shape this project has already been bitten by once — ThingsBoard silently aggregating
// nested telemetry to 0.0 instead of erroring (docs/protocol-v1-reconciliation.md §A) — where the
// lesson recorded at the time was that a wrong answer nobody can detect is worse than a loud one.
//
// **But a flat "unparseable ⇒ 502" is only correct until the fleet is upgraded, and then it is
// permanently wrong.** Shipping the device-side fix converts nothing: the records already written
// to ThingsBoard stay debug strings forever, so any window reaching back past the upgrade would
// answer 502 for the rest of this deployment's life. That turns a temporary honest failure into a
// permanently broken endpoint, which is not an improvement on the empty list. Found in review of
// PR #12.
//
// So the boundary is configurable, and it is the only thing that can distinguish the two cases,
// because the payloads themselves are identical: `stock.legacyFormatBefore`
// (Deps.StockLegacyFormatBefore) is the instant from which every device here is expected to publish
// V1 JSON.
//
//	unparseable, before the cutoff   a known, closed data hole. Counted as `legacySkipped`, still
//	                                 200 — nothing is wrong now and no action follows from it.
//	unparseable, at or after it      a device that should have been upgraded and was not. 502 —
//	                                 somebody has to go and look at it.
//
// Unset (the default) means "the upgrade has not happened", so every unreadable record counts as
// current breakage. That is the right default: a deployment nobody has told about a fleet upgrade
// must not start quietly writing off records.
//
// 502 rather than 500 or a 4xx: the caller did nothing wrong and this backend is working. It is the
// upstream — the device, via ThingsBoard — whose payload cannot be interpreted, which is what Bad
// Gateway means. `code` is stable and machine-checkable so a client can say 设备固件尚未升级
// instead of rendering a generic error.
//
// **The response is always an object with a `rows` array**, on both paths, and the key name is not
// arbitrary. Two independent reasons, neither of which is "it looked nicer":
//
//   - a bare array cannot carry `legacySkipped`, and a 200 that silently omits how many records it
//     could not read is the same mistake as the empty list, one layer up;
//   - `{"rows": [...]}` is what the mini-program already expects here
//     (yeweiyi-miniapp `miniprogram/pages/device-detail/device-detail.ts:98`, and its CLAUDE.md
//     records the same for /api/alarms, /api/oil-in-out and /api/devices), and it is this repo's own
//     house shape for list endpoints — /api/devices returns `{rows}`, commands `{commands}`, audit
//     `{audit}`. The bare array PR #11 introduced here was the departure; this returns to it.
//
// So the shape change is toward the documented cross-repo contract rather than away from it. The
// *rows themselves* remain incompatible with that client for reasons that predate this branch — the
// mini-program still expects the retired Alink columns (`begin_height`, `device_local_id`) — and
// resolving that needs the mini-program owner. See docs/protocol-v1-open-items.md §D.

// StockEventFormatUnsupported is the stable `code` on the degraded response. Clients should match on
// this rather than on the message, which is prose and will be translated.
const StockEventFormatUnsupported = "STOCK_EVENT_FORMAT_UNSUPPORTED"

// unparsableSampleRunes bounds the echoed sample. Long enough to recognise
// `DetailInoutRecord{oilcanId=1, ...}` and to paste into a bug report, short enough that a
// pathological value cannot make the error response the largest thing on the wire.
const unparsableSampleRunes = 200

type stockEventResponse struct {
	EventID   string `json:"eventId"`
	Tank      int    `json:"tank"`
	Direction string `json:"direction"`
	OilType   string `json:"oilType,omitempty"`

	BeginTime time.Time `json:"beginTime"`
	EndTime   time.Time `json:"endTime"`

	BeginLevelMm  float64 `json:"beginLevelMm"`
	EndLevelMm    float64 `json:"endLevelMm"`
	BeginVolumeL  float64 `json:"beginVolumeL"`
	EndVolumeL    float64 `json:"endVolumeL"`
	BeginTempC    float64 `json:"beginTempC"`
	EndTempC      float64 `json:"endTempC"`
	BeginWeightKg float64 `json:"beginWeightKg"`
	EndWeightKg   float64 `json:"endWeightKg"`

	NetVolumeL            float64 `json:"netVolumeL"`
	DuringDeliveryVolumeL float64 `json:"duringDeliveryVolumeL,omitempty"`
	// DeliveredVolumeL is what actually moved: for an inbound delivery the tank's net gain plus
	// whatever the pumps dispensed during it. Computed here rather than left to every client,
	// because getting it wrong understates deliveries and that is the number people argue with
	// suppliers about.
	DeliveredVolumeL float64 `json:"deliveredVolumeL"`

	Time time.Time `json:"time"` // when the device closed the record
}

// stockListResponse is the body on both paths. `rows` is always present and always an array — a
// client that renders the list before checking the status code shows the truthful partial list
// rather than crashing on a missing field — and the counts are always present too, so a caller that
// ignores the status code still cannot mistake an incomplete answer for a complete one.
type stockListResponse struct {
	Rows   []stockEventResponse `json:"rows"`
	Parsed int                  `json:"parsed"`
	// Unparsable counts records this deployment expects to be readable and is not. Non-zero means
	// HTTP 502.
	Unparsable int `json:"unparsable"`
	// LegacySkipped counts records from before stock.legacyFormatBefore: a closed, known hole in
	// the history rather than a fault. Reported rather than hidden, because the operator
	// reconciling this window still needs to know the numbers are incomplete.
	LegacySkipped int `json:"legacySkipped"`
	// Folded counts superseded re-reports of an eventId already in the list (see foldByEventID).
	// Not a fault; reported so a row count that differs from the raw telemetry count is explicable.
	Folded int `json:"folded"`

	// Only on the 502 path.
	Error  string `json:"error,omitempty"`
	Code   string `json:"code,omitempty"`
	Device string `json:"device,omitempty"`
	Sample string `json:"sample,omitempty"`
	// LegacyCutoff echoes the configured boundary so an operator seeing an unexpected 502 can tell
	// at a glance whether it is unset (the common cause) without reading the deployment's config.
	LegacyCutoff string `json:"legacyCutoff,omitempty"`
}

func toStockEventResponse(e protocol.StockEvent) stockEventResponse {
	delivered := e.NetVolumeL + e.DuringDeliveryVolumeL
	if e.Direction == protocol.StockOut {
		// Outbound: the tank's change is negative and there is no concurrent-delivery adjustment,
		// so the quantity moved is the magnitude of the drop.
		delivered = -e.NetVolumeL
	}

	return stockEventResponse{
		EventID:               e.EventID,
		Tank:                  e.Tank,
		Direction:             e.Direction,
		OilType:               e.OilType,
		BeginTime:             time.UnixMilli(e.BeginTimeMs).UTC(),
		EndTime:               time.UnixMilli(e.EndTimeMs).UTC(),
		BeginLevelMm:          e.BeginLevelMm,
		EndLevelMm:            e.EndLevelMm,
		BeginVolumeL:          e.BeginVolumeL,
		EndVolumeL:            e.EndVolumeL,
		BeginTempC:            e.BeginTempC,
		EndTempC:              e.EndTempC,
		BeginWeightKg:         e.BeginWeightKg,
		EndWeightKg:           e.EndWeightKg,
		NetVolumeL:            e.NetVolumeL,
		DuringDeliveryVolumeL: e.DuringDeliveryVolumeL,
		DeliveredVolumeL:      delivered,
		Time:                  time.UnixMilli(e.TSMs).UTC(),
	}
}

// stockRow is a parsed event plus the ThingsBoard envelope timestamp it arrived under.
type stockRow struct {
	ev protocol.StockEvent
	// envTS is the telemetry envelope ts: when the device *generated* this report, preserved verbatim
	// across offline replay (docs/protocol-v1-stock-event.md §3 makes that a hard requirement, and the
	// legacy-cutoff logic below already depends on it). It orders revisions of one movement, rather
	// than the payload's tsMs, because tsMs is the record-close time the API exposes as `time` and
	// redefining it as a revision counter would be a one-sided change to the cross-repo contract.
	envTS int64
}

// foldByEventID collapses re-reports of one movement into a single event. The device re-sends a
// stockEvent under the same eventId when the operator later fills in duringDeliveryVolumeL, so the
// raw telemetry holds two points for one delivery; listing both double-counts it against the
// supplier. The copy with the greatest envelope ts wins (the correction is generated after the
// original); on a tie the earlier-listed one stays, so the result is deterministic. Events with an
// empty eventId are never folded: there is nothing to say they are the same movement.
//
// The survivors are then re-sorted by envelope ts in the requested direction. Replacing a copy in
// place would otherwise leave a correction sitting in the original's slot, out of order relative to
// events between the two. The sort is stable, so equal timestamps keep the query's order.
func foldByEventID(rows []stockRow, asc bool) (out []stockRow, folded int) {
	idx := make(map[string]int, len(rows))
	out = make([]stockRow, 0, len(rows))
	for _, r := range rows {
		if r.ev.EventID == "" {
			out = append(out, r)
			continue
		}
		i, seen := idx[r.ev.EventID]
		if !seen {
			idx[r.ev.EventID] = len(out)
			out = append(out, r)
			continue
		}
		folded++
		if r.envTS > out[i].envTS {
			out[i] = r
		}
	}
	sort.SliceStable(out, func(a, b int) bool {
		if asc {
			return out[a].envTS < out[b].envTS
		}
		return out[a].envTS > out[b].envTS
	})
	return out, folded
}

func listOilInOutHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		p, ok := parseListParams(c)
		if !ok {
			return
		}
		if !requireDeviceOwnership(c, deps, p.device) {
			return
		}
		if deps.TB == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "ThingsBoard is not configured on this deployment"})
			return
		}
		// Same limitation as /api/alarms: ThingsBoard pages by time window, not row offset.
		if p.offset > 0 {
			c.JSON(http.StatusBadRequest, gin.H{
				"error": "offset is not supported; page by narrowing beginTime/endTime instead",
			})
			return
		}

		ctx := c.Request.Context()
		dev, err := deps.TB.GetDeviceByName(ctx, p.device)
		if err != nil {
			writeThingsBoardError(c, err, p.device)
			return
		}

		series, err := deps.TB.Timeseries(ctx, dev.ID.ID, tbclient.TimeseriesQuery{
			Keys:       []string{protocol.KeyStockEvent},
			StartMs:    p.begin.Time.UnixMilli(),
			EndMs:      p.end.Time.UnixMilli(),
			Limit:      int(p.limit),
			Descending: !p.asc,
		})
		if err != nil {
			writeThingsBoardError(c, err, p.device)
			return
		}

		cutoffMs := int64(0)
		if !deps.StockLegacyFormatBefore.IsZero() {
			cutoffMs = deps.StockLegacyFormatBefore.UnixMilli()
		}

		points := series[protocol.KeyStockEvent]
		rows := make([]stockRow, 0, len(points))
		unparsable, legacy := 0, 0
		sample := ""
		for _, pt := range points {
			ev, err := protocol.ParseStockEvent(pt.Value)
			if err != nil {
				// pt.TS classifies it, and nothing else can: the payload of a pre-upgrade record
				// and of a device that never upgraded are byte-identical.
				//
				// This is only sound because pt.TS is when the device *generated* the record, not
				// when ThingsBoard received it — which matters precisely for the case that breaks
				// a naive version of this: a record created before the fleet upgrade but replayed
				// after it, from the device's offline queue. Verified on the device side rather
				// than assumed: ThingsBoardManager.reportRawEvent (:168-176) stamps
				// `ts = System.currentTimeMillis()` at generation time and enqueues the already
				// serialized JSON, and PendingAlarmStore.drainAndSend (:87-99) republishes that
				// string verbatim with no re-stamping. ThingsBoard honours the envelope ts, so a
				// record replayed days late still lands at its original instant — pinned by
				// TestListOilInOut_ReplayedPreUpgradeRecordIsClassifiedByItsOwnTimestamp.
				//
				// The dependency is therefore a protocol requirement, not an implementation
				// detail: a stockEvent MUST carry an envelope ts and replay MUST preserve it. A
				// device that published without one would have ThingsBoard stamp arrival time,
				// and every replayed record would read as current breakage
				// (TestListOilInOut_RecordWithoutAnEnvelopeTimestampCountsAsCurrent pins that
				// degradation so it is a known boundary rather than a surprise). Stated for the
				// Android side in docs/protocol-v1-stock-event.md §3.
				if cutoffMs > 0 && pt.TS < cutoffMs {
					legacy++
					continue
				}
				// Counted, not dropped. The first offender is kept as the sample because one
				// example is enough to identify the format, and echoing every one of them would
				// make the error response the largest thing on the wire.
				unparsable++
				if sample == "" {
					sample = truncateRunes(rawValueString(pt.Value), unparsableSampleRunes)
				}
				continue
			}
			if ev.TSMs == 0 {
				ev.TSMs = pt.TS
			}
			rows = append(rows, stockRow{ev: ev, envTS: pt.TS})
		}
		rows, folded := foldByEventID(rows, p.asc)
		out := make([]stockEventResponse, 0, len(rows))
		for _, r := range rows {
			out = append(out, toStockEventResponse(r.ev))
		}

		body := stockListResponse{
			Rows:          out,
			Parsed:        len(out),
			Unparsable:    unparsable,
			LegacySkipped: legacy,
			Folded:        folded,
		}

		if unparsable > 0 {
			body.Error = "该设备上报的出入库记录中有 " + strconv.Itoa(unparsable) +
				" 条无法解析：设备固件仍以 DetailInoutRecord.toString() 上报，" +
				"而非 设备协议 V1 定义的 JSON（zhongchengyeweiyi#39）。" +
				"已解析的记录一并返回，但本时间窗内的出入库历史并不完整。"
			if cutoffMs == 0 {
				body.Error += "（本部署未配置 stock.legacyFormatBefore；若车队已完成固件升级，" +
					"设置该时间点可把升级前的旧记录计为 legacySkipped 而非故障。）"
			}
			body.Code = StockEventFormatUnsupported
			body.Device = p.device
			body.Sample = sample
			if cutoffMs > 0 {
				body.LegacyCutoff = deps.StockLegacyFormatBefore.UTC().Format(time.RFC3339)
			}
			c.JSON(http.StatusBadGateway, body)
			return
		}

		// 200 even with legacySkipped > 0: those records are a closed hole, not a fault, and there
		// is no action for anyone to take. The count is in the body precisely so that "200" does
		// not get read as "this list is complete".
		c.JSON(http.StatusOK, body)
	}
}

// rawValueString renders a telemetry value for the diagnostic sample. ThingsBoard hands back
// stringified telemetry, so the overwhelmingly common case is already a string; anything else is
// formatted rather than dropped, because "the value was not even a string" is itself the diagnosis.
func rawValueString(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprint(v)
}

// truncateRunes cuts on a rune boundary, not a byte one: the device's payloads carry Chinese oil
// type names, and a byte-sliced sample would put an invalid UTF-8 fragment into a JSON response.
func truncateRunes(s string, max int) string {
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	n := 0
	for i := range s {
		if n == max {
			return s[:i] + "…"
		}
		n++
	}
	return s
}
