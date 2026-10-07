package api_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"yeweiyi-cloud/internal/control"
)

// insertStation seeds a station directly, bypassing the API, so the schema rules can be tested on
// their own. It registers cleanup: devices pointing at it are detached first.
func (tn *tenancy) insertStation(t *testing.T, customerID int64, name string) int64 {
	t.Helper()
	var id int64
	if err := tn.env.pool.QueryRow(context.Background(),
		`INSERT INTO stations (customer_id, name, address) VALUES ($1, $2, '') RETURNING id`,
		customerID, name).Scan(&id); err != nil {
		t.Fatalf("seed station %q: %v", name, err)
	}
	t.Cleanup(func() {
		ctx := context.Background()
		tn.env.pool.Exec(ctx, `UPDATE devices SET station_id = NULL WHERE station_id = $1`, id)
		tn.env.pool.Exec(ctx, `DELETE FROM stations WHERE id = $1`, id)
	})
	return id
}

func wantSQLState(t *testing.T, err error, state, what string) {
	t.Helper()
	if err == nil || !strings.Contains(err.Error(), "SQLSTATE "+state) {
		t.Errorf("%s: got %v, want SQLSTATE %s", what, err, state)
	}
}

// The database, not the handlers, is what keeps a device's station inside the device's customer.
func TestStations_SchemaRules(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	pool := tn.env.pool

	name := uniqueName("s")
	sA := tn.insertStation(t, tn.custA, name)
	sB := tn.insertStation(t, tn.custB, name) // same name in another customer is fine

	_, err := pool.Exec(ctx, `INSERT INTO stations (customer_id, name, address) VALUES ($1, $2, '')`, tn.custA, name)
	wantSQLState(t, err, "23505", "duplicate name inside one customer")

	_, err = pool.Exec(ctx, `UPDATE devices SET station_id = $2 WHERE device_name = $1`, tn.devA1, sB)
	wantSQLState(t, err, "23503", "device of customer A on a station of customer B")

	_, err = pool.Exec(ctx, `UPDATE devices SET station_id = $2 WHERE device_name = $1`, tn.devUnassigned, sA)
	wantSQLState(t, err, "23514", "device with no customer on a station")

	if _, err := pool.Exec(ctx, `UPDATE devices SET station_id = $2 WHERE device_name = $1`, tn.devA1, sA); err != nil {
		t.Fatalf("device on a station of its own customer: %v", err)
	}
	_, err = pool.Exec(ctx, `DELETE FROM stations WHERE id = $1`, sA)
	wantSQLState(t, err, "23503", "deleting a station that still has a device")

	// Changing the customer without clearing the station must be impossible: that is exactly the
	// leftover the transfer handler has to avoid (Task 2).
	_, err = pool.Exec(ctx, `UPDATE devices SET customer_id = $2 WHERE device_name = $1`, tn.devA1, tn.custB)
	wantSQLState(t, err, "23503", "moving a device to another customer while it keeps its station")

	// A customer that owns a station cannot be deleted out from under it.
	_, err = pool.Exec(ctx, `DELETE FROM customers WHERE id = $1`, tn.custB)
	wantSQLState(t, err, "23503", "deleting a customer that owns a station")
}

func TestStations_TransferClearsStation(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	sA := tn.insertStation(t, tn.custA, uniqueName("s"))
	if _, err := tn.env.pool.Exec(ctx, `UPDATE devices SET station_id = $2 WHERE device_name = $1`, tn.devA1, sA); err != nil {
		t.Fatal(err)
	}

	rec := tn.send(t, "PUT", "/api/devices/"+tn.devA1+"/customer", tn.platform, map[string]int64{"customerId": tn.custB})
	if rec.Code != http.StatusOK {
		t.Fatalf("transfer: %d %s", rec.Code, rec.Body.String())
	}

	var station *int64
	if err := tn.env.pool.QueryRow(ctx, `SELECT station_id FROM devices WHERE device_name = $1`, tn.devA1).Scan(&station); err != nil {
		t.Fatal(err)
	}
	if station != nil {
		t.Errorf("station_id after transfer = %d, want NULL", *station)
	}
}

type deviceRow struct {
	DeviceName string `json:"device_name"`
	StationID  *int64 `json:"station_id"`
}

type stationRow struct {
	ID         int64  `json:"id"`
	CustomerID int64  `json:"customerId"`
	Name       string `json:"name"`
	Address    string `json:"address"`
}

func (tn *tenancy) deviceRows(t *testing.T, token string) map[string]*int64 {
	t.Helper()
	rec := tn.get(token, "/api/devices")
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/devices: %d %s", rec.Code, rec.Body.String())
	}
	body := decodeAs[struct {
		Rows []deviceRow `json:"rows"`
	}](t, rec)
	out := map[string]*int64{}
	for _, r := range body.Rows {
		out[r.DeviceName] = r.StationID
	}
	return out
}

func (tn *tenancy) stationIDs(t *testing.T, token string) map[int64]bool {
	t.Helper()
	rec := tn.get(token, "/api/stations")
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /api/stations: %d %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"rows":[`) {
		t.Fatalf("rows must be an array, never null: %s", rec.Body.String())
	}
	body := decodeAs[struct {
		Rows []stationRow `json:"rows"`
	}](t, rec)
	out := map[int64]bool{}
	for _, r := range body.Rows {
		out[r.ID] = true
	}
	return out
}

func (tn *tenancy) putDeviceOnStation(t *testing.T, device string, station int64) {
	t.Helper()
	if _, err := tn.env.pool.Exec(context.Background(),
		`UPDATE devices SET station_id = $2 WHERE device_name = $1`, device, station); err != nil {
		t.Fatalf("put %s on station %d: %v", device, station, err)
	}
}

func TestStations_DeviceListCarriesStationID(t *testing.T) {
	tn := newTenancy(t)
	sA := tn.insertStation(t, tn.custA, uniqueName("s"))
	tn.putDeviceOnStation(t, tn.devA1, sA)

	rows := tn.deviceRows(t, tn.userA)
	got, ok := rows[tn.devA1]
	if !ok || got == nil || *got != sA {
		t.Errorf("station_id of the bound device = %v, want %d", got, sA)
	}

	// A device with no station is present and null, not missing.
	rows = tn.deviceRows(t, tn.adminA)
	if v, ok := rows[tn.devA2]; !ok || v != nil {
		t.Errorf("device without a station: present=%v station_id=%v, want present and null", ok, v)
	}
}

// The station list is derived from the devices a caller may see, so it never reveals a station (or
// the number of devices in it) that the device list would not.
func TestStations_ListScope(t *testing.T) {
	tn := newTenancy(t)
	sWithBound := tn.insertStation(t, tn.custA, uniqueName("bound"))   // devA1, which userA is bound to
	sWithUnbound := tn.insertStation(t, tn.custA, uniqueName("unbnd")) // devA2, which nobody is bound to
	sEmpty := tn.insertStation(t, tn.custA, uniqueName("empty"))       // no devices at all
	sB := tn.insertStation(t, tn.custB, uniqueName("b"))
	tn.putDeviceOnStation(t, tn.devA1, sWithBound)
	tn.putDeviceOnStation(t, tn.devA2, sWithUnbound)
	tn.putDeviceOnStation(t, tn.devB1, sB)

	cases := []struct {
		who   string
		token string
		want  []int64
		not   []int64
	}{
		{"customer user, bound to one device", tn.userA, []int64{sWithBound}, []int64{sWithUnbound, sEmpty, sB}},
		{"customer admin sees the whole customer, empty station included", tn.adminA, []int64{sWithBound, sWithUnbound, sEmpty}, []int64{sB}},
		{"other customer's user", tn.userB, []int64{sB}, []int64{sWithBound, sWithUnbound, sEmpty}},
		{"platform admin sees everything", tn.platform, []int64{sWithBound, sWithUnbound, sEmpty, sB}, nil},
	}
	for _, c := range cases {
		got := tn.stationIDs(t, c.token)
		for _, id := range c.want {
			if !got[id] {
				t.Errorf("%s: station %d missing", c.who, id)
			}
		}
		for _, id := range c.not {
			if got[id] {
				t.Errorf("%s: station %d must not be visible", c.who, id)
			}
		}
	}
}

func TestStations_ListIsEmptyArrayWithoutBindings(t *testing.T) {
	tn := newTenancy(t)
	u, _ := tn.env.userIn(control.RoleCustomerUser, tn.custA) // no bindings
	if got := tn.stationIDs(t, u); len(got) != 0 {
		t.Errorf("user with no bound devices sees %v, want none", got)
	}
}

func TestStations_ListRequiresLogin(t *testing.T) {
	tn := newTenancy(t)
	if rec := tn.get("", "/api/stations"); rec.Code != http.StatusUnauthorized {
		t.Errorf("no token: got %d, want 401", rec.Code)
	}
}

func (tn *tenancy) apiStation(t *testing.T, token string, body map[string]any) (stationRow, *httptest.ResponseRecorder) {
	t.Helper()
	rec := tn.send(t, "POST", "/api/stations", token, body)
	if rec.Code != http.StatusCreated {
		return stationRow{}, rec
	}
	s := decodeAs[stationRow](t, rec)
	t.Cleanup(func() {
		ctx := context.Background()
		tn.env.pool.Exec(ctx, `UPDATE devices SET station_id = NULL WHERE station_id = $1`, s.ID)
		tn.env.pool.Exec(ctx, `DELETE FROM stations WHERE id = $1`, s.ID)
	})
	return s, rec
}

func TestStations_Create(t *testing.T) {
	tn := newTenancy(t)

	s, rec := tn.apiStation(t, tn.platform, map[string]any{"customerId": tn.custA, "name": "  站点一  ", "address": "某路 1 号"})
	if rec.Code != http.StatusCreated {
		t.Fatalf("platform admin create: %d %s", rec.Code, rec.Body.String())
	}
	if s.Name != "站点一" || s.CustomerID != tn.custA || s.Address != "某路 1 号" {
		t.Errorf("created %+v, want trimmed name, customer A and the address", s)
	}

	// A customer admin defaults to their own customer.
	if _, rec := tn.apiStation(t, tn.adminA, map[string]any{"name": uniqueName("own")}); rec.Code != http.StatusCreated {
		t.Errorf("customer admin, own customer by default: %d %s", rec.Code, rec.Body.String())
	}
	// ...may name their own customer explicitly...
	if _, rec := tn.apiStation(t, tn.adminA, map[string]any{"customerId": tn.custA, "name": uniqueName("own2")}); rec.Code != http.StatusCreated {
		t.Errorf("customer admin, own customer explicit: %d", rec.Code)
	}
	// ...but not another customer's.
	if _, rec := tn.apiStation(t, tn.adminA, map[string]any{"customerId": tn.custB, "name": uniqueName("x")}); rec.Code != http.StatusBadRequest {
		t.Errorf("customer admin creating in another customer: got %d, want 400", rec.Code)
	} else if _, ghost := tn.apiStation(t, tn.adminA, map[string]any{"customerId": 999999999, "name": uniqueName("x")}); ghost.Code != rec.Code || ghost.Body.String() != rec.Body.String() {
		t.Errorf("another customer's customerId vs a missing one: %d %q vs %d %q, want identical",
			rec.Code, rec.Body.String(), ghost.Code, ghost.Body.String())
	}
	if _, rec := tn.apiStation(t, tn.userA, map[string]any{"name": uniqueName("x")}); rec.Code != http.StatusForbidden {
		t.Errorf("customer user creating: got %d, want 403", rec.Code)
	}

	// Platform admin must say which customer; the customer must exist.
	if _, rec := tn.apiStation(t, tn.platform, map[string]any{"name": uniqueName("x")}); rec.Code != http.StatusBadRequest {
		t.Errorf("platform admin without customerId: got %d, want 400", rec.Code)
	}
	if _, rec := tn.apiStation(t, tn.platform, map[string]any{"customerId": 999999999, "name": uniqueName("x")}); rec.Code != http.StatusBadRequest {
		t.Errorf("unknown customerId: got %d, want 400", rec.Code)
	}

	// Names: required, 1-100 characters after trimming, unique per customer, free across customers.
	for label, name := range map[string]string{"blank": "   ", "101 chars": strings.Repeat("a", 101)} {
		if _, rec := tn.apiStation(t, tn.adminA, map[string]any{"name": name}); rec.Code != http.StatusBadRequest {
			t.Errorf("%s name: got %d, want 400", label, rec.Code)
		}
	}
	if _, rec := tn.apiStation(t, tn.adminA, map[string]any{"name": strings.Repeat("油", 100)}); rec.Code != http.StatusCreated {
		t.Errorf("100 CJK characters is within the limit: got %d", rec.Code)
	}
	dup := uniqueName("dup")
	if _, rec := tn.apiStation(t, tn.adminA, map[string]any{"name": dup}); rec.Code != http.StatusCreated {
		t.Fatalf("first %q: %d", dup, rec.Code)
	}
	if _, rec := tn.apiStation(t, tn.adminA, map[string]any{"name": " " + dup + " "}); rec.Code != http.StatusConflict {
		t.Errorf("same name in one customer (after trimming): got %d, want 409", rec.Code)
	}
	if _, rec := tn.apiStation(t, tn.adminB, map[string]any{"name": dup}); rec.Code != http.StatusCreated {
		t.Errorf("same name in another customer: got %d, want 201", rec.Code)
	}
}

func TestStations_UpdateAndDelete(t *testing.T) {
	tn := newTenancy(t)
	sA, rec := tn.apiStation(t, tn.adminA, map[string]any{"name": uniqueName("a"), "address": "old"})
	if rec.Code != http.StatusCreated {
		t.Fatal(rec.Body.String())
	}
	other := tn.insertStation(t, tn.custB, uniqueName("b"))
	path := func(id int64) string { return "/api/stations/" + strconv.FormatInt(id, 10) }

	// Rename, and omitting address clears it (PUT replaces).
	rec = tn.send(t, "PUT", path(sA.ID), tn.adminA, map[string]any{"name": uniqueName("renamed")})
	if rec.Code != http.StatusOK {
		t.Fatalf("rename: %d %s", rec.Code, rec.Body.String())
	}
	if got := decodeAs[stationRow](t, rec); got.Address != "" || got.CustomerID != tn.custA {
		t.Errorf("after PUT: %+v, want empty address and still customer A", got)
	}

	// Another customer's station answers exactly like one that does not exist.
	missing := tn.send(t, "PUT", path(999999999), tn.adminA, map[string]any{"name": uniqueName("x")})
	foreign := tn.send(t, "PUT", path(other), tn.adminA, map[string]any{"name": uniqueName("x")})
	// 404, not 403: clients treat 403 "not authorized" as a revoked account and log the user out (#54).
	if missing.Code != http.StatusNotFound || foreign.Code != http.StatusNotFound || missing.Body.String() != foreign.Body.String() {
		t.Errorf("foreign vs missing station: %d %q vs %d %q, want identical 404s",
			foreign.Code, foreign.Body.String(), missing.Code, missing.Body.String())
	}
	if strings.Contains(foreign.Body.String(), "not authorized") {
		t.Errorf("an object-level refusal carries the revoked-account body: %s", foreign.Body.String())
	}
	if rec := tn.send(t, "DELETE", path(other), tn.adminA, nil); rec.Code != http.StatusNotFound {
		t.Errorf("customer admin deleting another customer's station: got %d, want 404", rec.Code)
	}
	if rec := tn.send(t, "PUT", path(sA.ID), tn.userA, map[string]any{"name": uniqueName("x")}); rec.Code != http.StatusForbidden {
		t.Errorf("customer user renaming: got %d, want 403", rec.Code)
	}
	// A platform admin gets the honest answers.
	if rec := tn.send(t, "PUT", path(999999999), tn.platform, map[string]any{"name": uniqueName("x")}); rec.Code != http.StatusNotFound {
		t.Errorf("platform admin, missing station: got %d, want 404", rec.Code)
	}

	// Delete: refused while a device is on it, allowed once it is empty.
	tn.putDeviceOnStation(t, tn.devA1, sA.ID)
	if rec := tn.send(t, "DELETE", path(sA.ID), tn.adminA, nil); rec.Code != http.StatusConflict {
		t.Errorf("deleting a station that has a device: got %d, want 409", rec.Code)
	}
	tn.env.pool.Exec(context.Background(), `UPDATE devices SET station_id = NULL WHERE device_name = $1`, tn.devA1)
	if rec := tn.send(t, "DELETE", path(sA.ID), tn.adminA, nil); rec.Code != http.StatusNoContent {
		t.Errorf("deleting an empty station: got %d, want 204", rec.Code)
	}
	if rec := tn.send(t, "DELETE", path(sA.ID), tn.platform, nil); rec.Code != http.StatusNotFound {
		t.Errorf("deleting it again: got %d, want 404", rec.Code)
	}
}

func TestStations_AssignDevice(t *testing.T) {
	tn := newTenancy(t)
	sA, _ := tn.apiStation(t, tn.adminA, map[string]any{"name": uniqueName("a")})
	sB, _ := tn.apiStation(t, tn.adminB, map[string]any{"name": uniqueName("b")})
	assign := func(token, device string, body any) *httptest.ResponseRecorder {
		return tn.send(t, "PUT", "/api/devices/"+device+"/station", token, body)
	}
	stationOf := func(device string) *int64 {
		var v *int64
		if err := tn.env.pool.QueryRow(context.Background(),
			`SELECT station_id FROM devices WHERE device_name = $1`, device).Scan(&v); err != nil {
			t.Fatal(err)
		}
		return v
	}

	// A customer admin assigns a device of their own customer to their own station; so may a platform admin.
	if rec := assign(tn.adminA, tn.devA1, map[string]any{"stationId": sA.ID}); rec.Code != http.StatusOK {
		t.Fatalf("assign: %d %s", rec.Code, rec.Body.String())
	}
	if got := stationOf(tn.devA1); got == nil || *got != sA.ID {
		t.Fatalf("station after assign = %v, want %d", got, sA.ID)
	}
	if res := decodeAs[struct {
		Changed bool `json:"changed"`
	}](t, assign(tn.adminA, tn.devA1, map[string]any{"stationId": sA.ID})); res.Changed {
		t.Errorf("assigning the station it is already on reported changed=true")
	}
	if rec := assign(tn.platform, tn.devA2, map[string]any{"stationId": sA.ID}); rec.Code != http.StatusOK {
		t.Errorf("platform admin assign: %d", rec.Code)
	}

	// The request must say what it wants: a missing key is not a null.
	if rec := assign(tn.adminA, tn.devA1, map[string]any{}); rec.Code != http.StatusBadRequest {
		t.Errorf("missing stationId: got %d, want 400", rec.Code)
	}
	if rec := assign(tn.adminA, tn.devA1, map[string]any{"stationId": "x"}); rec.Code != http.StatusBadRequest {
		t.Errorf("non-numeric stationId: got %d, want 400", rec.Code)
	}
	if got := stationOf(tn.devA1); got == nil {
		t.Fatalf("a 400 must leave the assignment alone")
	}

	// The station must belong to the device's customer, whoever asks.
	if rec := assign(tn.platform, tn.devA1, map[string]any{"stationId": sB.ID}); rec.Code != http.StatusBadRequest {
		t.Errorf("platform admin, station of another customer: got %d, want 400", rec.Code)
	}
	if rec := assign(tn.adminA, tn.devA1, map[string]any{"stationId": sB.ID}); rec.Code != http.StatusBadRequest {
		t.Errorf("customer admin, station of another customer: got %d, want 400", rec.Code)
	}
	if rec := assign(tn.adminA, tn.devA1, map[string]any{"stationId": 999999999}); rec.Code != http.StatusBadRequest {
		t.Errorf("unknown station: got %d, want 400", rec.Code)
	}

	// Another customer's device answers like a device that does not exist.
	foreign := assign(tn.adminA, tn.devB1, map[string]any{"stationId": sA.ID})
	ghost := assign(tn.adminA, tn.devGhost, map[string]any{"stationId": sA.ID})
	if foreign.Code != http.StatusNotFound || ghost.Code != http.StatusNotFound || foreign.Body.String() != ghost.Body.String() {
		t.Errorf("foreign vs missing device: %d %q vs %d %q, want identical 404s",
			foreign.Code, foreign.Body.String(), ghost.Code, ghost.Body.String())
	}
	if strings.Contains(foreign.Body.String(), "not authorized") {
		t.Errorf("an object-level refusal carries the revoked-account body: %s", foreign.Body.String())
	}
	if rec := assign(tn.userA, tn.devA1, map[string]any{"stationId": sA.ID}); rec.Code != http.StatusForbidden {
		t.Errorf("customer user assigning: got %d, want 403", rec.Code)
	}
	if rec := assign(tn.platform, tn.devGhost, map[string]any{"stationId": sA.ID}); rec.Code != http.StatusNotFound {
		t.Errorf("platform admin, missing device: got %d, want 404", rec.Code)
	}
	// A device with no customer cannot be on a station.
	if rec := assign(tn.platform, tn.devUnassigned, map[string]any{"stationId": sA.ID}); rec.Code != http.StatusConflict {
		t.Errorf("device without a customer: got %d, want 409", rec.Code)
	}

	// null puts it back to "ungrouped".
	if rec := assign(tn.adminA, tn.devA1, map[string]any{"stationId": nil}); rec.Code != http.StatusOK {
		t.Errorf("clear: %d %s", rec.Code, rec.Body.String())
	}
	if got := stationOf(tn.devA1); got != nil {
		t.Errorf("station after clear = %d, want NULL", *got)
	}

	// Audit: a refusal is recorded as denied; a change is recorded under the device's customer.
	if n := tn.auditCount(t, `action = 'device.station' AND customer_id = $1 AND outcome = 'allowed'`, tn.custA); n < 1 {
		t.Errorf("no allowed device.station audit row for customer A")
	}
	if n := tn.auditCount(t, `action = 'device.station' AND outcome = 'denied'`); n < 1 {
		t.Errorf("no denied device.station audit row")
	}
}

func TestStations_DeleteCustomerWithOnlyStations(t *testing.T) {
	tn := newTenancy(t)
	cust := tn.env.newCustomer("only-stations")
	tn.insertStation(t, cust, uniqueName("s"))
	if rec := tn.send(t, "DELETE", "/api/customers/"+strconv.FormatInt(cust, 10), tn.platform, nil); rec.Code != http.StatusConflict {
		t.Errorf("deleting a customer that has a station: got %d %s, want 409", rec.Code, rec.Body.String())
	}
}

// TestStations_MutationsRecheckTheAdministrator: the early authority check reads the administrator
// before the transaction. An administrator moved to another customer, or disabled, while the request
// is queued must be judged on the current row, not on the stale one; otherwise they commit a change
// in the customer they just left.
func TestStations_MutationsRecheckTheAdministrator(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	lockUser := `SELECT 1 FROM users WHERE id = $1 FOR UPDATE`
	move := func(id int64) func(tx pgx.Tx) {
		return func(tx pgx.Tx) {
			if _, err := tx.Exec(ctx, `UPDATE users SET customer_id = $1 WHERE id = $2`, tn.custB, id); err != nil {
				t.Fatal(err)
			}
		}
	}
	disable := func(id int64) func(tx pgx.Tx) {
		return func(tx pgx.Tx) {
			if _, err := tx.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, id); err != nil {
				t.Fatal(err)
			}
		}
	}
	for _, how := range []struct {
		name   string
		change func(id int64) func(tx pgx.Tx)
	}{{"moved", move}, {"disabled", disable}} {
		t.Run(how.name, func(t *testing.T) {
			stationID := tn.insertStation(t, tn.custA, uniqueName("race"))
			path := "/api/stations/" + strconv.FormatInt(stationID, 10)
			newName := uniqueName("raced-create")
			t.Cleanup(func() { tn.env.pool.Exec(ctx, `DELETE FROM stations WHERE name = $1`, newName) })
			var before *int64
			tn.env.pool.QueryRow(ctx, `SELECT station_id FROM devices WHERE device_name = $1`, tn.devA1).Scan(&before)

			requests := map[string]func(token string) *httptest.ResponseRecorder{
				"create": func(token string) *httptest.ResponseRecorder {
					return tn.send(t, "POST", "/api/stations", token, map[string]any{"name": newName})
				},
				"update": func(token string) *httptest.ResponseRecorder {
					return tn.send(t, "PUT", path, token, map[string]any{"name": uniqueName("renamed")})
				},
				"delete": func(token string) *httptest.ResponseRecorder {
					return tn.send(t, "DELETE", path, token, nil)
				},
				"assign": func(token string) *httptest.ResponseRecorder {
					return tn.send(t, "PUT", "/api/devices/"+tn.devA1+"/station", token, map[string]any{"stationId": stationID})
				},
			}
			for name, request := range requests {
				token, id := tn.env.userIn(control.RoleCustomerAdmin, tn.custA)
				rec, elapsed := holdLockThen(t, tn, lockUser, id,
					func() *httptest.ResponseRecorder { return request(token) }, how.change(id))
				if elapsed < 400*time.Millisecond {
					t.Errorf("%s: finished in %v, it did not wait for the administrator row", name, elapsed)
				}
				// Still an administrator but of another customer, the device is simply no longer theirs:
				// that is an object-level refusal (404). Everything else is about the account (403).
				want := http.StatusForbidden
				if name == "assign" && how.name == "moved" {
					want = http.StatusNotFound
				}
				if rec.Code != want {
					t.Errorf("%s by an administrator who was %s mid-request: got %d %s, want %d", name, how.name, rec.Code, rec.Body.String(), want)
				}
			}

			var stationName string
			if err := tn.env.pool.QueryRow(ctx, `SELECT name FROM stations WHERE id = $1`, stationID).Scan(&stationName); err != nil {
				t.Fatalf("the station was deleted by a stale administrator: %v", err)
			}
			if strings.HasPrefix(stationName, "renamed") {
				t.Errorf("the station was renamed by a stale administrator: %q", stationName)
			}
			var n int
			tn.env.pool.QueryRow(ctx, `SELECT count(*) FROM stations WHERE name = $1`, newName).Scan(&n)
			if n != 0 {
				t.Errorf("a stale administrator created a station")
			}
			var after *int64
			tn.env.pool.QueryRow(ctx, `SELECT station_id FROM devices WHERE device_name = $1`, tn.devA1).Scan(&after)
			if (before == nil) != (after == nil) || (before != nil && *before != *after) {
				t.Errorf("a stale administrator reassigned the device: %v -> %v", before, after)
			}
		})
	}
}

// TestStations_AssignAuthorizesBeforeLockingTheDevice: a customer admin naming another tenant's
// device must be refused without queueing on that device's row, so the answer's timing cannot tell
// a real foreign device from a missing one and one tenant cannot contend on another's rows.
func TestStations_AssignAuthorizesBeforeLockingTheDevice(t *testing.T) {
	tn := newTenancy(t)
	sA := tn.insertStation(t, tn.custA, uniqueName("a"))
	rec, elapsed := holdLockThen(t, tn, `SELECT 1 FROM devices WHERE device_name = $1 FOR UPDATE`, tn.devB1,
		func() *httptest.ResponseRecorder {
			return tn.send(t, "PUT", "/api/devices/"+tn.devB1+"/station", tn.adminA, map[string]any{"stationId": sA})
		},
		func(tx pgx.Tx) {})
	if rec.Code != http.StatusNotFound {
		t.Fatalf("foreign device: got %d %s, want 404", rec.Code, rec.Body.String())
	}
	if elapsed >= 400*time.Millisecond {
		t.Errorf("the refusal took %v: it waited for the foreign device's row lock", elapsed)
	}
}
