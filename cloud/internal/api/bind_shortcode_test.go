package api_test

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
)

// Scanning a device code only navigates (cloud#63): POST /api/devices/resolve maps the scanned text
// to the device the client should open and writes nothing; POST /api/devices/bind is retired. The
// tests below prove "scan grants nothing" from the database, not from the response.

func newShortCodeEnv(t *testing.T, prefixes ...string) *testEnv {
	t.Helper()
	return newTestEnvWith(t, func(d *api.Deps) { d.DeviceCodePrefixes = prefixes })
}

// uniqueCode returns a 9-digit code that no leftover row can plausibly share.
func uniqueCode() string {
	return fmt.Sprintf("%09d", time.Now().UnixNano()%1_000_000_000)
}

type resolveBody struct {
	DeviceName string `json:"deviceName"`
	Code       string `json:"code"`
	Error      string `json:"error"`
}

func (tn *tenancy) resolve(t *testing.T, token, code string) (int, resolveBody, string) {
	t.Helper()
	rec := tn.send(t, "POST", "/api/devices/resolve", token, map[string]any{"code": code})
	return rec.Code, decodeAs[resolveBody](t, rec), rec.Body.String()
}

func (tn *tenancy) grantCount(t *testing.T) int {
	t.Helper()
	var n int
	if err := tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM user_tank_grants`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func (tn *tenancy) legacyBindingCount(t *testing.T) int {
	t.Helper()
	var n int
	if err := tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM user_devices`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestResolve_NavigatesAndGrantsNothing(t *testing.T) {
	tn := newTenancy(t)
	// userA holds grants on devA1 only (newTenancy); devA2 is another device of the same customer.
	grants, legacy := tn.grantCount(t), tn.legacyBindingCount(t)

	// An employee resolves a device they hold a grant on, by its exact name.
	code, got, body := tn.resolve(t, tn.userA, tn.devA1)
	if code != http.StatusOK || got.DeviceName != tn.devA1 {
		t.Fatalf("employee on a granted device: %d %s", code, body)
	}
	// A customer admin resolves any device of their customer, granted to anyone or not.
	if code, got, body := tn.resolve(t, tn.adminA, tn.devA2); code != http.StatusOK || got.DeviceName != tn.devA2 {
		t.Fatalf("customer admin: %d %s", code, body)
	}

	// An employee scanning a device of their own customer that they hold nothing on, another
	// customer's device and a code that does not exist all get the identical 404 — no oracle.
	c1, _, b1 := tn.resolve(t, tn.userA, tn.devA2)
	c2, _, b2 := tn.resolve(t, tn.userA, tn.devB1)
	c3, _, b3 := tn.resolve(t, tn.userA, tn.devGhost)
	if c1 != http.StatusNotFound || c2 != c1 || c3 != c1 || b1 != b2 || b2 != b3 {
		t.Errorf("ungranted/foreign/missing differ: %d %s | %d %s | %d %s", c1, b1, c2, b2, c3, b3)
	}
	if strings.Contains(b1, "not authorized") {
		t.Errorf("an object-level answer carries the revoked-account body: %s", b1)
	}
	// Repeating a scan, scanning a refused device and a malformed code leave no trace.
	for i := 0; i < 3; i++ {
		tn.resolve(t, tn.userA, tn.devA1)
		tn.resolve(t, tn.userA, tn.devA2)
	}
	if code, _, _ := tn.resolve(t, tn.userA, "bad code!"); code != http.StatusNotFound {
		t.Errorf("malformed code: %d, want 404", code)
	}
	if n := tn.grantCount(t); n != grants {
		t.Errorf("scanning changed the grant table: %d -> %d rows", grants, n)
	}
	if n := tn.legacyBindingCount(t); n != legacy {
		t.Errorf("scanning wrote a legacy binding: %d -> %d rows", legacy, n)
	}
	// And the refused device is still unreachable afterwards.
	if rec := tn.get(tn.userA, "/api/devices/"+tn.devA2+"/telemetry/latest"); rec.Code != http.StatusForbidden {
		t.Errorf("after scanning devA2 the employee reads it: %d, want 403", rec.Code)
	}
	// A disabled account is refused like anyone else.
	if _, err := tn.env.pool.Exec(context.Background(), `UPDATE users SET disabled_at = now() WHERE id = $1`, tn.userAID); err != nil {
		t.Fatal(err)
	}
	if rec := tn.send(t, "POST", "/api/devices/resolve", tn.userA, map[string]any{"code": tn.devA1}); rec.Code == http.StatusOK {
		t.Errorf("a disabled account resolved a device")
	}
}

func TestResolve_ExactMatchWinsAndPrefixesApplyInOrder(t *testing.T) {
	env := newShortCodeEnv(t, "ZCT", "ABC")
	code := uniqueCode()
	env.ownDevice("ZCT" + code)
	env.ownDevice("ABC" + code)

	rec := env.do("POST", "/api/devices/resolve", []byte(env.token), jsonBody(t, map[string]any{"code": code}))
	got := decodeAs[resolveBody](t, rec)
	if rec.Code != http.StatusOK || got.DeviceName != "ZCT"+code {
		t.Errorf("a bare code with two prefixed candidates: %d %s, want the first configured prefix (ZCT)", rec.Code, rec.Body.String())
	}
	// The full name is an exact match and wins over any prefix expansion.
	rec = env.do("POST", "/api/devices/resolve", []byte(env.token), jsonBody(t, map[string]any{"code": "ABC" + code}))
	got = decodeAs[resolveBody](t, rec)
	if rec.Code != http.StatusOK || got.DeviceName != "ABC"+code {
		t.Errorf("exact name: %d %s", rec.Code, rec.Body.String())
	}
	// Leading zeros survive: the code is an opaque string.
	zero := "000" + code
	env.ownDevice("ZCT" + zero)
	rec = env.do("POST", "/api/devices/resolve", []byte(env.token), jsonBody(t, map[string]any{"code": zero}))
	got = decodeAs[resolveBody](t, rec)
	if rec.Code != http.StatusOK || got.DeviceName != "ZCT"+zero {
		t.Errorf("leading zeros: %d %s", rec.Code, rec.Body.String())
	}
}

// The retired bind endpoint never writes: not with a short code, not with a pairing secret, not for
// any role, so there is no old entry through which a scan could still hand out access.
func TestBind_IsRetiredAndWritesNothing(t *testing.T) {
	tn := newTenancy(t)
	secretRec := tn.mint(tn.platform, tn.devA2, fmt.Sprintf(`{"customerId":%d}`, tn.custA))
	secret := tn.secretFrom(t, secretRec)
	grants, legacy := tn.grantCount(t), tn.legacyBindingCount(t)

	for name, tok := range map[string]string{"employee": tn.userA, "customerAdmin": tn.adminA, "platformAdmin": tn.platform} {
		for label, body := range map[string]map[string]any{
			"short code":     {"deviceName": tn.devA2},
			"pairing secret": {"deviceName": tn.devA2, "pairingToken": secret},
		} {
			rec := tn.send(t, "POST", "/api/devices/bind", tok, body)
			if rec.Code != http.StatusGone || !strings.Contains(rec.Body.String(), `"bind_removed"`) {
				t.Errorf("%s via %s: %d %s, want 410 bind_removed", name, label, rec.Code, rec.Body.String())
			}
		}
	}
	if n := tn.grantCount(t); n != grants {
		t.Errorf("bind changed the grant table: %d -> %d", grants, n)
	}
	if n := tn.legacyBindingCount(t); n != legacy {
		t.Errorf("bind wrote a legacy binding: %d -> %d", legacy, n)
	}
	if rec := tn.get(tn.userA, "/api/devices/"+tn.devA2+"/telemetry/latest"); rec.Code != http.StatusForbidden {
		t.Errorf("after a bind attempt the employee reads the device: %d, want 403", rec.Code)
	}
}
