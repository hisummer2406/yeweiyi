package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// newBindEnv wires a router with a live ThingsBoard client. bindDeviceHandler needs a real
// ThingsBoard to verify a pairing token against a real device's real credentials — unlike
// internal/control's tests, a fake Sender can't stand in here, since the whole point under test is
// that the token has to match what ThingsBoard actually issued.
func newBindEnv(t *testing.T) (*testEnv, *tbclient.Client) {
	t.Helper()
	if conn, err := net.DialTimeout("tcp", "localhost:8081", 2*time.Second); err != nil {
		t.Skipf("ThingsBoard not reachable (%v); start it with: docker compose -f docker-compose.thingsboard.yml up -d", err)
	} else {
		conn.Close()
	}

	tb, err := tbclient.New(tbclient.Config{
		BaseURL:  tbEnvOr("TB_BASE_URL", "http://localhost:8081"),
		Username: tbEnvOr("TB_USERNAME", "tenant@thingsboard.org"),
		Password: tbEnvOr("TB_PASSWORD", "tenant"),
	})
	if err != nil {
		t.Fatalf("tbclient.New: %v", err)
	}
	if err := tb.Login(context.Background()); err != nil {
		t.Skipf("ThingsBoard login failed: %v", err)
	}

	env := newTestEnvWith(t, func(d *api.Deps) { d.TB = tb })
	return env, tb
}

// provisionTBDevice creates a real ThingsBoard device and returns its name and its real,
// ThingsBoard-issued access token — the pairing proof a legitimate QR scan is meant to carry.
// Deliberately does not touch the business `devices`/`user_devices` tables: these tests are about
// what bindDeviceHandler itself does with that proof.
func provisionTBDevice(t *testing.T, tb *tbclient.Client) (deviceName, token string) {
	t.Helper()
	ctx := context.Background()
	deviceName = fmt.Sprintf("api-bind-%d", time.Now().UnixNano())

	dev, err := tb.EnsureDevice(ctx, deviceName, tbclient.DefaultDeviceProfile)
	if err != nil {
		t.Fatalf("provision device in ThingsBoard: %v", err)
	}
	t.Cleanup(func() { tb.DeleteDevice(context.Background(), dev.ID.ID) })

	creds, err := tb.GetDeviceCredentials(ctx, dev.ID.ID)
	if err != nil {
		t.Fatalf("get credentials: %v", err)
	}
	return deviceName, creds.CredentialsID
}

func TestBindDevice_RequiresDeviceName(t *testing.T) {
	env, _ := newBindEnv(t)

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"pairingToken": "irrelevant"}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBindDevice_RequiresPairingToken(t *testing.T) {
	env, _ := newBindEnv(t)

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": "whatever"}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBindDevice_CreatesDeviceRowAndBindingForBrandNewDevice(t *testing.T) {
	env, tb := newBindEnv(t)
	deviceName, token := provisionTBDevice(t, tb)
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup device: %v", err)
		}
	})

	// Deliberately not calling env.seedDevice — this device is new to the *business* database (only
	// ThingsBoard knows about it, via provisionTBDevice above), so this also proves EnsureDevice
	// still creates the row on a verified first bind.
	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": token}))
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	if _, err := env.queries.GetDevice(context.Background(), deviceName); err != nil {
		t.Fatalf("expected devices row to exist after bind: %v", err)
	}

	// The binding should now authorize a device-scoped read that would otherwise 403. The command
	// listing is used as the probe because it needs no ingested data — an owned device with no
	// commands is simply an empty list.
	if rec := env.authed("GET", "/api/devices/"+deviceName+"/commands", nil); rec.Code != http.StatusOK {
		t.Fatalf("expected 200 (owned, no commands yet), got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBindDevice_IsIdempotent(t *testing.T) {
	env, tb := newBindEnv(t)
	deviceName, token := provisionTBDevice(t, tb)

	for i := 0; i < 2; i++ {
		rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": token}))
		if rec.Code != http.StatusOK {
			t.Fatalf("bind attempt %d: expected 200, got %d: %s", i, rec.Code, rec.Body.String())
		}
	}
}

// TestBindDevice_RejectsWrongPairingToken and TestBindDevice_RejectsUnknownDeviceName are the
// regression tests for the P1 found in review of PR #11 (round 5): binding used to accept any
// client-supplied deviceName with no proof of possession at all, so any logged-in user who could
// guess or observe a real deviceName could bind (and from there read telemetry/alarms and issue
// control commands for) a device that was never theirs.
func TestBindDevice_RejectsWrongPairingToken(t *testing.T) {
	env, tb := newBindEnv(t)
	deviceName, _ := provisionTBDevice(t, tb)

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": "not-the-real-token"}))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}

	if owns, err := env.queries.UserOwnsDevice(context.Background(), db.UserOwnsDeviceParams{
		UserID:     mustUserID(t, env),
		DeviceName: deviceName,
	}); err != nil {
		t.Fatalf("check binding: %v", err)
	} else if owns {
		t.Fatal("a wrong pairing token must not create a binding")
	}
}

func TestBindDevice_RejectsUnknownDeviceName(t *testing.T) {
	env, _ := newBindEnv(t)
	deviceName := fmt.Sprintf("api-bind-nonexistent-%d", time.Now().UnixNano())

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": "anything"}))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestBindDevice_UnknownDeviceAndWrongTokenAreIndistinguishable pins the anti-oracle property:
// requireDeviceOwnership's own doc comment (and CLAUDE.md invariant 6) already establish that an
// unauthorized caller must not be able to tell "not yours" from "doesn't exist" apart via the
// response — bindDeviceHandler's verifyPairingToken must hold the same line for "wrong token on a
// real device" vs. "any token on a device that was never provisioned at all", or an attacker could
// enumerate real device names by bind attempts even though they can no longer take one over.
func TestBindDevice_UnknownDeviceAndWrongTokenAreIndistinguishable(t *testing.T) {
	env, tb := newBindEnv(t)
	realDevice, _ := provisionTBDevice(t, tb)
	fakeDevice := fmt.Sprintf("api-bind-nonexistent-%d", time.Now().UnixNano())

	wrongToken := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": realDevice, "pairingToken": "wrong"}))
	unknownDevice := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": fakeDevice, "pairingToken": "wrong"}))

	if wrongToken.Code != unknownDevice.Code {
		t.Fatalf("status codes differ: wrong-token-on-real-device=%d unknown-device=%d — this leaks which device names are real",
			wrongToken.Code, unknownDevice.Code)
	}
	if wrongToken.Body.String() != unknownDevice.Body.String() {
		t.Fatalf("response bodies differ: %q vs %q — this leaks which device names are real",
			wrongToken.Body.String(), unknownDevice.Body.String())
	}
}

// TestBindDevice_SecondUserCanBindSameDeviceWithSameToken is a regression guard the other direction:
// CLAUDE.md's 2026-08-29 authorization pass deliberately made user_devices many-to-many so more than
// one legitimate staff member can bind the same physical device (e.g. multiple technicians on one
// tank). The pairing-token fix above must not accidentally turn the token into a single-use secret
// that only the first scanner can ever redeem.
func TestBindDevice_SecondUserCanBindSameDeviceWithSameToken(t *testing.T) {
	env, tb := newBindEnv(t)
	deviceName, token := provisionTBDevice(t, tb)
	t.Cleanup(func() {
		env.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName)
	})

	body := jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": token})

	if rec := env.authed("POST", "/api/devices/bind", body); rec.Code != http.StatusOK {
		t.Fatalf("first user: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	secondToken := env.secondUserToken()
	rec := env.do("POST", "/api/devices/bind", []byte(secondToken), body)
	if rec.Code != http.StatusOK {
		t.Fatalf("second user: expected 200, got %d: %s", rec.Code, rec.Body.String())
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
