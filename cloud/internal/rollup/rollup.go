// Package rollup keeps the hourly summaries of continuous telemetry in the business database
// (issue #30).
//
// The 30-second snapshots stay in ThingsBoard only — CLAUDE.md constraint 5 — and live there for a
// bounded time. What the business database keeps for good is one row per (device, tank, field, hour)
// with avg/min/max and the sample count, computed by ThingsBoard itself (its own aggregation, so a
// rollup equals what a direct query over the same hour returns) and written here.
//
// Three properties make the job safe to run, rerun, interrupt and race:
//
//   - only completed hours are written, so a row never describes an hour still filling;
//   - writes are idempotent, and replace a stored hour only when the new sample count is not lower
//     (db/queries/telemetry_hourly.sql). A late replay from an offline device adds samples and so
//     refreshes the hour; ThingsBoard's TTL removing part of an hour's raw data lowers the count and so
//     can never overwrite a complete summary with a ragged one;
//   - hours are written oldest first and a pass stops at its first error, so the newest stored hour
//     is also the end of an unbroken run (hours with no samples at all have no row, which is not a
//     hole). Each pass therefore starts no later than that newest hour — itself included, since a
//     failure can have stopped partway through it — and a stopped or failing job leaves no hole as
//     long as it resumes before the raw data expires.
package rollup

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"time"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

const hourMs = int64(time.Hour / time.Millisecond)

// maxHoursPerQuery stays under ThingsBoard's DATABASE_TS_MAX_INTERVALS (default 700) with margin.
const maxHoursPerQuery = 600

// Config wires a Job.
type Config struct {
	TB      *tbclient.Client
	Queries *db.Queries

	// Interval is how often a pass runs. Defaults to 1h.
	Interval time.Duration
	// Recompute is how far back every pass re-reads, to pick up late replays from devices that were
	// offline. Defaults to 48h.
	Recompute time.Duration

	Logf func(format string, args ...any)
	// Now is for tests.
	Now func() time.Time
}

// Job computes hourly rollups for every device in the business database.
type Job struct{ cfg Config }

// New builds a Job.
func New(cfg Config) *Job {
	if cfg.Interval <= 0 {
		cfg.Interval = time.Hour
	}
	if cfg.Recompute <= 0 {
		cfg.Recompute = 48 * time.Hour
	}
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}
	if cfg.Now == nil {
		cfg.Now = time.Now
	}
	return &Job{cfg: cfg}
}

// Run does one pass immediately, then one per Interval, until ctx ends.
func (j *Job) Run(ctx context.Context) {
	j.Pass(ctx)
	t := time.NewTicker(j.cfg.Interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			j.Pass(ctx)
		}
	}
}

// Pass rolls up every device once. A failing device is logged and does not stop the others.
func (j *Job) Pass(ctx context.Context) {
	names, err := j.cfg.Queries.ListAllDeviceNames(ctx)
	if err != nil {
		j.cfg.Logf("rollup: list devices: %v", err)
		return
	}
	began := j.cfg.Now()
	failed := 0
	for _, name := range names {
		if ctx.Err() != nil {
			return
		}
		from, err := j.startFor(ctx, name)
		if err == nil {
			_, err = j.RollupDevice(ctx, name, from, 0)
		}
		if err != nil && !errors.Is(err, tbclient.ErrNotFound) && ctx.Err() == nil {
			j.cfg.Logf("rollup: %s: %v", name, err)
			failed++
		}
	}
	// Devices are walked one after another, so a long fleet could stretch a pass past its interval;
	// the duration is what shows that coming.
	j.cfg.Logf("rollup: pass over %d device(s) took %s, %d failed", len(names), j.cfg.Now().Sub(began).Round(time.Second), failed)
}

// startFor is where a pass for one device begins: the earlier of "Recompute ago" and the newest stored
// hour, so an outage longer than Recompute is still closed up. A device with no rows starts at 0,
// which RollupDevice reads as "from the earliest point ThingsBoard still holds" — whatever it has
// accumulated, not a fixed lookback that could leave older hours to expire unsummarised.
func (j *Job) startFor(ctx context.Context, name string) (int64, error) {
	now := j.cfg.Now()
	latest, err := j.cfg.Queries.LatestTelemetryHourStart(ctx, name)
	if err != nil {
		return 0, err
	}
	if latest == 0 {
		return 0, nil
	}
	from := now.Add(-j.cfg.Recompute).UnixMilli()
	if latest < from {
		from = latest
	}
	return from, nil
}

// Result counts what one RollupDevice call wrote.
type Result struct {
	Hours int // distinct hour buckets seen
	Rows  int // (tank, field, hour) rows submitted for writing
}

// RollupDevice rolls up the completed hours in [fromMs, toMs). toMs = 0 means "up to the last
// completed hour"; fromMs = 0 means "from the earliest point ThingsBoard holds". fromMs is floored to
// an hour; the hour in progress is never included.
func (j *Job) RollupDevice(ctx context.Context, deviceName string, fromMs, toMs int64) (Result, error) {
	var res Result
	nowMs := j.cfg.Now().UnixMilli()
	lastComplete := nowMs - ((nowMs%hourMs)+hourMs)%hourMs
	if toMs == 0 || toMs > lastComplete {
		toMs = lastComplete
	}

	dev, err := j.cfg.TB.GetDeviceByName(ctx, deviceName)
	if err != nil {
		return res, err
	}
	tanks, err := j.tankCount(ctx, deviceName, dev.ID.ID, toMs)
	if err != nil {
		return res, err
	}
	if tanks == 0 {
		return res, nil
	}

	var keys []string
	for t := 1; t <= tanks; t++ {
		for _, f := range protocol.AggregatableTankFields {
			keys = append(keys, protocol.TankKey(t, f))
		}
	}

	if fromMs == 0 {
		first, ok, err := j.earliest(ctx, dev.ID.ID, keys, toMs)
		if err != nil || !ok {
			return res, err
		}
		fromMs = first
	}
	fromMs -= ((fromMs % hourMs) + hourMs) % hourMs
	if fromMs >= toMs {
		return res, nil
	}

	seenHours := map[int64]bool{}
	for start := fromMs; start < toMs; start += maxHoursPerQuery * hourMs {
		end := min(start+maxHoursPerQuery*hourMs, toMs)
		n, hours, err := j.rollupChunk(ctx, deviceName, dev.ID.ID, keys, start, end)
		res.Rows += n
		for h := range hours {
			seenHours[h] = true
		}
		if err != nil {
			res.Hours = len(seenHours)
			return res, err
		}
	}
	res.Hours = len(seenHours)
	return res, nil
}

// earliest is the timestamp of the oldest raw point of any of keys. ok is false when there is none.
func (j *Job) earliest(ctx context.Context, deviceID string, keys []string, toMs int64) (ts int64, ok bool, err error) {
	series, err := j.cfg.TB.Timeseries(ctx, deviceID, tbclient.TimeseriesQuery{
		Keys: keys, StartMs: 0, EndMs: toMs, Limit: 1,
	})
	if err != nil {
		return 0, false, err
	}
	for _, pts := range series {
		for _, p := range pts {
			if !ok || p.TS < ts {
				ts, ok = p.TS, true
			}
		}
	}
	return ts, ok, nil
}

// tankCount is the highest tank number to ask about: the most the device has ever reported in the raw
// data ThingsBoard still holds (a unit that once had four tanks and now reports two still has four
// tanks' worth of history to keep), what it reports now, and the most it has ever been summarised
// under (the raw data may be gone by now).
func (j *Job) tankCount(ctx context.Context, name, deviceID string, toMs int64) (int, error) {
	n := 0
	latest, err := j.cfg.TB.LatestTimeseries(ctx, deviceID, []string{protocol.KeyTankCount})
	if err != nil {
		return 0, err
	}
	if v, ok := latest[protocol.KeyTankCount]; ok {
		if c, err := strconv.Atoi(v.Value); err == nil {
			n = c
		}
	}
	// One bucket spanning everything: the maximum over the whole retained history.
	hist, err := j.cfg.TB.Timeseries(ctx, deviceID, tbclient.TimeseriesQuery{
		Keys: []string{protocol.KeyTankCount}, StartMs: 0, EndMs: toMs, Agg: "MAX", IntervalMs: toMs,
	})
	if err != nil {
		return 0, err
	}
	for _, p := range hist[protocol.KeyTankCount] {
		if f, err := strconv.ParseFloat(p.Value, 64); err == nil {
			n = max(n, int(f))
		}
	}
	known, err := j.cfg.Queries.MaxTelemetryHourlyTank(ctx, name)
	if err != nil {
		return 0, err
	}
	n = max(n, int(known))
	return min(n, protocol.MaxTankCount), nil
}

type aggPoint struct {
	avg, min, max float64
	count         int
	have          uint8
}

// rollupChunk aggregates [start, end) — at most maxHoursPerQuery hours — with four ThingsBoard
// queries (AVG, MIN, MAX, COUNT) and stores the complete rows, oldest hour first.
//
// The four queries are not one snapshot: a late replay that lands between them can leave a row whose
// columns come from different sample sets. The window is small and the next pass re-reads the
// recompute range, and the samples guard in the upsert keeps the larger count, so it self-corrects;
// it is not prevented.
func (j *Job) rollupChunk(ctx context.Context, name, deviceID string, keys []string, start, end int64) (int, map[int64]bool, error) {
	byKeyHour := map[string]map[int64]*aggPoint{}
	for bit, agg := range []string{"AVG", "MIN", "MAX", "COUNT"} {
		raw, err := j.cfg.TB.Timeseries(ctx, deviceID, tbclient.TimeseriesQuery{
			Keys: keys, StartMs: start, EndMs: end, Agg: agg, IntervalMs: hourMs,
		})
		if err != nil {
			return 0, nil, fmt.Errorf("%s over [%d,%d): %w", agg, start, end, err)
		}
		for key, pts := range raw {
			for _, p := range pts {
				v, err := strconv.ParseFloat(p.Value, 64)
				if err != nil {
					continue
				}
				// ThingsBoard stamps a bucket with its midpoint.
				hour := p.TS - hourMs/2
				if hour < start || hour >= end {
					continue
				}
				m := byKeyHour[key]
				if m == nil {
					m = map[int64]*aggPoint{}
					byKeyHour[key] = m
				}
				ap := m[hour]
				if ap == nil {
					ap = &aggPoint{}
					m[hour] = ap
				}
				switch agg {
				case "AVG":
					ap.avg = v
				case "MIN":
					ap.min = v
				case "MAX":
					ap.max = v
				case "COUNT":
					ap.count = int(v)
				}
				ap.have |= 1 << bit
			}
		}
	}

	type row struct {
		hour  int64
		tank  int
		field string
		ap    *aggPoint
	}
	var out []row
	for key, m := range byKeyHour {
		tank, field, ok := splitTankKey(key)
		if !ok {
			continue
		}
		for hour, ap := range m {
			// A bucket missing any of the four aggregates, or with no samples, is not a usable row.
			if ap.have != 0b1111 || ap.count <= 0 {
				continue
			}
			out = append(out, row{hour, tank, field, ap})
		}
	}
	// Oldest first: if a write fails, what is stored is an unbroken run up to the failing hour, which
	// is what lets the next pass resume from the newest stored hour (see startFor).
	sort.Slice(out, func(a, b int) bool {
		if out[a].hour != out[b].hour {
			return out[a].hour < out[b].hour
		}
		if out[a].tank != out[b].tank {
			return out[a].tank < out[b].tank
		}
		return out[a].field < out[b].field
	})

	rows, hours := 0, map[int64]bool{}
	for _, r := range out {
		err := j.cfg.Queries.UpsertTelemetryHourly(ctx, db.UpsertTelemetryHourlyParams{
			DeviceName: name, Tank: int32(r.tank), Field: r.field, HourStartMs: r.hour,
			AvgValue: r.ap.avg, MinValue: r.ap.min, MaxValue: r.ap.max, Samples: int32(r.ap.count),
		})
		if err != nil {
			return rows, hours, err
		}
		rows++
		hours[r.hour] = true
	}
	return rows, hours, nil
}

// splitTankKey undoes protocol.TankKey: "tank2.oilLevelMm" -> (2, "oilLevelMm").
func splitTankKey(key string) (tank int, field string, ok bool) {
	if len(key) < 6 || key[:4] != "tank" {
		return 0, "", false
	}
	for i := 4; i < len(key); i++ {
		if key[i] == '.' {
			n, err := strconv.Atoi(key[4:i])
			if err != nil || n < 1 {
				return 0, "", false
			}
			return n, key[i+1:], true
		}
	}
	return 0, "", false
}
