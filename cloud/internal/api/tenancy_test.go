package api_test

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
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
		`INSERT INTO user_tank_grants (user_id, device_name, tank) SELECT $1, $2, g FROM generate_series(1, 16) g`, userID, device); err != nil {
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

// A platform admin's list carries each device's customer_id (null while unassigned), so the admin
// console can show who owns a device without a second request. Customer accounts do not get it
// (TestTenancy_ListDevicesDoesNotLeakCustomerID).
func TestTenancy_ListDevicesCarriesCustomerIDForPlatformAdmin(t *testing.T) {
	tn := newTenancy(t)
	rec := tn.get(tn.platform, "/api/devices")
	if rec.Code != http.StatusOK {
		t.Fatalf("list devices: %d %s", rec.Code, rec.Body.String())
	}
	var body struct {
		Rows []struct {
			DeviceName string `json:"device_name"`
			CustomerID *int64 `json:"customer_id"`
		} `json:"rows"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	want := map[string]*int64{tn.devA1: &tn.custA, tn.devB1: &tn.custB, tn.devUnassigned: nil}
	seen := 0
	for _, r := range body.Rows {
		w, ok := want[r.DeviceName]
		if !ok {
			continue
		}
		seen++
		switch {
		case w == nil && r.CustomerID != nil:
			t.Errorf("%s is unassigned, got customer_id %d", r.DeviceName, *r.CustomerID)
		case w != nil && (r.CustomerID == nil || *r.CustomerID != *w):
			t.Errorf("%s: customer_id = %v, want %d", r.DeviceName, r.CustomerID, *w)
		}
	}
	if seen != len(want) {
		t.Fatalf("expected %d fixture devices in the list, saw %d", len(want), seen)
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
	// customerUserDenied marks a route open to admins of the device's customer but never to a
	// customer user, so the "own customer is allowed" half only applies to the customer admin.
	customerUserDenied bool
	// foreignAdminNotFound marks a route where a customer admin naming another customer's device gets
	// the 404 a missing device gets, not 403: clients read 403 "not authorized" as "account revoked"
	// and log the user out (#54). Customer users still get 403 (their role is the problem).
	foreignAdminNotFound bool
}

// exemptRoutes are the routes that take no device and therefore have no cross-customer case here.
// Each needs a reason: a route that is merely forgotten must fail the test, not slip through.
var exemptRoutes = map[string]string{
	"GET /healthz":    "no device",
	"POST /api/login": "no device",
	"GET /api/me":     "no device; returns only the caller's own row",

	"GET /api/customers/:id/onboarding":           "no device; platform admin only, see TestOnboarding_*",
	"POST /api/customers/:id/onboarding/complete": "no device; platform admin only, see TestOnboarding_*",

	"POST /api/auth/wechat/login":           "no device, no session yet; see TestWeChat_*",
	"POST /api/auth/wechat/phone-login":     "no device, no session yet; binds only to the account of the WeChat-verified phone, see TestWeChat_*",
	"GET /api/devices":                      "scoped to the caller; see TestTenancy_ListDevicesByRole",
	"GET /api/stations":                     "no device in the path; scoped to the caller in SQL, see TestStations_ListScope",
	"POST /api/stations":                    "no device; customer admin limited to their own customer, see TestStations_Create",
	"PUT /api/stations/:id":                 "no device; station must belong to the caller's customer, see TestStations_UpdateAndDelete",
	"DELETE /api/stations/:id":              "no device; station must belong to the caller's customer, see TestStations_UpdateAndDelete",
	"POST /api/stations/:id/replace-device": "device comes from the body, authorized before it is read and required to be in the station's customer, see TestStationReplace_*",
	"POST /api/devices":                     "no device in the path; role-gated to platform admin, see TestRegisterDevice_*",
	"POST /api/devices/bind":                "device comes from the body and is checked against the pairing secret; see TestTenancy_Bind*",
	"POST /api/devices/unbind":              "deletes only the caller's own binding row",
	"POST /api/devices/resolve":             "device comes from the body; never 403 — a device the caller may not open is the same 404 as a missing one and nothing is written, see TestResolve_*",
	"GET /api/users/:id/tank-grants":        "no device; platform admin only, see TestTankGrants_*",
	"GET /api/device-purges":                "no device in the path; platform admin only, see TestDevicePurge_*",
	"GET /api/device-purges/:id":            "no device; platform admin only, see TestDevicePurge_*",

	"POST /api/customers":         "no device; role-gated to platform admin, see TestAdmin_Customers",
	"GET /api/customers":          "no device; role-gated to platform admin, see TestAdmin_Customers",
	"PUT /api/customers/:id":      "no device; role-gated to platform admin, see TestAdmin_Customers",
	"DELETE /api/customers/:id":   "no device; role-gated to platform admin, see TestAdmin_Customers",
	"POST /api/users":             "no device; role-gated to platform admin, see TestAdmin_UserLifecycle",
	"GET /api/users":              "no device; a customer admin sees only their customer's staff, see TestAdmin_UserLifecycle",
	"PUT /api/users/:id":          "no device; role-gated to platform admin, see TestAdmin_DisableAndResetPassword",
	"PUT /api/users/:id/customer": "no device; role-gated to platform admin, see TestAdmin_MoveUser",
}

func deviceScopedRoutes(t *testing.T, tn *tenancy) map[string]routeCase {
	cmdBody := jsonBody(t, map[string]any{"method": protocol.MethodAlarmMute, "reason": "tenancy walk"})
	query := func(prefix string) func(string, string) (string, []byte) {
		return func(device, _ string) (string, []byte) { return prefix + device, nil }
	}
	path := func(suffix string) func(string, string) (string, []byte) {
		return func(device, _ string) (string, []byte) { return "/api/devices/" + device + suffix, nil }
	}
	return map[string]routeCase{
		"GET /api/alarms":                            {build: query("/api/alarms?device=")},
		"GET /api/oil-in-out":                        {build: query("/api/oil-in-out?device=")},
		"GET /api/audit":                             {build: query("/api/audit?device=")},
		"GET /api/devices/:device/mini-code":         {adminOnly: true, build: path("/mini-code")},
		"GET /api/devices/:device/telemetry/latest":  {build: path("/telemetry/latest")},
		"GET /api/devices/:device/telemetry/history": {build: path("/telemetry/history?tank=1&fields=oilLevelMm&interval=1h")},
		"GET /api/devices/:device/commands":          {build: path("/commands")},
		"POST /api/devices/:device/commands": {customerUserDenied: true, build: func(device, _ string) (string, []byte) {
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
		"PUT /api/devices/:device/station": {
			customerUserDenied:   true,
			foreignAdminNotFound: true,
			build: func(device, _ string) (string, []byte) {
				return "/api/devices/" + device + "/station", []byte(`{"stationId":null}`)
			},
		},
		"GET /api/devices/:device/tanks":                   {build: path("/tanks")},
		"GET /api/devices/:device/tank-settings":           {build: path("/tank-settings")},
		"GET /api/devices/:device/tanks/:tank/calibration": {build: path("/tanks/1/calibration")},
		// Platform admins only (cloud#83); every customer-side caller is refused by role.
		"POST /api/devices/:device/purge": {
			adminOnly: true,
			build: func(device, _ string) (string, []byte) {
				return "/api/devices/" + device + "/purge", []byte(`{"confirmDeviceName":"` + device + `","reason":"tenancy walk"}`)
			},
		},
		// userAID is an employee of customer A. Only a platform administrator writes grants, so every
		// customer-side caller (admin or employee, own customer or not) is refused by role.
		"PUT /api/users/:id/tank-grants/:device": {
			adminOnly: true,
			build: func(device, _ string) (string, []byte) {
				return fmt.Sprintf("/api/users/%d/tank-grants/%s", tn.userAID, device), []byte(`{"tanks":[]}`)
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

	scoped := deviceScopedRoutes(t, tn)
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
				want := http.StatusForbidden
				if name == "adminB" && rc.foreignAdminNotFound {
					want = http.StatusNotFound
				}
				if code := do(token, tn.devA1); code != want {
					t.Errorf("%s on customer A's device: got %d, want %d", name, code, want)
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
			own := map[string]string{"userA": tn.userA, "adminA": tn.adminA}
			if rc.customerUserDenied {
				own = map[string]string{"adminA": tn.adminA}
			}
			for name, token := range own {
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
	var platformName string
	tn.env.pool.QueryRow(context.Background(), `SELECT username FROM users WHERE id = $1`, tn.platformID).Scan(&platformName)
	var sawPlatform bool
	for _, e := range a {
		if e.Actor == platformName && e.Outcome == "allowed" {
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

	// Each row below is valid in every respect but the one invariant it names, so the rejection
	// cannot come from an unrelated CHECK.
	if err := exec(`INSERT INTO users (username, password_hash, role, customer_id) VALUES ($1,'x','platform_admin',$2)`,
		name("pa"), tn.custA); err == nil {
		t.Error("a platform admin with a customer must be rejected")
	}
	ph := testPhone()
	if err := exec(`INSERT INTO users (username, phone, role) VALUES ($1,$1,'customer_user')`, ph); err == nil {
		t.Error("a customer user without a customer must be rejected")
	}
	ph = testPhone()
	// Phone staff may have a password (000031); a username account may not go without one.
	if err := exec(`INSERT INTO users (username, phone, password_hash, role, customer_id) VALUES ($1,$1,'x','customer_user',$2)`,
		ph, tn.custA); err != nil {
		t.Errorf("phone staff with a password hash must be accepted: %v", err)
	}
	// It was accepted, so it exists: remove it, or the customers cleaned up afterwards stay referenced.
	exec(`DELETE FROM users WHERE username = $1`, ph)
	if err := exec(`INSERT INTO users (username, role, customer_id) VALUES ($1,'customer_user',$2)`,
		name("nopw"), tn.custA); err == nil {
		t.Error("a username staff account without a password hash must be rejected")
	}
	ph = testPhone()
	if err := exec(`INSERT INTO users (username, phone, role, customer_id) VALUES ($1,$1,'customer_admin',$2)`,
		ph, tn.custA); err == nil {
		t.Error("a customer admin without a password hash must be rejected")
	}
	if err := exec(`INSERT INTO users (username, password_hash, role, customer_id) VALUES ($1,'x','customer_admin',$2)`,
		testPhone(), tn.custA); err == nil {
		t.Error("a customer account without a phone must be rejected")
	}
	if err := exec(`INSERT INTO users (username, phone, role, customer_id) VALUES ($1,'12345678901','customer_user',$2)`,
		"12345678901", tn.custA); err == nil {
		t.Error("a malformed phone must be rejected")
	}
	if err := exec(`INSERT INTO users (username, phone, role, customer_id) VALUES ('someone-else',$1,'customer_user',$2)`,
		testPhone(), tn.custA); err == nil {
		t.Error("a customer account whose username differs from its phone must be rejected")
	}
	dup := testPhone()
	if err := exec(`INSERT INTO users (username, phone, role, customer_id) VALUES ($1,$1,'customer_user',$2)`,
		dup, tn.custA); err != nil {
		t.Fatalf("seed for the duplicate-phone check: %v", err)
	}
	t.Cleanup(func() { tn.env.pool.Exec(context.Background(), `DELETE FROM users WHERE phone = $1`, dup) })
	if err := exec(`INSERT INTO users (username, phone, role, customer_id) VALUES ('dup-'||$1,$1,'customer_user',$2)`,
		dup, tn.custA); err == nil {
		t.Error("a second account with the same phone must be rejected")
	}
	if err := exec(`INSERT INTO customers (name) VALUES ('default')`); err == nil {
		t.Error("a duplicate customer name must be rejected")
	}
	if err := exec(`DELETE FROM customers WHERE id = $1`, tn.custA); err == nil {
		t.Error("a customer that still owns devices and users must not be deletable")
	}
}

// TestTenancy_MintUsesTheCurrentDatabaseRole: minting a pairing secret can claim a device for any
// customer, so it must follow the user's row in the database, not the role baked into a token that is
// still unexpired. A platform admin who was disabled or demoted afterwards gets refused.
func TestTenancy_MintUsesTheCurrentDatabaseRole(t *testing.T) {
	tn := newTenancy(t)
	body := fmt.Sprintf(`{"customerId":%d}`, tn.custA)

	disabledToken, disabledID := tn.env.userIn(control.RolePlatformAdmin, 0)
	demotedToken, demotedID := tn.env.userIn(control.RolePlatformAdmin, 0)
	for name, token := range map[string]string{"disabled": disabledToken, "demoted": demotedToken} {
		if rec := tn.mint(token, tn.devA1, body); rec.Code != http.StatusOK {
			t.Fatalf("precondition: an active platform admin may mint (%s): %d %s", name, rec.Code, rec.Body.String())
		}
	}

	ctx := context.Background()
	if _, err := tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, disabledID); err != nil {
		t.Fatal(err)
	}
	if _, err := tn.env.pool.Exec(ctx, `UPDATE users SET role = 'customer_user', customer_id = $2, phone = username, password_hash = NULL WHERE id = $1`, demotedID, tn.custA); err != nil {
		t.Fatal(err)
	}

	for name, token := range map[string]string{"disabled": disabledToken, "demoted": demotedToken} {
		if rec := tn.mint(token, tn.devA1, body); rec.Code != http.StatusForbidden {
			t.Errorf("a %s platform admin with an old token minted a secret: got %d, want 403", name, rec.Code)
		}
		if rec := tn.mint(token, tn.devUnassigned, body); rec.Code != http.StatusForbidden {
			t.Errorf("a %s platform admin with an old token claimed a device: got %d, want 403", name, rec.Code)
		}
	}
	var owner *int64
	tn.env.pool.QueryRow(ctx, `SELECT customer_id FROM devices WHERE device_name = $1`, tn.devUnassigned).Scan(&owner)
	if owner != nil {
		t.Errorf("the refused mint still assigned the unassigned device to customer %d", *owner)
	}
}

// TestTenancy_DisabledOrDeletedUserIsRefusedEverywhere: the check lives in the auth middleware, so a
// disabled or deleted user is refused on every protected route — including ones, like unbind, that
// never ask the device-access question.
func TestTenancy_DisabledOrDeletedUserIsRefusedEverywhere(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	token, id := tn.env.userIn(control.RoleCustomerUser, tn.custA)
	tn.bind(t, id, tn.devA1)

	unbind := func() int {
		return tn.send(t, "POST", "/api/devices/unbind", token, map[string]string{"deviceName": tn.devA1}).Code
	}
	bound := func() int {
		var n int
		tn.env.pool.QueryRow(ctx, `SELECT count(DISTINCT device_name) FROM user_tank_grants WHERE user_id = $1`, id).Scan(&n)
		return n
	}

	if _, err := tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, id); err != nil {
		t.Fatal(err)
	}
	for _, route := range []struct{ method, path string }{
		{"GET", "/api/devices"},
		{"GET", "/api/devices/" + tn.devA1 + "/commands"},
		{"GET", "/api/audit?device=" + tn.devA1},
		{"GET", "/api/users"},
	} {
		if rec := tn.send(t, route.method, route.path, token, nil); rec.Code != http.StatusForbidden {
			t.Errorf("disabled user on %s %s: got %d, want 403", route.method, route.path, rec.Code)
		}
	}
	if code := unbind(); code != http.StatusForbidden {
		t.Errorf("a disabled user unbinding: got %d, want 403", code)
	}
	if bound() != 1 {
		t.Error("a disabled user's refused unbind still deleted the binding")
	}

	// Re-enabled, the same token works again, and unbind really does delete.
	if _, err := tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = NULL WHERE id = $1`, id); err != nil {
		t.Fatal(err)
	}
	// unbind is a retired no-op (cloud#63): access is the administrator's grants, which an employee
	// cannot edit — so it answers 200 and leaves the grants alone.
	if code := unbind(); code != http.StatusOK || bound() != 1 {
		t.Errorf("re-enabled unbind: %d, grants on %d device(s) (want 200 and the grant untouched)", code, bound())
	}

	// A deleted user's token is refused too.
	if _, err := tn.env.pool.Exec(ctx, `DELETE FROM users WHERE id = $1`, id); err != nil {
		t.Fatal(err)
	}
	if rec := tn.send(t, "GET", "/api/devices", token, nil); rec.Code != http.StatusForbidden {
		t.Errorf("a deleted user's token: got %d, want 403", rec.Code)
	}
}

// TestTenancy_ResolveDeviceAccessAgreesWithUserMayAccessDevice: the access rule is written out twice
// in SQL — once for REST/WebSocket, once combined with the customer snapshot for control commands —
// so nothing but this test stops them drifting apart. It also pins the snapshot each combination gets.
func TestTenancy_ResolveDeviceAccessAgreesWithUserMayAccessDevice(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	q := tn.env.queries

	_, disabledID := tn.env.userIn(control.RoleCustomerAdmin, tn.custA)
	if _, err := tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, disabledID); err != nil {
		t.Fatal(err)
	}

	users := map[string]int64{
		"platform": tn.platformID, "adminA": tn.adminAID, "adminB": tn.adminBID,
		"userA": tn.userAID, "userB": tn.userBID, "disabled": disabledID, "nobody": 999999999,
	}
	devices := []string{tn.devA1, tn.devA2, tn.devB1, tn.devUnassigned, tn.devGhost}

	for name, id := range users {
		for _, d := range devices {
			want, err := q.UserMayAccessDevice(ctx, db.UserMayAccessDeviceParams{UserID: id, DeviceName: d})
			if err != nil {
				t.Fatal(err)
			}
			got, err := q.ResolveDeviceAccess(ctx, db.ResolveDeviceAccessParams{UserID: id, DeviceName: d})
			if name == "nobody" {
				if err == nil || want {
					t.Errorf("nonexistent user on %s: want no row and not allowed, got row=%+v allowed=%v err=%v", d, got, want, err)
				}
				continue
			}
			if err != nil {
				t.Fatalf("%s on %s: %v", name, d, err)
			}
			if got.Allowed != want {
				t.Errorf("%s on %s: ResolveDeviceAccess=%v but UserMayAccessDevice=%v", name, d, got.Allowed, want)
			}
		}
	}

	// The snapshot: a user's own customer, or for a platform admin the device's customer.
	snapshot := func(user int64, device string) *int64 {
		row, err := q.ResolveDeviceAccess(ctx, db.ResolveDeviceAccessParams{UserID: user, DeviceName: device})
		if err != nil {
			t.Fatal(err)
		}
		c := row.UserCustomerID
		if !c.Valid {
			c = row.DeviceCustomerID
		}
		if !c.Valid {
			return nil
		}
		return &c.Int64
	}
	for name, tc := range map[string]struct {
		user   int64
		device string
		want   *int64
	}{
		"customer admin on their device":             {tn.adminAID, tn.devA1, ptr(tn.custA)},
		"attacker in B on A's device":                {tn.adminBID, tn.devA1, ptr(tn.custB)},
		"platform admin on A's device":               {tn.platformID, tn.devA1, ptr(tn.custA)},
		"platform admin on an unassigned device":     {tn.platformID, tn.devUnassigned, nil},
		"platform admin on a device that is a ghost": {tn.platformID, tn.devGhost, nil},
	} {
		got := snapshot(tc.user, tc.device)
		if (got == nil) != (tc.want == nil) || (got != nil && *got != *tc.want) {
			t.Errorf("%s: snapshot = %v, want %v", name, got, tc.want)
		}
	}
}

func (tn *tenancy) issueMethod(t *testing.T, token, device, method string) *httptest.ResponseRecorder {
	t.Helper()
	return tn.send(t, "POST", "/api/devices/"+device+"/commands", token,
		map[string]any{"method": method, "reason": "tenancy test"})
}

// TestTenancy_DemotedAdminLosesAdminMethodsImmediately: the role that gates *which methods* a user may
// issue must come from the database too. A demoted administrator who is still bound to a device
// keeps the control-level methods and loses the admin-level ones on the very next request, with the
// token they already hold.
func TestTenancy_DemotedAdminLosesAdminMethodsImmediately(t *testing.T) {
	tn := newTenancy(t)
	tn.bind(t, tn.adminAID, tn.devA1)

	if rec := tn.issueMethod(t, tn.adminA, tn.devA1, protocol.MethodConfigTank); rec.Code != http.StatusAccepted {
		t.Fatalf("precondition: a customer admin may issue %s, got %d %s", protocol.MethodConfigTank, rec.Code, rec.Body.String())
	}
	if _, err := tn.env.pool.Exec(context.Background(), `UPDATE users SET role = 'customer_user', password_hash = NULL WHERE id = $1`, tn.adminAID); err != nil {
		t.Fatal(err)
	}
	if rec := tn.issueMethod(t, tn.adminA, tn.devA1, protocol.MethodConfigTank); rec.Code != http.StatusForbidden {
		t.Errorf("a demoted admin issuing an admin-level method with an old token: got %d, want 403", rec.Code)
	}
	// Demoted to an employee: view-only, so not even the control tier (cloud#63).
	if rec := tn.issueMethod(t, tn.adminA, tn.devA1, protocol.MethodAlarmMute); rec.Code != http.StatusForbidden {
		t.Errorf("an employee muting: got %d %s, want 403", rec.Code, rec.Body.String())
	}
}

// TestTenancy_CommandInsertEnforcesAccessItself: the insert re-checks the access rule in the same
// statement, so authorization cannot go stale between an early decision and the write — which in
// Issue is separated by a ThingsBoard round trip. Zero rows means "no longer allowed".
func TestTenancy_CommandInsertEnforcesAccessItself(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	q := tn.env.queries

	insert := func(userID int64, device string) (db.ControlCommand, error) {
		id := pgtype.UUID{Valid: true}
		if _, err := rand.Read(id.Bytes[:]); err != nil {
			t.Fatal(err)
		}
		now := time.Now()
		return q.CreateControlCommand(ctx, db.CreateControlCommandParams{
			CommandID: id, DeviceName: device, Method: protocol.MethodAlarmMute, Args: []byte(`{}`),
			OperatorID: userID, Operator: "insert-test", Reason: "r",
			IssuedAt:  pgtype.Timestamptz{Time: now, Valid: true},
			ExpiresAt: pgtype.Timestamptz{Time: now.Add(time.Minute), Valid: true},
		})
	}
	_, disabledID := tn.env.userIn(control.RoleCustomerAdmin, tn.custA)
	tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, disabledID)

	for name, tc := range map[string]struct {
		user    int64
		device  string
		allowed bool
		want    int64
	}{
		"customer admin on their device":        {tn.adminAID, tn.devA1, true, tn.custA},
		"bound customer user":                   {tn.userAID, tn.devA1, true, tn.custA},
		"platform admin: the device's customer": {tn.platformID, tn.devA1, true, tn.custA},
		"unbound customer user":                 {tn.userAID, tn.devA2, false, 0},
		"another customer's admin":              {tn.adminBID, tn.devA1, false, 0},
		"unassigned device":                     {tn.adminAID, tn.devUnassigned, false, 0},
		"disabled user":                         {disabledID, tn.devA1, false, 0},
		"user that does not exist":              {999999999, tn.devA1, false, 0},
	} {
		cmd, err := insert(tc.user, tc.device)
		switch {
		case tc.allowed && err != nil:
			t.Errorf("%s: %v", name, err)
		case tc.allowed && (!cmd.CustomerID.Valid || cmd.CustomerID.Int64 != tc.want):
			t.Errorf("%s: filed under %+v, want customer %d", name, cmd.CustomerID, tc.want)
		case !tc.allowed && !errors.Is(err, pgx.ErrNoRows):
			t.Errorf("%s: got err=%v, want pgx.ErrNoRows (nothing recorded)", name, err)
		}
	}

	// Authorized a moment ago, then the device changes hands before the write: refused, and nothing
	// is filed under the old customer.
	if _, err := insert(tn.adminAID, tn.devA1); err != nil {
		t.Fatalf("precondition: %v", err)
	}
	if _, err := tn.env.pool.Exec(ctx, `UPDATE devices SET customer_id = $1 WHERE device_name = $2`, tn.custB, tn.devA1); err != nil {
		t.Fatal(err)
	}
	var before int
	tn.env.pool.QueryRow(ctx, `SELECT count(*) FROM control_commands WHERE device_name = $1`, tn.devA1).Scan(&before)
	if _, err := insert(tn.adminAID, tn.devA1); !errors.Is(err, pgx.ErrNoRows) {
		t.Errorf("after the device was transferred, the old customer's admin: err=%v, want pgx.ErrNoRows", err)
	}
	var after int
	tn.env.pool.QueryRow(ctx, `SELECT count(*) FROM control_commands WHERE device_name = $1`, tn.devA1).Scan(&after)
	if after != before {
		t.Errorf("the refused insert still recorded a command (%d -> %d)", before, after)
	}
}

// TestTenancy_DeviceListAgreesWithAccessRule: the list query is the one place the rule is written
// out as a list rather than a predicate, so it gets its own agreement check against the function.
func TestTenancy_DeviceListAgreesWithAccessRule(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	q := tn.env.queries

	_, disabledID := tn.env.userIn(control.RoleCustomerAdmin, tn.custA)
	tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, disabledID)

	existing := []string{tn.devA1, tn.devA2, tn.devB1, tn.devUnassigned}
	for name, id := range map[string]int64{
		"platform": tn.platformID, "adminA": tn.adminAID, "adminB": tn.adminBID,
		"userA": tn.userAID, "userB": tn.userBID, "disabled": disabledID,
	} {
		rows, err := q.ListDevicesForUser(ctx, id)
		if err != nil {
			t.Fatal(err)
		}
		listed := map[string]bool{}
		for _, r := range rows {
			listed[r.DeviceName] = true
		}
		for _, d := range existing {
			want, err := q.UserMayAccessDevice(ctx, db.UserMayAccessDeviceParams{UserID: id, DeviceName: d})
			if err != nil {
				t.Fatal(err)
			}
			if listed[d] != want {
				t.Errorf("%s / %s: listed=%v but UserMayAccessDevice=%v", name, d, listed[d], want)
			}
		}
	}
}

// TestTenancy_UnauthorizedCallerGetsTheSame403ForPhysicalMethods: with the interlock closed (the
// default), a caller who may not touch the device must still be told 403 — not 409 because the
// method happens to be physical. Only a caller who *is* authorized learns the gate is closed.
func TestTenancy_UnauthorizedCallerGetsTheSame403ForPhysicalMethods(t *testing.T) {
	tn := newTenancy(t) // control.Config{}: physical commands disabled

	for name, tc := range map[string]struct {
		token, device string
		want          int
	}{
		"another customer's user":        {tn.userB, tn.devA1, http.StatusForbidden},
		"another customer's admin":       {tn.adminB, tn.devA1, http.StatusForbidden},
		"unbound customer user":          {tn.userA, tn.devA2, http.StatusForbidden},
		"unassigned device":              {tn.adminA, tn.devUnassigned, http.StatusForbidden},
		"device that does not exist":     {tn.adminA, tn.devGhost, http.StatusForbidden},
		"authorized: the gate is closed": {tn.userA, tn.devA1, http.StatusConflict},
		"platform admin: gate applies":   {tn.platform, tn.devA1, http.StatusConflict},
	} {
		if rec := tn.issueMethod(t, tc.token, tc.device, protocol.MethodPumpStart); rec.Code != tc.want {
			t.Errorf("%s: pump.start got %d, want %d (%s)", name, rec.Code, tc.want, rec.Body.String())
		}
	}
	if tn.sender.calls != 0 {
		t.Errorf("ThingsBoard was called %d times although every request was refused", tn.sender.calls)
	}
}

// TestTenancy_UnauthorizedCallerIsRefusedBeforeTheMethodIsExamined: a caller who may not touch the
// device gets 403 even for a method that does not exist; only someone allowed to send the request is
// told what is wrong with it.
func TestTenancy_UnauthorizedCallerIsRefusedBeforeTheMethodIsExamined(t *testing.T) {
	tn := newTenancy(t)
	const bogus = "no.such.method"

	for name, tc := range map[string]struct {
		token, device string
		want          int
	}{
		"another customer's user":  {tn.userB, tn.devA1, http.StatusForbidden},
		"unbound customer user":    {tn.userA, tn.devA2, http.StatusForbidden},
		"device that is a ghost":   {tn.adminA, tn.devGhost, http.StatusForbidden},
		"authorized: now it's 400": {tn.userA, tn.devA1, http.StatusBadRequest},
	} {
		if rec := tn.issueMethod(t, tc.token, tc.device, bogus); rec.Code != tc.want {
			t.Errorf("%s: unknown method got %d, want %d (%s)", name, rec.Code, tc.want, rec.Body.String())
		}
	}
	// The refusals are audited under the caller's customer like any other denial.
	if n := tn.auditCount(t, `action = 'control.issue' AND outcome = 'denied' AND customer_id = $1 AND detail->>'method' = $2`, tn.custB, bogus); n != 1 {
		t.Errorf("expected the cross-customer attempt audited under customer B once, got %d", n)
	}
}

// seedHistory writes one command and one audit row for the device filed under customerID, as the
// history of a past ownership period would be.
func (tn *tenancy) seedHistory(t *testing.T, device string, customerID int64, tag string) {
	t.Helper()
	ctx := context.Background()
	if _, err := tn.env.pool.Exec(ctx, `
		INSERT INTO control_commands (command_id, device_name, method, operator, reason, expires_at, customer_id)
		VALUES (gen_random_uuid(), $1, 'alarm.mute', $2, 'history', now() + interval '1 hour', $3)`,
		device, tag, customerID); err != nil {
		t.Fatal(err)
	}
	if _, err := tn.env.pool.Exec(ctx, `
		INSERT INTO audit_log (actor, action, device_name, outcome, customer_id)
		VALUES ($1, 'control.issue', $2, 'allowed', $3)`, tag, device, customerID); err != nil {
		t.Fatal(err)
	}
}

// TestTenancy_ReadsDecideAccessAndScopeInOneStatement: the command listing and the audit listing
// authorize the read and scope it to the caller's customer inside the statement that reads. The setup
// is the dangerous one — device D belongs to A now but carries history filed under B from an earlier
// owner — and the callers are the ones who could reach it if access and scope came from different
// moments: users of B, who have the customer the history is filed under but no access to D.
func TestTenancy_ReadsDecideAccessAndScopeInOneStatement(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	q := tn.env.queries
	tn.seedHistory(t, tn.devA1, tn.custA, "history-of-A")
	tn.seedHistory(t, tn.devA1, tn.custB, "history-of-B")

	_, disabledID := tn.env.userIn(control.RoleCustomerAdmin, tn.custA)
	tn.env.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, disabledID)

	commandOperators := func(user int64) map[string]bool {
		rows, err := q.ListControlCommandsVisibleToUser(ctx, db.ListControlCommandsVisibleToUserParams{
			UserID: user, DeviceName: tn.devA1, Lim: 100, Off: 0,
		})
		if err != nil {
			t.Fatal(err)
		}
		out := map[string]bool{}
		for _, r := range rows {
			out[r.Operator] = true
		}
		return out
	}
	auditActors := func(user int64, device string, customer pgtype.Int8) map[string]bool {
		rows, err := q.ListAuditLogVisibleToUser(ctx, db.ListAuditLogVisibleToUserParams{
			UserID:     user,
			DeviceName: pgtype.Text{String: device, Valid: device != ""},
			CustomerID: customer,
			BeginTime:  pgtype.Timestamptz{Time: time.Now().Add(-time.Hour), Valid: true},
			EndTime:    pgtype.Timestamptz{Time: time.Now().Add(time.Hour), Valid: true},
			Lim:        1000, Off: 0,
		})
		if err != nil {
			t.Fatal(err)
		}
		out := map[string]bool{}
		for _, r := range rows {
			out[r.Actor] = true
		}
		return out
	}
	only := func(got map[string]bool, want ...string) bool {
		if len(got) != len(want) {
			return false
		}
		for _, w := range want {
			if !got[w] {
				return false
			}
		}
		return true
	}

	// Commands for D.
	for name, tc := range map[string]struct {
		user int64
		want []string
	}{
		"admin of A: its own history only":     {tn.adminAID, []string{"history-of-A"}},
		"bound user of A: nothing (view-only)": {tn.userAID, nil},
		"admin of B: no access to D, nothing":  {tn.adminBID, nil},
		"user of B: no access to D, nothing":   {tn.userBID, nil},
		"disabled admin of A: nothing":         {disabledID, nil},
		"platform admin: both periods":         {tn.platformID, []string{"history-of-A", "history-of-B"}},
		"user that does not exist: nothing":    {999999999, nil},
	} {
		if got := commandOperators(tc.user); !only(got, tc.want...) {
			t.Errorf("commands, %s: got %v, want %v", name, got, tc.want)
		}
	}

	// Audit, naming D.
	for name, tc := range map[string]struct {
		user int64
		want []string
	}{
		"admin of A naming D": {tn.adminAID, []string{"history-of-A"}},
		"user of A naming D":  {tn.userAID, nil},
		"admin of B naming D": {tn.adminBID, nil},
		"user of B naming D":  {tn.userBID, nil},
		"disabled admin":      {disabledID, nil},
		"platform naming D":   {tn.platformID, []string{"history-of-A", "history-of-B"}},
	} {
		if got := auditActors(tc.user, tn.devA1, pgtype.Int8{}); !only(got, tc.want...) {
			t.Errorf("audit, %s: got %v, want %v", name, got, tc.want)
		}
	}
	// Audit, no device named.
	if got := auditActors(tn.userAID, "", pgtype.Int8{}); len(got) != 0 {
		t.Errorf("a customer user without a device must read nothing, got %v", got)
	}
	if got := auditActors(tn.adminAID, "", pgtype.Int8{}); got["history-of-B"] || !got["history-of-A"] {
		t.Errorf("a customer admin without a device sees their own customer only, got %v", got)
	}
	// A customer filter narrows a platform admin and is ignored for anyone else.
	if got := auditActors(tn.platformID, "", pgtype.Int8{Int64: tn.custB, Valid: true}); !got["history-of-B"] || got["history-of-A"] {
		t.Errorf("platform admin narrowed to B: got %v", got)
	}
	if got := auditActors(tn.adminAID, "", pgtype.Int8{Int64: tn.custB, Valid: true}); got["history-of-B"] {
		t.Errorf("a customer admin widened their audit scope with a customer filter: %v", got)
	}
}

// TestTenancy_PairingSecretWriteRechecksOwnership: the secret write carries its own ownership check
// and locks the device row, because the handler checks ownership first and hashes (slowly) before it
// writes. A transfer in that gap must not let the old customer's request rotate the secret of a
// device that is now someone else's.
func TestTenancy_PairingSecretWriteRechecksOwnership(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	q := tn.env.queries
	upsert := func(device string, customer int64, hash string) error {
		_, err := q.UpsertDevicePairingSecretForCustomer(ctx, db.UpsertDevicePairingSecretForCustomerParams{
			DeviceName: device, CustomerID: pgtype.Int8{Int64: customer, Valid: true}, SecretHash: hash,
		})
		return err
	}
	currentHash := func(device string) string {
		h, err := q.GetDevicePairingSecretHash(ctx, device)
		if err != nil {
			return ""
		}
		return h
	}

	if err := upsert(tn.devA2, tn.custA, "hash-original"); err != nil {
		t.Fatalf("rotating for the owning customer: %v", err)
	}
	for name, tc := range map[string]struct {
		device   string
		customer int64
	}{
		"another customer's device":   {tn.devA2, tn.custB},
		"an unassigned device":        {tn.devUnassigned, tn.custA},
		"a device that doesn't exist": {tn.devGhost, tn.custA},
	} {
		if err := upsert(tc.device, tc.customer, "hash-intruder"); !errors.Is(err, pgx.ErrNoRows) {
			t.Errorf("%s: err=%v, want pgx.ErrNoRows", name, err)
		}
	}
	if got := currentHash(tn.devA2); got != "hash-original" {
		t.Fatalf("a refused rotation changed the secret to %q", got)
	}

	// The race, deterministically: a transfer holds the device row, the old customer's rotation
	// queues behind it, the transfer commits — and the rotation must find the device is no longer
	// its customer's.
	tx, err := tn.env.pool.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `SELECT 1 FROM devices WHERE device_name = $1 FOR UPDATE`, tn.devA2); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- upsert(tn.devA2, tn.custA, "hash-stale-rotation") }()
	time.Sleep(500 * time.Millisecond)
	if _, err := tx.Exec(ctx, `UPDATE devices SET customer_id = $1 WHERE device_name = $2`, tn.custB, tn.devA2); err != nil {
		t.Fatal(err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if !errors.Is(err, pgx.ErrNoRows) {
			t.Errorf("a rotation racing a transfer: err=%v, want pgx.ErrNoRows", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("the rotation never returned")
	}
	if got := currentHash(tn.devA2); got != "hash-original" {
		t.Errorf("the stale rotation overwrote the new owner's secret: %q", got)
	}
}
