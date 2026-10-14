package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/wechat"
)

// fakeWX stands in for WeChat. It is NOT evidence that real WeChat behaves this way: the contract
// with the real service is only verified by a run against a real mini program. What these tests
// prove is what *our* server does with each answer WeChat can give.
type fakeWX struct {
	mu       sync.Mutex
	appid    string
	openids  map[string]string       // login code -> openid
	phones   map[string]wechat.Phone // phone code -> verified phone
	failWith map[string]error        // code -> error to return
	used     map[string]bool         // codes are single-use, like WeChat's
	lastCode [3]string               // scene, page, env of the last MiniCode call
}

func newFakeWX() *fakeWX {
	return &fakeWX{appid: "wx-api-test", openids: map[string]string{}, phones: map[string]wechat.Phone{},
		failWith: map[string]error{}, used: map[string]bool{}}
}

func (f *fakeWX) AppID() string { return f.appid }

// MiniCode returns a recognisable payload and records what it was asked for.
func (f *fakeWX) MiniCode(_ context.Context, scene, page, env string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.lastCode = [3]string{scene, page, env}
	return []byte("\x89PNG-fake:" + scene), nil
}

func (f *fakeWX) consume(code string) error {
	if err, ok := f.failWith[code]; ok {
		return err
	}
	if f.used[code] {
		return wechat.ErrInvalidCode
	}
	f.used[code] = true
	return nil
}

func (f *fakeWX) OpenID(_ context.Context, code string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	id, ok := f.openids[code]
	if !ok {
		return "", wechat.ErrInvalidCode
	}
	if err := f.consume(code); err != nil {
		return "", err
	}
	return id, nil
}

func (f *fakeWX) PhoneNumber(_ context.Context, code string) (wechat.Phone, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p, ok := f.phones[code]
	if !ok {
		return wechat.Phone{}, wechat.ErrInvalidCode
	}
	if err := f.consume(code); err != nil {
		return wechat.Phone{}, err
	}
	return p, nil
}

func wxEnv(t *testing.T) (*testEnv, *fakeWX) {
	t.Helper()
	fx := newFakeWX()
	env := newTestEnvWith(t, func(d *api.Deps) { d.WeChat = fx })
	return env, fx
}

func (e *testEnv) wxPost(path string, body map[string]any) (int, map[string]any) {
	e.t.Helper()
	rec := e.do("POST", path, nil, jsonBody(e.t, body))
	out := map[string]any{}
	json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

func (e *testEnv) staffIn(customerID int64) (phone string, id int64) {
	e.t.Helper()
	_, id = e.userIn(control.RoleCustomerUser, customerID)
	if err := e.pool.QueryRow(context.Background(), `SELECT phone FROM users WHERE id = $1`, id).Scan(&phone); err != nil {
		e.t.Fatal(err)
	}
	return phone, id
}

func (e *testEnv) identityCount(where string, args ...any) int {
	e.t.Helper()
	var n int
	if err := e.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM user_wechat_identities WHERE `+where, args...).Scan(&n); err != nil {
		e.t.Fatal(err)
	}
	return n
}

func codeOf(body map[string]any) string { s, _ := body["code"].(string); return s }

func TestWeChat_DisabledDoesNotAffectPasswordLogin(t *testing.T) {
	env := newTestEnv(t) // no WeChat client: the feature is off
	for _, path := range []string{"/api/auth/wechat/login", "/api/auth/wechat/phone-login"} {
		code, body := env.wxPost(path, map[string]any{"loginCode": "x", "phoneCode": "y"})
		if code != http.StatusServiceUnavailable || codeOf(body) != "wechat_unavailable" {
			t.Errorf("%s with WeChat off: %d %v, want 503 wechat_unavailable", path, code, body)
		}
	}
	env.setRole(control.RolePlatformAdmin)
	if c, b := env.loginRaw(map[string]any{"username": env.username, "password": "e2e-test-pass"}); c != http.StatusOK {
		t.Errorf("password login with WeChat off: %d %s", c, b)
	}
}

func TestWeChat_FirstLoginBindsThenRestores(t *testing.T) {
	env, fx := wxEnv(t)
	phone, id := env.staffIn(env.defaultCustomerID())
	fx.openids["L1"], fx.phones["P1"] = "openid-A", wechat.Phone{Pure: phone, CountryCode: "86"}

	// Before the first authorization the identity is unknown, and logging in does not create it.
	fx.openids["L0"] = "openid-A"
	if code, body := env.wxPost("/api/auth/wechat/login", map[string]any{"loginCode": "L0"}); code != http.StatusUnauthorized || codeOf(body) != "wechat_not_bound" {
		t.Fatalf("unbound login: %d %v, want 401 wechat_not_bound", code, body)
	}
	if n := env.identityCount(`user_id = $1`, id); n != 0 {
		t.Fatalf("a restore attempt created %d identity rows", n)
	}

	code, body := env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L1", "phoneCode": "P1"})
	if code != http.StatusOK {
		t.Fatalf("phone-login: %d %v", code, body)
	}
	user := body["user"].(map[string]any)
	if int64(user["id"].(float64)) != id || user["role"] != control.RoleCustomerUser {
		t.Errorf("user = %v, want id %d staff", user, id)
	}
	token := body["token"].(string)
	claims := parseTestToken(t, token)
	if claims["amr"] != "wx" {
		t.Errorf("amr = %v, want wx", claims["amr"])
	}
	if rec := env.do("GET", "/api/me", []byte(token), nil); rec.Code != http.StatusOK {
		t.Errorf("the WeChat token on /api/me: %d %s", rec.Code, rec.Body.String())
	}
	if n := env.identityCount(`user_id = $1 AND openid = 'openid-A' AND appid = $2`, id, fx.appid); n != 1 {
		t.Errorf("identity rows = %d, want 1", n)
	}
	var audited int
	env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE action = 'auth.wechat_bind' AND outcome = 'allowed' AND actor_id = $1
		   AND detail::text NOT LIKE '%openid-A%' AND detail::text NOT LIKE '%'||$2||'%'`, id, phone).Scan(&audited)
	if audited != 1 {
		t.Errorf("bind audit rows = %d, want exactly 1 without the openid or phone in it", audited)
	}

	// Next time the same WeChat account restores the session without a phone authorization.
	fx.openids["L2"] = "openid-A"
	code, body = env.wxPost("/api/auth/wechat/login", map[string]any{"loginCode": "L2"})
	if code != http.StatusOK || int64(body["user"].(map[string]any)["id"].(float64)) != id {
		t.Fatalf("restore: %d %v", code, body)
	}

	// A re-authorization from the same WeChat account is idempotent, not a conflict.
	fx.openids["L3"], fx.phones["P3"] = "openid-A", wechat.Phone{Pure: phone, CountryCode: "86"}
	if code, body := env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L3", "phoneCode": "P3"}); code != http.StatusOK {
		t.Errorf("repeated authorization: %d %v", code, body)
	}
	if n := env.identityCount(`user_id = $1`, id); n != 1 {
		t.Errorf("identity rows after re-authorization = %d, want 1", n)
	}
}

func TestWeChat_NoAccountNoSession(t *testing.T) {
	env, fx := wxEnv(t)
	stranger := testPhone()
	fx.openids["L"], fx.phones["P"] = "openid-S", wechat.Phone{Pure: stranger, CountryCode: "86"}

	var before int
	env.pool.QueryRow(context.Background(), `SELECT count(*) FROM users`).Scan(&before)
	code, body := env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L", "phoneCode": "P"})
	if code != http.StatusNotFound || codeOf(body) != "phone_not_provisioned" {
		t.Fatalf("unprovisioned phone: %d %v, want 404 phone_not_provisioned", code, body)
	}
	if _, has := body["token"]; has {
		t.Error("a token was issued for an unprovisioned phone")
	}
	var after int
	env.pool.QueryRow(context.Background(), `SELECT count(*) FROM users`).Scan(&after)
	if after != before {
		t.Errorf("login created %d user rows: accounts are never auto-registered", after-before)
	}
	if n := env.identityCount(`openid = 'openid-S'`); n != 0 {
		t.Errorf("an identity was bound for an unprovisioned phone")
	}
}

func TestWeChat_DisabledAccountIsRefused(t *testing.T) {
	env, fx := wxEnv(t)
	phone, id := env.staffIn(env.defaultCustomerID())
	fx.openids["L1"], fx.phones["P1"] = "openid-D", wechat.Phone{Pure: phone, CountryCode: "86"}
	if code, _ := env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L1", "phoneCode": "P1"}); code != http.StatusOK {
		t.Fatalf("bind: %d", code)
	}
	env.pool.Exec(context.Background(), `UPDATE users SET disabled_at = now() WHERE id = $1`, id)

	// Restoring a session for a disabled account is refused ...
	fx.openids["L2"] = "openid-D"
	code, body := env.wxPost("/api/auth/wechat/login", map[string]any{"loginCode": "L2"})
	if code != http.StatusForbidden || codeOf(body) != "account_disabled" || body["token"] != nil {
		t.Errorf("disabled restore: %d %v, want 403 account_disabled and no token", code, body)
	}
	// ... and so is a fresh authorization, which also cannot create a binding.
	other, otherID := env.staffIn(env.defaultCustomerID())
	env.pool.Exec(context.Background(), `UPDATE users SET disabled_at = now() WHERE id = $1`, otherID)
	fx.openids["L3"], fx.phones["P3"] = "openid-E", wechat.Phone{Pure: other, CountryCode: "86"}
	code, body = env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L3", "phoneCode": "P3"})
	if code != http.StatusForbidden || codeOf(body) != "account_disabled" {
		t.Errorf("disabled first login: %d %v, want 403 account_disabled", code, body)
	}
	if n := env.identityCount(`user_id = $1`, otherID); n != 0 {
		t.Error("a disabled account got bound")
	}
}

func TestWeChat_IdentityConflictNeverOverwrites(t *testing.T) {
	env, fx := wxEnv(t)
	cust := env.defaultCustomerID()
	phoneA, idA := env.staffIn(cust)
	phoneB, idB := env.staffIn(cust)

	fx.openids["L1"], fx.phones["P1"] = "openid-1", wechat.Phone{Pure: phoneA, CountryCode: "86"}
	if code, _ := env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L1", "phoneCode": "P1"}); code != http.StatusOK {
		t.Fatalf("bind A: %d", code)
	}

	// A different WeChat account authorizes A's number: refused, A keeps openid-1.
	fx.openids["L2"], fx.phones["P2"] = "openid-2", wechat.Phone{Pure: phoneA, CountryCode: "86"}
	code, body := env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L2", "phoneCode": "P2"})
	if code != http.StatusConflict || codeOf(body) != "wechat_identity_conflict" || body["token"] != nil {
		t.Errorf("second WeChat on A's number: %d %v, want 409 wechat_identity_conflict", code, body)
	}
	// The same WeChat account (openid-1) authorizes B's number: refused, it is not moved to B.
	fx.openids["L3"], fx.phones["P3"] = "openid-1", wechat.Phone{Pure: phoneB, CountryCode: "86"}
	code, body = env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L3", "phoneCode": "P3"})
	if code != http.StatusConflict || codeOf(body) != "wechat_identity_conflict" {
		t.Errorf("openid-1 on B's number: %d %v, want 409", code, body)
	}
	if n := env.identityCount(`user_id = $1 AND openid = 'openid-1'`, idA); n != 1 {
		t.Error("A's identity was changed")
	}
	if n := env.identityCount(`user_id = $1`, idB); n != 0 {
		t.Error("B was bound to someone else's WeChat account")
	}
	var denied int
	env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE action = 'auth.wechat_bind' AND outcome = 'denied' AND actor_id IN ($1,$2)`, idA, idB).Scan(&denied)
	if denied != 2 {
		t.Errorf("denied bind audit rows = %d, want 2", denied)
	}
}

func TestWeChat_CodesAreSingleUse(t *testing.T) {
	env, fx := wxEnv(t)
	phone, _ := env.staffIn(env.defaultCustomerID())
	fx.openids["L"], fx.phones["P"] = "openid-R", wechat.Phone{Pure: phone, CountryCode: "86"}

	req := map[string]any{"loginCode": "L", "phoneCode": "P"}
	if code, _ := env.wxPost("/api/auth/wechat/phone-login", req); code != http.StatusOK {
		t.Fatalf("first use: %d", code)
	}
	code, body := env.wxPost("/api/auth/wechat/phone-login", req)
	if code != http.StatusUnauthorized || codeOf(body) != "wechat_code_invalid" || body["token"] != nil {
		t.Errorf("replayed codes: %d %v, want 401 wechat_code_invalid and no token", code, body)
	}
}

func TestWeChat_FailuresGiveNoSession(t *testing.T) {
	env, fx := wxEnv(t)
	phone, _ := env.staffIn(env.defaultCustomerID())
	fx.openids["L"], fx.phones["P"] = "openid-F", wechat.Phone{Pure: phone, CountryCode: "86"}
	fx.phones["PUS"] = wechat.Phone{Pure: "2025550123", CountryCode: "1"}
	fx.failWith["LDOWN"], fx.openids["LDOWN"] = wechat.ErrUpstream, "x"
	fx.failWith["PMIS"], fx.phones["PMIS"] = wechat.ErrAppMismatch, wechat.Phone{Pure: phone, CountryCode: "86"}

	cases := map[string]struct {
		path string
		body map[string]any
		code int
		want string
	}{
		"unknown login code":    {"/api/auth/wechat/login", map[string]any{"loginCode": "nope"}, 401, "wechat_code_invalid"},
		"missing login code":    {"/api/auth/wechat/login", map[string]any{}, 400, "invalid_request"},
		"WeChat unreachable":    {"/api/auth/wechat/login", map[string]any{"loginCode": "LDOWN"}, 502, "wechat_upstream_error"},
		"missing phone code":    {"/api/auth/wechat/phone-login", map[string]any{"loginCode": "L"}, 400, "invalid_request"},
		"unknown phone code":    {"/api/auth/wechat/phone-login", map[string]any{"loginCode": "L", "phoneCode": "nope"}, 401, "wechat_code_invalid"},
		"number from other app": {"/api/auth/wechat/phone-login", map[string]any{"loginCode": "L", "phoneCode": "PMIS"}, 401, "wechat_code_invalid"},
		"foreign number":        {"/api/auth/wechat/phone-login", map[string]any{"loginCode": "L", "phoneCode": "PUS"}, 400, "phone_unsupported"},
	}
	for name, tc := range cases {
		// Each case needs a fresh login code: the fake (like WeChat) burns a code when it is used.
		if c, ok := tc.body["loginCode"].(string); ok && c == "L" {
			fresh := "L-" + strings.ReplaceAll(name, " ", "-")
			fx.openids[fresh] = "openid-F"
			tc.body["loginCode"] = fresh
		}
		code, body := env.wxPost(tc.path, tc.body)
		if code != tc.code || codeOf(body) != tc.want || body["token"] != nil {
			t.Errorf("%s: %d %v, want %d %s with no token", name, code, body, tc.code, tc.want)
		}
	}
	if n := env.identityCount(`openid = 'openid-F'`); n != 0 {
		t.Errorf("a failed login left %d identity rows", n)
	}
}

func TestWeChat_ClientCannotAssertPhoneOrOpenID(t *testing.T) {
	env, fx := wxEnv(t)
	cust := env.defaultCustomerID()
	mine, myID := env.staffIn(cust)
	victim, victimID := env.staffIn(cust)
	fx.openids["L"], fx.phones["P"] = "openid-M", wechat.Phone{Pure: mine, CountryCode: "86"}

	// Extra fields naming someone else's number or an openid are not read at all.
	code, body := env.wxPost("/api/auth/wechat/phone-login", map[string]any{
		"loginCode": "L", "phoneCode": "P", "phone": victim, "phoneNumber": victim, "openid": "openid-VICTIM", "userId": victimID,
	})
	if code != http.StatusOK || int64(body["user"].(map[string]any)["id"].(float64)) != myID {
		t.Fatalf("login: %d %v, want it bound to the WeChat-verified number's account %d", code, body, myID)
	}
	if n := env.identityCount(`user_id = $1`, victimID); n != 0 {
		t.Error("a client-supplied phone bound the victim's account")
	}
	if n := env.identityCount(`openid = 'openid-VICTIM'`); n != 0 {
		t.Error("a client-supplied openid was stored")
	}
}

func TestWeChat_CustomerAdminIsTheSameAccountOnBothEntrances(t *testing.T) {
	env, fx := wxEnv(t)
	_, adminID := env.userIn(control.RoleCustomerAdmin, env.defaultCustomerID())
	var phone string
	env.pool.QueryRow(context.Background(), `SELECT phone FROM users WHERE id = $1`, adminID).Scan(&phone)

	_, pwBody := env.loginRaw(map[string]any{"phone": phone, "password": "e2e-test-pass"})
	var pw loginResp
	json.Unmarshal([]byte(pwBody), &pw)

	fx.openids["L"], fx.phones["P"] = "openid-CA", wechat.Phone{Pure: phone, CountryCode: "86"}
	code, body := env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L", "phoneCode": "P"})
	if code != http.StatusOK {
		t.Fatalf("wechat: %d %v", code, body)
	}
	u := body["user"].(map[string]any)
	if int64(u["id"].(float64)) != pw.User.ID || int64(u["customerId"].(float64)) != *pw.User.CustomerID || u["role"] != pw.User.Role {
		t.Errorf("web login %+v and WeChat login %v are different accounts", pw.User, u)
	}
	// Binding WeChat does not change how the admin signs in on the web.
	if c, b := env.loginRaw(map[string]any{"phone": phone, "password": "e2e-test-pass"}); c != http.StatusOK {
		t.Errorf("password login after binding: %d %s", c, b)
	}
}

func TestWeChat_ConcurrentFirstLoginsBindOnce(t *testing.T) {
	env, fx := wxEnv(t)
	phone, id := env.staffIn(env.defaultCustomerID())
	const n = 8
	var wg sync.WaitGroup
	codes := make([]int, n)
	for i := 0; i < n; i++ {
		l, p := "L"+string(rune('a'+i)), "P"+string(rune('a'+i))
		fx.openids[l], fx.phones[p] = "openid-C", wechat.Phone{Pure: phone, CountryCode: "86"}
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes[i], _ = env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": l, "phoneCode": p})
		}()
	}
	wg.Wait()
	for i, c := range codes {
		if c != http.StatusOK {
			t.Errorf("concurrent login %d: %d, want 200 (same WeChat account, same user)", i, c)
		}
	}
	if got := env.identityCount(`user_id = $1`, id); got != 1 {
		t.Errorf("identity rows = %d, want exactly 1", got)
	}
}

func TestWeChat_TokenIsAFullAuthentication(t *testing.T) {
	env, fx := wxEnv(t)
	phone, _ := env.staffIn(env.defaultCustomerID())
	fx.openids["L"], fx.phones["P"] = "openid-T", wechat.Phone{Pure: phone, CountryCode: "86"}
	_, body := env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "L", "phoneCode": "P"})
	claims := parseTestToken(t, body["token"].(string))
	within(t, "authAt", claimTime(t, claims, "authAt"), time.Now(), 5*time.Second)
}

func TestMiniCode_PlatformAdminOnlyAndNoGrant(t *testing.T) {
	env, fx := wxEnv(t)
	const dev = "ZCT9MINI01"
	env.seedDevice(dev)
	url := "/api/devices/" + dev + "/mini-code"

	// Default role is customer_user: refused, and nothing is rendered.
	if rec := env.authed("GET", url, nil); rec.Code != http.StatusForbidden {
		t.Fatalf("customer_user: %d, want 403", rec.Code)
	}
	env.setRole(control.RoleCustomerAdmin)
	if rec := env.authed("GET", url, nil); rec.Code != http.StatusForbidden {
		t.Fatalf("customer_admin: %d, want 403", rec.Code)
	}
	if fx.lastCode[0] != "" {
		t.Fatalf("a refused caller reached WeChat: %v", fx.lastCode)
	}

	env.setRole(control.RolePlatformAdmin)
	rec := env.authed("GET", url, nil)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), dev) {
		t.Fatalf("platform admin: %d %q", rec.Code, rec.Body.String())
	}
	if fx.lastCode[0] != dev {
		t.Errorf("scene = %q, want the device name", fx.lastCode[0])
	}
	if rec := env.authed("GET", "/api/devices/NOPE-DEVICE/mini-code", nil); rec.Code != http.StatusNotFound {
		t.Errorf("unknown device: %d, want 404", rec.Code)
	}
	var grants int
	env.pool.QueryRow(context.Background(), `SELECT count(*) FROM user_tank_grants`).Scan(&grants)
	if grants != 0 {
		t.Errorf("rendering a code wrote %d grants", grants)
	}
}

func TestMiniCode_WeChatOffIs503(t *testing.T) {
	env := newTestEnv(t)
	env.setRole(control.RolePlatformAdmin)
	env.seedDevice("ZCT9MINI02")
	rec := env.authed("GET", "/api/devices/ZCT9MINI02/mini-code", nil)
	if rec.Code != http.StatusServiceUnavailable {
		t.Errorf("WeChat off: %d, want 503", rec.Code)
	}
}
