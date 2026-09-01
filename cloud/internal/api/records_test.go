package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"yeweiyi-cloud/internal/store/db"
)

func TestListAlarms_RequiresDevice(t *testing.T) {
	env := newTestEnv(t)

	rec := env.authed("GET", "/api/alarms", nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

// The "returns real alarms" case moved to alarms_test.go: /api/alarms now reads from ThingsBoard,
// so it needs a real device publishing a real alarm rather than a seeded database row.

func TestListAlarms_ReturnsUnavailableWhenThingsBoardNotConfigured(t *testing.T) {
	env := newTestEnv(t) // no TB wired
	const deviceName = "api-test-alarms-no-tb"
	env.ownDevice(deviceName)

	rec := env.authed("GET", "/api/alarms?device="+deviceName, nil)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestListAlarms_ForbiddenWhenDeviceNotOwned(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-alarms-unowned-device"
	env.seedDevice(deviceName) // exists, but never bound to env's user

	rec := env.authed("GET", "/api/alarms?device="+deviceName, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestListAlarms_Unauthenticated(t *testing.T) {
	env := newTestEnv(t)

	rec := env.do("GET", "/api/alarms?device=x", nil, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestListOilInOut_RequiresDeviceAndReturnsSeededRow(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-oilinout-device"
	env.ownDevice(deviceName)

	if rec := env.authed("GET", "/api/oil-in-out", nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 with no device param, got %d", rec.Code)
	}

	if _, err := env.pool.Exec(context.Background(), `
		INSERT INTO oil_in_out (device_name, device_local_id, oil_id, jiezhi, time, begin_height, end_height, begin_volume, end_volume, begin_weight, end_weight)
		VALUES ($1, 1, 1, '0号柴油', '2026-08-15 09:20+08', 100, 20, 4000, 800, 3400, 700)`, deviceName); err != nil {
		t.Fatalf("seed oil_in_out: %v", err)
	}
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM oil_in_out WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup oil_in_out: %v", err)
		}
	})

	rec := env.authed("GET", "/api/oil-in-out?device="+deviceName+"&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Rows []db.OilInOut `json:"rows"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(resp.Rows) != 1 || resp.Rows[0].BeginHeight != 100 {
		t.Fatalf("unexpected rows: %s", rec.Body.String())
	}
}

func TestListShifts_RequiresDeviceAndReturnsSeededRow(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-shifts-device"
	env.ownDevice(deviceName)

	if rec := env.authed("GET", "/api/shifts", nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 with no device param, got %d", rec.Code)
	}

	if _, err := env.pool.Exec(context.Background(), `
		INSERT INTO commit_works (device_name, device_local_id, work_id, time, end_time, info)
		VALUES ($1, 1, 101, '2026-08-15 08:00+08', '2026-08-15 16:00+08', '{}')`, deviceName); err != nil {
		t.Fatalf("seed commit_works: %v", err)
	}
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM commit_works WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup commit_works: %v", err)
		}
	})

	rec := env.authed("GET", "/api/shifts?device="+deviceName+"&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Rows []db.CommitWork `json:"rows"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(resp.Rows) != 1 || resp.Rows[0].WorkID != 101 {
		t.Fatalf("unexpected rows: %s", rec.Body.String())
	}
}
