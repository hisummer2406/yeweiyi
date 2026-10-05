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
	ID         int64  `json:"id"`
	Username   string `json:"username"`
	Role       string `json:"role"`
	CustomerID *int64 `json:"customerId"`
	Disabled   bool   `json:"disabled"`
}

func uniqueName(label string) string { return fmt.Sprintf("adm-%s-%d", label, time.Now().UnixNano()) }

const testPassword = "correct-horse-1"

// createUser creates a user through the API as token and registers cleanup.
func (tn *tenancy) createUser(t *testing.T, token, role string, customerID *int64) (adminUser, *httptest.ResponseRecorder) {
	t.Helper()
	body := map[string]any{"username": uniqueName("u"), "password": testPassword, "role": role}
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

func (tn *tenancy) loginCode(username, password string) int {
	body, _ := json.Marshal(map[string]string{"username": username, "password": password})
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
	if code := tn.loginCode(ca.Username, testPassword); code != http.StatusOK {
		t.Errorf("the new customer admin cannot log in: %d", code)
	}

	// A customer admin creates customer users in their own customer — with or without naming it.
	cu, rec := tn.createUser(t, tn.adminA, control.RoleCustomerUser, nil)
	if rec.Code != http.StatusCreated || cu.CustomerID == nil || *cu.CustomerID != tn.custA {
		t.Fatalf("customer admin creating a customer user: %d %s", rec.Code, rec.Body.String())
	}
	if _, rec := tn.createUser(t, tn.adminA, control.RoleCustomerUser, ptr(tn.custA)); rec.Code != http.StatusCreated {
		t.Errorf("naming their own customer explicitly should work: %d", rec.Code)
	}

	// ... but never a role above their own, and never in someone else's customer.
	for name, tc := range map[string]struct {
		token string
		role  string
		cust  *int64
	}{
		"customer admin creating a customer admin":    {tn.adminA, control.RoleCustomerAdmin, nil},
		"customer admin creating a platform admin":    {tn.adminA, control.RolePlatformAdmin, nil},
		"customer admin creating in another customer": {tn.adminA, control.RoleCustomerUser, ptr(tn.custB)},
		"customer user creating anyone":               {tn.userA, control.RoleCustomerUser, nil},
	} {
		if _, rec := tn.createUser(t, tc.token, tc.role, tc.cust); rec.Code != http.StatusForbidden {
			t.Errorf("%s: got %d, want 403 (%s)", name, rec.Code, rec.Body.String())
		}
	}

	// Validation.
	post := func(body map[string]any) int { return tn.send(t, "POST", "/api/users", tn.platform, body).Code }
	good := func() map[string]any {
		return map[string]any{"username": uniqueName("v"), "password": testPassword, "role": control.RoleCustomerUser, "customerId": tn.custA}
	}
	for name, mutate := range map[string]func(map[string]any){
		"short password":                   func(b map[string]any) { b["password"] = "short" },
		"password over bcrypt's 72 bytes":  func(b map[string]any) { b["password"] = strings.Repeat("x", 73) },
		"unknown role":                     func(b map[string]any) { b["role"] = "root" },
		"blank username":                   func(b map[string]any) { b["username"] = "  " },
		"customer role without a customer": func(b map[string]any) { delete(b, "customerId") },
		"unknown customer":                 func(b map[string]any) { b["customerId"] = 999999999 },
		"platform admin with a customer":   func(b map[string]any) { b["role"] = control.RolePlatformAdmin },
	} {
		b := good()
		mutate(b)
		if code := post(b); code != http.StatusBadRequest {
			t.Errorf("%s: got %d, want 400", name, code)
		}
	}
	dup := good()
	dup["username"] = cu.Username
	if code := post(dup); code != http.StatusConflict {
		t.Errorf("duplicate username: got %d, want 409", code)
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
	mine := ids(tn.send(t, "GET", "/api/users", tn.adminA, nil))
	if !mine[cu.ID] || !mine[tn.userAID] || mine[tn.userBID] || mine[tn.adminBID] || mine[tn.platformID] {
		t.Errorf("customer admin A's user list is wrong: %v", mine)
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

	// The creation is audited under the new user's customer.
	if n := tn.auditCount(t, `action = 'user.create' AND outcome = 'allowed' AND customer_id = $1`, tn.custA); n < 3 {
		t.Errorf("expected the creations to be audited under customer A, got %d", n)
	}
}

func TestAdmin_DisableAndResetPassword(t *testing.T) {
	tn := newTenancy(t)

	cu, rec := tn.createUser(t, tn.adminA, control.RoleCustomerUser, nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("create: %d", rec.Code)
	}
	tn.bind(t, cu.ID, tn.devA2)
	token := tn.env.login(cu.Username, testPassword)
	path := "/api/devices/" + tn.devA2 + "/commands"
	if rec := tn.get(token, path); rec.Code != http.StatusOK {
		t.Fatalf("precondition: the bound user should have access, got %d", rec.Code)
	}
	userPath := fmt.Sprintf("/api/users/%d", cu.ID)

	// Disabling takes effect immediately, even for a token issued before it.
	if rec := tn.send(t, "PUT", userPath, tn.adminA, map[string]any{"disabled": true}); rec.Code != http.StatusOK || !decodeAs[adminUser](t, rec).Disabled {
		t.Fatalf("disable: %d %s", rec.Code, rec.Body.String())
	}
	if rec := tn.get(token, path); rec.Code != http.StatusForbidden {
		t.Errorf("a disabled user's existing token must be refused at once: got %d", rec.Code)
	}
	if code := tn.loginCode(cu.Username, testPassword); code != http.StatusUnauthorized {
		t.Errorf("a disabled user logging in: got %d, want 401", code)
	}
	if code := tn.loginCode(cu.Username, "wrong-password"); code != http.StatusUnauthorized {
		t.Errorf("wrong password: got %d, want 401", code)
	}
	if rec := tn.send(t, "GET", "/api/audit?device="+tn.devA2, token, nil); rec.Code != http.StatusForbidden {
		t.Errorf("a disabled user reading the audit log: got %d, want 403", rec.Code)
	}

	// Re-enabling restores access.
	if rec := tn.send(t, "PUT", userPath, tn.adminA, map[string]any{"disabled": false}); rec.Code != http.StatusOK {
		t.Fatalf("enable: %d", rec.Code)
	}
	if rec := tn.get(token, path); rec.Code != http.StatusOK {
		t.Errorf("after re-enabling: got %d, want 200", rec.Code)
	}

	// Password reset: the old password stops working, the new one works.
	if rec := tn.send(t, "PUT", userPath, tn.adminA, map[string]any{"password": "brand-new-pass-2"}); rec.Code != http.StatusOK {
		t.Fatalf("reset: %d %s", rec.Code, rec.Body.String())
	}
	if code := tn.loginCode(cu.Username, testPassword); code != http.StatusUnauthorized {
		t.Errorf("the old password after a reset: got %d, want 401", code)
	}
	if code := tn.loginCode(cu.Username, "brand-new-pass-2"); code != http.StatusOK {
		t.Errorf("the new password: got %d, want 200", code)
	}
	if n := tn.auditCount(t, `action = 'user.reset-password' AND detail::text LIKE '%brand-new%'`); n != 0 {
		t.Error("the new password must never be written to the audit log")
	}

	// Reach: another customer's user and a nonexistent id are indistinguishable.
	other := tn.send(t, "PUT", fmt.Sprintf("/api/users/%d", tn.userBID), tn.adminA, map[string]any{"disabled": true})
	ghost := tn.send(t, "PUT", "/api/users/999999999", tn.adminA, map[string]any{"disabled": true})
	if other.Code != http.StatusNotFound || ghost.Code != http.StatusNotFound || other.Body.String() != ghost.Body.String() {
		t.Errorf("other customer's user: %d %s; unknown id: %d %s — want identical 404s", other.Code, other.Body.String(), ghost.Code, ghost.Body.String())
	}
	if rec := tn.get(tn.userB, "/api/devices/"+tn.devB1+"/commands"); rec.Code != http.StatusOK {
		t.Errorf("the refused request must not have disabled customer B's user: got %d", rec.Code)
	}

	// A customer admin manages customer users only — not peers, not themselves.
	peer, _ := tn.createUser(t, tn.platform, control.RoleCustomerAdmin, ptr(tn.custA))
	if rec := tn.send(t, "PUT", fmt.Sprintf("/api/users/%d", peer.ID), tn.adminA, map[string]any{"disabled": true}); rec.Code != http.StatusForbidden {
		t.Errorf("customer admin disabling a peer admin: got %d, want 403", rec.Code)
	}
	if rec := tn.send(t, "PUT", userPath, tn.userA, map[string]any{"disabled": true}); rec.Code != http.StatusForbidden {
		t.Errorf("customer user managing users: got %d, want 403", rec.Code)
	}

	// A platform admin cannot lock themselves out.
	if rec := tn.send(t, "PUT", fmt.Sprintf("/api/users/%d", tn.platformID), tn.platform, map[string]any{"disabled": true}); rec.Code != http.StatusBadRequest {
		t.Errorf("self-disable: got %d, want 400", rec.Code)
	}

	// Validation.
	if rec := tn.send(t, "PUT", userPath, tn.adminA, map[string]any{}); rec.Code != http.StatusBadRequest {
		t.Errorf("empty update: got %d, want 400", rec.Code)
	}
	if rec := tn.send(t, "PUT", userPath, tn.adminA, map[string]any{"password": "short"}); rec.Code != http.StatusBadRequest {
		t.Errorf("short password: got %d, want 400", rec.Code)
	}

	// A disabled administrator cannot administer with a token they already hold.
	boss, _ := tn.createUser(t, tn.platform, control.RoleCustomerAdmin, ptr(tn.custA))
	bossToken := tn.env.login(boss.Username, testPassword)
	if _, rec := tn.createUser(t, bossToken, control.RoleCustomerUser, nil); rec.Code != http.StatusCreated {
		t.Fatalf("precondition: an active admin can create users, got %d", rec.Code)
	}
	tn.send(t, "PUT", fmt.Sprintf("/api/users/%d", boss.ID), tn.platform, map[string]any{"disabled": true})
	if _, rec := tn.createUser(t, bossToken, control.RoleCustomerUser, nil); rec.Code != http.StatusForbidden {
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
