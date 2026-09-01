package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"yeweiyi-cloud/internal/store/db"
)

func TestBindDevice_RequiresDeviceName(t *testing.T) {
	env := newTestEnv(t)

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBindDevice_CreatesDeviceRowAndBindingForBrandNewDevice(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-bind-brandnew-device"
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup device: %v", err)
		}
	})

	// Deliberately not calling env.seedDevice — this device has never sent an MQTT message, so it
	// has no `devices` row yet. Binding must still succeed (EnsureDevice creates it).
	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName}))
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	if _, err := env.queries.GetDevice(context.Background(), deviceName); err != nil {
		t.Fatalf("expected devices row to exist after bind: %v", err)
	}

	// The binding should now authorize a device-scoped read that would otherwise 403. The command
	// listing is used as the probe because it needs neither ThingsBoard nor any ingested data —
	// an owned device with no commands is simply an empty list.
	if rec := env.authed("GET", "/api/devices/"+deviceName+"/commands", nil); rec.Code != http.StatusOK {
		t.Fatalf("expected 200 (owned, no commands yet), got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBindDevice_IsIdempotent(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-bind-idempotent-device"
	env.seedDevice(deviceName)

	for i := 0; i < 2; i++ {
		rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName}))
		if rec.Code != http.StatusOK {
			t.Fatalf("bind attempt %d: expected 200, got %d: %s", i, rec.Code, rec.Body.String())
		}
	}
}

func TestListDevices_ReturnsOnlyBoundDevices(t *testing.T) {
	env := newTestEnv(t)
	const boundDevice = "api-test-list-devices-bound"
	const unboundDevice = "api-test-list-devices-unbound"
	env.ownDevice(boundDevice)
	env.seedDevice(unboundDevice) // exists, but never bound to env's user — must not appear

	rec := env.authed("GET", "/api/devices", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp struct {
		Rows []db.Device `json:"rows"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(resp.Rows) != 1 || resp.Rows[0].DeviceName != boundDevice {
		t.Fatalf("expected exactly [%s], got: %+v", boundDevice, resp.Rows)
	}
}

func TestListDevices_EmptyForUserWithNoBoundDevices(t *testing.T) {
	env := newTestEnv(t)

	rec := env.authed("GET", "/api/devices", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Rows []db.Device `json:"rows"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(resp.Rows) != 0 {
		t.Fatalf("expected no rows, got: %+v", resp.Rows)
	}
}

func TestUnbindDevice_RemovesAuthorization(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-unbind-device"
	env.ownDevice(deviceName)

	if rec := env.authed("GET", "/api/devices/"+deviceName+"/commands", nil); rec.Code != http.StatusOK {
		t.Fatalf("expected 200 before unbind (owned), got %d: %s", rec.Code, rec.Body.String())
	}

	rec := env.authed("POST", "/api/devices/unbind", jsonBody(t, map[string]any{"deviceName": deviceName}))
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	if rec := env.authed("GET", "/api/devices/"+deviceName+"/commands", nil); rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 after unbind, got %d: %s", rec.Code, rec.Body.String())
	}
}
