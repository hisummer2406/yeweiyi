package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"yeweiyi-cloud/internal/control"
)

// Phase 2 of the multi-tenancy change: customers, users and device assignment. All of it runs on the
// two-customer world from tenancy_test.go.

func (tn *tenancy) send(t *testing.T, method, path, token string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var b []byte
	if body != nil {
		b = jsonBody(t, body)
	}
	return tn.env.do(method, path, []byte(token), b)
}

func decodeAs[T any](t *testing.T, rec *httptest.ResponseRecorder) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("decode %q: %v", rec.Body.String(), err)
	}
	return v
}

type adminUser struct {
	ID         int64   `json:"id"`
	Username   *string `json:"username"`
	Phone      string  `json:"phone"`
	Role       string  `json:"role"`
	CustomerID *int64  `json:"customerId"`
	Disabled   bool    `json:"disabled"`
}

// loginName is what the user logs in with: the phone for customer accounts, the username otherwise.
func (u adminUser) loginName() string {
	if u.Phone != "" {
		return u.Phone
	}
	if u.Username != nil {
		return *u.Username
	}
	return ""
}

func uniqueName(label string) string { return fmt.Sprintf("adm-%s-%d", label, time.Now().UnixNano()) }

const testPassword = "correct-horse-1"

// createUser creates a user through the API as token and registers cleanup.
func (tn *tenancy) createUser(t *testing.T, token, role string, customerID *int64) (adminUser, *httptest.ResponseRecorder) {
	t.Helper()
	body := map[string]any{"role": role}
	switch role {
	case control.RolePlatformAdmin:
		body["username"] = uniqueName("u")
		body["password"] = testPassword
	case control.RoleCustomerAdmin:
		body["phone"] = testPhone()
		body["password"] = testPassword
	default: // staff have no password
		body["phone"] = testPhone()
	}
	if customerID != nil {
		body["customerId"] = *customerID
	}
	rec := tn.send(t, "POST", "/api/users", token, body)
	if rec.Code != http.StatusCreated {
		return adminUser{}, rec
	}
	u := decodeAs[adminUser](t, rec)
	t.Cleanup(func() {
		tn.env.pool.Exec(context.Background(), `DELETE FROM user_devices WHERE user_id = $1`, u.ID)
		tn.env.pool.Exec(context.Background(), `DELETE FROM users WHERE id = $1`, u.ID)
	})
	return u, rec
}

// loginCode posts a password login for u, with the field the contract assigns to its role.
func (tn *tenancy) loginCode(u adminUser, password string) int {
	field := "username"
	if u.Phone != "" {
		field = "phone"
	}
	body, _ := json.Marshal(map[string]string{field: u.loginName(), "password": password})
	return tn.env.do("POST", "/api/login", nil, body).Code
}

func (tn *tenancy) auditCount(t *testing.T, where string, args ...any) int {
	t.Helper()
	var n int
	if err := tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM audit_log WHERE `+where, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func ptr(v int64) *int64 { return &v }

// --- customers ----------------------------------------------------------------------------------

func TestAdmin_Customers(t *testing.T) {
	tn := newTenancy(t)
	name := uniqueName("cust")

	// Only a platform admin may manage customers.
	for who, token := range map[string]string{"customer admin": tn.adminA, "customer user": tn.userA} {
		if rec := tn.send(t, "POST", "/api/customers", token, map[string]string{"name": name}); rec.Code != http.StatusForbidden {
			t.Errorf("%s creating a customer: got %d, want 403", who, rec.Code)
		}
		if rec := tn.send(t, "GET", "/api/customers", token, nil); rec.Code != http.StatusForbidden {
			t.Errorf("%s listing customers: got %d, want 403", who, rec.Code)
		}
	}
	// Two refused attempts (the customer admin's and the customer user's), both in customer A.
	if n := tn.auditCount(t, `action = 'customer.create' AND outcome = 'denied' AND customer_id = $1`, tn.custA); n != 2 {
		t.Errorf("each refused attempt should be audited under the caller's customer, got %d rows, want 2", n)
	}

	rec := tn.send(t, "POST", "/api/customers", tn.platform, map[string]string{"name": name})
	if rec.Code != http.StatusCreated {
		t.Fatalf("create: %d %s", rec.Code, rec.Body.String())
	}
	created := decodeAs[struct {
		ID   int64  `json:"id"`
		Name string `json:"name"`
	}](t, rec)
	t.Cleanup(func() { tn.env.pool.Exec(context.Background(), `DELETE FROM customers WHERE id = $1`, created.ID) })

	if rec := tn.send(t, "POST", "/api/customers", tn.platform, map[string]string{"name": name}); rec.Code != http.StatusConflict {
		t.Errorf("duplicate name: got %d, want 409", rec.Code)
	}
	if rec := tn.send(t, "POST", "/api/customers", tn.platform, map[string]string{"name": "   "}); rec.Code != http.StatusBadRequest {
		t.Errorf("blank name: got %d, want 400", rec.Code)
	}

	list := decodeAs[struct {
		Customers []struct {
			ID int64 `json:"id"`
		} `json:"customers"`
	}](t, tn.send(t, "GET", "/api/customers", tn.platform, nil))
	found := false
	for _, c := range list.Customers {
		found = found || c.ID == created.ID
	}
	if !found {
		t.Errorf("the new customer is missing from the list: %+v", list)
	}

	// Rename.
	renamed := name + "-renamed"
	if rec := tn.send(t, "PUT", fmt.Sprintf("/api/customers/%d", created.ID), tn.platform, map[string]string{"name": renamed}); rec.Code != http.StatusOK {
		t.Fatalf("rename: %d %s", rec.Code, rec.Body.String())
	}
	if rec := tn.send(t, "PUT", fmt.Sprintf("/api/customers/%d", created.ID), tn.platform, map[string]string{"name": renamed}); rec.Code != http.StatusOK {
		t.Errorf("renaming to the same name should be a no-op success, got %d", rec.Code)
	}
	var custAName string
	tn.env.pool.QueryRow(context.Background(), `SELECT name FROM customers WHERE id = $1`, tn.custA).Scan(&custAName)
	if rec := tn.send(t, "PUT", fmt.Sprintf("/api/customers/%d", created.ID), tn.platform, map[string]string{"name": custAName}); rec.Code != http.StatusConflict {
		t.Errorf("renaming onto an existing name: got %d, want 409", rec.Code)
	}
	if rec := tn.send(t, "PUT", "/api/customers/999999999", tn.platform, map[string]string{"name": "x"}); rec.Code != http.StatusNotFound {
		t.Errorf("renaming an unknown customer: got %d, want 404", rec.Code)
	}
	defID := tn.env.defaultCustomerID()
	if rec := tn.send(t, "PUT", fmt.Sprintf("/api/customers/%d", defID), tn.platform, map[string]string{"name": "x"}); rec.Code != http.StatusConflict {
		t.Errorf("renaming the default customer: got %d, want 409", rec.Code)
	}

	// Delete: refused while anything references the customer, allowed once nothing does.
	if rec := tn.send(t, "DELETE", fmt.Sprintf("/api/customers/%d", defID), tn.platform, nil); rec.Code != http.StatusConflict {
		t.Errorf("deleting the default customer: got %d, want 409", rec.Code)
	}
	if rec := tn.send(t, "DELETE", fmt.Sprintf("/api/customers/%d", tn.custA), tn.platform, nil); rec.Code != http.StatusConflict {
		t.Errorf("deleting a customer that still has devices and users: got %d, want 409", rec.Code)
	}
	if _, err := tn.env.queries.GetCustomer(context.Background(), tn.custA); err != nil {
		t.Errorf("the refused delete must leave the customer in place: %v", err)
	}
	if rec := tn.send(t, "DELETE", fmt.Sprintf("/api/customers/%d", created.ID), tn.platform, nil); rec.Code != http.StatusNoContent {
		t.Errorf("deleting an empty customer: got %d, want 204", rec.Code)
	}
	if rec := tn.send(t, "DELETE", fmt.Sprintf("/api/customers/%d", created.ID), tn.platform, nil); rec.Code != http.StatusNotFound {
		t.Errorf("deleting it again: got %d, want 404", rec.Code)
	}

	for _, action := range []string{"customer.create", "customer.rename", "customer.delete"} {
		if n := tn.auditCount(t, `action = $1 AND outcome = 'allowed' AND (detail->>'customerId')::bigint = $2`, action, created.ID); n < 1 {
			t.Errorf("no audit row for %s", action)
		}
	}
}

// --- users --------------------------------------------------------------------------------------

func TestAdmin_UserLifecycle(t *testing.T) {
	tn := newTenancy(t)

	// A platform admin creates a customer admin; they can log in, and the password hash never leaks.
	ca, rec := tn.createUser(t, tn.platform, control.RoleCustomerAdmin, ptr(tn.custA))
	if rec.Code != http.StatusCreated {
		t.Fatalf("platform creating a customer admin: %d %s", rec.Code, rec.Body.String())
	}
	if ca.CustomerID == nil || *ca.CustomerID != tn.custA || ca.Role != control.RoleCustomerAdmin {
		t.Errorf("created user: %+v", ca)
	}
	if strings.Contains(rec.Body.String(), "password") || strings.Contains(rec.Body.String(), "$2") {
		t.Errorf("the response exposes credential material: %s", rec.Body.String())
	}
	if code := tn.loginCode(ca, testPassword); code != http.StatusOK {
		t.Errorf("the new customer admin cannot log in: %d", code)
	}

	// Staff are created by the platform admin with a phone and a customer, and no password. They
	// cannot log in with a password at all.
	cu, rec := tn.createUser(t, tn.platform, control.RoleCustomerUser, ptr(tn.custA))
	if rec.Code != http.StatusCreated || cu.CustomerID == nil || *cu.CustomerID != tn.custA || cu.Role != control.RoleCustomerUser {
		t.Fatalf("platform admin creating staff: %d %s", rec.Code, rec.Body.String())
	}
	if cu.Username != nil || cu.Phone == "" {
		t.Errorf("a customer-side account is identified by phone, not username: %+v", cu)
	}
	var hashIsNull bool
	tn.env.pool.QueryRow(context.Background(), `SELECT password_hash IS NULL FROM users WHERE id = $1`, cu.ID).Scan(&hashIsNull)
	if !hashIsNull {
		t.Error("staff must be stored without any password hash")
	}
	for _, pw := range []string{testPassword, "", "x"} {
		if code := tn.loginCode(cu, pw); code == http.StatusOK {
			t.Errorf("staff logged in with a password %q", pw)
		}
	}

	// Nobody but a platform admin creates accounts: not a customer admin, whatever role or customer
	// they name, and not staff. Every attempt is refused before the body is read and creates nothing.
	var before int
	tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM users`).Scan(&before)
	for name, tc := range map[string]struct {
		token string
		role  string
		cust  *int64
	}{
		"customer admin creating staff":                 {tn.adminA, control.RoleCustomerUser, nil},
		"customer admin creating staff, naming its own": {tn.adminA, control.RoleCustomerUser, ptr(tn.custA)},
		"customer admin creating a customer admin":      {tn.adminA, control.RoleCustomerAdmin, nil},
		"customer admin creating a platform admin":      {tn.adminA, control.RolePlatformAdmin, nil},
		"customer admin creating in another customer":   {tn.adminA, control.RoleCustomerUser, ptr(tn.custB)},
		"staff creating anyone":                         {tn.userA, control.RoleCustomerUser, nil},
	} {
		if _, rec := tn.createUser(t, tc.token, tc.role, tc.cust); rec.Code != http.StatusForbidden {
			t.Errorf("%s: got %d, want 403 (%s)", name, rec.Code, rec.Body.String())
		}
	}
	// A forged body that no valid request would send gets the same 403 — it is not even parsed.
	if rec := tn.send(t, "POST", "/api/users", tn.adminA, map[string]any{"role": "root", "phone": "x", "customerId": "y"}); rec.Code != http.StatusForbidden {
		t.Errorf("forged body from a customer admin: got %d, want 403", rec.Code)
	}
	var after int
	tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM users`).Scan(&after)
	if after != before {
		t.Errorf("refused creations left %d new user rows", after-before)
	}

	// Validation (as the platform admin).
	post := func(body map[string]any) (int, string) {
		rec := tn.send(t, "POST", "/api/users", tn.platform, body)
		return rec.Code, rec.Body.String()
	}
	good := func() map[string]any {
		return map[string]any{"phone": testPhone(), "role": control.RoleCustomerUser, "customerId": tn.custA}
	}
	for name, tc := range map[string]struct {
		mutate func(map[string]any)
		code   string
	}{
		"password sent for staff":          {func(b map[string]any) { b["password"] = testPassword }, "password_not_allowed"},
		"unknown role":                     {func(b map[string]any) { b["role"] = "root" }, "invalid_request"},
		"blank phone":                      {func(b map[string]any) { b["phone"] = "  " }, "invalid_phone"},
		"malformed phone":                  {func(b map[string]any) { b["phone"] = "12345" }, "invalid_phone"},
		"foreign country code":             {func(b map[string]any) { b["phone"] = "+1 13800138000" }, "invalid_phone"},
		"username on a customer account":   {func(b map[string]any) { b["username"] = "someone" }, "invalid_request"},
		"customer role without a customer": {func(b map[string]any) { delete(b, "customerId") }, "invalid_request"},
		"platform admin with a customer": {func(b map[string]any) {
			b["role"] = control.RolePlatformAdmin
			delete(b, "phone")
			b["username"] = uniqueName("v")
			b["password"] = testPassword
		}, "invalid_request"},
		"customer admin without password": {func(b map[string]any) { b["role"] = control.RoleCustomerAdmin }, "invalid_request"},
		"customer admin short password": {func(b map[string]any) {
			b["role"] = control.RoleCustomerAdmin
			b["password"] = "short"
		}, "invalid_request"},
		"customer admin password over bcrypt's 72 bytes": {func(b map[string]any) {
			b["role"] = control.RoleCustomerAdmin
			b["password"] = strings.Repeat("x", 73)
		}, "invalid_request"},
	} {
		b := good()
		tc.mutate(b)
		code, body := post(b)
		if code != http.StatusBadRequest || !strings.Contains(body, `"code":"`+tc.code+`"`) {
			t.Errorf("%s: got %d %s, want 400 with code %s", name, code, body, tc.code)
		}
	}
	unknown := good()
	unknown["customerId"] = 999999999
	if code, _ := post(unknown); code != http.StatusBadRequest {
		t.Errorf("unknown customer: got %d, want 400", code)
	}
	dup := good()
	dup["phone"] = cu.Phone
	code, body := post(dup)
	if code != http.StatusConflict || !strings.Contains(body, `"code":"phone_exists"`) {
		t.Errorf("duplicate phone: got %d %s, want 409 phone_exists", code, body)
	}
	// The same number, written differently, is the same number — also across customers.
	dupOther := good()
	dupOther["phone"] = "+86 " + cu.Phone[:3] + " " + cu.Phone[3:]
	dupOther["customerId"] = tn.custB
	if code, body := post(dupOther); code != http.StatusConflict {
		t.Errorf("duplicate phone in another customer, formatted differently: got %d %s, want 409", code, body)
	} else if strings.Contains(body, "custom") && strings.Contains(strings.ToLower(body), "customer a") {
		t.Errorf("the conflict must not name the other customer: %s", body)
	}

	// A platform admin can create another platform admin, which belongs to no customer.
	pa, rec := tn.createUser(t, tn.platform, control.RolePlatformAdmin, nil)
	if rec.Code != http.StatusCreated || pa.CustomerID != nil {
		t.Errorf("platform admin creation: %d %s", rec.Code, rec.Body.String())
	}

	// Listing: a customer admin sees only their customer; a customer user sees none.
	ids := func(rec *httptest.ResponseRecorder) map[int64]bool {
		out := map[int64]bool{}
		for _, u := range decodeAs[struct {
			Users []adminUser `json:"users"`
		}](t, rec).Users {
			out[u.ID] = true
		}
		return out
	}
	listA := tn.send(t, "GET", "/api/users", tn.adminA, nil)
	mine := ids(listA)
	// A customer admin lists their own customer's staff — to choose whom to assign tanks to — and
	// not the customer's other administrators.
	if !mine[cu.ID] || !mine[tn.userAID] || mine[tn.userBID] || mine[tn.adminBID] || mine[tn.platformID] || mine[ca.ID] || mine[tn.adminAID] {
		t.Errorf("customer admin A's user list is wrong: %v", mine)
	}
	for _, u := range decodeAs[struct {
		Users []adminUser `json:"users"`
	}](t, listA).Users {
		if u.Phone == "" || !strings.Contains(u.Phone, "****") {
			t.Errorf("a customer admin must see masked phone numbers, got %q", u.Phone)
		}
	}
	platformView := decodeAs[struct {
		Users []adminUser `json:"users"`
	}](t, tn.send(t, "GET", fmt.Sprintf("/api/users?customerId=%d", tn.custA), tn.platform, nil))
	for _, u := range platformView.Users {
		if u.ID == cu.ID && u.Phone != cu.Phone {
			t.Errorf("a platform admin sees the full number: got %q, want %q", u.Phone, cu.Phone)
		}
	}
	if rec := tn.send(t, "GET", "/api/users?customerId="+fmt.Sprint(tn.custB), tn.adminA, nil); ids(rec)[tn.userBID] {
		t.Error("a customer admin widened their user list with ?customerId=")
	}
	onlyB := ids(tn.send(t, "GET", fmt.Sprintf("/api/users?customerId=%d", tn.custB), tn.platform, nil))
	if !onlyB[tn.userBID] || onlyB[tn.userAID] {
		t.Errorf("platform admin ?customerId=B: %v", onlyB)
	}
	if rec := tn.send(t, "GET", "/api/users", tn.userA, nil); rec.Code != http.StatusForbidden {
		t.Errorf("customer user listing users: got %d, want 403", rec.Code)
	}
	if rec := tn.send(t, "GET", "/api/users?customerId=abc", tn.platform, nil); rec.Code != http.StatusBadRequest {
		t.Errorf("malformed customerId: got %d, want 400", rec.Code)
	}

	// The creation is audited under the new user's customer — by id and role, never by phone.
	if n := tn.auditCount(t, `action = 'user.create' AND detail::text LIKE '%'||$1||'%'`, cu.Phone); n != 0 {
		t.Error("a phone number was written into the audit log")
	}
	if n := tn.auditCount(t, `action = 'user.create' AND outcome = 'allowed' AND customer_id = $1`, tn.custA); n < 2 {
		t.Errorf("expected the creations to be audited under customer A, got %d", n)
	}
}

func TestAdmin_DisableAndResetPassword(t *testing.T) {
	tn := newTenancy(t)

	cu, rec := tn.createUser(t, tn.platform, control.RoleCustomerUser, ptr(tn.custA))
	if rec.Code != http.StatusCreated {
		t.Fatalf("create: %d", rec.Code)
	}
	ca, rec := tn.createUser(t, tn.platform, control.RoleCustomerAdmin, ptr(tn.custA))
	if rec.Code != http.StatusCreated {
		t.Fatalf("create admin: %d", rec.Code)
	}
	tn.bind(t, cu.ID, tn.devA2)
	token := tn.env.login(cu.Phone, "")
	path := "/api/devices/" + tn.devA2 + "/commands"
	if rec := tn.get(token, path); rec.Code != http.StatusOK {
		t.Fatalf("precondition: the bound user should have access, got %d", rec.Code)
	}
	userPath := fmt.Sprintf("/api/users/%d", cu.ID)
	caPath := fmt.Sprintf("/api/users/%d", ca.ID)

	// A customer admin cannot enable, disable or reset anyone — staff of their own customer included —
	// and the refusal leaves the user untouched.
	for name, body := range map[string]map[string]any{
		"disable": {"disabled": true},
		"reset":   {"password": "brand-new-pass-2"},
	} {
		if rec := tn.send(t, "PUT", userPath, tn.adminA, body); rec.Code != http.StatusForbidden {
			t.Errorf("customer admin trying to %s their own staff: got %d, want 403", name, rec.Code)
		}
	}
	if rec := tn.get(token, path); rec.Code != http.StatusOK {
		t.Errorf("the refused requests must not have disabled the user: got %d", rec.Code)
	}
	if rec := tn.send(t, "PUT", userPath, tn.userA, map[string]any{"disabled": true}); rec.Code != http.StatusForbidden {
		t.Errorf("staff managing users: got %d, want 403", rec.Code)
	}
	// An unknown id and another customer's user are indistinguishable to a customer admin: both 403
	// before anything is looked up, so the endpoint is not a user-id oracle.
	other := tn.send(t, "PUT", fmt.Sprintf("/api/users/%d", tn.userBID), tn.adminA, map[string]any{"disabled": true})
	ghost := tn.send(t, "PUT", "/api/users/999999999", tn.adminA, map[string]any{"disabled": true})
	if other.Code != http.StatusForbidden || other.Body.String() != ghost.Body.String() || ghost.Code != other.Code {
		t.Errorf("other customer's user: %d %s; unknown id: %d %s — want identical 403s", other.Code, other.Body.String(), ghost.Code, ghost.Body.String())
	}

	// The platform admin disables staff; it takes effect immediately, even for a token issued before.
	if rec := tn.send(t, "PUT", userPath, tn.platform, map[string]any{"disabled": true}); rec.Code != http.StatusOK || !decodeAs[adminUser](t, rec).Disabled {
		t.Fatalf("disable: %d %s", rec.Code, rec.Body.String())
	}
	if rec := tn.get(token, path); rec.Code != http.StatusForbidden {
		t.Errorf("a disabled user's existing token must be refused at once: got %d", rec.Code)
	}
	if rec := tn.send(t, "GET", "/api/audit?device="+tn.devA2, token, nil); rec.Code != http.StatusForbidden {
		t.Errorf("a disabled user reading the audit log: got %d, want 403", rec.Code)
	}
	if rec := tn.send(t, "GET", "/api/me", token, nil); rec.Code != http.StatusForbidden {
		t.Errorf("a disabled user reading /api/me: got %d, want 403", rec.Code)
	}
	// Re-enabling restores access.
	if rec := tn.send(t, "PUT", userPath, tn.platform, map[string]any{"disabled": false}); rec.Code != http.StatusOK {
		t.Fatalf("enable: %d", rec.Code)
	}
	if rec := tn.get(token, path); rec.Code != http.StatusOK {
		t.Errorf("after re-enabling: got %d, want 200", rec.Code)
	}

	// A disabled customer admin cannot log in, and is answered like a wrong password.
	if rec := tn.send(t, "PUT", caPath, tn.platform, map[string]any{"disabled": true}); rec.Code != http.StatusOK {
		t.Fatalf("disable admin: %d", rec.Code)
	}
	if code := tn.loginCode(ca, testPassword); code != http.StatusUnauthorized {
		t.Errorf("a disabled admin logging in: got %d, want 401", code)
	}
	if code := tn.loginCode(ca, "wrong-password"); code != http.StatusUnauthorized {
		t.Errorf("wrong password: got %d, want 401", code)
	}
	tn.send(t, "PUT", caPath, tn.platform, map[string]any{"disabled": false})

	// Password reset applies to accounts that have a password: the old one stops working.
	if rec := tn.send(t, "PUT", caPath, tn.platform, map[string]any{"password": "brand-new-pass-2"}); rec.Code != http.StatusOK {
		t.Fatalf("reset: %d %s", rec.Code, rec.Body.String())
	}
	if code := tn.loginCode(ca, testPassword); code != http.StatusUnauthorized {
		t.Errorf("the old password after a reset: got %d, want 401", code)
	}
	if code := tn.loginCode(ca, "brand-new-pass-2"); code != http.StatusOK {
		t.Errorf("the new password: got %d, want 200", code)
	}
	if n := tn.auditCount(t, `action = 'user.reset-password' AND detail::text LIKE '%brand-new%'`); n != 0 {
		t.Error("the new password must never be written to the audit log")
	}
	// ... staff have none to reset, and none is invented.
	rec = tn.send(t, "PUT", userPath, tn.platform, map[string]any{"password": "brand-new-pass-2"})
	if rec.Code != http.StatusConflict || !strings.Contains(rec.Body.String(), `"code":"no_password_for_role"`) {
		t.Errorf("resetting staff's password: got %d %s, want 409 no_password_for_role", rec.Code, rec.Body.String())
	}
	var stillNull bool
	tn.env.pool.QueryRow(context.Background(), `SELECT password_hash IS NULL FROM users WHERE id = $1`, cu.ID).Scan(&stillNull)
	if !stillNull {
		t.Error("the refused reset gave staff a password")
	}
	if code := tn.loginCode(cu, "brand-new-pass-2"); code == http.StatusOK {
		t.Error("staff logged in with a password after a refused reset")
	}

	// Unknown id, as the platform admin, is a plain 404.
	if rec := tn.send(t, "PUT", "/api/users/999999999", tn.platform, map[string]any{"disabled": true}); rec.Code != http.StatusNotFound {
		t.Errorf("platform admin updating an unknown id: got %d, want 404", rec.Code)
	}

	// A platform admin cannot lock themselves out.
	if rec := tn.send(t, "PUT", fmt.Sprintf("/api/users/%d", tn.platformID), tn.platform, map[string]any{"disabled": true}); rec.Code != http.StatusBadRequest {
		t.Errorf("self-disable: got %d, want 400", rec.Code)
	}

	// Validation.
	if rec := tn.send(t, "PUT", caPath, tn.platform, map[string]any{}); rec.Code != http.StatusBadRequest {
		t.Errorf("empty update: got %d, want 400", rec.Code)
	}
	if rec := tn.send(t, "PUT", caPath, tn.platform, map[string]any{"password": "short"}); rec.Code != http.StatusBadRequest {
		t.Errorf("short password: got %d, want 400", rec.Code)
	}

	// A disabled platform admin cannot administer with a token they already hold.
	boss, _ := tn.createUser(t, tn.platform, control.RolePlatformAdmin, nil)
	bossToken := tn.env.login(boss.loginName(), testPassword)
	if _, rec := tn.createUser(t, bossToken, control.RoleCustomerUser, ptr(tn.custA)); rec.Code != http.StatusCreated {
		t.Fatalf("precondition: an active platform admin can create users, got %d", rec.Code)
	}
	tn.send(t, "PUT", fmt.Sprintf("/api/users/%d", boss.ID), tn.platform, map[string]any{"disabled": true})
	if _, rec := tn.createUser(t, bossToken, control.RoleCustomerUser, ptr(tn.custA)); rec.Code != http.StatusForbidden {
		t.Errorf("a disabled admin creating a user: got %d, want 403", rec.Code)
	}

	// The customer admin can see what was done to their customer's users.
	var sawDisable bool
	for _, e := range auditEntries(t, tn.get(tn.adminA, "/api/audit")) {
		sawDisable = sawDisable || e.Action == "user.disable"
	}
	if !sawDisable {
		t.Error("user.disable is missing from the customer admin's audit trail")
	}
}

func TestAdmin_MoveUser(t *testing.T) {
	tn := newTenancy(t)
	movePath := func(id int64) string { return fmt.Sprintf("/api/users/%d/customer", id) }
	move := func(token string, id int64, body any) *httptest.ResponseRecorder {
		return tn.send(t, "PUT", movePath(id), token, body)
	}
	bindings := func(userID int64) int {
		var n int
		tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM user_devices WHERE user_id = $1`, userID).Scan(&n)
		return n
	}

	for who, token := range map[string]string{"customer admin": tn.adminA, "customer user": tn.userA} {
		if rec := move(token, tn.userAID, map[string]int64{"customerId": tn.custB}); rec.Code != http.StatusForbidden {
			t.Errorf("%s moving a user: got %d, want 403", who, rec.Code)
		}
	}
	if rec := move(tn.platform, tn.platformID, map[string]int64{"customerId": tn.custB}); rec.Code != http.StatusBadRequest {
		t.Errorf("moving a platform admin: got %d, want 400", rec.Code)
	}
	if rec := move(tn.platform, tn.userAID, map[string]int64{"customerId": 999999999}); rec.Code != http.StatusBadRequest {
		t.Errorf("moving to an unknown customer: got %d, want 400", rec.Code)
	}
	if rec := move(tn.platform, 999999999, map[string]int64{"customerId": tn.custB}); rec.Code != http.StatusNotFound {
		t.Errorf("moving an unknown user: got %d, want 404", rec.Code)
	}
	if rec := move(tn.platform, tn.userAID, map[string]int64{}); rec.Code != http.StatusBadRequest {
		t.Errorf("missing customerId: got %d, want 400", rec.Code)
	}

	// Moving to the customer they are already in is a no-op that keeps their bindings.
	if rec := move(tn.platform, tn.userBID, map[string]int64{"customerId": tn.custB}); rec.Code != http.StatusOK || bindings(tn.userBID) != 1 {
		t.Errorf("no-op move: %d, bindings=%d (want 200 and 1)", rec.Code, bindings(tn.userBID))
	}

	if bindings(tn.userAID) != 1 {
		t.Fatal("precondition: userA is bound to a device")
	}
	rec := move(tn.platform, tn.userAID, map[string]int64{"customerId": tn.custB})
	if rec.Code != http.StatusOK {
		t.Fatalf("move: %d %s", rec.Code, rec.Body.String())
	}
	if u := decodeAs[adminUser](t, rec); u.CustomerID == nil || *u.CustomerID != tn.custB {
		t.Errorf("moved user: %+v", u)
	}
	if n := bindings(tn.userAID); n != 0 {
		t.Errorf("the move left %d binding(s) behind", n)
	}
	// Their old token: no access to the old customer's device, and nothing carried over to B's.
	if rec := tn.get(tn.userA, "/api/devices/"+tn.devA1+"/commands"); rec.Code != http.StatusForbidden {
		t.Errorf("old customer's device after the move: got %d, want 403", rec.Code)
	}
	if rec := tn.get(tn.userA, "/api/devices/"+tn.devB1+"/commands"); rec.Code != http.StatusForbidden {
		t.Errorf("new customer's device without a binding: got %d, want 403", rec.Code)
	}

	// Both customers can see it happen.
	rows, err := tn.env.pool.Query(context.Background(),
		`SELECT customer_id, detail->>'direction' FROM audit_log WHERE action = 'user.move' AND (detail->>'userId')::bigint = $1`, tn.userAID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	got := map[string]int64{}
	for rows.Next() {
		var c int64
		var dir string
		if err := rows.Scan(&c, &dir); err != nil {
			t.Fatal(err)
		}
		got[dir] = c
	}
	if got["out"] != tn.custA || got["in"] != tn.custB {
		t.Errorf("move audit rows by direction: %v, want out=%d in=%d", got, tn.custA, tn.custB)
	}
}

// --- device assignment --------------------------------------------------------------------------

func TestAdmin_AssignDevice(t *testing.T) {
	tn := newTenancy(t)
	assign := func(token, device string, body any) *httptest.ResponseRecorder {
		return tn.send(t, "PUT", "/api/devices/"+device+"/customer", token, body)
	}

	for who, token := range map[string]string{"customer admin": tn.adminA, "customer user": tn.userA} {
		if rec := assign(token, tn.devA1, map[string]int64{"customerId": tn.custB}); rec.Code != http.StatusForbidden {
			t.Errorf("%s assigning a device: got %d, want 403", who, rec.Code)
		}
	}
	if rec := assign(tn.platform, tn.devGhost, map[string]int64{"customerId": tn.custB}); rec.Code != http.StatusNotFound {
		t.Errorf("assigning a device that does not exist: got %d, want 404", rec.Code)
	}
	if rec := assign(tn.platform, tn.devA1, map[string]int64{"customerId": 999999999}); rec.Code != http.StatusBadRequest {
		t.Errorf("assigning to an unknown customer: got %d, want 400", rec.Code)
	}
	if rec := assign(tn.platform, tn.devA1, map[string]int64{}); rec.Code != http.StatusBadRequest {
		t.Errorf("missing customerId: got %d, want 400", rec.Code)
	}
	if rec := tn.get(tn.userA, "/api/devices/"+tn.devA1+"/commands"); rec.Code != http.StatusOK {
		t.Fatalf("the refused attempts must not have changed anything: userA got %d", rec.Code)
	}

	// Moving a device between customers drops its bindings and hands access over.
	rec := assign(tn.platform, tn.devA1, map[string]int64{"customerId": tn.custB})
	if rec.Code != http.StatusOK {
		t.Fatalf("move: %d %s", rec.Code, rec.Body.String())
	}
	res := decodeAs[struct {
		Changed         bool  `json:"changed"`
		BindingsCleared int64 `json:"bindingsCleared"`
	}](t, rec)
	if !res.Changed || res.BindingsCleared != 1 {
		t.Errorf("move result: %+v, want changed with 1 binding cleared", res)
	}
	if rec := tn.get(tn.userA, "/api/devices/"+tn.devA1+"/commands"); rec.Code != http.StatusForbidden {
		t.Errorf("the previously bound user after the move: got %d, want 403", rec.Code)
	}
	if rec := tn.get(tn.adminA, "/api/devices/"+tn.devA1+"/commands"); rec.Code != http.StatusForbidden {
		t.Errorf("the old customer's admin after the move: got %d, want 403", rec.Code)
	}
	if rec := tn.get(tn.adminB, "/api/devices/"+tn.devA1+"/commands"); rec.Code != http.StatusOK {
		t.Errorf("the new customer's admin after the move: got %d, want 200", rec.Code)
	}

	// Doing it again changes nothing.
	again := decodeAs[struct {
		Changed bool `json:"changed"`
	}](t, assign(tn.platform, tn.devA1, map[string]int64{"customerId": tn.custB}))
	if again.Changed {
		t.Error("assigning a device to the customer it already belongs to must report changed=false")
	}

	// Both customers can see the move.
	if n := tn.auditCount(t, `action = 'device.assign' AND device_name = $1 AND customer_id = $2 AND detail->>'direction' = 'out'`, tn.devA1, tn.custA); n != 1 {
		t.Errorf("audit rows under the old customer: %d, want 1", n)
	}
	if n := tn.auditCount(t, `action = 'device.assign' AND device_name = $1 AND customer_id = $2 AND detail->>'direction' = 'in'`, tn.devA1, tn.custB); n != 1 {
		t.Errorf("audit rows under the new customer: %d, want 1", n)
	}

	// An unassigned device can be claimed; there is no "out" side to audit.
	if rec := tn.get(tn.adminA, "/api/devices/"+tn.devUnassigned+"/commands"); rec.Code != http.StatusForbidden {
		t.Fatalf("precondition: an unassigned device is nobody's: got %d", rec.Code)
	}
	if rec := assign(tn.platform, tn.devUnassigned, map[string]int64{"customerId": tn.custA}); rec.Code != http.StatusOK {
		t.Fatalf("claim: %d %s", rec.Code, rec.Body.String())
	}
	if rec := tn.get(tn.adminA, "/api/devices/"+tn.devUnassigned+"/commands"); rec.Code != http.StatusOK {
		t.Errorf("after the claim: got %d, want 200", rec.Code)
	}
	if n := tn.auditCount(t, `action = 'device.assign' AND device_name = $1`, tn.devUnassigned); n != 1 {
		t.Errorf("claiming an unassigned device should write exactly one audit row, got %d", n)
	}
}

func (tn *tenancy) commandCount(t *testing.T, token, device string) int {
	t.Helper()
	rec := tn.get(token, "/api/devices/"+device+"/commands")
	if rec.Code != http.StatusOK {
		t.Fatalf("list commands for %s: %d %s", device, rec.Code, rec.Body.String())
	}
	return len(decodeAs[struct {
		Commands []json.RawMessage `json:"commands"`
	}](t, rec).Commands)
}

// TestAdmin_HistoryStaysAfterReassignment ties the two phases together: a command issued while the
// device belonged to A is still A's history after the device is moved to B — in the audit trail and
// in the command listing, which carries the operator, the reason and the arguments.
func TestAdmin_HistoryStaysAfterReassignment(t *testing.T) {
	tn := newTenancy(t)

	if rec := tn.issue(t, tn.adminA, tn.devA1); rec.Code != http.StatusAccepted {
		t.Fatalf("issue: %d %s", rec.Code, rec.Body.String())
	}
	if n := tn.commandCount(t, tn.adminA, tn.devA1); n != 1 {
		t.Fatalf("precondition: customer A's admin should see its own command, got %d", n)
	}
	if rec := tn.send(t, "PUT", "/api/devices/"+tn.devA1+"/customer", tn.platform, map[string]int64{"customerId": tn.custB}); rec.Code != http.StatusOK {
		t.Fatalf("move: %d", rec.Code)
	}

	for _, c := range tn.commandCustomers(t, tn.devA1) {
		if c != tn.custA {
			t.Errorf("command customer = %d after the move, want it to stay %d", c, tn.custA)
		}
	}

	// The new owner must not read the previous owner's commands.
	if n := tn.commandCount(t, tn.adminB, tn.devA1); n != 0 {
		t.Errorf("customer B sees %d of customer A's commands for the device it just received", n)
	}
	// Platform staff still see everything.
	if n := tn.commandCount(t, tn.platform, tn.devA1); n != 1 {
		t.Errorf("platform admin sees %d commands, want 1", n)
	}
	// And the new owner's own commands show up for it, alongside nothing of the old owner's.
	if rec := tn.issue(t, tn.adminB, tn.devA1); rec.Code != http.StatusAccepted {
		t.Fatalf("customer B issuing on its new device: %d %s", rec.Code, rec.Body.String())
	}
	if n := tn.commandCount(t, tn.adminB, tn.devA1); n != 1 {
		t.Errorf("customer B sees %d commands, want only its own 1", n)
	}
	if n := tn.commandCount(t, tn.platform, tn.devA1); n != 2 {
		t.Errorf("platform admin sees %d commands, want 2", n)
	}

	// A's admin no longer reaches the device, but B's audit view of it excludes A's history.
	for _, e := range auditEntries(t, tn.get(tn.adminB, "/api/audit?device="+tn.devA1)) {
		if e.Action == "control.issue" && e.Actor != "" && strings.Contains(e.Actor, "adminA") {
			t.Errorf("customer B sees customer A's control history for the device it just received: %+v", e)
		}
	}
}

// TestAdmin_UpdateUserRechecksUnderLock reproduces the race deterministically. Our own transaction
// holds the target's row lock — as another administrator in the middle of disabling them would —
// while a platform admin's request, which already passed its early check, arrives. It must block on
// the lock and then act on the row as committed, not on what it read before queueing: here the other
// administrator disabled the user an hour ago, and the original timestamp has to survive. (Whether
// the *actor* is re-checked under lock is covered by TestAdmin_CreateUserRechecksTheCreator.)
func TestAdmin_UpdateUserRechecksUnderLock(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	cu, rec := tn.createUser(t, tn.platform, control.RoleCustomerUser, ptr(tn.custA))
	if rec.Code != http.StatusCreated {
		t.Fatalf("create: %d", rec.Code)
	}
	disabledAt := time.Now().Add(-time.Hour).Truncate(time.Second)

	rec, elapsed := holdLockThen(t, tn, `SELECT 1 FROM users WHERE id = $1 FOR UPDATE`, cu.ID,
		func() *httptest.ResponseRecorder {
			return tn.send(t, "PUT", fmt.Sprintf("/api/users/%d", cu.ID), tn.platform, map[string]any{"disabled": true})
		},
		func(tx pgx.Tx) {
			if _, err := tx.Exec(ctx, `UPDATE users SET disabled_at = $1 WHERE id = $2`, disabledAt, cu.ID); err != nil {
				t.Fatal(err)
			}
		})
	if elapsed < 400*time.Millisecond {
		t.Errorf("the request finished in %v: it did not wait for the lock", elapsed)
	}
	if rec.Code != http.StatusOK {
		t.Fatalf("disable: %d %s", rec.Code, rec.Body.String())
	}
	var got time.Time
	tn.env.pool.QueryRow(ctx, `SELECT disabled_at FROM users WHERE id = $1`, cu.ID).Scan(&got)
	if !got.Truncate(time.Second).Equal(disabledAt) {
		t.Errorf("disabled_at = %v, want the earlier timestamp %v kept", got, disabledAt)
	}
}

// holdLockThen runs request while the test's own transaction holds the given row lock, waits long
// enough for the request to pass its early checks and queue on that lock, applies change inside the
// same transaction, and commits — reproducing "something else commits while this request is in
// flight" deterministically. It returns the request's result and how long it took.
func holdLockThen(t *testing.T, tn *tenancy, lockSQL string, lockArg any, request func() *httptest.ResponseRecorder, change func(tx pgx.Tx)) (*httptest.ResponseRecorder, time.Duration) {
	t.Helper()
	ctx := context.Background()
	tx, err := tn.env.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, lockSQL, lockArg); err != nil {
		t.Fatal(err)
	}

	type result struct {
		rec     *httptest.ResponseRecorder
		elapsed time.Duration
	}
	done := make(chan result, 1)
	go func() {
		start := time.Now()
		r := request()
		done <- result{r, time.Since(start)}
	}()

	time.Sleep(500 * time.Millisecond)
	change(tx)
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case r := <-done:
		return r.rec, r.elapsed
	case <-time.After(10 * time.Second):
		t.Fatal("the request never returned")
		return nil, 0
	}
}

// TestAdmin_CreateUserRechecksTheCreator: bcrypt runs between the early check and the insert, and
// that is exactly when an administrator can be demoted or disabled. The creator is locked and
// reloaded inside the transaction and the decision is made again on that current row.
func TestAdmin_CreateUserRechecksTheCreator(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	create := func(token, phone string) func() *httptest.ResponseRecorder {
		return func() *httptest.ResponseRecorder {
			return tn.send(t, "POST", "/api/users", token, map[string]any{
				"phone": phone, "role": control.RoleCustomerUser, "customerId": tn.custA,
			})
		}
	}
	exists := func(phone string) bool {
		var n int
		tn.env.pool.QueryRow(ctx, `SELECT count(*) FROM users WHERE phone = $1`, phone).Scan(&n)
		return n > 0
	}

	// Demoted to customer admin while the request is in flight: nothing may be created.
	demotedToken, demotedID := tn.env.userIn(control.RolePlatformAdmin, 0)
	phone1 := testPhone()
	t.Cleanup(func() { tn.env.pool.Exec(ctx, `DELETE FROM users WHERE phone = $1`, phone1) })
	rec, elapsed := holdLockThen(t, tn, `SELECT 1 FROM users WHERE id = $1 FOR UPDATE`, demotedID, create(demotedToken, phone1),
		func(tx pgx.Tx) {
			if _, err := tx.Exec(ctx,
				`UPDATE users SET role = 'customer_admin', customer_id = $1, phone = username WHERE id = $2`,
				tn.custA, demotedID); err != nil {
				t.Fatal(err)
			}
		})
	if elapsed < 400*time.Millisecond {
		t.Errorf("the request finished in %v: it did not wait for the lock", elapsed)
	}
	if rec.Code != http.StatusForbidden || exists(phone1) {
		t.Errorf("an administrator demoted mid-request: got %d, account exists=%v; want 403 and no account", rec.Code, exists(phone1))
	}

	// Disabled while the request is in flight: nothing may be created either.
	disabledToken, disabledID := tn.env.userIn(control.RolePlatformAdmin, 0)
	phone2 := testPhone()
	t.Cleanup(func() { tn.env.pool.Exec(ctx, `DELETE FROM users WHERE phone = $1`, phone2) })
	rec, _ = holdLockThen(t, tn, `SELECT 1 FROM users WHERE id = $1 FOR UPDATE`, disabledID, create(disabledToken, phone2),
		func(tx pgx.Tx) {
			if _, err := tx.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, disabledID); err != nil {
				t.Fatal(err)
			}
		})
	if rec.Code != http.StatusForbidden || exists(phone2) {
		t.Errorf("an administrator disabled mid-request: got %d, account exists=%v; want 403 and no account", rec.Code, exists(phone2))
	}
}

// TestAdmin_ConcurrentMovesAreAuditedFromTheLockedRow: when the user was already moved to C by
// someone else, a move to B is C→B and the departure is filed under C — not under the A the request
// saw before it queued.
func TestAdmin_ConcurrentMovesAreAuditedFromTheLockedRow(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	custC := tn.env.newCustomer("c")
	cu, rec := tn.createUser(t, tn.platform, control.RoleCustomerUser, ptr(tn.custA))
	if rec.Code != http.StatusCreated {
		t.Fatalf("create: %d", rec.Code)
	}

	rec, _ = holdLockThen(t, tn, `SELECT 1 FROM users WHERE id = $1 FOR UPDATE`, cu.ID,
		func() *httptest.ResponseRecorder {
			return tn.send(t, "PUT", fmt.Sprintf("/api/users/%d/customer", cu.ID), tn.platform, map[string]int64{"customerId": tn.custB})
		},
		func(tx pgx.Tx) {
			if _, err := tx.Exec(ctx, `UPDATE users SET customer_id = $1 WHERE id = $2`, custC, cu.ID); err != nil {
				t.Fatal(err)
			}
		})
	if rec.Code != http.StatusOK {
		t.Fatalf("move: %d %s", rec.Code, rec.Body.String())
	}

	rows, err := tn.env.pool.Query(ctx,
		`SELECT customer_id, detail->>'direction', (detail->>'from')::bigint FROM audit_log
		 WHERE action = 'user.move' AND (detail->>'userId')::bigint = $1`, cu.ID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	type side struct{ customer, from int64 }
	got := map[string]side{}
	for rows.Next() {
		var dir string
		var sd side
		if err := rows.Scan(&sd.customer, &dir, &sd.from); err != nil {
			t.Fatal(err)
		}
		got[dir] = sd
	}
	if got["out"].customer != custC || got["out"].from != custC {
		t.Errorf("departure audit = %+v, want it filed under customer %d with from=%d (the user's customer when the lock was taken), not %d", got["out"], custC, custC, tn.custA)
	}
	if got["in"].customer != tn.custB {
		t.Errorf("arrival audit = %+v, want customer %d", got["in"], tn.custB)
	}
}
