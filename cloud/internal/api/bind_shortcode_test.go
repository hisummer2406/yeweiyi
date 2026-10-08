package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/control"
)

// Short-code binding: the QR code carries only the 6-digit code, no pairing secret, so the
// customer boundary is the authorization (openspec change bind-device-by-short-code).

const shortCodeWrongCredential = `{"error":"invalid device name or pairing token"}`

func newShortCodeEnv(t *testing.T, prefixes ...string) *testEnv {
	t.Helper()
	return newTestEnvWith(t, func(d *api.Deps) { d.DeviceCodePrefixes = prefixes })
}

// uniqueCode returns a 9-digit code that no leftover row can plausibly share.
func uniqueCode() string {
	return fmt.Sprintf("%09d", time.Now().UnixNano()%1_000_000_000)
}

func (e *testEnv) bindByCode(token []byte, code string) (int, string) {
	e.t.Helper()
	rec := e.do("POST", "/api/devices/bind", token, jsonBody(e.t, map[string]any{"deviceName": code}))
	return rec.Code, rec.Body.String()
}

func (e *testEnv) countBindings(deviceName string) int {
	e.t.Helper()
	var n int
	if err := e.pool.QueryRow(context.Background(), `SELECT count(*) FROM user_devices WHERE device_name = $1`, deviceName).Scan(&n); err != nil {
		e.t.Fatalf("count bindings: %v", err)
	}
	return n
}

func (e *testEnv) deviceRows(like string) int {
	e.t.Helper()
	var n int
	if err := e.pool.QueryRow(context.Background(), `SELECT count(*) FROM devices WHERE device_name LIKE $1`, like).Scan(&n); err != nil {
		e.t.Fatalf("count devices: %v", err)
	}
	return n
}

func TestBindByCode_CustomerUserAndAdminBindOwnCustomersDevice(t *testing.T) {
	for _, role := range []string{control.RoleCustomerUser, control.RoleCustomerAdmin} {
		t.Run(role, func(t *testing.T) {
			env := newShortCodeEnv(t, "ZCT")
			code := uniqueCode()
			full := "ZCT" + code
			env.seedDevice(full)
			token, _ := env.userIn(role, env.defaultCustomerID())

			status, body := env.bindByCode([]byte(token), code)
			if status != http.StatusOK {
				t.Fatalf("expected 200, got %d: %s", status, body)
			}
			var resp struct {
				DeviceName string `json:"deviceName"`
				Bound      bool   `json:"bound"`
			}
			if err := json.Unmarshal([]byte(body), &resp); err != nil {
				t.Fatal(err)
			}
			if resp.DeviceName != full || !resp.Bound {
				t.Fatalf("expected resolved name %q bound, got %+v", full, resp)
			}
			if env.countBindings(full) != 1 {
				t.Fatal("expected one binding")
			}
		})
	}
}

func TestBindByCode_IsIdempotent(t *testing.T) {
	env := newShortCodeEnv(t, "ZCT")
	code := uniqueCode()
	env.seedDevice("ZCT" + code)
	for i := 0; i < 2; i++ {
		if status, body := env.bindByCode([]byte(env.token), code); status != http.StatusOK {
			t.Fatalf("attempt %d: expected 200, got %d: %s", i, status, body)
		}
	}
	if env.countBindings("ZCT"+code) != 1 {
		t.Fatal("expected exactly one binding")
	}
}

func TestBindByCode_RefusalsAreIdenticalAndHaveNoSideEffects(t *testing.T) {
	env := newShortCodeEnv(t, "ZCT")
	other := env.newCustomer("shortcode-other")

	foreign, unassigned, missing := uniqueCode(), uniqueCode()+"1", uniqueCode()+"2"
	env.seedDeviceIn("ZCT"+foreign, other)
	env.seedDeviceIn("ZCT"+unassigned, 0)
	own := uniqueCode() + "3"
	env.seedDevice("ZCT" + own)

	adminToken, _ := env.userIn(control.RolePlatformAdmin, 0)
	disabledToken, disabledID := env.userIn(control.RoleCustomerUser, env.defaultCustomerID())
	if _, err := env.pool.Exec(context.Background(), `UPDATE users SET disabled_at = now() WHERE id = $1`, disabledID); err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name  string
		token string
		code  string
	}{
		{"other customer's device", env.token, foreign},
		{"unassigned device", env.token, unassigned},
		{"no such device", env.token, missing},
		{"platform_admin", adminToken, own},
		{"space", env.token, "00 01"},
		{"percent", env.token, "%"},
		{"underscore", env.token, "_"},
		{"too long", env.token, "012345678901234567890123456789012"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			status, body := env.bindByCode([]byte(tc.token), tc.code)
			if status != http.StatusUnauthorized || body != shortCodeWrongCredential {
				t.Fatalf("expected the generic 401, got %d: %s", status, body)
			}
		})
	}
	// A disabled account never reaches the handler: the auth middleware answers 403 (the client
	// treats it as "account invalid"). The SQL still filters disabled_at for the race window.
	if status, _ := env.bindByCode([]byte(disabledToken), own); status != http.StatusForbidden {
		t.Fatalf("disabled user: expected 403 from auth middleware, got %d", status)
	}
	if env.countBindings("ZCT"+foreign)+env.countBindings("ZCT"+unassigned)+env.countBindings("ZCT"+own) != 0 {
		t.Fatal("a refused bind created a binding")
	}
	if env.deviceRows("%"+missing) != 0 {
		t.Fatal("a refused bind created a devices row")
	}
}

func TestBindByCode_ExactMatchWinsOverPrefix(t *testing.T) {
	env := newShortCodeEnv(t, "ZCT")
	code := uniqueCode()
	env.seedDevice(code)
	env.seedDevice("ZCT" + code)
	if status, body := env.bindByCode([]byte(env.token), code); status != http.StatusOK {
		t.Fatalf("got %d: %s", status, body)
	}
	if env.countBindings(code) != 1 || env.countBindings("ZCT"+code) != 0 {
		t.Fatal("exact match must win")
	}
}

func TestBindByCode_NoPrefixConfiguredMeansExactOnly(t *testing.T) {
	env := newShortCodeEnv(t)
	code := uniqueCode()
	env.seedDevice("ZCT" + code)
	if status, _ := env.bindByCode([]byte(env.token), code); status != http.StatusUnauthorized {
		t.Fatalf("expected 401 without prefixes, got %d", status)
	}
	exact := uniqueCode() + "9"
	env.seedDevice(exact)
	if status, body := env.bindByCode([]byte(env.token), exact); status != http.StatusOK {
		t.Fatalf("exact name should bind, got %d: %s", status, body)
	}
}

// A transfer holding the device row's lock must make a queued short-code bind see the new owner.
func TestBindByCode_RacingDeviceTransferIsRefused(t *testing.T) {
	env := newShortCodeEnv(t, "ZCT")
	other := env.newCustomer("shortcode-race")
	code := uniqueCode()
	full := "ZCT" + code
	env.seedDevice(full)

	ctx := context.Background()
	tx, err := env.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `SELECT 1 FROM devices WHERE device_name = $1 FOR UPDATE`, full); err != nil {
		t.Fatal(err)
	}

	type result struct {
		status int
		body   string
	}
	done := make(chan result, 1)
	go func() {
		s, b := env.bindByCode([]byte(env.token), code)
		done <- result{s, b}
	}()
	time.Sleep(300 * time.Millisecond)
	if _, err := tx.Exec(ctx, `UPDATE devices SET customer_id = $2 WHERE device_name = $1`, full, other); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}

	r := <-done
	if r.status != http.StatusUnauthorized || r.body != shortCodeWrongCredential {
		t.Fatalf("expected generic 401 after transfer, got %d: %s", r.status, r.body)
	}
	if env.countBindings(full) != 0 {
		t.Fatal("binding left behind a transfer")
	}
}
