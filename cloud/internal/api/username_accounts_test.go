package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"testing"

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
		if out["username"] != name || out["phone"] != nil || out["mustChangePassword"] != true {
			t.Fatalf("%s: created = %v", role, out)
		}

		// Case does not matter when logging in; the response says the password is an initial one.
		code, body := env.loginRaw(map[string]any{"username": strings.ToUpper(name), "password": accountPassword})
		if code != http.StatusOK {
			t.Fatalf("%s login: %d %s", role, code, body)
		}
		var resp struct {
			User map[string]any `json:"user"`
		}
		json.Unmarshal([]byte(body), &resp)
		if resp.User["mustChangePassword"] != true || resp.User["role"] != role {
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

	// Phone accounts are unchanged: staff still take no password.
	code, out := env.createAccount(map[string]any{"role": control.RoleCustomerUser, "phone": testPhone(), "password": accountPassword, "customerId": cust})
	if code != 400 || out["code"] != "password_not_allowed" {
		t.Errorf("phone staff with password: %d %v", code, out)
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

func TestUsernameAccounts_ChangeOwnPassword(t *testing.T) {
	env := platformEnv(t, nil)
	name := "change-" + testPhone()[4:]
	id, _ := env.usernameAccount(control.RoleCustomerUser, name)
	tok := env.login(name, accountPassword)
	post := func(old, new string) (int, map[string]any) {
		raw, _ := json.Marshal(map[string]string{"oldPassword": old, "newPassword": new})
		rec := env.do("POST", "/api/me/password", []byte(tok), raw)
		var out map[string]any
		json.Unmarshal(rec.Body.Bytes(), &out)
		return rec.Code, out
	}

	// A wrong old password is a 400, never a 401: a 401 would read as "session over" to the client.
	if code, out := post("not-the-password", "brand-new-pass"); code != 400 || out["code"] != "wrong_password" {
		t.Fatalf("wrong old: %d %v", code, out)
	}
	if code, out := post(accountPassword, accountPassword); code != 400 || out["code"] != "password_unchanged" {
		t.Fatalf("same password: %d %v", code, out)
	}
	if code, out := post(accountPassword, "short"); code != 400 || out["code"] != "invalid_password" {
		t.Fatalf("short: %d %v", code, out)
	}

	code, out := post(accountPassword, "brand-new-pass")
	if code != 200 || out["mustChangePassword"] != false {
		t.Fatalf("change: %d %v", code, out)
	}
	if c, _ := env.loginRaw(map[string]any{"username": name, "password": accountPassword}); c != http.StatusUnauthorized {
		t.Errorf("the old password still works: %d", c)
	}
	code, body := env.loginRaw(map[string]any{"username": name, "password": "brand-new-pass"})
	if code != 200 || !strings.Contains(body, `"mustChangePassword":false`) {
		t.Errorf("new password login: %d %s", code, body)
	}

	// Audited, without either password.
	var detail string
	err := env.pool.QueryRow(context.Background(),
		`SELECT detail::text FROM audit_log WHERE action = 'user.change-password' AND actor = $1 ORDER BY id DESC LIMIT 1`, name).Scan(&detail)
	if err != nil {
		t.Fatalf("no audit row for the password change: %v", err)
	}
	if strings.Contains(detail, "brand-new-pass") || strings.Contains(detail, accountPassword) || !strings.Contains(detail, itoa(id)) {
		t.Errorf("audit detail = %s", detail)
	}
}

func TestUsernameAccounts_PhoneStaffHaveNoPasswordToChange(t *testing.T) {
	env := newTestEnv(t) // the seeded user is phone staff: no password hash
	raw, _ := json.Marshal(map[string]string{"oldPassword": "whatever-old", "newPassword": "whatever-new"})
	rec := env.authed("POST", "/api/me/password", raw)
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "no_password_for_role") {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
}

func TestUsernameAccounts_AdminResetSetsInitialPasswordAgain(t *testing.T) {
	env := platformEnv(t, nil)
	name := "reset-" + testPhone()[4:]
	id, _ := env.usernameAccount(control.RoleCustomerUser, name)

	tok := env.login(name, accountPassword)
	raw, _ := json.Marshal(map[string]string{"oldPassword": accountPassword, "newPassword": "owner-chosen-pw"})
	if rec := env.do("POST", "/api/me/password", []byte(tok), raw); rec.Code != 200 {
		t.Fatalf("owner change: %d %s", rec.Code, rec.Body.String())
	}

	rec := env.authed("PUT", "/api/users/"+itoa(id), []byte(`{"password":"admin-reset-pw1"}`))
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"mustChangePassword":true`) {
		t.Fatalf("reset: %d %s", rec.Code, rec.Body.String())
	}
	if c, _ := env.loginRaw(map[string]any{"username": name, "password": "owner-chosen-pw"}); c != http.StatusUnauthorized {
		t.Errorf("the replaced password still works")
	}
	if c, _ := env.loginRaw(map[string]any{"username": name, "password": "admin-reset-pw1"}); c != http.StatusOK {
		t.Errorf("the reset password does not work")
	}

	// Phone staff still have none to reset.
	_, otherID := env.userIn(control.RoleCustomerUser, env.defaultCustomerID())
	rec = env.authed("PUT", "/api/users/"+itoa(otherID), []byte(`{"password":"admin-reset-pw1"}`))
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), "no_password_for_role") {
		t.Errorf("reset of phone staff: %d %s", rec.Code, rec.Body.String())
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
