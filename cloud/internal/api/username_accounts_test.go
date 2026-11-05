package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/control"
)

// Customer-side accounts with an independent username and password (cloud#77): for users without
// WeChat. The platform admin creates them; the owner changes the initial password; guessing is limited.

const accountPassword = "initial-pass-1"

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

// platformEnv is an environment whose seeded user is a platform admin, i.e. may create accounts.
func platformEnv(t *testing.T, customize func(*api.Deps)) *testEnv {
	t.Helper()
	env := newTestEnvWith(t, customize)
	env.setRole(control.RolePlatformAdmin)
	return env
}

// createAccount posts to /api/users as the platform admin and registers cleanup for what it made.
func (e *testEnv) createAccount(body map[string]any) (int, map[string]any) {
	e.t.Helper()
	raw, _ := json.Marshal(body)
	rec := e.authed("POST", "/api/users", raw)
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	if rec.Code == http.StatusCreated {
		id := int64(out["id"].(float64))
		e.t.Cleanup(func() {
			e.pool.Exec(context.Background(), `DELETE FROM audit_log WHERE actor IN (SELECT username FROM users WHERE id = $1)`, id)
			e.pool.Exec(context.Background(), `DELETE FROM users WHERE id = $1`, id)
		})
	}
	return rec.Code, out
}

func (e *testEnv) usernameAccount(role, username string) (int64, map[string]any) {
	e.t.Helper()
	code, out := e.createAccount(map[string]any{
		"role": role, "username": username, "password": accountPassword, "customerId": e.defaultCustomerID(),
	})
	if code != http.StatusCreated {
		e.t.Fatalf("create %s: %d %v", username, code, out)
	}
	return int64(out["id"].(float64)), out
}

func TestUsernameAccounts_CreateAndLogin(t *testing.T) {
	env := platformEnv(t, nil)
	for _, role := range []string{control.RoleCustomerUser, control.RoleCustomerAdmin} {
		name := "Maria." + strings.ReplaceAll(role, "_", "-") + "-" + testPhone()[4:]
		_, out := env.usernameAccount(role, name)
		if out["username"] != name || out["phone"] != nil {
			t.Fatalf("%s: created = %v", role, out)
		}

		// Case does not matter when logging in.
		code, body := env.loginRaw(map[string]any{"username": strings.ToUpper(name), "password": accountPassword})
		if code != http.StatusOK {
			t.Fatalf("%s login: %d %s", role, code, body)
		}
		var resp struct {
			User map[string]any `json:"user"`
		}
		json.Unmarshal([]byte(body), &resp)
		if resp.User["role"] != role || resp.User["username"] != name {
			t.Fatalf("%s login user = %v", role, resp.User)
		}
	}
}

func TestUsernameAccounts_CreateRules(t *testing.T) {
	env := platformEnv(t, nil)
	cust := env.defaultCustomerID()
	taken := "Taken-" + testPhone()[4:]
	env.usernameAccount(control.RoleCustomerUser, taken)

	for name, tc := range map[string]struct {
		body map[string]any
		code int
		want string
	}{
		"looks like a phone":  {map[string]any{"username": "13800138000", "password": accountPassword}, 400, "invalid_username"},
		"too short":           {map[string]any{"username": "ab", "password": accountPassword}, 400, "invalid_username"},
		"bad character":       {map[string]any{"username": "has space", "password": accountPassword}, 400, "invalid_username"},
		"no password":         {map[string]any{"username": "nopass-" + testPhone()[4:]}, 400, "invalid_password"},
		"short password":      {map[string]any{"username": "short-" + testPhone()[4:], "password": "abc"}, 400, "invalid_password"},
		"username and phone":  {map[string]any{"username": "both-" + testPhone()[4:], "phone": testPhone(), "password": accountPassword}, 400, "invalid_request"},
		"same name, new case": {map[string]any{"username": strings.ToUpper(taken), "password": accountPassword}, 409, "username_taken"},
	} {
		tc.body["role"], tc.body["customerId"] = control.RoleCustomerUser, cust
		code, out := env.createAccount(tc.body)
		raw, _ := json.Marshal(out)
		if code != tc.code || !strings.Contains(string(raw), tc.want) {
			t.Errorf("%s: %d %s, want %d %s", name, code, raw, tc.code, tc.want)
		}
	}

}

// Phone staff may be given a password at creation or later (cloud#77): without one they are WeChat only,
// with one they can also sign in with phone + password.
func TestUsernameAccounts_PhoneStaffMayHaveAPassword(t *testing.T) {
	env := platformEnv(t, nil)
	cust := env.defaultCustomerID()

	phone := testPhone()
	code, out := env.createAccount(map[string]any{"role": control.RoleCustomerUser, "phone": phone, "password": accountPassword, "customerId": cust})
	if code != http.StatusCreated {
		t.Fatalf("phone staff with password: %d %v", code, out)
	}
	if c, body := env.loginRaw(map[string]any{"phone": phone, "password": accountPassword}); c != http.StatusOK {
		t.Fatalf("phone staff login: %d %s", c, body)
	}
	// Still not reachable through the username field.
	if c, _ := env.loginRaw(map[string]any{"username": phone, "password": accountPassword}); c != http.StatusUnauthorized {
		t.Errorf("phone staff via username: %d", c)
	}

	// Without a password: unchanged, WeChat only and no password login.
	bare := testPhone()
	if code, out := env.createAccount(map[string]any{"role": control.RoleCustomerUser, "phone": bare, "customerId": cust}); code != http.StatusCreated {
		t.Fatalf("phone staff without password: %d %v", code, out)
	}
	if c, _ := env.loginRaw(map[string]any{"phone": bare, "password": accountPassword}); c != http.StatusUnauthorized {
		t.Errorf("passwordless staff logged in: %d", c)
	}
	// A weak one is refused with the stable code.
	if code, out := env.createAccount(map[string]any{"role": control.RoleCustomerUser, "phone": testPhone(), "password": "abc", "customerId": cust}); code != 400 || out["code"] != "invalid_password" {
		t.Errorf("weak password: %d %v", code, out)
	}
}

func TestUsernameAccounts_LoginRefusalsLookAlike(t *testing.T) {
	env := platformEnv(t, nil)
	name := "ghost-" + testPhone()[4:]
	id, _ := env.usernameAccount(control.RoleCustomerUser, name)

	_, wrong := env.loginRaw(map[string]any{"username": name, "password": "wrong-password"})
	_, unknown := env.loginRaw(map[string]any{"username": "nobody-" + testPhone()[4:], "password": "wrong-password"})
	if wrong != unknown || !strings.Contains(wrong, "invalid_credentials") {
		t.Fatalf("wrong password %q vs unknown account %q", wrong, unknown)
	}

	// Disabled: right password, same answer as a wrong one.
	if rec := env.authed("PUT", "/api/users/"+itoa(id), []byte(`{"disabled":true}`)); rec.Code != http.StatusOK {
		t.Fatalf("disable: %d %s", rec.Code, rec.Body.String())
	}
	code, disabled := env.loginRaw(map[string]any{"username": name, "password": accountPassword})
	if code != http.StatusUnauthorized || disabled != wrong {
		t.Fatalf("disabled account: %d %q, want the wrong-password answer %q", code, disabled, wrong)
	}
}

func TestUsernameAccounts_PhoneAccountsStayOnTheirField(t *testing.T) {
	env := platformEnv(t, nil)
	_, adminID := env.userIn(control.RoleCustomerAdmin, env.defaultCustomerID())
	var phone string
	env.pool.QueryRow(context.Background(), `SELECT phone FROM users WHERE id = $1`, adminID).Scan(&phone)
	// A phone account's username column mirrors its number but must not be a second way in.
	if code, body := env.loginRaw(map[string]any{"username": phone, "password": "e2e-test-pass"}); code != http.StatusUnauthorized {
		t.Fatalf("phone account via username: %d %s", code, body)
	}
	if code, body := env.loginRaw(map[string]any{"phone": phone, "password": "e2e-test-pass"}); code != http.StatusOK {
		t.Fatalf("phone account via phone: %d %s", code, body)
	}
}

func TestUsernameAccounts_AdminResetReplacesThePassword(t *testing.T) {
	env := platformEnv(t, nil)
	name := "reset-" + testPhone()[4:]
	id, _ := env.usernameAccount(control.RoleCustomerUser, name)

	rec := env.authed("PUT", "/api/users/"+itoa(id), []byte(`{"password":"admin-reset-pw1"}`))
	if rec.Code != 200 {
		t.Fatalf("reset: %d %s", rec.Code, rec.Body.String())
	}
	if c, _ := env.loginRaw(map[string]any{"username": name, "password": accountPassword}); c != http.StatusUnauthorized {
		t.Errorf("the replaced password still works")
	}
	if c, _ := env.loginRaw(map[string]any{"username": name, "password": "admin-reset-pw1"}); c != http.StatusOK {
		t.Errorf("the reset password does not work")
	}

}

func TestUsernameAccounts_ResetNeverRecordsPassword(t *testing.T) {
	env := platformEnv(t, nil)
	name := "audit-" + testPhone()[4:]
	id, _ := env.usernameAccount(control.RoleCustomerUser, name)
	env.authed("PUT", "/api/users/"+itoa(id), []byte(`{"password":"super-secret-pw1"}`))
	var n int
	env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE detail::text LIKE '%super-secret-pw1%' OR detail::text LIKE '%`+accountPassword+`%'`).Scan(&n)
	if n != 0 {
		t.Fatalf("%d audit rows contain a password", n)
	}
}

func TestLoginLimit_LocksAfterRepeatedFailuresAndResetRecovers(t *testing.T) {
	env := platformEnv(t, func(d *api.Deps) { d.LoginMaxFailures = 3 })
	ctx := context.Background()
	ipKey := "loginfail:ip:192.0.2.1"
	env.rdb.Del(ctx, ipKey)
	t.Cleanup(func() { env.rdb.Del(context.Background(), ipKey) })

	name := "locked-" + testPhone()[4:]
	id, _ := env.usernameAccount(control.RoleCustomerUser, name)
	acctKey := "loginfail:acct:u:" + strings.ToLower(name)
	env.rdb.Del(ctx, acctKey)
	t.Cleanup(func() { env.rdb.Del(context.Background(), acctKey) })

	for i := 0; i < 3; i++ {
		if c, _ := env.loginRaw(map[string]any{"username": name, "password": "wrong-password"}); c != http.StatusUnauthorized {
			t.Fatalf("attempt %d: %d", i, c)
		}
	}
	// Locked: even the right password is refused, and the answer says when to come back.
	rec := env.do("POST", "/api/login", nil, []byte(`{"username":"`+name+`","password":"`+accountPassword+`"}`))
	if rec.Code != http.StatusTooManyRequests || !strings.Contains(rec.Body.String(), "login_rate_limited") || rec.Header().Get("Retry-After") == "" {
		t.Fatalf("locked: %d %s retry-after=%q", rec.Code, rec.Body.String(), rec.Header().Get("Retry-After"))
	}

	// The recovery path: a platform admin resets the password.
	if rec := env.authed("PUT", "/api/users/"+itoa(id), []byte(`{"password":"fresh-password1"}`)); rec.Code != 200 {
		t.Fatalf("reset: %d %s", rec.Code, rec.Body.String())
	}
	env.rdb.Del(ctx, ipKey) // the address counter is shared by every test in this process
	if c, body := env.loginRaw(map[string]any{"username": name, "password": "fresh-password1"}); c != 200 {
		t.Fatalf("after reset: %d %s", c, body)
	}
}

func TestLoginLimit_UnknownAccountsAreCountedToo(t *testing.T) {
	env := newTestEnvWith(t, func(d *api.Deps) { d.LoginMaxFailures = 2 })
	ctx := context.Background()
	ghost := "nobody-" + testPhone()[4:]
	acctKey := "loginfail:acct:u:" + strings.ToLower(ghost)
	ipKey := "loginfail:ip:192.0.2.1"
	env.rdb.Del(ctx, acctKey, ipKey)
	t.Cleanup(func() { env.rdb.Del(context.Background(), acctKey, ipKey) })

	env.loginRaw(map[string]any{"username": ghost, "password": "wrong-password"})
	env.loginRaw(map[string]any{"username": ghost, "password": "wrong-password"})
	if c, body := env.loginRaw(map[string]any{"username": ghost, "password": "wrong-password"}); c != http.StatusTooManyRequests {
		t.Fatalf("an unknown account must lock exactly like a real one: %d %s", c, body)
	}
}

func TestLoginLimit_SuccessForgetsFailures(t *testing.T) {
	env := platformEnv(t, func(d *api.Deps) { d.LoginMaxFailures = 3 })
	ctx := context.Background()
	ipKey := "loginfail:ip:192.0.2.1"
	name := "forgive-" + testPhone()[4:]
	env.usernameAccount(control.RoleCustomerUser, name)
	acctKey := "loginfail:acct:u:" + strings.ToLower(name)
	env.rdb.Del(ctx, acctKey, ipKey)
	t.Cleanup(func() { env.rdb.Del(context.Background(), acctKey, ipKey) })

	for round := 0; round < 3; round++ {
		env.loginRaw(map[string]any{"username": name, "password": "wrong-password"})
		env.loginRaw(map[string]any{"username": name, "password": "wrong-password"})
		if c, body := env.loginRaw(map[string]any{"username": name, "password": accountPassword}); c != 200 {
			t.Fatalf("round %d: %d %s", round, c, body)
		}
	}
}

// Staff passwords are for the mini program and the app, not for administration: a staff token, even one
// that claims a higher role, is refused by every management route because the role is read from the
// database (cloud#77). Read access stays limited to granted tanks, which is tested in tank_grants_test.go.
func TestUsernameAccounts_StaffTokenCannotAdminister(t *testing.T) {
	env := platformEnv(t, nil)
	name := "staff-" + testPhone()[4:]
	staffID, _ := env.usernameAccount(control.RoleCustomerUser, name)
	honest := env.login(name, accountPassword)

	// A token that lies about the role. Only the database row decides.
	forged, err := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"uid": staffID, "sub": itoa(staffID), "role": control.RolePlatformAdmin,
		"iat": time.Now().Unix(), "exp": time.Now().Add(time.Hour).Unix(),
	}).SignedString([]byte("api-test-secret"))
	if err != nil {
		t.Fatal(err)
	}

	cust := itoa(env.defaultCustomerID())
	routes := []struct{ method, path, body string }{
		{"POST", "/api/users", `{"role":"customer_user","phone":"13800138000","customerId":` + cust + `}`},
		{"GET", "/api/users", ``},
		{"PUT", "/api/users/" + itoa(staffID), `{"disabled":true}`},
		{"PUT", "/api/users/" + itoa(staffID) + "/customer", `{"customerId":` + cust + `}`},
		{"PUT", "/api/users/" + itoa(staffID) + "/tank-grants/any-device", `{"tanks":[1]}`},
		{"POST", "/api/customers", `{"name":"x"}`},
		{"PUT", "/api/customers/" + cust, `{"name":"x"}`},
		{"POST", "/api/customers/" + cust + "/onboarding/complete", ``},
		{"POST", "/api/stations", `{"customerId":` + cust + `,"name":"x"}`},
		{"PUT", "/api/devices/any-device/customer", `{"customerId":` + cust + `}`},
		{"POST", "/api/devices", `{"deviceName":"any-device","customerId":` + cust + `}`},
		{"POST", "/api/devices/any-device/pairing-secret", `{"customerId":` + cust + `}`},
		{"GET", "/api/audit", ``},
	}
	for label, tok := range map[string]string{"honest": honest, "forged-role": forged} {
		for _, r := range routes {
			var body []byte
			if r.body != "" {
				body = []byte(r.body)
			}
			rec := env.do(r.method, r.path, []byte(tok), body)
			if rec.Code != http.StatusForbidden {
				t.Errorf("%s staff token: %s %s = %d %s, want 403", label, r.method, r.path, rec.Code, rec.Body.String())
			}
		}
	}
}
