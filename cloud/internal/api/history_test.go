package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"yeweiyi-cloud/internal/protocol"
)

// seedHistory publishes one tank1.oilLevelMm sample every 30 s for two hours starting at base,
// valued at "minutes since base", except for a 10-minute hole at minutes 90–100 (a device that was
// offline). Returns the values keyed by minute offset so tests can compute expectations from the
// data rather than from remembered constants.
func seedHistory(t *testing.T, token string, base time.Time) map[float64]float64 {
	t.Helper()
	key := protocol.TankKey(1, protocol.KeyOilLevelMm)
	samples := map[float64]float64{}
	var batch []map[string]any
	for i := 0; i < 240; i++ {
		min := float64(i) / 2
		if min >= 90 && min < 100 {
			continue
		}
		samples[min] = min
		batch = append(batch, map[string]any{
			"ts":     base.Add(time.Duration(i) * 30 * time.Second).UnixMilli(),
			"values": map[string]any{key: min},
		})
	}
	for start := 0; start < len(batch); start += 60 {
		end := min(start+60, len(batch))
		payload, err := json.Marshal(batch[start:end])
		if err != nil {
			t.Fatal(err)
		}
		postDeviceTelemetry(t, token, payload)
	}
	return samples
}

type historyBody struct {
	Series map[string][]struct {
		Time  time.Time `json:"time"`
		Value float64   `json:"value"`
	} `json:"series"`
	BeginTime time.Time `json:"beginTime"`
	EndTime   time.Time `json:"endTime"`
}

func fetchHistory(t *testing.T, env *testEnv, device string, q url.Values, want int) historyBody {
	t.Helper()
	path := "/api/devices/" + device + "/telemetry/history?" + q.Encode()
	rec := env.authed("GET", path, nil)
	if rec.Code != want {
		t.Fatalf("GET %s: expected %d, got %d: %s", path, want, rec.Code, rec.Body.String())
	}
	var out historyBody
	if want == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatalf("decode: %v (%s)", err, rec.Body.String())
		}
	}
	return out
}

func TestTelemetryHistory_AggregatesAgainstRealThingsBoard(t *testing.T) {
	env, _, device, token := newStockEnv(t)
	// The fixture device was assigned to its customer just now, which would clip away the
	// backdated samples seeded below; this test is about aggregation, not the assignment clip.
	if _, err := env.pool.Exec(context.Background(),
		"UPDATE devices SET customer_assigned_at = NULL WHERE device_name = $1", device); err != nil {
		t.Fatal(err)
	}

	base := time.Now().UTC().Truncate(time.Hour).Add(-3 * time.Hour)
	samples := seedHistory(t, token, base)
	field := protocol.KeyOilLevelMm

	query := func(interval, agg string) url.Values {
		q := url.Values{
			"tank": {"1"}, "fields": {field}, "interval": {interval},
			// Deliberately unaligned: the API must widen to bucket boundaries.
			"beginTime": {base.Add(7 * time.Second).Format(time.RFC3339)},
			"endTime":   {base.Add(2*time.Hour - 7*time.Second).Format(time.RFC3339)},
		}
		if agg != "" {
			q.Set("agg", agg)
		}
		return q
	}

	// Data is eventually visible through ThingsBoard's read path; wait for the last bucket.
	var got historyBody
	deadline := time.Now().Add(20 * time.Second)
	for {
		got = fetchHistory(t, env, device, query("1h", ""), http.StatusOK)
		if len(got.Series[field]) == 2 || time.Now().After(deadline) {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}

	if !got.BeginTime.Equal(base) || !got.EndTime.Equal(base.Add(2*time.Hour)) {
		t.Errorf("window = [%v, %v), want it widened to [%v, %v)", got.BeginTime, got.EndTime, base, base.Add(2*time.Hour))
	}
	pts := got.Series[field]
	if len(pts) != 2 {
		t.Fatalf("1h buckets = %d, want 2: %+v", len(pts), pts)
	}
	// Bucket START, not ThingsBoard's midpoint.
	if !pts[0].Time.Equal(base) || !pts[1].Time.Equal(base.Add(time.Hour)) {
		t.Errorf("bucket times = %v, %v; want %v, %v", pts[0].Time, pts[1].Time, base, base.Add(time.Hour))
	}
	for i, p := range pts {
		lo, hi := float64(i*60), float64(i*60+60)
		var sum, n float64
		for m, v := range samples {
			if m >= lo && m < hi {
				sum += v
				n++
			}
		}
		if want := sum / n; math.Abs(p.Value-want) > 1e-9 {
			t.Errorf("hour %d avg = %v, want %v computed from the seeded samples", i, p.Value, want)
		}
	}

	// min / max reduce rather than average.
	if mx := fetchHistory(t, env, device, query("1h", "max"), http.StatusOK).Series[field]; len(mx) != 2 || mx[0].Value != 59.5 {
		t.Errorf("1h max = %+v, want first bucket 59.5", mx)
	}
	if mn := fetchHistory(t, env, device, query("1h", "min"), http.StatusOK).Series[field]; len(mn) != 2 || mn[0].Value != 0 || mn[1].Value != 60 {
		t.Errorf("1h min = %+v, want 0 and 60", mn)
	}

	// The offline hole must be absent, not a zero: 5-minute buckets at minutes 90 and 95 have no
	// samples, so 24 buckets in the window become 22.
	five := fetchHistory(t, env, device, query("5m", ""), http.StatusOK).Series[field]
	if len(five) != 22 {
		t.Fatalf("5m buckets = %d, want 22 (24 minus the 2 empty ones)", len(five))
	}
	for _, p := range five {
		off := p.Time.Sub(base)
		if off >= 90*time.Minute && off < 100*time.Minute {
			t.Errorf("bucket at +%v exists but the device sent nothing then: %+v", off, p)
		}
		if p.Value == 0 && off != 0 {
			t.Errorf("bucket at +%v is a fabricated zero", off)
		}
	}
}

func TestTelemetryHistory_RejectsBadParameters(t *testing.T) {
	env, _, device, _ := newStockEnv(t)

	ok := func() url.Values {
		return url.Values{"tank": {"1"}, "fields": {"oilLevelMm"}, "interval": {"1h"}}
	}
	cases := map[string]func(url.Values){
		"missing tank":       func(q url.Values) { q.Del("tank") },
		"tank zero":          func(q url.Values) { q.Set("tank", "0") },
		"tank above ceiling": func(q url.Values) { q.Set("tank", fmt.Sprint(protocol.MaxTankCount+1)) },
		"missing fields":     func(q url.Values) { q.Del("fields") },
		"string field":       func(q url.Values) { q.Set("fields", "oilType") },
		"unknown field":      func(q url.Values) { q.Set("fields", "oilLevelMm,bogus") },
		"free-form interval": func(q url.Values) { q.Set("interval", "7s") },
		"missing interval":   func(q url.Values) { q.Del("interval") },
		"unknown agg":        func(q url.Values) { q.Set("agg", "median") },
		"begin after end": func(q url.Values) {
			q.Set("beginTime", "2026-01-02T00:00:00Z")
			q.Set("endTime", "2026-01-01T00:00:00Z")
		},
		"window beyond 7 days": func(q url.Values) {
			q.Set("beginTime", "2026-01-01T00:00:00Z")
			q.Set("endTime", "2026-01-09T00:00:00Z")
		},
		"too many buckets": func(q url.Values) {
			q.Set("interval", "1m")
			q.Set("beginTime", "2026-01-01T00:00:00Z")
			q.Set("endTime", "2026-01-04T00:00:00Z")
		},
		"bad time": func(q url.Values) { q.Set("beginTime", "yesterday") },
	}
	for name, mutate := range cases {
		t.Run(strings.ReplaceAll(name, " ", "_"), func(t *testing.T) {
			q := ok()
			mutate(q)
			fetchHistory(t, env, device, q, http.StatusBadRequest)
		})
	}
}

// TestTelemetryHistory_BucketCeilingMatchesThingsBoard pins the boundary: exactly the platform's
// 700 intervals is served, one more is refused by us (with a 400) instead of being passed on to
// ThingsBoard to fail there.
func TestTelemetryHistory_BucketCeilingMatchesThingsBoard(t *testing.T) {
	env, _, device, _ := newStockEnv(t)

	begin := time.Now().UTC().Truncate(time.Minute).Add(-24 * time.Hour)
	q := func(minutes int) url.Values {
		return url.Values{
			"tank": {"1"}, "fields": {"oilLevelMm"}, "interval": {"1m"},
			"beginTime": {begin.Format(time.RFC3339)},
			"endTime":   {begin.Add(time.Duration(minutes) * time.Minute).Format(time.RFC3339)},
		}
	}
	fetchHistory(t, env, device, q(700), http.StatusOK)
	fetchHistory(t, env, device, q(701), http.StatusBadRequest)
}

// A device moved to another customer must not expose the previous customer's history: ThingsBoard
// telemetry carries no customer, so the query is clipped at devices.customer_assigned_at.
func TestTelemetryHistory_ClippedToCurrentCustomerAssignment(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	base := time.Now().UTC().Truncate(time.Hour).Add(-3 * time.Hour)
	seedHistory(t, token, base)
	field := protocol.KeyOilLevelMm
	q := url.Values{
		"tank": {"1"}, "fields": {field}, "interval": {"5m"},
		"beginTime": {base.Format(time.RFC3339)},
		"endTime":   {base.Add(2 * time.Hour).Format(time.RFC3339)},
	}

	// Assigned mid-bucket at +61m: the straddling 60–65m bucket mixes owners, so the window starts at +65m.
	assigned := base.Add(61 * time.Minute)
	if _, err := env.pool.Exec(context.Background(),
		"UPDATE devices SET customer_assigned_at = $2 WHERE device_name = $1", device, assigned); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = env.pool.Exec(context.Background(), "UPDATE devices SET customer_assigned_at = NULL WHERE device_name = $1", device)
	})

	wantBegin := base.Add(65 * time.Minute)
	var got historyBody
	deadline := time.Now().Add(20 * time.Second)
	for {
		got = fetchHistory(t, env, device, q, http.StatusOK)
		if len(got.Series[field]) > 0 || time.Now().After(deadline) {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if !got.BeginTime.Equal(wantBegin) {
		t.Errorf("beginTime = %v, want %v (assignment rounded up to a bucket)", got.BeginTime, wantBegin)
	}
	if len(got.Series[field]) == 0 {
		t.Fatal("no points after the assignment time")
	}
	for _, p := range got.Series[field] {
		if p.Time.Before(wantBegin) {
			t.Errorf("point at %v predates the assignment (%v)", p.Time, assigned)
		}
	}

	// A window entirely before the assignment is empty, not an error and not the old data.
	q.Set("endTime", base.Add(30*time.Minute).Format(time.RFC3339))
	got = fetchHistory(t, env, device, q, http.StatusOK)
	if n := len(got.Series[field]); n != 0 {
		t.Errorf("window before assignment returned %d points, want 0", n)
	}
}
