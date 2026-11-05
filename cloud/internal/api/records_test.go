package api_test

import (
	"net/http"
	"strings"
	"testing"
)

func TestListAlarms_RequiresDevice(t *testing.T) {
	env := newTestEnv(t)

	rec := env.authed("GET", "/api/alarms", nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

// The record endpoints read the business database, not ThingsBoard: with no ThingsBoard configured
// they still answer, and a device with no records is an empty answer rather than an outage.
func TestListAlarms_NoThingsBoardNeeded(t *testing.T) {
	env := newTestEnv(t) // no TB wired
	const deviceName = "api-test-alarms-no-tb"
	env.ownDevice(deviceName)

	rec := env.authed("GET", "/api/alarms?device="+deviceName, nil)
	if rec.Code != http.StatusOK || strings.TrimSpace(rec.Body.String()) != "[]" {
		t.Fatalf("expected 200 [], got %d: %s", rec.Code, rec.Body.String())
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

func TestListOilInOut_RequiresDevice(t *testing.T) {
	env := newTestEnv(t)

	if rec := env.authed("GET", "/api/oil-in-out", nil); rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 with no device param, got %d", rec.Code)
	}
}

func TestListOilInOut_NoThingsBoardNeeded(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-oilinout-no-tb"
	env.ownDevice(deviceName)

	rec := env.authed("GET", "/api/oil-in-out?device="+deviceName, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	body := decodeStock(t, rec.Body.Bytes())
	if body.Rows == nil || len(body.Rows) != 0 || body.Unparsable != 0 {
		t.Fatalf("expected an empty rows array and no unparsable records, got %+v", body)
	}
}

func TestListOilInOut_ForbiddenWhenDeviceNotOwned(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-oilinout-unowned"
	env.seedDevice(deviceName)

	if rec := env.authed("GET", "/api/oil-in-out?device="+deviceName, nil); rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestShiftsEndpointIsGone pins the removal. 设备协议 V1 has no 交班 uplink on either side, so the
// endpoint could only ever have answered 200 with an empty list — which looks like a working
// feature and is worse than a 404. If someone re-adds it without an uplink, this fails.
func TestShiftsEndpointIsGone(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-shifts-removed"
	env.ownDevice(deviceName)

	rec := env.authed("GET", "/api/shifts?device="+deviceName, nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for the removed /api/shifts, got %d: %s", rec.Code, rec.Body.String())
	}
}
