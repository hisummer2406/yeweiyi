package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// cloud#61: one device per station, and replacing a device starts the station's data over under the
// new device name. Everything here runs against the real database; the contract is
// docs/station-device-replacement-contract.md.

type conflictBody struct {
	Error             string  `json:"error"`
	Code              string  `json:"code"`
	OccupiedBy        string  `json:"occupiedBy"`
	CurrentDeviceName string  `json:"currentDeviceName"`
	DeviceName        string  `json:"deviceName"`
	PreviousDevice    *string `json:"previousDeviceName"`
	Changed           bool    `json:"changed"`
}

func (tn *tenancy) replace(t *testing.T, token string, station int64, body any) (*httptest.ResponseRecorder, conflictBody) {
	t.Helper()
	rec := tn.send(t, "POST", fmt.Sprintf("/api/stations/%d/replace-device", station), token, body)
	var out conflictBody
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec, out
}

func (tn *tenancy) stationOfDevice(t *testing.T, device string) *int64 {
	t.Helper()
	var v *int64
	if err := tn.env.pool.QueryRow(context.Background(),
		`SELECT station_id FROM devices WHERE device_name = $1`, device).Scan(&v); err != nil {
		t.Fatal(err)
	}
	return v
}

func (tn *tenancy) devicesOnStation(t *testing.T, station int64) []string {
	t.Helper()
	rows, err := tn.env.pool.Query(context.Background(),
		`SELECT device_name FROM devices WHERE station_id = $1 ORDER BY device_name`, station)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			t.Fatal(err)
		}
		out = append(out, n)
	}
	return out
}

// The database refuses a second device on a station on its own, whatever wrote it.
func TestStationOneDevice_DatabaseIsTheGuarantee(t *testing.T) {
	tn := newTenancy(t)
	st := tn.insertStation(t, tn.custA, uniqueName("one"))
	ctx := context.Background()
	if _, err := tn.env.pool.Exec(ctx, `UPDATE devices SET station_id = $1 WHERE device_name = $2`, st, tn.devA1); err != nil {
		t.Fatalf("first device: %v", err)
	}
	_, err := tn.env.pool.Exec(ctx, `UPDATE devices SET station_id = $1 WHERE device_name = $2`, st, tn.devA2)
	wantSQLState(t, err, "23505", "second device on the same station")
	// Many unassigned devices are fine: NULL is not a station.
	if _, err := tn.env.pool.Exec(ctx, `UPDATE devices SET station_id = NULL WHERE device_name = ANY($1)`,
		[]string{tn.devA1, tn.devA2}); err != nil {
		t.Fatalf("clearing: %v", err)
	}
}

// Concurrent ordinary assignments to one empty station: exactly one wins, the rest are told the
// station is occupied, and nothing is a 500.
func TestStationOneDevice_ConcurrentAssignOneWinner(t *testing.T) {
	tn := newTenancy(t)
	st := tn.insertStation(t, tn.custA, uniqueName("race"))
	const n = 8
	devices := make([]string, n)
	for i := range devices {
		devices[i] = fmt.Sprintf("tn-race-%d-%d", i, time.Now().UnixNano())
		tn.env.seedDeviceIn(devices[i], tn.custA)
	}
	codes := make([]int, n)
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := range devices {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			rec := tn.send(t, "PUT", "/api/devices/"+devices[i]+"/station", tn.adminA, map[string]any{"stationId": st})
			codes[i] = rec.Code
		}(i)
	}
	close(start)
	wg.Wait()

	ok, conflict := 0, 0
	for _, c := range codes {
		switch c {
		case http.StatusOK:
			ok++
		case http.StatusConflict:
			conflict++
		}
	}
	if ok != 1 || conflict != n-1 {
		t.Errorf("status codes %v: want exactly one 200 and %d 409", codes, n-1)
	}
	if got := tn.devicesOnStation(t, st); len(got) != 1 {
		t.Errorf("devices on the station = %v, want exactly one", got)
	}
}

func TestStationReplace_SwapsInOneStep(t *testing.T) {
	tn := newTenancy(t)
	st := tn.insertStation(t, tn.custA, uniqueName("swap"))
	tn.putDeviceOnStation(t, tn.devA1, st)
	// newTenancy already binds the employee (userA) to devA1, the OLD device, and to nothing else.

	rec, got := tn.replace(t, tn.adminA, st, map[string]any{"deviceName": tn.devA2, "expectedCurrentDeviceName": tn.devA1})
	if rec.Code != http.StatusOK || !got.Changed || got.DeviceName != tn.devA2 || got.PreviousDevice == nil || *got.PreviousDevice != tn.devA1 {
		t.Fatalf("replace: %d %s", rec.Code, rec.Body.String())
	}
	if s := tn.stationOfDevice(t, tn.devA1); s != nil {
		t.Errorf("old device still on station %d", *s)
	}
	if s := tn.stationOfDevice(t, tn.devA2); s == nil || *s != st {
		t.Errorf("new device station = %v, want %d", s, st)
	}

	// Nothing was copied, renamed or deleted: the old device keeps its customer and its binding, the
	// new device has no binding.
	var cust *int64
	if err := tn.env.pool.QueryRow(context.Background(), `SELECT customer_id FROM devices WHERE device_name=$1`, tn.devA1).Scan(&cust); err != nil || cust == nil || *cust != tn.custA {
		t.Errorf("old device customer = %v (%v), want %d", cust, err, tn.custA)
	}
	var oldBound, newBound int
	tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM user_tank_grants WHERE device_name=$1`, tn.devA1).Scan(&oldBound)
	tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM user_tank_grants WHERE device_name=$1`, tn.devA2).Scan(&newBound)
	if oldBound != 16 || newBound != 0 {
		t.Errorf("bindings old=%d new=%d, want 1 and 0 (authorization is not copied)", oldBound, newBound)
	}
	// The employee therefore cannot reach the new device.
	if rec := tn.get(tn.userA, "/api/devices/"+tn.devA2+"/telemetry/latest"); rec.Code != http.StatusForbidden {
		t.Errorf("employee on the new device: %d, want 403", rec.Code)
	}

	// Retrying the same request is idempotent.
	rec, got = tn.replace(t, tn.adminA, st, map[string]any{"deviceName": tn.devA2, "expectedCurrentDeviceName": tn.devA1})
	if rec.Code != http.StatusOK || got.Changed {
		t.Errorf("retry: %d %s, want 200 changed=false", rec.Code, rec.Body.String())
	}
	if n := tn.auditCount(t, `action = 'station.replace_device' AND outcome = 'allowed' AND customer_id = $1`, tn.custA); n != 1 {
		t.Errorf("replace audit rows = %d, want 1 (the retry changes nothing)", n)
	}
}

func TestStationReplace_Conflicts(t *testing.T) {
	tn := newTenancy(t)
	st := tn.insertStation(t, tn.custA, uniqueName("conf"))
	other := tn.insertStation(t, tn.custA, uniqueName("other"))
	tn.putDeviceOnStation(t, tn.devA1, st)
	devA3 := fmt.Sprintf("tn-a3-%d", time.Now().UnixNano())
	tn.env.seedDeviceIn(devA3, tn.custA)

	check := func(what, token string, body map[string]any, status int, code string) conflictBody {
		t.Helper()
		rec, got := tn.replace(t, token, st, body)
		if rec.Code != status || got.Code != code {
			t.Errorf("%s: %d %s, want %d code=%s", what, rec.Code, rec.Body.String(), status, code)
		}
		return got
	}
	assertUnchanged := func(what string) {
		t.Helper()
		if got := tn.devicesOnStation(t, st); len(got) != 1 || got[0] != tn.devA1 {
			t.Errorf("%s changed the station: %v", what, got)
		}
	}

	// A page that last saw an empty station, or another device, cannot overwrite what is there.
	got := check("stale: page saw empty", tn.adminA, map[string]any{"deviceName": tn.devA2, "expectedCurrentDeviceName": nil}, 409, "station_device_changed")
	if got.CurrentDeviceName != tn.devA1 {
		t.Errorf("currentDeviceName = %q, want %s", got.CurrentDeviceName, tn.devA1)
	}
	check("stale: page saw another device", tn.adminA, map[string]any{"deviceName": tn.devA2, "expectedCurrentDeviceName": devA3}, 409, "station_device_changed")
	assertUnchanged("stale replace")

	// The key must be there: a missing key is a client that never looked.
	check("missing expectedCurrentDeviceName", tn.adminA, map[string]any{"deviceName": tn.devA2}, 400, "invalid_request")
	check("missing deviceName", tn.adminA, map[string]any{"expectedCurrentDeviceName": tn.devA1}, 400, "invalid_request")
	check("new == expected", tn.adminA, map[string]any{"deviceName": tn.devA1, "expectedCurrentDeviceName": tn.devA1}, 400, "same_device")

	// The new device must not be on another station: it is not silently moved.
	tn.putDeviceOnStation(t, tn.devA2, other)
	check("device on another station", tn.adminA, map[string]any{"deviceName": tn.devA2, "expectedCurrentDeviceName": tn.devA1}, 409, "device_in_other_station")
	assertUnchanged("replace with a device on another station")
	if s := tn.stationOfDevice(t, tn.devA2); s == nil || *s != other {
		t.Errorf("the device that was refused was moved: %v", s)
	}

	// Other customers' and unregistered devices.
	check("another customer's device (customer admin)", tn.adminA, map[string]any{"deviceName": tn.devB1, "expectedCurrentDeviceName": tn.devA1}, 404, "device_not_found")
	check("unregistered device (customer admin)", tn.adminA, map[string]any{"deviceName": tn.devGhost, "expectedCurrentDeviceName": tn.devA1}, 404, "device_not_found")
	check("another customer's device (platform admin)", tn.platform, map[string]any{"deviceName": tn.devB1, "expectedCurrentDeviceName": tn.devA1}, 409, "device_customer_mismatch")
	check("device without a customer (platform admin)", tn.platform, map[string]any{"deviceName": tn.devUnassigned, "expectedCurrentDeviceName": tn.devA1}, 409, "device_without_customer")
	check("unregistered device (platform admin)", tn.platform, map[string]any{"deviceName": tn.devGhost, "expectedCurrentDeviceName": tn.devA1}, 404, "device_not_found")
	assertUnchanged("refused replacements")

	// Customer admin: a foreign customer's device and a missing one answer the same.
	a := tn.send(t, "POST", fmt.Sprintf("/api/stations/%d/replace-device", st), tn.adminA, map[string]any{"deviceName": tn.devB1, "expectedCurrentDeviceName": tn.devA1})
	b := tn.send(t, "POST", fmt.Sprintf("/api/stations/%d/replace-device", st), tn.adminA, map[string]any{"deviceName": tn.devGhost, "expectedCurrentDeviceName": tn.devA1})
	if a.Code != b.Code || a.Body.String() != b.Body.String() {
		t.Errorf("foreign vs missing device differ: %d %s vs %d %s", a.Code, a.Body.String(), b.Code, b.Body.String())
	}
}

func TestStationReplace_Authority(t *testing.T) {
	tn := newTenancy(t)
	stA := tn.insertStation(t, tn.custA, uniqueName("auth"))
	stB := tn.insertStation(t, tn.custB, uniqueName("authb"))
	body := map[string]any{"deviceName": tn.devA2, "expectedCurrentDeviceName": nil}

	// A customer user is refused by role: the account-level 403.
	if rec, _ := tn.replace(t, tn.userA, stA, body); rec.Code != http.StatusForbidden || !strings.Contains(rec.Body.String(), "not authorized") {
		t.Errorf("customer user: %d %s, want 403 not authorized", rec.Code, rec.Body.String())
	}
	// Another customer's station is an object-level 404 for a customer admin, identical to a missing one,
	// and never the revoked-account body.
	rec, _ := tn.replace(t, tn.adminA, stB, body)
	ghost, _ := tn.replace(t, tn.adminA, 999999999, body)
	if rec.Code != http.StatusNotFound || ghost.Code != http.StatusNotFound || rec.Body.String() != ghost.Body.String() || strings.Contains(rec.Body.String(), "not authorized") {
		t.Errorf("foreign vs missing station: %d %s vs %d %s", rec.Code, rec.Body.String(), ghost.Code, ghost.Body.String())
	}
	if rec, _ := tn.replace(t, tn.platform, 999999999, body); rec.Code != http.StatusNotFound {
		t.Errorf("platform admin, missing station: %d, want 404", rec.Code)
	}
	if got := tn.devicesOnStation(t, stA); len(got) != 0 {
		t.Errorf("a refused request changed the station: %v", got)
	}
	// An allowed replacement on an empty station (page saw empty) works for a customer admin.
	if rec, got := tn.replace(t, tn.adminA, stA, body); rec.Code != http.StatusOK || !got.Changed || got.PreviousDevice != nil {
		t.Errorf("fill empty station: %d %s", rec.Code, rec.Body.String())
	}
	if n := tn.auditCount(t, `action = 'station.replace_device' AND outcome = 'denied'`); n < 1 {
		t.Errorf("no denied station.replace_device audit row")
	}
}

// After A -> B the station's data is B's alone. Every record table is keyed by device_name, so this
// seeds the SAME alarm id and tank on both devices and a late write for A after the swap, and reads
// them back through the API.
func TestStationReplace_OldDeviceDataNeverReachesNewDevice(t *testing.T) {
	tn := newTenancy(t)
	ctx := context.Background()
	st := tn.insertStation(t, tn.custA, uniqueName("iso"))
	tn.putDeviceOnStation(t, tn.devA1, st)

	// A record read starts a safety margin after the device's customer assignment; the fixture assigned
	// the devices a moment ago, so move that into the past.
	if _, err := tn.env.pool.Exec(ctx, `UPDATE devices SET customer_assigned_at = now() - interval '2 days' WHERE device_name = ANY($1)`,
		[]string{tn.devA1, tn.devA2}); err != nil {
		t.Fatal(err)
	}
	seedAlarm := func(device string, tank int) {
		t.Helper()
		_, err := tn.env.pool.Exec(ctx, `
			INSERT INTO alarm_events (device_name, alarm_id, status, occurred_at, env_ts, payload, customer_id)
			VALUES ($1, 'same-alarm-id', 'ACTIVE', now() - interval '1 hour', $2,
			        jsonb_build_object('alarmId','same-alarm-id','tank',$3::int,'type','HIGH_HIGH_LEVEL',
			                           'severity','CRITICAL','status','ACTIVE','text','t','value',1.0,
			                           'tsMs',(extract(epoch from now())*1000)::bigint),
			        $4)
			ON CONFLICT DO NOTHING`, device, time.Now().UnixMilli(), tank, tn.custA)
		if err != nil {
			t.Fatalf("seed alarm: %v", err)
		}
	}
	t.Cleanup(func() {
		tn.env.pool.Exec(ctx, `DELETE FROM alarm_events WHERE device_name = ANY($1)`, []string{tn.devA1, tn.devA2})
		tn.env.pool.Exec(ctx, `DELETE FROM telemetry_hourly WHERE device_name = ANY($1)`, []string{tn.devA1, tn.devA2})
	})
	alarms := func(device string) int {
		t.Helper()
		rec := tn.get(tn.adminA, "/api/alarms?device="+device)
		if rec.Code != http.StatusOK {
			t.Fatalf("alarms %s: %d %s", device, rec.Code, rec.Body.String())
		}
		var body struct {
			Rows []map[string]any `json:"rows"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err == nil && body.Rows != nil {
			return len(body.Rows)
		}
		var rows []map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &rows); err != nil {
			t.Fatalf("alarms body %s: %v", rec.Body.String(), err)
		}
		return len(rows)
	}

	seedAlarm(tn.devA1, 1)
	if got := alarms(tn.devA1); got != 1 {
		t.Fatalf("before the swap A has %d alarms, want 1", got)
	}

	if rec, _ := tn.replace(t, tn.adminA, st, map[string]any{"deviceName": tn.devA2, "expectedCurrentDeviceName": tn.devA1}); rec.Code != http.StatusOK {
		t.Fatalf("replace: %d %s", rec.Code, rec.Body.String())
	}
	if got := alarms(tn.devA2); got != 0 {
		t.Errorf("new device shows %d of the old device's alarms, want 0 (a real empty state)", got)
	}

	// A late report from the old device, same alarm id and tank number, lands on the old device only.
	seedAlarm(tn.devA1, 1)
	if got := alarms(tn.devA2); got != 0 {
		t.Errorf("a late write for the old device reached the new one: %d", got)
	}
	if got := alarms(tn.devA1); got != 1 {
		t.Errorf("old device alarms = %d, want its own 1 (not deleted, not duplicated)", got)
	}
	// And the new device's own record with the identical id is a separate row.
	seedAlarm(tn.devA2, 1)
	if a, b := alarms(tn.devA1), alarms(tn.devA2); a != 1 || b != 1 {
		t.Errorf("same alarm id on two devices: A=%d B=%d, want 1 and 1", a, b)
	}
}

// Two operators replace the same device at once with different new devices (both pages saw A): one
// wins, the other is told the station's device changed, and the station never holds two.
func TestStationReplace_ConcurrentReplacementsOneWinner(t *testing.T) {
	tn := newTenancy(t)
	st := tn.insertStation(t, tn.custA, uniqueName("rrace"))
	tn.putDeviceOnStation(t, tn.devA1, st)
	const n = 6
	news := make([]string, n)
	for i := range news {
		news[i] = fmt.Sprintf("tn-new-%d-%d", i, time.Now().UnixNano())
		tn.env.seedDeviceIn(news[i], tn.custA)
	}
	codes := make([]int, n)
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := range news {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			rec := tn.send(t, "POST", fmt.Sprintf("/api/stations/%d/replace-device", st), tn.adminA,
				map[string]any{"deviceName": news[i], "expectedCurrentDeviceName": tn.devA1})
			codes[i] = rec.Code
		}(i)
	}
	close(start)
	wg.Wait()
	ok, conflict := 0, 0
	for _, c := range codes {
		switch c {
		case http.StatusOK:
			ok++
		case http.StatusConflict:
			conflict++
		}
	}
	if ok != 1 || conflict != n-1 {
		t.Errorf("status codes %v: want one 200 and %d 409", codes, n-1)
	}
	if got := tn.devicesOnStation(t, st); len(got) != 1 || got[0] == tn.devA1 {
		t.Errorf("devices on the station = %v, want exactly one new device", got)
	}
}
