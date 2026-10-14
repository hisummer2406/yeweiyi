package api_test

import (
	"context"
	"github.com/jackc/pgx/v5/pgtype"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
	"yeweiyi-cloud/internal/wechat"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// Customer onboarding (cloud#60). The fixture customer is seeded active; each test flips it to
// configuring so tokens minted while it was active can be reused as "old tokens".

type onboardingBody struct {
	Status   string   `json:"status"`
	Complete bool     `json:"complete"`
	Missing  []string `json:"missing"`
	Code     string   `json:"code"`
}

func (tn *tenancy) setOnboarding(t *testing.T, cust int64, status string) {
	t.Helper()
	q := `UPDATE customers SET onboarding_status = 'configuring', onboarded_at = NULL WHERE id = $1`
	if status == "active" {
		q = `UPDATE customers SET onboarding_status = 'active', onboarded_at = now() WHERE id = $1`
	}
	if _, err := tn.env.pool.Exec(context.Background(), q, cust); err != nil {
		t.Fatalf("set onboarding: %v", err)
	}
}

func (tn *tenancy) phoneOf(t *testing.T, userID int64) string {
	t.Helper()
	var phone string
	if err := tn.env.pool.QueryRow(context.Background(), `SELECT phone FROM users WHERE id = $1`, userID).Scan(&phone); err != nil {
		t.Fatalf("phone: %v", err)
	}
	return phone
}

func onboardingPath(cust int64, suffix string) string {
	return "/api/customers/" + strconv.FormatInt(cust, 10) + "/onboarding" + suffix
}

func TestOnboarding_ConfiguringCustomerCannotSignInOrUseTokens(t *testing.T) {
	tn := newTenancy(t)
	phone := tn.phoneOf(t, tn.adminAID)
	tn.setOnboarding(t, tn.custA, "configuring")

	// Password login: right password -> the business refusal; wrong password -> unchanged 401.
	rec := tn.send(t, "POST", "/api/login", "", map[string]string{"phone": phone, "password": "e2e-test-pass"})
	if got := decodeAs[onboardingBody](t, rec); rec.Code != http.StatusForbidden || got.Code != "customer_not_onboarded" {
		t.Errorf("login in configuring customer: %d %s", rec.Code, rec.Body.String())
	}
	rec = tn.send(t, "POST", "/api/login", "", map[string]string{"phone": phone, "password": "wrong-password"})
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("wrong password must stay 401 and say nothing about the customer, got %d", rec.Code)
	}

	// Old tokens: customer admin and staff are both refused, with no renewed token.
	for who, token := range map[string]string{"customer admin": tn.adminA, "customer user": tn.userA} {
		rec := tn.send(t, "GET", "/api/me", token, nil)
		if got := decodeAs[onboardingBody](t, rec); rec.Code != http.StatusForbidden || got.Code != "customer_not_onboarded" {
			t.Errorf("%s old token: %d %s", who, rec.Code, rec.Body.String())
		}
		if rec.Header().Get("X-Renewed-Token") != "" {
			t.Errorf("%s: a refused request must not renew the token", who)
		}
	}
	// Other customers and the platform admin are untouched.
	if rec := tn.send(t, "GET", "/api/me", tn.adminB, nil); rec.Code != http.StatusOK {
		t.Errorf("other customer's admin: %d", rec.Code)
	}
	if rec := tn.send(t, "GET", "/api/me", tn.platform, nil); rec.Code != http.StatusOK {
		t.Errorf("platform admin: %d", rec.Code)
	}

	// Completing restores the very same old token.
	tn.setOnboarding(t, tn.custA, "active")
	if rec := tn.send(t, "GET", "/api/me", tn.adminA, nil); rec.Code != http.StatusOK {
		t.Errorf("after onboarding the old token must work again: %d", rec.Code)
	}
}

func TestOnboarding_CompleteChecksConditions(t *testing.T) {
	tn := newTenancy(t)
	cust := tn.env.newCustomer("onb")
	ctx := context.Background()
	// userIn signs in, which needs an active customer: seed the admin first, park it disabled, then
	// put the customer back into configuring.
	_, adminID := tn.env.userIn(control.RoleCustomerAdmin, cust)
	tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, adminID)
	tn.setOnboarding(t, cust, "configuring")

	// Authority first: customer admins and staff never reach the customer id.
	for who, token := range map[string]string{"customer admin": tn.adminA, "customer user": tn.userA} {
		for _, rec := range []*httpRec{
			{tn.send(t, "POST", onboardingPath(cust, "/complete"), token, nil)},
			{tn.send(t, "GET", onboardingPath(cust, ""), token, nil)},
		} {
			if rec.Code != http.StatusForbidden || rec.Body.String() != `{"error":"not authorized"}` {
				t.Errorf("%s: %d %s", who, rec.Code, rec.Body.String())
			}
		}
	}

	rec := tn.send(t, "POST", onboardingPath(cust, "/complete"), tn.platform, nil)
	got := decodeAs[onboardingBody](t, rec)
	if rec.Code != http.StatusConflict || got.Code != "onboarding_incomplete" || len(got.Missing) != 3 {
		t.Fatalf("empty customer: %d %s", rec.Code, rec.Body.String())
	}
	if n := tn.auditCount(t, `action = 'customer.onboard' AND outcome = 'denied'`); n < 1 {
		t.Errorf("a refused completion should be audited")
	}

	// A disabled admin does not count (still all three missing, checked above); an enabled one does.
	tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = NULL WHERE id = $1`, adminID)
	rec = tn.send(t, "GET", onboardingPath(cust, ""), tn.platform, nil)
	if got := decodeAs[onboardingBody](t, rec); len(got.Missing) != 2 || got.Missing[0] != "station" {
		t.Errorf("after admin: %s", rec.Body.String())
	}

	// Station without device, then device without station: still incomplete.
	var stationID int64
	if err := tn.env.pool.QueryRow(ctx, `INSERT INTO stations (customer_id, name) VALUES ($1, 'onb') RETURNING id`, cust).Scan(&stationID); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { tn.env.pool.Exec(ctx, `DELETE FROM stations WHERE id = $1`, stationID) })
	dev := "onb-dev-" + strconv.FormatInt(cust, 10)
	tn.env.seedDeviceIn(dev, cust)
	rec = tn.send(t, "POST", onboardingPath(cust, "/complete"), tn.platform, nil)
	if got := decodeAs[onboardingBody](t, rec); rec.Code != http.StatusConflict || len(got.Missing) != 1 || got.Missing[0] != "device_in_station" {
		t.Fatalf("device not in station: %d %s", rec.Code, rec.Body.String())
	}
	// Nothing was rolled back by the refusal: the admin and station are still there.
	tn.env.pool.Exec(ctx, `UPDATE devices SET station_id = $1 WHERE device_name = $2`, stationID, dev)

	rec = tn.send(t, "POST", onboardingPath(cust, "/complete"), tn.platform, nil)
	got = decodeAs[onboardingBody](t, rec)
	if rec.Code != http.StatusOK || got.Status != "active" || !got.Complete {
		t.Fatalf("complete: %d %s", rec.Code, rec.Body.String())
	}
	// Idempotent, and no second audit row.
	before := tn.auditCount(t, `action = 'customer.onboard' AND outcome = 'allowed'`)
	rec = tn.send(t, "POST", onboardingPath(cust, "/complete"), tn.platform, nil)
	if rec.Code != http.StatusOK {
		t.Errorf("repeat: %d", rec.Code)
	}
	if after := tn.auditCount(t, `action = 'customer.onboard' AND outcome = 'allowed'`); after != before {
		t.Errorf("repeat wrote audit rows: %d -> %d", before, after)
	}

	// Active stays active when assets go away.
	tn.env.pool.Exec(ctx, `UPDATE devices SET station_id = NULL WHERE device_name = $1`, dev)
	rec = tn.send(t, "GET", onboardingPath(cust, ""), tn.platform, nil)
	if got := decodeAs[onboardingBody](t, rec); got.Status != "active" || len(got.Missing) != 0 {
		t.Errorf("active customer must stay active: %s", rec.Body.String())
	}

	if rec := tn.send(t, "POST", onboardingPath(999999999, "/complete"), tn.platform, nil); rec.Code != http.StatusNotFound {
		t.Errorf("unknown customer: %d", rec.Code)
	}
}

// httpRec lets the authority loop above range over responses without naming httptest here.
type httpRec struct {
	*httptest.ResponseRecorder
}

func TestOnboarding_WeChatEntrancesRefusedWhileConfiguring(t *testing.T) {
	env, fx := wxEnv(t)
	cust := env.newCustomer("wxonb")
	setStatus := func(s string) {
		q := `UPDATE customers SET onboarding_status = 'configuring', onboarded_at = NULL WHERE id = $1`
		if s == "active" {
			q = `UPDATE customers SET onboarding_status = 'active', onboarded_at = now() WHERE id = $1`
		}
		if _, err := env.pool.Exec(context.Background(), q, cust); err != nil {
			t.Fatal(err)
		}
	}
	phone, id := env.staffIn(cust)
	// An already-bound identity for the restore path.
	if _, err := env.pool.Exec(context.Background(),
		`INSERT INTO user_wechat_identities (user_id, appid, openid) VALUES ($1, $2, 'openid-onb')`, id, fx.appid); err != nil {
		t.Fatal(err)
	}
	fx.openids["R1"] = "openid-onb"
	setStatus("configuring")

	code, body := env.wxPost("/api/auth/wechat/login", map[string]any{"loginCode": "R1"})
	if code != http.StatusForbidden || codeOf(body) != "customer_not_onboarded" || body["token"] != nil {
		t.Errorf("restore: %d %v", code, body)
	}

	// First-time phone authorization of a different account in the same configuring customer binds nothing.
	phone2, id2 := env.staffIn(cust)
	_ = phone
	fx.openids["F1"], fx.phones["PF1"] = "openid-new", wechat.Phone{Pure: phone2, CountryCode: "86"}
	code, body = env.wxPost("/api/auth/wechat/phone-login", map[string]any{"loginCode": "F1", "phoneCode": "PF1"})
	if code != http.StatusForbidden || codeOf(body) != "customer_not_onboarded" || body["token"] != nil {
		t.Errorf("first login: %d %v", code, body)
	}
	if n := env.identityCount(`user_id = $1`, id2); n != 0 {
		t.Errorf("a configuring customer's user got bound (%d identity rows)", n)
	}

	// After onboarding the same flows work.
	setStatus("active")
	fx.openids["R2"] = "openid-onb"
	if code, body := env.wxPost("/api/auth/wechat/login", map[string]any{"loginCode": "R2"}); code != http.StatusOK {
		t.Errorf("restore after onboarding: %d %v", code, body)
	}
}

// The list query is an independent copy of the device-access rule. A caller that reaches it without
// the HTTP middleware (anything internal) must get the same answer as user_may_access_device: nothing
// for a configuring customer's admin or employee, everything again once it is active.
func TestOnboarding_DeviceListAgreesWithAccessFunction(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	q := db.New(tn.env.pool)
	list := func(userID int64) int {
		rows, err := q.ListDevicesForUser(ctx, userID)
		if err != nil {
			t.Fatalf("ListDevicesForUser: %v", err)
		}
		return len(rows)
	}
	if list(tn.adminAID) == 0 {
		t.Fatalf("fixture: customer A's admin sees no device while the customer is active")
	}
	tn.setOnboarding(t, tn.custA, "configuring")
	for name, id := range map[string]int64{"admin": tn.adminAID, "employee": tn.userAID} {
		if n := list(id); n != 0 {
			t.Errorf("%s of a configuring customer lists %d devices, want 0", name, n)
		}
	}
	if list(tn.platformID) == 0 {
		t.Errorf("a platform administrator must keep listing devices of a configuring customer")
	}
	tn.setOnboarding(t, tn.custA, "active")
	if list(tn.adminAID) == 0 {
		t.Errorf("after completion the admin lists nothing")
	}
}

// Stations and the audit log are two more hand-written copies of the access rule. Called without the
// HTTP middleware they must agree with user_may_access_device too: nothing for a configuring
// customer's admin (the audit query without a device filter is the case the function alone misses),
// everything again once it is active, and a platform administrator is never affected.
func TestOnboarding_StationAndAuditListsAgreeWithAccessFunction(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	q := db.New(tn.env.pool)
	if _, err := tn.env.pool.Exec(ctx, `INSERT INTO stations (customer_id, name) VALUES ($1, $2)`,
		tn.custA, uniqueName("onb-station")); err != nil {
		t.Fatalf("seed station: %v", err)
	}
	if _, err := tn.env.pool.Exec(ctx,
		`INSERT INTO audit_log (actor, action, device_name, outcome, customer_id) VALUES ('onb-test', 'onb.test', $1, 'allowed', $2)`,
		tn.devA1, tn.custA); err != nil {
		t.Fatalf("seed audit row: %v", err)
	}
	t.Cleanup(func() {
		tn.env.pool.Exec(context.Background(), `DELETE FROM audit_log WHERE actor = 'onb-test'`)
		tn.env.pool.Exec(context.Background(), `DELETE FROM stations WHERE customer_id = $1`, tn.custA)
	})
	stations := func(userID int64) int {
		rows, err := q.ListStationsForUser(ctx, userID)
		if err != nil {
			t.Fatalf("ListStationsForUser: %v", err)
		}
		n := 0
		for _, r := range rows {
			if r.CustomerID == tn.custA {
				n++
			}
		}
		return n
	}
	audit := func(userID int64) int {
		rows, err := q.ListAuditLogVisibleToUser(ctx, db.ListAuditLogVisibleToUserParams{
			UserID:    userID,
			BeginTime: pgtype.Timestamptz{Time: time.Now().Add(-time.Hour), Valid: true},
			EndTime:   pgtype.Timestamptz{Time: time.Now().Add(time.Hour), Valid: true},
			Lim:       100,
		})
		if err != nil {
			t.Fatalf("ListAuditLogVisibleToUser: %v", err)
		}
		n := 0
		for _, r := range rows {
			if r.CustomerID.Valid && r.CustomerID.Int64 == tn.custA && r.Action == "onb.test" {
				n++
			}
		}
		return n
	}

	if stations(tn.adminAID) == 0 || audit(tn.adminAID) == 0 {
		t.Fatalf("fixture: an active customer's admin sees %d stations, %d audit rows", stations(tn.adminAID), audit(tn.adminAID))
	}
	tn.setOnboarding(t, tn.custA, "configuring")
	if n := stations(tn.adminAID); n != 0 {
		t.Errorf("configuring customer's admin lists %d stations, want 0", n)
	}
	if n := audit(tn.adminAID); n != 0 {
		t.Errorf("configuring customer's admin reads %d audit rows, want 0", n)
	}
	if stations(tn.platformID) == 0 || audit(tn.platformID) == 0 {
		t.Errorf("a platform administrator must keep seeing a configuring customer's stations and audit rows")
	}
	tn.setOnboarding(t, tn.custA, "active")
	if stations(tn.adminAID) == 0 || audit(tn.adminAID) == 0 {
		t.Errorf("after completion the admin lists nothing")
	}
}
