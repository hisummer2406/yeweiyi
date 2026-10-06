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
//   - each pass starts no later than the first hour after the last stored one, so a stopped or failing
//     job leaves no hole as long as it resumes before the raw data expires.
package rollup

import (
	"context"
	"errors"
	"fmt"
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
	// InitialLookback is how far back the first pass for a device with no stored hours goes.
	// Defaults to 8 days (one more than the 7-day raw retention).
	InitialLookback time.Duration

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
	if cfg.InitialLookback <= 0 {
		cfg.InitialLookback = 8 * 24 * time.Hour
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
		}
	}
}

// startFor is where a pass for one device begins: the earlier of "Recompute ago" and "the hour after
// the last stored one", so an outage longer than Recompute is still closed up, and a device with no
// rows starts at the initial lookback.
func (j *Job) startFor(ctx context.Context, name string) (int64, error) {
	now := j.cfg.Now()
	latest, err := j.cfg.Queries.LatestTelemetryHourStart(ctx, name)
	if err != nil {
		return 0, err
	}
	if latest == 0 {
		return now.Add(-j.cfg.InitialLookback).UnixMilli(), nil
	}
	from := now.Add(-j.cfg.Recompute).UnixMilli()
	if next := latest + hourMs; next < from {
		from = next
	}
	return from, nil
}

// Result counts what one RollupDevice call wrote.
type Result struct {
	Hours int // distinct hour buckets seen
	Rows  int // (tank, field, hour) rows submitted for writing
}

// RollupDevice rolls up the completed hours in [fromMs, toMs). toMs = 0 means "up to the last
// completed hour". fromMs is floored to an hour; the hour in progress is never included.
func (j *Job) RollupDevice(ctx context.Context, deviceName string, fromMs, toMs int64) (Result, error) {
	var res Result
	nowMs := j.cfg.Now().UnixMilli()
	lastComplete := nowMs - ((nowMs%hourMs)+hourMs)%hourMs
	if toMs == 0 || toMs > lastComplete {
		toMs = lastComplete
	}
	fromMs -= ((fromMs % hourMs) + hourMs) % hourMs
	if fromMs >= toMs {
		return res, nil
	}

	dev, err := j.cfg.TB.GetDeviceByName(ctx, deviceName)
	if err != nil {
		return res, err
	}
	tanks, err := j.tankCount(ctx, deviceName, dev.ID.ID)
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

// tankCount is the highest tank number to ask about: what the device reports now, or failing that
// (silent for longer than the raw retention) the highest it has ever been summarised under.
func (j *Job) tankCount(ctx context.Context, name, deviceID string) (int, error) {
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
// queries (AVG, MIN, MAX, COUNT) and stores the complete rows.
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

	rows, hours := 0, map[int64]bool{}
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
			err := j.cfg.Queries.UpsertTelemetryHourly(ctx, db.UpsertTelemetryHourlyParams{
				DeviceName: name, Tank: int32(tank), Field: field, HourStartMs: hour,
				AvgValue: ap.avg, MinValue: ap.min, MaxValue: ap.max, Samples: int32(ap.count),
			})
			if err != nil {
				return rows, hours, err
			}
			rows++
			hours[hour] = true
		}
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
