package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"golang.org/x/crypto/bcrypt"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
)

// tenancy is a two-customer world used by every test in this file:
//
//	customer A: devA1 (userA is bound to it), devA2 (bound to nobody)
//	customer B: devB1 (userB is bound to it)
//	devUnassigned: registered but owned by no customer
//	devGhost: does not exist at all
//
// adminA/adminB are customer_admins of their customers, platform is a platform_admin.
type tenancy struct {
	env    *testEnv
	sender *stubSender

	custA, custB int64

	devA1, devA2, devB1, devUnassigned, devGhost string

	platform, adminA, userA, adminB, userB string
	platformID, adminAID, adminBID         int64
	userAID, userBID                       int64
}

func newTenancy(t *testing.T) *tenancy {
	t.Helper()
	env, sender := newControlEnv(t, control.Config{})
	suffix := fmt.Sprintf("%d", time.Now().UnixNano())

	tn := &tenancy{
		env:           env,
		sender:        sender,
		devA1:         "tn-a1-" + suffix,
		devA2:         "tn-a2-" + suffix,
		devB1:         "tn-b1-" + suffix,
		devUnassigned: "tn-un-" + suffix,
		devGhost:      "tn-ghost-" + suffix,
	}
	tn.custA = env.newCustomer("a")
	tn.custB = env.newCustomer("b")

	env.seedDeviceIn(tn.devA1, tn.custA)
	env.seedDeviceIn(tn.devA2, tn.custA)
	env.seedDeviceIn(tn.devB1, tn.custB)
	env.seedDeviceIn(tn.devUnassigned, 0)

	tn.platform, tn.platformID = env.userIn(control.RolePlatformAdmin, 0)
	tn.adminA, tn.adminAID = env.userIn(control.RoleCustomerAdmin, tn.custA)
	tn.adminB, tn.adminBID = env.userIn(control.RoleCustomerAdmin, tn.custB)
	tn.userA, tn.userAID = env.userIn(control.RoleCustomerUser, tn.custA)
	tn.userB, tn.userBID = env.userIn(control.RoleCustomerUser, tn.custB)

	tn.bind(t, tn.userAID, tn.devA1)
	tn.bind(t, tn.userBID, tn.devB1)
	return tn
}

func (tn *tenancy) bind(t *testing.T, userID int64, device string) {
	t.Helper()
	if _, err := tn.env.pool.Exec(context.Background(),
		`INSERT INTO user_devices (user_id, device_name) VALUES ($1, $2)`, userID, device); err != nil {
		t.Fatalf("bind %d to %s: %v", userID, device, err)
	}
}

func (tn *tenancy) get(token, path string) *httptest.ResponseRecorder {
	return tn.env.do("GET", path, []byte(token), nil)
}

// TestTenancy_AccessMatrix pins the whole device-access rule: platform admin → anything; customer
// admin → every device of their customer, bound or not; customer user → bound devices of their
// customer. Nothing crosses a customer boundary, and an unassigned device is nobody's but an admin's.
func TestTenancy_AccessMatrix(t *testing.T) {
	tn := newTenancy(t)

	type who struct {
		name  string
		token string
	}
	allowed := map[string][]string{
		"platform": {tn.devA1, tn.devA2, tn.devB1, tn.devUnassigned, tn.devGhost},
		"adminA":   {tn.devA1, tn.devA2},
		"userA":    {tn.devA1},
		"adminB":   {tn.devB1},
		"userB":    {tn.devB1},
	}
	tokens := map[string]string{
		"platform": tn.platform, "adminA": tn.adminA, "userA": tn.userA, "adminB": tn.adminB, "userB": tn.userB,
	}
	devices := []string{tn.devA1, tn.devA2, tn.devB1, tn.devUnassigned, tn.devGhost}

	for name, token := range tokens {
		ok := map[string]bool{}
		for _, d := range allowed[name] {
			ok[d] = true
		}
		for _, d := range devices {
			rec := tn.get(token, "/api/devices/"+d+"/commands")
			want := http.StatusForbidden
			if ok[d] {
				want = http.StatusOK
			}
			if rec.Code != want {
				t.Errorf("%s → %s: got %d, want %d (%s)", name, d, rec.Code, want, rec.Body.String())
			}
		}
	}
}

// TestTenancy_DenialsAreIndistinguishable: a caller must not be able to tell "no such device",
// "someone else's device", "yours-by-customer but not bound to you" and "unassigned" apart — any
// difference is a device-name oracle (CLAUDE.md invariant 6).
func TestTenancy_DenialsAreIndistinguishable(t *testing.T) {
	tn := newTenancy(t)

	for name, tc := range map[string]struct {
		token   string
		denied  []string
		ownerOK string
	}{
		"customer user A":  {tn.userA, []string{tn.devGhost, tn.devB1, tn.devA2, tn.devUnassigned}, tn.devA1},
		"customer admin A": {tn.adminA, []string{tn.devGhost, tn.devB1, tn.devUnassigned}, tn.devA1},
	} {
		var first string
		for i, d := range tc.denied {
			rec := tn.get(tc.token, "/api/devices/"+d+"/commands")
			if rec.Code != http.StatusForbidden {
				t.Fatalf("%s → %s: got %d, want 403", name, d, rec.Code)
			}
			if i == 0 {
				first = rec.Body.String()
			} else if rec.Body.String() != first {
				t.Errorf("%s: denial for %s differs from the first denial:\n  %s\n  %s", name, d, rec.Body.String(), first)
			}
		}
	}
}

func listedDevices(t *testing.T, rec *httptest.ResponseRecorder) []string {
	t.Helper()
	if rec.Code != http.StatusOK {
		t.Fatalf("list devices: %d %s", rec.Code, rec.Body.String())
	}
	var body struct {
		Rows []struct {
			DeviceName string `json:"device_name"`
		} `json:"rows"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	out := []string{}
	for _, r := range body.Rows {
		out = append(out, r.DeviceName)
	}
	sort.Strings(out)
	return out
}

func TestTenancy_ListDevicesByRole(t *testing.T) {
	tn := newTenancy(t)

	same := func(got, want []string) bool {
		sort.Strings(want)
		return strings.Join(got, ",") == strings.Join(want, ",")
	}

	if got := listedDevices(t, tn.get(tn.adminA, "/api/devices")); !same(got, []string{tn.devA1, tn.devA2}) {
		t.Errorf("customer admin A: %v", got)
	}
	if got := listedDevices(t, tn.get(tn.userA, "/api/devices")); !same(got, []string{tn.devA1}) {
		t.Errorf("customer user A: %v", got)
	}
	if got := listedDevices(t, tn.get(tn.userB, "/api/devices")); !same(got, []string{tn.devB1}) {
		t.Errorf("customer user B: %v", got)
	}

	all := listedDevices(t, tn.get(tn.platform, "/api/devices"))
	for _, d := range []string{tn.devA1, tn.devA2, tn.devB1, tn.devUnassigned} {
		if !contains(all, d) {
			t.Errorf("platform admin should see %s, got %v", d, all)
		}
	}
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

// TestTenancy_ListDevicesDoesNotLeakCustomerID: the REST row shape is a cross-repo contract, so a new
// table column must not appear in it by accident.
func TestTenancy_ListDevicesDoesNotLeakCustomerID(t *testing.T) {
	tn := newTenancy(t)
	rec := tn.get(tn.adminA, "/api/devices")
	if strings.Contains(rec.Body.String(), "customer") {
		t.Errorf("response exposes a customer field: %s", rec.Body.String())
	}
}

// TestTenancy_MovedUserLosesAccessImmediately: the tenant boundary is read from the database, not
// from the token, so an already-issued token stops working on the old customer's devices the moment
// the user is moved.
func TestTenancy_MovedUserLosesAccessImmediately(t *testing.T) {
	tn := newTenancy(t)

	if rec := tn.get(tn.userA, "/api/devices/"+tn.devA1+"/commands"); rec.Code != http.StatusOK {
		t.Fatalf("precondition: %d", rec.Code)
	}
	if _, err := tn.env.pool.Exec(context.Background(),
		`UPDATE users SET customer_id = $1 WHERE id = $2`, tn.custB, tn.userAID); err != nil {
		t.Fatalf("move user: %v", err)
	}
	if rec := tn.get(tn.userA, "/api/devices/"+tn.devA1+"/commands"); rec.Code != http.StatusForbidden {
		t.Errorf("after the move the old token must be refused on the old customer's device: got %d", rec.Code)
	}
}

// --- route walk -------------------------------------------------------------------------------

type routeCase struct {
	// build returns the request path (and optional body) targeting device with token.
	build func(device, token string) (path string, body []byte)
	// adminOnly marks a route a customer admin is refused regardless of customer, so the "own
	// customer is allowed" half of the check does not apply to it.
	adminOnly bool
}

// exemptRoutes are the routes that take no device and therefore have no cross-customer case here.
// Each needs a reason: a route that is merely forgotten must fail the test, not slip through.
var exemptRoutes = map[string]string{
	"GET /healthz":             "no device",
	"POST /api/login":          "no device",
	"GET /api/devices":         "scoped to the caller; see TestTenancy_ListDevicesByRole",
	"POST /api/devices/bind":   "device comes from the body and is checked against the pairing secret; see TestTenancy_Bind*",
	"POST /api/devices/unbind": "deletes only the caller's own binding row",

	"POST /api/customers":         "no device; role-gated to platform admin, see TestAdmin_Customers",
	"GET /api/customers":          "no device; role-gated to platform admin, see TestAdmin_Customers",
	"PUT /api/customers/:id":      "no device; role-gated to platform admin, see TestAdmin_Customers",
	"DELETE /api/customers/:id":   "no device; role-gated to platform admin, see TestAdmin_Customers",
	"POST /api/users":             "no device; scoped to the caller's customer, see TestAdmin_UserLifecycle",
	"GET /api/users":              "no device; scoped to the caller's customer, see TestAdmin_UserLifecycle",
	"PUT /api/users/:id":          "no device; target must be in the caller's customer, see TestAdmin_DisableAndResetPassword",
	"PUT /api/users/:id/customer": "no device; role-gated to platform admin, see TestAdmin_MoveUser",
}

func deviceScopedRoutes(t *testing.T) map[string]routeCase {
	cmdBody := jsonBody(t, map[string]any{"method": protocol.MethodAlarmMute, "reason": "tenancy walk"})
	query := func(prefix string) func(string, string) (string, []byte) {
		return func(device, _ string) (string, []byte) { return prefix + device, nil }
	}
	path := func(suffix string) func(string, string) (string, []byte) {
		return func(device, _ string) (string, []byte) { return "/api/devices/" + device + suffix, nil }
	}
	return map[string]routeCase{
		"GET /api/alarms":                           {build: query("/api/alarms?device=")},
		"GET /api/oil-in-out":                       {build: query("/api/oil-in-out?device=")},
		"GET /api/audit":                            {build: query("/api/audit?device=")},
		"GET /api/devices/:device/telemetry/latest": {build: path("/telemetry/latest")},
		"GET /api/devices/:device/commands":         {build: path("/commands")},
		"POST /api/devices/:device/commands": {build: func(device, _ string) (string, []byte) {
			return "/api/devices/" + device + "/commands", cmdBody
		}},
		"POST /api/devices/:device/pairing-secret": {
			adminOnly: true,
			build: func(device, _ string) (string, []byte) {
				return "/api/devices/" + device + "/pairing-secret", []byte(`{"customerId":1}`)
			},
		},
		"PUT /api/devices/:device/customer": {
			adminOnly: true,
			build: func(device, _ string) (string, []byte) {
				return "/api/devices/" + device + "/customer", []byte(`{"customerId":1}`)
			},
		},
		"GET /ws/alarms": {build: func(device, token string) (string, []byte) {
			return "/ws/alarms?device=" + device + "&token=" + token, nil
		}},
	}
}

// TestTenancy_EveryDeviceScopedRouteDeniesCrossCustomer walks the router's route table. Every route
// must be classified: either it is device-scoped and gets a cross-customer denial check here, or it
// is listed in exemptRoutes with a reason. Adding a route and forgetting the tenancy check therefore
// fails the build instead of shipping a hole — and the "own customer is allowed" half proves each
// request really reaches its handler, so a 403 here cannot be an accident of a malformed request.
func TestTenancy_EveryDeviceScopedRouteDeniesCrossCustomer(t *testing.T) {
	tn := newTenancy(t)
	engine, ok := tn.env.router.(*gin.Engine)
	if !ok {
		t.Fatalf("router is %T, want *gin.Engine", tn.env.router)
	}

	scoped := deviceScopedRoutes(t)
	seen := map[string]bool{}

	for _, r := range engine.Routes() {
		key := r.Method + " " + r.Path
		seen[key] = true

		if _, exempt := exemptRoutes[key]; exempt {
			continue
		}
		rc, ok := scoped[key]
		if !ok {
			t.Errorf("route %q is not classified: add it to deviceScopedRoutes with a cross-customer case, or to exemptRoutes with a reason", key)
			continue
		}

		t.Run(key, func(t *testing.T) {
			do := func(token, device string) int {
				path, body := rc.build(device, token)
				return tn.env.do(r.Method, path, []byte(token), body).Code
			}

			// Customer B (user and admin) against customer A's device: refused.
			for name, token := range map[string]string{"userB": tn.userB, "adminB": tn.adminB} {
				if code := do(token, tn.devA1); code != http.StatusForbidden {
					t.Errorf("%s on customer A's device: got %d, want 403", name, code)
				}
			}
			// A customer user bound to nothing of A's second device: refused too.
			if code := do(tn.userA, tn.devA2); code != http.StatusForbidden {
				t.Errorf("unbound userA: got %d, want 403", code)
			}
			if rc.adminOnly {
				return
			}
			// The legitimate caller must get past authorization, or the checks above prove nothing.
			for name, token := range map[string]string{"userA": tn.userA, "adminA": tn.adminA} {
				if code := do(token, tn.devA1); code == http.StatusForbidden || code == http.StatusUnauthorized {
					t.Errorf("%s on their own device was refused (%d): the request never reached its handler", name, code)
				}
			}
		})
	}

	for key := range scoped {
		if !seen[key] {
			t.Errorf("deviceScopedRoutes lists %q but the router has no such route — remove the stale entry", key)
		}
	}
	for key := range exemptRoutes {
		if !seen[key] {
			t.Errorf("exemptRoutes lists %q but the router has no such route — remove the stale entry", key)
		}
	}
}

// --- websocket --------------------------------------------------------------------------------

func dialAlarmsWSAs(t *testing.T, tn *tenancy, device, token string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	srv := httptest.NewServer(tn.env.router)
	t.Cleanup(srv.Close)
	return websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/ws/alarms?device="+device+"&token="+token, nil)
}

func TestTenancy_WSAlarms(t *testing.T) {
	tn := newTenancy(t)

	// A customer admin may subscribe to an unbound device of their own customer ...
	conn, resp, err := dialAlarmsWSAs(t, tn, tn.devA2, tn.adminA)
	if err != nil {
		t.Fatalf("customer admin on their customer's unbound device: %v (resp %v)", err, resp)
	}
	conn.Close()

	// ... but not another customer's, and not an unassigned device.
	for _, d := range []string{tn.devB1, tn.devUnassigned} {
		_, resp, err := dialAlarmsWSAs(t, tn, d, tn.adminA)
		if err == nil || resp == nil || resp.StatusCode != http.StatusForbidden {
			t.Errorf("customer admin A subscribing to %s: err=%v resp=%v, want a 403 handshake rejection", d, err, resp)
		}
	}
}

// --- pairing ----------------------------------------------------------------------------------

func (tn *tenancy) mint(token, device string, body string) *httptest.ResponseRecorder {
	return tn.env.do("POST", "/api/devices/"+device+"/pairing-secret", []byte(token), []byte(body))
}

func (tn *tenancy) secretFrom(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	if rec.Code != http.StatusOK {
		t.Fatalf("mint: %d %s", rec.Code, rec.Body.String())
	}
	var r struct {
		PairingSecret string `json:"pairingSecret"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &r); err != nil || r.PairingSecret == "" {
		t.Fatalf("mint response: %s (%v)", rec.Body.String(), err)
	}
	return r.PairingSecret
}

func TestTenancy_MintPairingSecret(t *testing.T) {
	tn := newTenancy(t)
	custJSON := func(id int64) string { return fmt.Sprintf(`{"customerId":%d}`, id) }

	if rec := tn.mint(tn.platform, "tn-new-device-"+tn.devA1, ""); rec.Code != http.StatusBadRequest {
		t.Errorf("minting without customerId: got %d, want 400", rec.Code)
	}
	if rec := tn.mint(tn.platform, tn.devA1, custJSON(999999999)); rec.Code != http.StatusBadRequest {
		t.Errorf("minting for an unknown customer: got %d, want 400", rec.Code)
	}

	// Another customer's device is never silently moved by a mint.
	if rec := tn.mint(tn.platform, tn.devA1, custJSON(tn.custB)); rec.Code != http.StatusConflict {
		t.Errorf("minting device A1 for customer B: got %d, want 409", rec.Code)
	}
	var owner int64
	if err := tn.env.pool.QueryRow(context.Background(),
		`SELECT customer_id FROM devices WHERE device_name = $1`, tn.devA1).Scan(&owner); err != nil || owner != tn.custA {
		t.Errorf("device A1 customer = %d (%v), want %d", owner, err, tn.custA)
	}

	// Re-minting for its own customer is the normal rotation.
	tn.secretFrom(t, tn.mint(tn.platform, tn.devA1, custJSON(tn.custA)))

	// An unassigned device is claimed by the customer it is minted for.
	tn.secretFrom(t, tn.mint(tn.platform, tn.devUnassigned, custJSON(tn.custA)))
	if err := tn.env.pool.QueryRow(context.Background(),
		`SELECT customer_id FROM devices WHERE device_name = $1`, tn.devUnassigned).Scan(&owner); err != nil || owner != tn.custA {
		t.Errorf("claimed device customer = %d (%v), want %d", owner, err, tn.custA)
	}

	// A brand-new device name is created under the given customer.
	fresh := "tn-fresh-" + tn.devA1
	t.Cleanup(func() { tn.env.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, fresh) })
	tn.secretFrom(t, tn.mint(tn.platform, fresh, custJSON(tn.custB)))
	if err := tn.env.pool.QueryRow(context.Background(),
		`SELECT customer_id FROM devices WHERE device_name = $1`, fresh).Scan(&owner); err != nil || owner != tn.custB {
		t.Errorf("new device customer = %d (%v), want %d", owner, err, tn.custB)
	}
}

func (tn *tenancy) bindAs(token, device, secret string) *httptest.ResponseRecorder {
	body, _ := json.Marshal(map[string]string{"deviceName": device, "pairingToken": secret})
	return tn.env.do("POST", "/api/devices/bind", []byte(token), body)
}

// TestTenancy_BindChecksTheCustomer: a valid QR code is proof of possession, not of being the right
// customer. A foreign customer's code, an unassigned device and a wrong secret must be answered
// identically, or bind becomes a way to learn which devices exist and who owns them.
func TestTenancy_BindChecksTheCustomer(t *testing.T) {
	tn := newTenancy(t)
	secretA2 := tn.secretFrom(t, tn.mint(tn.platform, tn.devA2, fmt.Sprintf(`{"customerId":%d}`, tn.custA)))

	// A secret for a device that is unassigned (claiming it via mint would assign it, so write the
	// secret row directly).
	hash, err := bcrypt.GenerateFromPassword([]byte("unassigned-secret"), bcrypt.MinCost)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tn.env.pool.Exec(context.Background(),
		`INSERT INTO device_pairing_secrets (device_name, secret_hash) VALUES ($1, $2)`, tn.devUnassigned, string(hash)); err != nil {
		t.Fatalf("seed secret: %v", err)
	}

	wrong := tn.bindAs(tn.userB, tn.devA2, "not-the-secret")
	if wrong.Code != http.StatusUnauthorized {
		t.Fatalf("wrong secret: got %d, want 401", wrong.Code)
	}
	foreign := tn.bindAs(tn.userB, tn.devA2, secretA2) // customer B's user, customer A's valid code
	unassigned := tn.bindAs(tn.userB, tn.devUnassigned, "unassigned-secret")
	for name, rec := range map[string]*httptest.ResponseRecorder{"foreign customer": foreign, "unassigned device": unassigned} {
		if rec.Code != wrong.Code || rec.Body.String() != wrong.Body.String() {
			t.Errorf("%s answered %d %s, but a wrong secret answers %d %s", name, rec.Code, rec.Body.String(), wrong.Code, wrong.Body.String())
		}
	}
	var n int
	tn.env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM user_devices WHERE user_id = $1 AND device_name IN ($2, $3)`,
		tn.userBID, tn.devA2, tn.devUnassigned).Scan(&n)
	if n != 0 {
		t.Errorf("a refused bind still created %d binding(s)", n)
	}

	// The same code works for a user of the right customer.
	if rec := tn.bindAs(tn.userA, tn.devA2, secretA2); rec.Code != http.StatusOK {
		t.Errorf("same-customer bind: got %d %s", rec.Code, rec.Body.String())
	}
}

// --- commands and audit -----------------------------------------------------------------------

func (tn *tenancy) issue(t *testing.T, token, device string) *httptest.ResponseRecorder {
	t.Helper()
	return tn.env.do("POST", "/api/devices/"+device+"/commands", []byte(token),
		jsonBody(t, map[string]any{"method": protocol.MethodAlarmMute, "reason": "tenancy test"}))
}

func (tn *tenancy) commandCustomers(t *testing.T, device string) []int64 {
	t.Helper()
	rows, err := tn.env.pool.Query(context.Background(),
		`SELECT customer_id FROM control_commands WHERE device_name = $1 ORDER BY issued_at`, device)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []int64
	for rows.Next() {
		var c *int64
		if err := rows.Scan(&c); err != nil {
			t.Fatal(err)
		}
		if c == nil {
			out = append(out, 0)
		} else {
			out = append(out, *c)
		}
	}
	return out
}

func TestTenancy_Commands(t *testing.T) {
	tn := newTenancy(t)

	// Customer B's admin cannot command customer A's device: refused, nothing recorded.
	if rec := tn.issue(t, tn.adminB, tn.devA1); rec.Code != http.StatusForbidden {
		t.Fatalf("cross-customer command: got %d, want 403 (%s)", rec.Code, rec.Body.String())
	}
	if got := tn.commandCustomers(t, tn.devA1); len(got) != 0 {
		t.Fatalf("a refused command left rows behind: %v", got)
	}
	if tn.sender.calls != 0 {
		t.Errorf("ThingsBoard was called %d times for a refused command", tn.sender.calls)
	}

	// The refusal is audited under the *attacker's* customer (B), not the victim's.
	var denied int
	tn.env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM audit_log WHERE device_name = $1 AND outcome = 'denied' AND customer_id = $2`,
		tn.devA1, tn.custB).Scan(&denied)
	if denied != 1 {
		t.Errorf("denied audit rows under customer B = %d, want 1", denied)
	}

	// Customer A's admin: allowed, filed under A.
	if rec := tn.issue(t, tn.adminA, tn.devA1); rec.Code != http.StatusAccepted {
		t.Fatalf("own-customer command: got %d (%s)", rec.Code, rec.Body.String())
	}
	// A platform admin has no customer of their own: the command is filed under the device's.
	if rec := tn.issue(t, tn.platform, tn.devA1); rec.Code != http.StatusAccepted {
		t.Fatalf("platform admin command: got %d (%s)", rec.Code, rec.Body.String())
	}
	for i, c := range tn.commandCustomers(t, tn.devA1) {
		if c != tn.custA {
			t.Errorf("command %d customer = %d, want %d", i, c, tn.custA)
		}
	}

	// Reassigning the device does not move history.
	if _, err := tn.env.pool.Exec(context.Background(),
		`UPDATE devices SET customer_id = $1 WHERE device_name = $2`, tn.custB, tn.devA1); err != nil {
		t.Fatal(err)
	}
	for i, c := range tn.commandCustomers(t, tn.devA1) {
		if c != tn.custA {
			t.Errorf("after reassignment command %d customer = %d, want it to stay %d", i, c, tn.custA)
		}
	}
}

type auditEntry struct{ Actor, Action, Outcome, DeviceName string }

func auditEntries(t *testing.T, rec *httptest.ResponseRecorder) []auditEntry {
	t.Helper()
	if rec.Code != http.StatusOK {
		t.Fatalf("audit: %d %s", rec.Code, rec.Body.String())
	}
	var body struct {
		Audit []auditEntry `json:"audit"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode audit: %v (%s)", err, rec.Body.String())
	}
	return body.Audit
}

func TestTenancy_AuditScope(t *testing.T) {
	tn := newTenancy(t)

	tn.issue(t, tn.adminA, tn.devA1)   // A's own activity
	tn.issue(t, tn.platform, tn.devA1) // platform staff acting on A's device: A should see it
	tn.issue(t, tn.adminB, tn.devA1)   // B's refused attempt on A's device: only B (and platform) see it
	tn.issue(t, tn.adminB, tn.devB1)   // B's own activity

	forDevice := func(entries []auditEntry, device string) int {
		n := 0
		for _, e := range entries {
			if e.DeviceName == device {
				n++
			}
		}
		return n
	}

	// Customer admin A, no device named: their own trail only.
	a := auditEntries(t, tn.get(tn.adminA, "/api/audit"))
	if forDevice(a, tn.devB1) != 0 {
		t.Errorf("customer A's admin sees customer B's device in the audit trail: %v", a)
	}
	if forDevice(a, tn.devA1) == 0 {
		t.Errorf("customer A's admin cannot see activity on their own device: %v", a)
	}
	for _, e := range a {
		if e.Outcome == "denied" {
			t.Errorf("customer A sees B's refused attempt against A's device: %+v", e)
		}
	}
	var sawPlatform bool
	for _, e := range a {
		if strings.HasPrefix(e.Actor, "api-test-other-") && e.Outcome == "allowed" {
			sawPlatform = true
		}
	}
	if !sawPlatform {
		t.Errorf("platform staff's action on A's device is invisible to A: %v", a)
	}

	// ?customerId= is a platform-admin filter; for anyone else it must not widen the scope.
	b := auditEntries(t, tn.get(tn.adminA, fmt.Sprintf("/api/audit?customerId=%d", tn.custB)))
	if forDevice(b, tn.devB1) != 0 {
		t.Errorf("a customer admin widened their scope with ?customerId=: %v", b)
	}

	// A customer user must name a device.
	if rec := tn.get(tn.userA, "/api/audit"); rec.Code != http.StatusForbidden {
		t.Errorf("customer user without a device: got %d, want 403", rec.Code)
	}

	// Platform admin: everything, or narrowed to a customer.
	all := auditEntries(t, tn.get(tn.platform, "/api/audit"))
	if forDevice(all, tn.devA1) == 0 || forDevice(all, tn.devB1) == 0 {
		t.Errorf("platform admin should see both customers' devices: %v", all)
	}
	onlyB := auditEntries(t, tn.get(tn.platform, fmt.Sprintf("/api/audit?customerId=%d", tn.custB)))
	if forDevice(onlyB, tn.devB1) == 0 {
		t.Errorf("?customerId=B should include B's device: %v", onlyB)
	}
	if rec := tn.get(tn.platform, "/api/audit?customerId=abc"); rec.Code != http.StatusBadRequest {
		t.Errorf("malformed customerId: got %d, want 400", rec.Code)
	}
}

// --- schema -----------------------------------------------------------------------------------

func TestTenancy_SchemaInvariants(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()

	exec := func(sql string, args ...any) error {
		_, err := tn.env.pool.Exec(ctx, sql, args...)
		return err
	}
	name := func(s string) string { return fmt.Sprintf("schema-%s-%d", s, time.Now().UnixNano()) }

	if err := exec(`INSERT INTO users (username, password_hash, role, customer_id) VALUES ($1,'x','platform_admin',$2)`,
		name("pa"), tn.custA); err == nil {
		t.Error("a platform admin with a customer must be rejected")
	}
	if err := exec(`INSERT INTO users (username, password_hash, role) VALUES ($1,'x','customer_user')`, name("cu")); err == nil {
		t.Error("a customer user without a customer must be rejected")
	}
	if err := exec(`INSERT INTO customers (name) VALUES ('default')`); err == nil {
		t.Error("a duplicate customer name must be rejected")
	}
	if err := exec(`DELETE FROM customers WHERE id = $1`, tn.custA); err == nil {
		t.Error("a customer that still owns devices and users must not be deletable")
	}
}
