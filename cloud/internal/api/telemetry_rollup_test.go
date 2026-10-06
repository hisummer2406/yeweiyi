package api_test

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/rollup"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// Acceptance tests of issue #30: hourly rollups live in the business database, are computed from
// ThingsBoard's own aggregation, and answer telemetry/history for what raw retention no longer holds.

type rollupEnv struct {
	*testEnv
	device, token string
	tb            *tbclient.Client
	deps          api.Deps
	job           *rollup.Job
}

func newRollupEnv(t *testing.T) *rollupEnv {
	t.Helper()
	var deps api.Deps
	env, _, device, token := newStockEnv(t, func(d *api.Deps) { deps = *d })
	t.Cleanup(func() {
		env.pool.Exec(context.Background(), `DELETE FROM telemetry_hourly WHERE device_name = $1`, device)
	})
	return &rollupEnv{
		testEnv: env, device: device, token: token, tb: deps.TB, deps: deps,
		job: rollup.New(rollup.Config{TB: deps.TB, Queries: env.queries, Logf: t.Logf}),
	}
}

// withRetention swaps in a router whose raw retention is d, as a deployment with the TTL on would be.
func (e *rollupEnv) withRetention(d time.Duration) {
	deps := e.deps
	deps.TelemetryRetention = d
	e.router = api.NewRouter(deps)
}

func hourlyQuery(begin, end time.Time, interval, agg string) url.Values {
	return url.Values{
		"tank": {"1"}, "fields": {"oilLevelMm"}, "interval": {interval}, "agg": {agg},
		"beginTime": {begin.UTC().Format(time.RFC3339)}, "endTime": {end.UTC().Format(time.RFC3339)},
	}
}

// seedCompleteHours publishes the standard two-hour series starting three whole hours ago, so both
// hours are complete, and returns that start. tankCount is published so the job knows how many tanks to ask for.
func (e *rollupEnv) seedCompleteHours(t *testing.T) time.Time {
	t.Helper()
	base := time.Now().UTC().Truncate(time.Hour).Add(-3 * time.Hour)
	seedHistory(t, e.token, base)
	postDeviceTelemetry(t, e.token, []byte(fmt.Sprintf(`{"ts":%d,"values":{"tankCount":1}}`, base.UnixMilli())))
	return base
}

func seriesOf(b historyBody) []float64 {
	var out []float64
	for _, p := range b.Series["oilLevelMm"] {
		out = append(out, p.Value)
	}
	return out
}

// TestRollup_MatchesDirectAggregationAndSurvivesTTL: the rollup equals what ThingsBoard aggregates
// over the same hours, and still answers after ThingsBoard has lost the raw data.
func TestRollup_MatchesDirectAggregationAndSurvivesTTL(t *testing.T) {
	e := newRollupEnv(t)
	base := e.seedCompleteHours(t)
	end := base.Add(2 * time.Hour)

	// Ground truth: ThingsBoard's own hourly aggregation, through the raw path.
	want := map[string]historyBody{}
	for _, agg := range []string{"avg", "min", "max"} {
		deadline := time.Now().Add(20 * time.Second)
		for {
			want[agg] = fetchHistory(t, e.testEnv, e.device, hourlyQuery(base, end, "1h", agg), http.StatusOK)
			if len(seriesOf(want[agg])) == 2 || time.Now().After(deadline) {
				break
			}
			time.Sleep(300 * time.Millisecond)
		}
		if len(seriesOf(want[agg])) != 2 {
			t.Fatalf("ThingsBoard never returned 2 hourly buckets for %s: %v", agg, want[agg])
		}
	}

	res, err := e.job.RollupDevice(context.Background(), e.device, base.UnixMilli(), 0)
	if err != nil {
		t.Fatalf("RollupDevice: %v", err)
	}
	if res.Hours < 2 {
		t.Fatalf("rolled up %d hours, want at least 2", res.Hours)
	}

	// Retention of one hour puts both seeded hours on the rollup side of the boundary.
	e.withRetention(time.Hour)
	for agg, w := range want {
		got := fetchHistory(t, e.testEnv, e.device, hourlyQuery(base, end, "1h", agg), http.StatusOK)
		if g, ww := seriesOf(got), seriesOf(w); len(g) != len(ww) {
			t.Fatalf("%s: rollup %v vs ThingsBoard %v", agg, g, ww)
		} else {
			for i := range g {
				if math.Abs(g[i]-ww[i]) > 1e-9 || !got.Series["oilLevelMm"][i].Time.Equal(w.Series["oilLevelMm"][i].Time) {
					t.Errorf("%s bucket %d: rollup %v@%v vs ThingsBoard %v@%v", agg, i,
						g[i], got.Series["oilLevelMm"][i].Time, ww[i], w.Series["oilLevelMm"][i].Time)
				}
			}
		}
	}

	// The TTL: ThingsBoard loses the raw data but keeps the device. Simulated by deleting the device
	// and registering it again empty. Hourly still answers.
	dev, err := e.tb.GetDeviceByName(context.Background(), e.device)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.tb.DeleteDevice(context.Background(), dev.ID.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.tb.CreateDevice(context.Background(), e.device, dev.Type, ""); err != nil {
		t.Fatal(err)
	}
	after := fetchHistory(t, e.testEnv, e.device, hourlyQuery(base, end, "1h", "avg"), http.StatusOK)
	if fmt.Sprint(seriesOf(after)) != fmt.Sprint(seriesOf(want["avg"])) {
		t.Errorf("after TTL: %v, want %v", seriesOf(after), seriesOf(want["avg"]))
	}

	// ...but a minute-level query over that range is refused, and says since when it works.
	rec := e.authed("GET", "/api/devices/"+e.device+"/telemetry/history?"+hourlyQuery(base, end, "5m", "avg").Encode(), nil)
	if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "only available from") {
		t.Fatalf("5m beyond retention = %d %s, want 400 naming the earliest instant", rec.Code, rec.Body.String())
	}
}

// TestRollup_RerunIsIdempotentAndNeverShrinks: a second run changes nothing, and a more ragged
// recomputation (what a TTL that cut into an hour would produce) cannot replace a complete value.
func TestRollup_RerunIsIdempotentAndNeverShrinks(t *testing.T) {
	e := newRollupEnv(t)
	base := e.seedCompleteHours(t)
	ctx := context.Background()

	count := func() (n int) {
		if err := e.pool.QueryRow(ctx, `SELECT count(*) FROM telemetry_hourly WHERE device_name=$1`, e.device).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	deadline := time.Now().Add(20 * time.Second)
	var first int
	for first == 0 && time.Now().Before(deadline) {
		if _, err := e.job.RollupDevice(ctx, e.device, base.UnixMilli(), 0); err != nil {
			t.Fatal(err)
		}
		first = count()
		time.Sleep(300 * time.Millisecond)
	}
	if first == 0 {
		t.Fatal("no rollup rows were written")
	}
	for i := 0; i < 3; i++ {
		if _, err := e.job.RollupDevice(ctx, e.device, base.UnixMilli(), 0); err != nil {
			t.Fatal(err)
		}
	}
	if n := count(); n != first {
		t.Fatalf("rows after reruns = %d, want %d", n, first)
	}

	var samples int32
	var avg float64
	read := func() {
		if err := e.pool.QueryRow(ctx,
			`SELECT samples, avg_value FROM telemetry_hourly WHERE device_name=$1 AND field='oilLevelMm' AND hour_start=$2`,
			e.device, base).Scan(&samples, &avg); err != nil {
			t.Fatal(err)
		}
	}
	read()
	up := func(samples int32, avg float64) {
		if err := e.queries.UpsertTelemetryHourly(ctx, db.UpsertTelemetryHourlyParams{
			DeviceName: e.device, Tank: 1, Field: "oilLevelMm", HourStartMs: base.UnixMilli(),
			AvgValue: avg, MinValue: avg, MaxValue: avg, Samples: samples,
		}); err != nil {
			t.Fatal(err)
		}
	}
	complete, completeAvg := samples, avg
	up(complete-10, -1)
	if read(); samples != complete || avg != completeAvg {
		t.Errorf("a ragged recomputation replaced a complete hour: samples %d avg %v", samples, avg)
	}
	up(complete+1, 42)
	if read(); samples != complete+1 || avg != 42 {
		t.Errorf("a recomputation with more samples (late replay) was not applied: samples %d avg %v", samples, avg)
	}
}

// TestRollup_PassClosesAGapLongerThanTheRecomputeWindow: a job that was down while several hours
// passed resumes from the hour after the last stored one, not just from "recompute ago".
func TestRollup_PassClosesAGapLongerThanTheRecomputeWindow(t *testing.T) {
	e := newRollupEnv(t)
	base := e.seedCompleteHours(t)
	ctx := context.Background()

	// Only the first seeded hour was ever rolled up.
	deadline := time.Now().Add(20 * time.Second)
	for {
		if _, err := e.job.RollupDevice(ctx, e.device, base.UnixMilli(), base.Add(time.Hour).UnixMilli()); err != nil {
			t.Fatal(err)
		}
		var n int
		e.pool.QueryRow(ctx, `SELECT count(*) FROM telemetry_hourly WHERE device_name=$1`, e.device).Scan(&n)
		if n > 0 || time.Now().After(deadline) {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	e.pool.Exec(ctx, `DELETE FROM telemetry_hourly WHERE device_name=$1 AND hour_start >= $2`, e.device, base.Add(time.Hour))

	// A one-minute recompute window alone would start at "now", leaving base+1h unfilled.
	j := rollup.New(rollup.Config{TB: e.tb, Queries: e.queries, Recompute: time.Minute, Logf: t.Logf})
	j.Pass(ctx)

	var n int
	if err := e.pool.QueryRow(ctx,
		`SELECT count(*) FROM telemetry_hourly WHERE device_name=$1 AND hour_start=$2`, e.device, base.Add(time.Hour)).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n == 0 {
		t.Fatal("the hour after the last stored one was not filled in")
	}
}

// TestRollup_HistoryIsClippedToTheCurrentCustomer: the rollup is the previous owner's data too.
func TestRollup_HistoryIsClippedToTheCurrentCustomer(t *testing.T) {
	e := newRollupEnv(t)
	ctx := context.Background()
	h := time.Now().UTC().Truncate(time.Hour)

	for i := 10; i >= 5; i-- {
		if err := e.queries.UpsertTelemetryHourly(ctx, db.UpsertTelemetryHourlyParams{
			DeviceName: e.device, Tank: 1, Field: "oilLevelMm", HourStartMs: h.Add(-time.Duration(i) * time.Hour).UnixMilli(),
			AvgValue: float64(i), MinValue: float64(i), MaxValue: float64(i), Samples: 120,
		}); err != nil {
			t.Fatal(err)
		}
	}
	e.withRetention(time.Hour)
	// Joined its current customer at H-8h+10min: the first hour the new owner may read is
	// ceil(...)+1h = H-6h (the same allowance as the raw path).
	if _, err := e.pool.Exec(ctx, `UPDATE devices SET customer_assigned_at=$2 WHERE device_name=$1`,
		e.device, h.Add(-8*time.Hour+10*time.Minute)); err != nil {
		t.Fatal(err)
	}

	for _, role := range []string{"customer_admin", "platform_admin"} {
		e.setRole(role)
		got := fetchHistory(t, e.testEnv, e.device, hourlyQuery(h.Add(-10*time.Hour), h.Add(-4*time.Hour), "1h", "avg"), http.StatusOK)
		if g := fmt.Sprint(seriesOf(got)); g != "[6 5]" {
			t.Errorf("%s sees %s, want [6 5] (hours before H-6h belong to the previous owner)", role, g)
		}
	}
}

// TestRollup_HistoryOfADeviceMissingInThingsBoardIs404ForAnyWindow: a device that is in the business
// database but not in ThingsBoard answers 404 even when the window is served entirely from rollups
// or predates the assignment, so the answer does not depend on which window was asked for.
func TestRollup_HistoryOfADeviceMissingInThingsBoardIs404ForAnyWindow(t *testing.T) {
	e := newRollupEnv(t)
	ctx := context.Background()
	const ghost = "rollup-ghost-device"
	t.Cleanup(func() { e.pool.Exec(ctx, `DELETE FROM devices WHERE device_name = $1`, ghost) })
	if _, err := e.pool.Exec(ctx, `INSERT INTO devices (device_name, customer_id) VALUES ($1, `+defaultCustomerSQL+`)`, ghost); err != nil {
		t.Fatal(err)
	}
	e.setRole("platform_admin")
	e.withRetention(24 * time.Hour)

	old := time.Now().UTC().Truncate(time.Hour).Add(-10 * 24 * time.Hour)
	q := hourlyQuery(old, old.Add(2*time.Hour), "1h", "avg")
	q.Set("device", ghost)
	if rec := e.authed("GET", "/api/telemetry/history?"+q.Encode(), nil); rec.Code != http.StatusNotFound {
		t.Errorf("window older than retention: status %d, want 404 (%s)", rec.Code, rec.Body.String())
	}
}

// TestRollup_FirstPassReachesBeyondAnyFixedLookbackAndKeepsRemovedTanks: with the TTL off the raw data
// can be arbitrarily old, and the unit may once have had more tanks than it reports now. A first pass
// must summarise both, or the TTL would delete them unsummarised.
func TestRollup_FirstPassReachesBeyondAnyFixedLookbackAndKeepsRemovedTanks(t *testing.T) {
	e := newRollupEnv(t)
	ctx := context.Background()
	e.seedCompleteHours(t) // current tankCount is 1

	old := time.Now().UTC().Truncate(time.Hour).Add(-20 * 24 * time.Hour).Add(10 * time.Minute)
	postDeviceTelemetry(t, e.token, []byte(fmt.Sprintf(
		`{"ts":%d,"values":{"tankCount":2,"tank2.oilLevelMm":321}}`, old.UnixMilli())))

	deadline := time.Now().Add(30 * time.Second)
	for {
		if _, err := e.job.RollupDevice(ctx, e.device, 0, 0); err != nil {
			t.Fatal(err)
		}
		var n int
		e.pool.QueryRow(ctx, `SELECT count(*) FROM telemetry_hourly WHERE device_name=$1 AND tank=2 AND hour_start=$2`,
			e.device, old.Truncate(time.Hour)).Scan(&n)
		if n > 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("the 20-day-old reading of the removed tank 2 was not summarised")
		}
		time.Sleep(300 * time.Millisecond)
	}
}
