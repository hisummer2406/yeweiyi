package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"
)

// Optional tank / direction filters on /api/alarms and /api/oil-in-out (#84): filtered in SQL before
// LIMIT/OFFSET, so a page full of another tank's (or the other direction's) records cannot make the
// wanted ones look absent, and a named tank the caller may not see is refused, never answered empty.

const filterWindow = "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

func (g *grantEnv) seedAlarmAt(t *testing.T, id string, tank int, at time.Time) {
	t.Helper()
	raw, _ := json.Marshal(map[string]any{"alarmId": id, "tank": tank, "type": "HIGH_LEVEL", "severity": "MAJOR",
		"status": "ACTIVE", "text": id, "value": 1.0, "tsMs": at.UnixMilli()})
	if _, err := g.env.pool.Exec(context.Background(), `
		INSERT INTO alarm_events (device_name, alarm_id, status, occurred_at, env_ts, payload, customer_id)
		VALUES ($1, $2, 'ACTIVE', $3, $4, $5, $6)`,
		g.device, id, at, at.UnixMilli(), raw, g.cust); err != nil {
		t.Fatalf("seed alarm: %v", err)
	}
}

func (g *grantEnv) seedStockAt(t *testing.T, id string, tank int, direction string, at time.Time) {
	t.Helper()
	raw, _ := json.Marshal(map[string]any{"eventId": id, "tank": tank, "direction": direction, "oilType": "0号柴油",
		"beginTimeMs": at.Add(-time.Hour).UnixMilli(), "endTimeMs": at.UnixMilli(),
		"beginLevelMm": 400.0, "endLevelMm": 1600.0, "beginVolumeL": 6000.0, "endVolumeL": 24000.0,
		"netVolumeL": 18000.0, "tsMs": at.UnixMilli()})
	if _, err := g.env.pool.Exec(context.Background(), `
		INSERT INTO stock_events (device_name, event_id, occurred_at, env_ts, report_ts, payload, customer_id)
		VALUES ($1, $2, $3, $4, ARRAY[$4::bigint], $5, $6)`,
		g.device, id, at, at.UnixMilli(), raw, g.cust); err != nil {
		t.Fatalf("seed stock: %v", err)
	}
}

func (g *grantEnv) alarmIDs(t *testing.T, url, token string) []string {
	t.Helper()
	code, body := g.call(t, "GET", url, token, nil)
	if code != http.StatusOK {
		t.Fatalf("alarms: %d %s", code, body)
	}
	var rows []struct {
		AlarmID string `json:"alarmId"`
		Tank    int    `json:"tank"`
	}
	if err := json.Unmarshal([]byte(body), &rows); err != nil {
		t.Fatalf("decode %s: %v", body, err)
	}
	ids := []string{}
	for _, r := range rows {
		ids = append(ids, r.AlarmID)
	}
	return ids
}

type stockPage struct {
	Rows []struct {
		EventID   string `json:"eventId"`
		Tank      int    `json:"tank"`
		Direction string `json:"direction"`
	} `json:"rows"`
	Truncated bool `json:"truncated"`
}

func (g *grantEnv) stockPage(t *testing.T, url, token string) stockPage {
	t.Helper()
	code, body := g.call(t, "GET", url, token, nil)
	if code != http.StatusOK {
		t.Fatalf("oil-in-out: %d %s", code, body)
	}
	var p stockPage
	if err := json.Unmarshal([]byte(body), &p); err != nil {
		t.Fatalf("decode %s: %v", body, err)
	}
	return p
}

func sameStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestRecordsFilter_AlarmTankIsFilteredBeforePaging(t *testing.T) {
	g := newGrantEnv(t)
	base := time.Now().Add(-48 * time.Hour).UTC().Truncate(time.Second)
	// Tank 2's five alarms are the oldest; sixty newer ones of tank 1 fill every unfiltered first page.
	for i := 0; i < 5; i++ {
		g.seedAlarmAt(t, fmt.Sprintf("t2-%d", i), 2, base.Add(time.Duration(i)*time.Minute))
	}
	for i := 0; i < 60; i++ {
		g.seedAlarmAt(t, fmt.Sprintf("t1-%02d", i), 1, base.Add(time.Hour+time.Duration(i)*time.Minute))
	}
	url := "/api/alarms?device=" + g.device + filterWindow + "&sort=false"

	first := g.alarmIDs(t, url+"&limit=50", g.admin)
	if len(first) != 50 || first[0] != "t1-59" {
		t.Fatalf("unfiltered first page = %d rows starting %v: the filter must not change the default", len(first), first[:1])
	}

	page1 := g.alarmIDs(t, url+"&tank=2&limit=3", g.admin)
	if !sameStrings(page1, []string{"t2-4", "t2-3", "t2-2"}) {
		t.Errorf("tank 2, first page = %v, want its three newest", page1)
	}
	page2 := g.alarmIDs(t, url+"&tank=2&limit=3&offset=3", g.admin)
	if !sameStrings(page2, []string{"t2-1", "t2-0"}) {
		t.Errorf("tank 2, second page = %v, want the remaining two", page2)
	}
	past := g.alarmIDs(t, url+"&tank=2&limit=3&offset=6", g.admin)
	if len(past) != 0 {
		t.Errorf("tank 2 past the end = %v, want []", past)
	}
	if none := g.alarmIDs(t, url+"&tank=3", g.admin); len(none) != 0 {
		t.Errorf("tank 3 has no alarms but got %v", none)
	}
}

func TestRecordsFilter_StockTankAndDirection(t *testing.T) {
	g := newGrantEnv(t)
	base := time.Now().Add(-48 * time.Hour).UTC().Truncate(time.Second)
	// Interleaved: per minute, tank 1 IN, tank 1 OUT, tank 2 IN, tank 2 OUT.
	n := 0
	for i := 0; i < 10; i++ {
		for _, tank := range []int{1, 2} {
			for _, dir := range []string{"IN", "OUT"} {
				g.seedStockAt(t, fmt.Sprintf("s%02d-t%d-%s", i, tank, dir), tank, dir, base.Add(time.Duration(n)*time.Minute))
				n++
			}
		}
	}
	url := "/api/oil-in-out?device=" + g.device + filterWindow + "&sort=false"

	p := g.stockPage(t, url+"&tank=2&direction=OUT&limit=4", g.admin)
	if len(p.Rows) != 4 || !p.Truncated {
		t.Fatalf("tank 2 OUT, limit 4: %d rows, truncated=%v; want 4 and more to come", len(p.Rows), p.Truncated)
	}
	for _, r := range p.Rows {
		if r.Tank != 2 || r.Direction != "OUT" {
			t.Fatalf("tank 2 OUT page holds %+v", r)
		}
	}
	if p.Rows[0].EventID != "s09-t2-OUT" {
		t.Errorf("newest first: got %s", p.Rows[0].EventID)
	}
	last := g.stockPage(t, url+"&tank=2&direction=OUT&limit=4&offset=8", g.admin)
	if got := len(last.Rows); got != 2 {
		t.Errorf("tank 2 OUT, last page = %d rows, want 2 (10 in all)", got)
	}
	if last.Truncated {
		t.Errorf("last page says truncated")
	}

	in := g.stockPage(t, url+"&direction=IN&limit=500", g.admin)
	if len(in.Rows) != 20 {
		t.Errorf("direction IN across tanks = %d rows, want 20", len(in.Rows))
	}
	all := g.stockPage(t, url+"&limit=500", g.admin)
	if len(all.Rows) != 40 {
		t.Errorf("no filter = %d rows, want all 40", len(all.Rows))
	}
}

func TestRecordsFilter_EmployeeNamesOnlyGrantedTanks(t *testing.T) {
	g := newGrantEnv(t)
	if code, body := g.set(t, g.platform, g.empID, g.device, 1); code != http.StatusOK {
		t.Fatalf("grant tank 1: %d %s", code, body)
	}
	at := time.Now().Add(-time.Hour).UTC()
	g.seedAlarmAt(t, "a1", 1, at)
	g.seedAlarmAt(t, "a2", 2, at)
	g.seedStockAt(t, "e2", 2, "IN", at)

	for _, path := range []string{"/api/alarms", "/api/oil-in-out"} {
		url := path + "?device=" + g.device + filterWindow
		if code, body := g.call(t, "GET", url+"&tank=1", g.emp, nil); code != http.StatusOK {
			t.Errorf("%s granted tank: %d %s", path, code, body)
		}
		// Ungranted and non-existent tanks answer the same refusal, never an empty list.
		c2, b2 := g.call(t, "GET", url+"&tank=2", g.emp, nil)
		c9, b9 := g.call(t, "GET", url+"&tank=9", g.emp, nil)
		if c2 != http.StatusForbidden || c9 != http.StatusForbidden || b2 != b9 {
			t.Errorf("%s ungranted tank: %d %s / non-existent: %d %s, want the same 403", path, c2, b2, c9, b9)
		}
		var body struct {
			Code string `json:"code"`
		}
		json.Unmarshal([]byte(b2), &body)
		if body.Code != "tank_not_authorized" {
			t.Errorf("%s ungranted tank code = %q, want tank_not_authorized", path, body.Code)
		}
	}
	got := g.alarmIDs(t, "/api/alarms?device="+g.device+filterWindow+"&tank=1", g.emp)
	if !sameStrings(got, []string{"a1"}) {
		t.Errorf("employee tank 1 alarms = %v, want [a1]", got)
	}
	if code, body := g.call(t, "GET", "/api/alarms?device="+g.device+filterWindow+"&tank=2", g.admin, nil); code != http.StatusOK {
		t.Errorf("admin may name any tank: %d %s", code, body)
	}
}

func TestRecordsFilter_InvalidValues(t *testing.T) {
	env := newTestEnv(t)
	dev := fmt.Sprintf("filter-dev-%d", time.Now().UnixNano())
	env.ownDevice(dev)
	for _, tc := range []struct{ query, code string }{
		{"/api/alarms?device=" + dev + "&tank=0", "invalid_tank"},
		{"/api/alarms?device=" + dev + "&tank=x", "invalid_tank"},
		{"/api/oil-in-out?device=" + dev + "&tank=65", "invalid_tank"},
		{"/api/oil-in-out?device=" + dev + "&direction=in", "invalid_direction"},
	} {
		rec := env.authed("GET", tc.query, nil)
		var body struct {
			Code string `json:"code"`
		}
		json.Unmarshal(rec.Body.Bytes(), &body)
		if rec.Code != http.StatusBadRequest || body.Code != tc.code {
			t.Errorf("%s: %d %s, want 400 %s", tc.query, rec.Code, rec.Body.String(), tc.code)
		}
	}
}
