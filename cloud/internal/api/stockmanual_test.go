package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

	"yeweiyi-cloud/internal/control"
)

// cloud#69: manual stock entry. Real PostgreSQL, no ThingsBoard needed: the record is written by the
// cloud and read back through /api/oil-in-out, which serves the business database.

type manualEnv struct {
	env     *testEnv
	device  string
	cust    int64
	admin   string
	adminID int64
}

func newManualEnv(t *testing.T) *manualEnv {
	t.Helper()
	env := newTestEnvWith(t, nil)
	m := &manualEnv{env: env, device: fmt.Sprintf("man-%d", time.Now().UnixNano()), cust: env.defaultCustomerID()}
	env.seedDeviceIn(m.device, m.cust)
	// Reads clip at the moment of assignment; backdate it so the tests can enter past records.
	if _, err := env.pool.Exec(context.Background(),
		`UPDATE devices SET customer_assigned_at = now() - interval '60 days' WHERE device_name = $1`, m.device); err != nil {
		t.Fatal(err)
	}
	m.admin, m.adminID = env.userIn(control.RoleCustomerAdmin, m.cust)
	t.Cleanup(func() {
		ctx := context.Background()
		env.pool.Exec(ctx, `DELETE FROM stock_events WHERE device_name = $1`, m.device)
		env.pool.Exec(ctx, `DELETE FROM audit_log WHERE device_name = $1`, m.device)
	})
	return m
}

func (m *manualEnv) post(t *testing.T, token string, body map[string]any) (int, map[string]any) {
	t.Helper()
	rec := m.env.do("POST", "/api/devices/"+m.device+"/stock-events", []byte(token), jsonBody(t, body))
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

func entry(over map[string]any) map[string]any {
	b := map[string]any{
		"direction": "IN", "tank": 3, "time": time.Now().Add(-48 * time.Hour).Format(time.RFC3339),
		"beforeLevelMm": 1333.3, "volumeL": 540, "reason": "断电期间漏记",
	}
	for k, v := range over {
		b[k] = v
	}
	return b
}

func (m *manualEnv) list(t *testing.T, token string) []map[string]any {
	t.Helper()
	path := fmt.Sprintf("/api/oil-in-out?device=%s&beginTime=%s", m.device, time.Now().Add(-30*24*time.Hour).UTC().Format(time.RFC3339))
	rec := m.env.do("GET", path, []byte(token), nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("list: %d %s", rec.Code, rec.Body.String())
	}
	return decodeStock(t, rec.Body.Bytes()).Rows
}

func TestManualStock_AddReadBackAndShape(t *testing.T) {
	m := newManualEnv(t)

	code, got := m.post(t, m.admin, entry(nil))
	if code != http.StatusCreated {
		t.Fatalf("IN: %d %v", code, got)
	}
	if got["source"] != "MANUAL" || got["origin"] != "cloud" || got["direction"] != "IN" || got["tank"] != float64(3) {
		t.Errorf("response = %v", got)
	}
	if got["deliveredVolumeL"] != float64(540) || got["netVolumeL"] != float64(540) || got["beginLevelMm"] != 1333.3 {
		t.Errorf("quantities = %v", got)
	}
	// What the form does not know is null, never 0.
	for _, k := range []string{"endLevelMm", "beginVolumeL", "endVolumeL", "beginTempC", "endTempC", "beginWeightKg", "endWeightKg"} {
		if v, present := got[k]; !present || v != nil {
			t.Errorf("%s = %v (present=%v), want null", k, v, present)
		}
	}
	if id, _ := got["eventId"].(string); len(id) < 10 || id[:7] != "manual-" {
		t.Errorf("eventId = %v", got["eventId"])
	}

	code, out := m.post(t, m.admin, entry(map[string]any{"direction": "OUT", "volumeL": 120.5, "time": time.Now().Add(-24 * time.Hour).Format(time.RFC3339)}))
	if code != http.StatusCreated || out["deliveredVolumeL"] != 120.5 || out["netVolumeL"] != -120.5 {
		t.Errorf("OUT: %d %v (delivered must be positive, the tank change negative)", code, out)
	}

	// Both are served by the ordinary read path, newest first by default.
	rows := m.list(t, m.admin)
	if len(rows) != 2 {
		t.Fatalf("rows = %d", len(rows))
	}
	if rows[0]["origin"] != "cloud" || rows[0]["source"] != "MANUAL" {
		t.Errorf("row = %v", rows[0])
	}

	// And the audit trail says who, what and why.
	var n int
	m.env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE device_name = $1 AND action = 'stock.manual_add' AND outcome = 'allowed'
		   AND detail->>'reason' = '断电期间漏记' AND actor_id = $2`, m.device, m.adminID).Scan(&n)
	if n != 2 {
		t.Errorf("audit rows = %d, want 2", n)
	}
}

func TestManualStock_Validation(t *testing.T) {
	m := newManualEnv(t)
	cases := map[string]struct {
		over map[string]any
		code string
	}{
		"direction":      {map[string]any{"direction": "SIDEWAYS"}, "direction_invalid"},
		"tank 0":         {map[string]any{"tank": 0}, "tank_invalid"},
		"tank 17":        {map[string]any{"tank": 17}, "tank_invalid"},
		"no tank":        {map[string]any{"tank": nil}, "tank_invalid"},
		"negative level": {map[string]any{"beforeLevelMm": -1}, "level_invalid"},
		"no level":       {map[string]any{"beforeLevelMm": nil}, "level_invalid"},
		"zero volume":    {map[string]any{"volumeL": 0}, "volume_invalid"},
		"no volume":      {map[string]any{"volumeL": nil}, "volume_invalid"},
		"no reason":      {map[string]any{"reason": "   "}, "reason_required"},
		"date only":      {map[string]any{"time": "2026-10-14"}, "time_invalid"},
		"no offset":      {map[string]any{"time": "2026-10-14T08:30:00"}, "time_invalid"},
		"future":         {map[string]any{"time": time.Now().Add(time.Hour).Format(time.RFC3339)}, "time_in_future"},
		"before visible": {map[string]any{"time": time.Now().Add(-90 * 24 * time.Hour).Format(time.RFC3339)}, "time_before_visible"},
	}
	for name, c := range cases {
		code, out := m.post(t, m.admin, entry(c.over))
		if code != http.StatusBadRequest || out["code"] != c.code {
			t.Errorf("%s: %d %v, want 400 %s", name, code, out, c.code)
		}
	}
	if rows := m.list(t, m.admin); len(rows) != 0 {
		t.Errorf("rejected requests left %d rows", len(rows))
	}
}

func TestManualStock_RolesAndTenancy(t *testing.T) {
	m := newManualEnv(t)

	emp, _ := m.env.userIn(control.RoleCustomerUser, m.cust)
	if code, _ := m.post(t, emp, entry(nil)); code != http.StatusForbidden {
		t.Errorf("employee: %d, want 403", code)
	}
	foreign, _ := m.env.userIn(control.RoleCustomerAdmin, m.env.newCustomer("other"))
	if code, _ := m.post(t, foreign, entry(nil)); code != http.StatusForbidden {
		t.Errorf("another customer's admin: %d, want 403", code)
	}
	platform, _ := m.env.userIn(control.RolePlatformAdmin, 0)
	if code, out := m.post(t, platform, entry(nil)); code != http.StatusCreated {
		t.Errorf("platform admin: %d %v", code, out)
	}
	if rows := m.list(t, m.admin); len(rows) != 1 {
		t.Errorf("rows = %d, want only the platform admin's entry", len(rows))
	}
}

func TestManualStock_RequestIDMakesRetriesIdempotent(t *testing.T) {
	m := newManualEnv(t)
	body := entry(map[string]any{"requestId": "req-1"})

	code, first := m.post(t, m.admin, body)
	if code != http.StatusCreated {
		t.Fatalf("first: %d %v", code, first)
	}
	code, again := m.post(t, m.admin, body)
	if code != http.StatusOK || again["eventId"] != first["eventId"] {
		t.Errorf("retry: %d %v, want 200 with the same eventId", code, again)
	}
	if rows := m.list(t, m.admin); len(rows) != 1 {
		t.Errorf("a retry made %d rows", len(rows))
	}
	if code, out := m.post(t, m.admin, entry(map[string]any{"requestId": "req-1", "volumeL": 999})); code != http.StatusConflict || out["code"] != "request_id_reused" {
		t.Errorf("same requestId, different content: %d %v, want 409", code, out)
	}
}

func TestManualStock_WithdrawOnlyCloudEntries(t *testing.T) {
	m := newManualEnv(t)
	code, made := m.post(t, m.admin, entry(nil))
	id, _ := made["eventId"].(string)
	if code != http.StatusCreated || id == "" {
		t.Fatalf("setup: %d %v", code, made)
	}
	url := "/api/devices/" + m.device + "/stock-events/"

	// A device-reported record (and a device's own manual backfill) is never touched.
	if _, err := m.env.pool.Exec(context.Background(), `
		INSERT INTO stock_events (device_name, event_id, occurred_at, env_ts, report_ts, payload, customer_id)
		VALUES ($1, 'in-1-1', now() - interval '2 days', 1, ARRAY[1::bigint],
		        '{"eventId":"in-1-1","tank":1,"direction":"IN","source":"MANUAL","tsMs":1,"netVolumeL":5}', $2)`, m.device, m.cust); err != nil {
		t.Fatal(err)
	}
	if rec := m.env.do("DELETE", url+"in-1-1", []byte(m.admin), nil); rec.Code != http.StatusConflict {
		t.Errorf("device record: %d %s, want 409", rec.Code, rec.Body.String())
	}
	if rec := m.env.do("DELETE", url+"manual-nope", []byte(m.admin), nil); rec.Code != http.StatusNotFound {
		t.Errorf("unknown record: %d, want 404", rec.Code)
	}
	emp, _ := m.env.userIn(control.RoleCustomerUser, m.cust)
	if rec := m.env.do("DELETE", url+id, []byte(emp), nil); rec.Code != http.StatusForbidden {
		t.Errorf("employee: %d, want 403", rec.Code)
	}

	if rec := m.env.do("DELETE", url+id, []byte(m.admin), nil); rec.Code != http.StatusNoContent {
		t.Fatalf("withdraw: %d %s", rec.Code, rec.Body.String())
	}
	if rec := m.env.do("DELETE", url+id, []byte(m.admin), nil); rec.Code != http.StatusNoContent {
		t.Errorf("withdraw again: %d, want 204 (idempotent)", rec.Code)
	}
	rows := m.list(t, m.admin)
	if len(rows) != 1 || rows[0]["eventId"] != "in-1-1" {
		t.Errorf("rows after withdraw = %v", rows)
	}

	// Kept for good: soft delete, and the audit row carries the withdrawn content.
	var kept, audited int
	m.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM stock_events WHERE event_id = $1 AND deleted_at IS NOT NULL`, id).Scan(&kept)
	m.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM audit_log WHERE device_name = $1 AND action = 'stock.manual_delete'`, m.device).Scan(&audited)
	if kept != 1 || audited != 1 {
		t.Errorf("soft-deleted rows = %d, audit rows = %d, want 1 and 1", kept, audited)
	}
}
