package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"testing"

	"yeweiyi-cloud/internal/control"
)

func TestLogin_WrongPassword(t *testing.T) {
	env := newTestEnv(t) // also exercises the successful-login path via newTestEnv's own setup

	env.setRole(control.RolePlatformAdmin)
	body := jsonBody(t, map[string]string{"username": env.username, "password": "not-the-right-password"})
	rec := env.do("POST", "/api/login", nil, body)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestLogin_UnknownUser(t *testing.T) {
	env := newTestEnv(t)

	body := jsonBody(t, map[string]string{"username": "no-such-user", "password": "whatever"})
	rec := env.do("POST", "/api/login", nil, body)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestLogin_ReturnsUsableToken(t *testing.T) {
	env := newTestEnv(t)

	// env.token came from newTestEnv's own login() call — confirm it actually authorizes a request
	// rather than just being a non-empty string.
	rec := env.authed("GET", "/api/alarms?device=nonexistent-device-for-token-check", nil)
	if rec.Code == http.StatusUnauthorized {
		t.Fatalf("token from login should have authorized the request, got 401: %s", rec.Body.String())
	}
}

func TestAuthMiddleware_MissingToken(t *testing.T) {
	env := newTestEnv(t)

	rec := env.do("GET", "/api/alarms?device=x", nil, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestAuthMiddleware_InvalidToken(t *testing.T) {
	env := newTestEnv(t)

	rec := env.do("GET", "/api/alarms?device=x", []byte("not-a-real-jwt"), nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode error body: %v", err)
	}
	if resp["error"] == "" {
		t.Fatalf("expected an error message, got: %s", rec.Body.String())
	}
}

// loginResp is the shape clients rely on: the token where it always was, plus the user.
type loginResp struct {
	Token string `json:"token"`
	User  struct {
		ID         int64   `json:"id"`
		Role       string  `json:"role"`
		Username   *string `json:"username"`
		Phone      *string `json:"phone"`
		CustomerID *int64  `json:"customerId"`
	} `json:"user"`
}

func (e *testEnv) loginRaw(body map[string]any) (int, string) {
	e.t.Helper()
	rec := e.do("POST", "/api/login", nil, jsonBody(e.t, body))
	return rec.Code, rec.Body.String()
}

func TestLogin_CustomerAdminByPhone(t *testing.T) {
	env := newTestEnv(t)
	token, id := env.userIn(control.RoleCustomerAdmin, env.defaultCustomerID())
	_ = token
	var phone string
	if err := env.pool.QueryRow(context.Background(), `SELECT phone FROM users WHERE id = $1`, id).Scan(&phone); err != nil {
		t.Fatal(err)
	}

	// The number may be written the usual ways; they are all the same account.
	for _, written := range []string{phone, "+86" + phone, "+86 " + phone[:3] + " " + phone[3:7] + " " + phone[7:], phone[:3] + "-" + phone[3:]} {
		code, body := env.loginRaw(map[string]any{"phone": written, "password": "e2e-test-pass"})
		if code != http.StatusOK {
			t.Fatalf("login with %q: %d %s", written, code, body)
		}
		var resp loginResp
		if err := json.Unmarshal([]byte(body), &resp); err != nil {
			t.Fatal(err)
		}
		if resp.Token == "" || resp.User.ID != id || resp.User.Role != control.RoleCustomerAdmin {
			t.Errorf("login with %q returned %+v", written, resp)
		}
		if resp.User.Username != nil {
			t.Errorf("a customer-side account has no username, got %q", *resp.User.Username)
		}
		if resp.User.Phone == nil || *resp.User.Phone != phone {
			t.Errorf("the user sees their own full number, got %v", resp.User.Phone)
		}
		if strings.Contains(body, "password") || strings.Contains(body, "$2") {
			t.Errorf("credential material in the login response: %s", body)
		}

		// The token identifies the user by id; /api/me agrees with the login response.
		claims := parseTestToken(t, resp.Token)
		if claims["sub"] != strconv.FormatInt(id, 10) {
			t.Errorf("sub = %v, want the user id %d", claims["sub"], id)
		}
		me := env.do("GET", "/api/me", []byte(resp.Token), nil)
		if me.Code != http.StatusOK {
			t.Fatalf("/api/me: %d %s", me.Code, me.Body.String())
		}
		var u struct {
			ID    int64   `json:"id"`
			Phone *string `json:"phone"`
		}
		json.Unmarshal(me.Body.Bytes(), &u)
		if u.ID != id || u.Phone == nil || *u.Phone != phone {
			t.Errorf("/api/me = %s", me.Body.String())
		}
	}
}

func TestLogin_FieldRules(t *testing.T) {
	env := newTestEnv(t)
	_, adminID := env.userIn(control.RoleCustomerAdmin, env.defaultCustomerID())
	var phone string
	env.pool.QueryRow(context.Background(), `SELECT phone FROM users WHERE id = $1`, adminID).Scan(&phone)
	env.setRole(control.RolePlatformAdmin)
	pw := "e2e-test-pass"

	cases := map[string]struct {
		body map[string]any
		code int
		want string // substring of the body
	}{
		"both fields":                           {map[string]any{"username": env.username, "phone": phone, "password": pw}, 400, "invalid_request"},
		"neither field":                         {map[string]any{"password": pw}, 400, "invalid_request"},
		"no password":                           {map[string]any{"phone": phone}, 400, "invalid_request"},
		"malformed phone":                       {map[string]any{"phone": "12345", "password": pw}, 400, "invalid_phone"},
		"customer admin via the username field": {map[string]any{"username": phone, "password": pw}, 401, "invalid_credentials"},
		"platform admin via the phone field":    {map[string]any{"phone": env.username, "password": pw}, 401, "invalid_credentials"},
		"wrong password":                        {map[string]any{"phone": phone, "password": "nope-nope-nope"}, 401, "invalid_credentials"},
		"unknown phone":                         {map[string]any{"phone": testPhone(), "password": pw}, 401, "invalid_credentials"},
		"unknown username":                      {map[string]any{"username": "nobody-" + phone, "password": pw}, 401, "invalid_credentials"},
	}
	for name, tc := range cases {
		code, body := env.loginRaw(tc.body)
		if code != tc.code || !strings.Contains(body, tc.want) {
			t.Errorf("%s: got %d %s, want %d containing %q", name, code, body, tc.code, tc.want)
		}
	}
	// Right credentials on the right field still work.
	if code, body := env.loginRaw(map[string]any{"username": env.username, "password": pw}); code != http.StatusOK {
		t.Errorf("platform admin by username: %d %s", code, body)
	}
	// The three "no" answers for an account that exists, does not exist and has the wrong role are
	// byte-identical, so the response says nothing about which accounts there are.
	_, a := env.loginRaw(map[string]any{"phone": phone, "password": "nope-nope-nope"})
	_, b := env.loginRaw(map[string]any{"phone": testPhone(), "password": "nope-nope-nope"})
	_, c := env.loginRaw(map[string]any{"username": phone, "password": pw})
	if a != b || b != c {
		t.Errorf("rejections differ: %q / %q / %q", a, b, c)
	}
}

func TestLogin_StaffCannotUsePassword(t *testing.T) {
	env := newTestEnv(t)
	staff := env.username // the seeded user is staff: a phone, no password hash
	for _, body := range []map[string]any{
		{"phone": staff, "password": "e2e-test-pass"},
		{"username": staff, "password": "e2e-test-pass"},
		{"phone": staff, "password": ""},
		{"phone": staff, "password": "x"},
	} {
		if code, resp := env.loginRaw(body); code == http.StatusOK {
			t.Errorf("staff logged in with %v: %s", body, resp)
		}
	}
	var n int
	env.pool.QueryRow(context.Background(), `SELECT count(*) FROM users WHERE username = $1 AND password_hash IS NOT NULL`, staff).Scan(&n)
	if n != 0 {
		t.Fatalf("the staff fixture has a password")
	}
	// Their token (from the WeChat login in production) still opens the business API.
	if rec := env.do("GET", "/api/me", []byte(env.token), nil); rec.Code != http.StatusOK {
		t.Errorf("staff token on /api/me: %d %s", rec.Code, rec.Body.String())
	}
	if rec := env.do("GET", "/api/devices", []byte(env.token), nil); rec.Code != http.StatusOK {
		t.Errorf("staff token on /api/devices: %d %s", rec.Code, rec.Body.String())
	}
}

func TestMe_RequiresAuthAndReflectsTheDatabase(t *testing.T) {
	env := newTestEnv(t)
	if rec := env.do("GET", "/api/me", nil, nil); rec.Code != http.StatusUnauthorized {
		t.Errorf("no token: got %d, want 401", rec.Code)
	}
	// Role comes from the row, not from the token's claim: promote the user and the same token
	// answers with the new role.
	env.pool.Exec(context.Background(), `UPDATE users SET role = 'customer_admin', password_hash = $2 WHERE username = $1`,
		env.username, testUserPasswordHash)
	rec := env.do("GET", "/api/me", []byte(env.token), nil)
	var u struct {
		Role string `json:"role"`
	}
	json.Unmarshal(rec.Body.Bytes(), &u)
	if rec.Code != http.StatusOK || u.Role != control.RoleCustomerAdmin {
		t.Errorf("/api/me after promotion: %d %s", rec.Code, rec.Body.String())
	}
}
