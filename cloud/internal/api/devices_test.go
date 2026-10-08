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

func TestBindDevice_RequiresDeviceName(t *testing.T) {
	env := newTestEnv(t)

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"pairingToken": "irrelevant"}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBindDevice_CreatesDeviceRowAndBindingForBrandNewDevice(t *testing.T) {
	env := newTestEnv(t)
	deviceName := newBindDeviceName(t)
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup device: %v", err)
		}
	})

	// Deliberately not calling env.seedDevice — mintPairingSecret's own EnsureDevice call is what's
	// under test here, proving a secret can be minted (and then redeemed) for a device that has no
	// `devices` row yet.
	secret := env.mintPairingSecret(deviceName)

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": secret}))
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	if _, err := env.queries.GetDevice(context.Background(), deviceName); err != nil {
		t.Fatalf("expected devices row to exist after bind: %v", err)
	}

	// The binding should now authorize a device-scoped read that would otherwise 403. The command
	// listing is used as the probe because it needs no ThingsBoard/ingested data — an owned device
	// with no commands is simply an empty list.
	if rec := env.authed("GET", "/api/devices/"+deviceName+"/commands", nil); rec.Code != http.StatusOK {
		t.Fatalf("expected 200 (owned, no commands yet), got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestBindDevice_IsIdempotent(t *testing.T) {
	env := newTestEnv(t)
	deviceName := newBindDeviceName(t)
	secret := env.mintPairingSecret(deviceName)

	for i := 0; i < 2; i++ {
		rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": secret}))
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
	env := newTestEnv(t)
	deviceName := newBindDeviceName(t)
	env.mintPairingSecret(deviceName) // a real secret exists; the request below deliberately isn't it

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": "not-the-real-secret"}))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}

	if owns, err := env.queries.UserDeviceBindingExists(context.Background(), db.UserDeviceBindingExistsParams{
		UserID:     mustUserID(t, env),
		DeviceName: deviceName,
	}); err != nil {
		t.Fatalf("check binding: %v", err)
	} else if owns {
		t.Fatal("a wrong pairing token must not create a binding")
	}
}

func TestBindDevice_RejectsUnknownDeviceName(t *testing.T) {
	env := newTestEnv(t)
	deviceName := newBindDeviceName(t) // never minted a secret at all

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": "anything"}))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestBindDevice_UnknownDeviceAndWrongTokenAreIndistinguishable pins the anti-oracle property:
// requireDeviceOwnership's own doc comment (and CLAUDE.md invariant 6) already establish that an
// unauthorized caller must not be able to tell "not yours" from "doesn't exist" apart via the
// response — bindDeviceHandler's verifyPairingToken must hold the same line for "wrong token on a
// device with a real minted secret" vs. "any token on a device nobody ever minted a secret for", or
// an attacker could enumerate real device names by bind attempts even though they can no longer
// take one over.
func TestBindDevice_UnknownDeviceAndWrongTokenAreIndistinguishable(t *testing.T) {
	env := newTestEnv(t)
	realDevice := newBindDeviceName(t)
	env.mintPairingSecret(realDevice)
	fakeDevice := newBindDeviceName(t)

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
// tank). The pairing-secret design must not accidentally turn it into a single-use credential that
// only the first scanner can ever redeem.
func TestBindDevice_SecondUserCanBindSameDeviceWithSameToken(t *testing.T) {
	env := newTestEnv(t)
	deviceName := newBindDeviceName(t)
	secret := env.mintPairingSecret(deviceName)
	t.Cleanup(func() {
		env.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName)
	})

	body := jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": secret})

	if rec := env.authed("POST", "/api/devices/bind", body); rec.Code != http.StatusOK {
		t.Fatalf("first user: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	secondToken := env.secondUserToken()
	rec := env.do("POST", "/api/devices/bind", []byte(secondToken), body)
	if rec.Code != http.StatusOK {
		t.Fatalf("second user: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
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

// TestMintPairingSecret_DoesNotReturnOrAcceptThingsBoardAccessToken is the direct regression test
// for round 6's finding: the pairing secret must be unrelated to ThingsBoard's device identity, so
// that leaking it (a photographed QR code, a log line) cannot be used to impersonate the device on
// MQTT. A minted secret should not equal — and binding should not accept — anything that looks like
// it was derived from a ThingsBoard access token; concretely, this pins that mintPairingSecretHandler
// never touches deps.TB (the handler doesn't even receive a TB-wired Deps here, so any reliance on
// ThingsBoard would fail loudly instead of silently, unlike round 5's version which needed TB to
// function at all).
func TestMintPairingSecret_DoesNotReturnOrAcceptThingsBoardAccessToken(t *testing.T) {
	env := newTestEnv(t) // deliberately no TB wired into deps — see newTestEnvWith
	deviceName := newBindDeviceName(t)

	secret := env.mintPairingSecret(deviceName)
	if secret == "" {
		t.Fatal("expected a non-empty pairing secret even with no ThingsBoard configured")
	}

	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": secret}))
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 binding with no ThingsBoard configured, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestMintPairingSecret_RotatingReplacesThePreviousSecretButKeepsExistingBindings covers the
// rotation story CLAUDE.md's fix writeup promises: re-minting is how a leaked secret gets fixed, and
// it must not evict whoever is already bound.
func TestMintPairingSecret_RotatingReplacesThePreviousSecretButKeepsExistingBindings(t *testing.T) {
	env := newTestEnv(t)
	deviceName := newBindDeviceName(t)
	t.Cleanup(func() {
		env.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName)
	})

	oldSecret := env.mintPairingSecret(deviceName)
	if rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": oldSecret})); rec.Code != http.StatusOK {
		t.Fatalf("bind with old secret: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	newSecret := env.mintPairingSecret(deviceName)
	if newSecret == oldSecret {
		t.Fatal("re-minting must produce a different secret")
	}

	// The old secret is dead...
	rec := env.authed("POST", "/api/devices/bind", jsonBody(t, map[string]any{"deviceName": deviceName, "pairingToken": oldSecret}))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected the rotated-out secret to be rejected (401), got %d: %s", rec.Code, rec.Body.String())
	}

	// ...but the binding made with it before rotation is untouched.
	owns, err := env.queries.UserDeviceBindingExists(context.Background(), db.UserDeviceBindingExistsParams{UserID: mustUserID(t, env), DeviceName: deviceName})
	if err != nil {
		t.Fatalf("check binding: %v", err)
	}
	if !owns {
		t.Fatal("rotating the pairing secret must not undo an existing binding")
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
