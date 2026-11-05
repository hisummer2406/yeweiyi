package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

	"yeweiyi-cloud/internal/store/db"
)

func newBindDeviceName(t *testing.T) string {
	t.Helper()
	return fmt.Sprintf("api-bind-%d", time.Now().UnixNano())
}

// TestMintPairingSecret_ForbiddenForNonAdmin is the regression test for the P1 found in review of
// PR #11 (round 6): round 5's fix reused ThingsBoard's own device access token as the pairing
// proof, which leaked the device's actual MQTT identity to anyone who saw the QR code. This test
// pins the replacement design's own new attack surface — minting must stay platform_admin-only, or
// any customer_user could mint-then-bind themselves onto someone else's already-provisioned device,
// silently reopening round 5's takeover through a different door.
func TestMintPairingSecret_ForbiddenForNonAdmin(t *testing.T) {
	env := newTestEnv(t) // default role is customer_user
	deviceName := newBindDeviceName(t)

	rec := env.authed("POST", "/api/devices/"+deviceName+"/pairing-secret", nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403, got %d: %s", rec.Code, rec.Body.String())
	}

	var count int
	if err := env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM device_pairing_secrets WHERE device_name = $1`, deviceName).Scan(&count); err != nil {
		t.Fatalf("check secret table: %v", err)
	}
	if count != 0 {
		t.Fatal("a non-admin's rejected mint attempt must not create a pairing secret")
	}
}

func mustUserID(t *testing.T, env *testEnv) int64 {
	t.Helper()
	var id int64
	if err := env.pool.QueryRow(context.Background(), `SELECT id FROM users WHERE username = $1`, env.username).Scan(&id); err != nil {
		t.Fatalf("look up user id: %v", err)
	}
	return id
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
