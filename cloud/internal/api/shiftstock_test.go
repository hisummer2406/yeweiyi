package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"yeweiyi-cloud/internal/ingest"
)

// GET /api/shift-records and /api/stock-records (cloud#85): the shared device fixtures go through the
// real ingest writers into the business database and come back out per (record, tank), with missing
// readings as null, the newest report winning, per-tank authorization and filtering before paging.

const recordsWindow = "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

// sharedFixture returns a shared protocol fixture's envelope ts and the raw JSON under key.
func sharedFixture(t *testing.T, file, key string) (int64, string) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", "fixtures", "protocol", "v1", file))
	if err != nil {
		t.Fatal(err)
	}
	var env struct {
		TS     int64                      `json:"ts"`
		Values map[string]json.RawMessage `json:"values"`
	}
	if err := json.Unmarshal(b, &env); err != nil {
		t.Fatal(err)
	}
	return env.TS, string(env.Values[key])
}

type shiftRow struct {
	ShiftID      string   `json:"shiftId"`
	Status       string   `json:"status"`
	BeginTime    string   `json:"beginTime"`
	EndTime      *string  `json:"endTime"`
	Tank         int      `json:"tank"`
	OilType      *string  `json:"oilType"`
	OilTypeIndex *int     `json:"oilTypeIndex"`
	BeginLevelMm *float64 `json:"beginLevelMm"`
	EndLevelMm   *float64 `json:"endLevelMm"`
	OutVolumeL   *float64 `json:"outVolumeL"`
	OutWeightKg  *float64 `json:"outWeightKg"`
}

type stockRecRow struct {
	RecordID     string   `json:"recordId"`
	Trigger      *string  `json:"trigger"`
	Tank         int      `json:"tank"`
	OilTypeIndex *int     `json:"oilTypeIndex"`
	WaterLevelMm *float64 `json:"waterLevelMm"`
	WeightKg     *float64 `json:"weightKg"`
}

type recordList[T any] struct {
	Rows       []T    `json:"rows"`
	Truncated  bool   `json:"truncated"`
	Unparsable int    `json:"unparsable"`
	SyncStatus string `json:"syncStatus"`
}

func getRecords[T any](t *testing.T, g *grantEnv, url, token string) recordList[T] {
	t.Helper()
	code, body := g.call(t, "GET", url, token, nil)
	if code != http.StatusOK {
		t.Fatalf("GET %s: %d %s", url, code, body)
	}
	var out recordList[T]
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatalf("decode %s: %v", body, err)
	}
	return out
}

func (g *grantEnv) saveShift(t *testing.T, file string) {
	t.Helper()
	ts, raw := sharedFixture(t, file, "shiftEvent")
	if err := ingest.SaveShiftEvent(context.Background(), g.env.queries, g.device, ts, raw); err != nil {
		t.Fatalf("save %s: %v", file, err)
	}
}

func TestShiftRecords_OpenThenClosedFromSharedFixtures(t *testing.T) {
	g := newGrantEnv(t)
	url := "/api/shift-records?device=" + g.device + recordsWindow

	if got := getRecords[shiftRow](t, g, url, g.admin); got.SyncStatus != "UNSUPPORTED" || len(got.Rows) != 0 {
		t.Fatalf("before any shift report: %+v, want UNSUPPORTED and no rows", got)
	}

	g.saveShift(t, "shift_event_open.json")
	open := getRecords[shiftRow](t, g, url, g.admin)
	if len(open.Rows) != 2 || open.SyncStatus == "UNSUPPORTED" {
		t.Fatalf("open shift: %+v, want two tank rows", open)
	}
	r := open.Rows[0]
	if r.ShiftID != "shift-1762146400000" || r.Status != "OPEN" || r.Tank != 1 || r.EndTime != nil {
		t.Errorf("open shift row: %+v", r)
	}
	// The open shift's end and out figures are null, not 0.
	if r.EndLevelMm != nil || r.OutVolumeL != nil || r.OutWeightKg != nil {
		t.Errorf("open shift carries end figures: %+v", r)
	}
	if r.BeginLevelMm == nil || *r.BeginLevelMm != 1520.0 || r.OilTypeIndex == nil || *r.OilTypeIndex != 5 {
		t.Errorf("open shift begin figures: %+v", r)
	}

	g.saveShift(t, "shift_event_closed.json")
	// Replaying the older OPEN report after the CLOSED one must not reopen the shift.
	g.saveShift(t, "shift_event_open.json")
	closed := getRecords[shiftRow](t, g, url, g.admin)
	if len(closed.Rows) != 2 {
		t.Fatalf("closed shift: %d rows", len(closed.Rows))
	}
	c := closed.Rows[1]
	if c.Status != "CLOSED" || c.Tank != 2 || c.EndTime == nil || c.OutWeightKg == nil || *c.OutWeightKg != 705.6 {
		t.Errorf("closed shift tank 2: %+v", c)
	}
	if !strings.HasPrefix(*c.EndTime, "2025-11-03T13:06:40") {
		t.Errorf("endTime = %s", *c.EndTime)
	}
}

func TestShiftRecords_NewestReportReplacesTankSet(t *testing.T) {
	g := newGrantEnv(t)
	url := "/api/shift-records?device=" + g.device + recordsWindow

	// Same shift, same envelope ts: the first of two equal reports stays.
	g.saveShift(t, "shift_event_open.json")
	g.saveShift(t, "shift_event_tank_added.json") // CLOSED: tanks 1 and 3
	rows := getRecords[shiftRow](t, g, url, g.admin).Rows
	if len(rows) != 2 || rows[0].Tank != 1 || rows[1].Tank != 3 {
		t.Fatalf("after tank_added: %+v, want tanks 1 and 3 (tank 2 gone with the newer report)", rows)
	}
	// Tank 3 joined mid-shift: no begin figures, no oil type — never filled in.
	if rows[1].BeginLevelMm != nil || rows[1].OilType != nil || rows[1].OilTypeIndex != nil || rows[1].OutVolumeL != nil {
		t.Errorf("added tank carries invented figures: %+v", rows[1])
	}
}

func TestStockRecords_FromSharedFixture(t *testing.T) {
	g := newGrantEnv(t)
	url := "/api/stock-records?device=" + g.device + recordsWindow
	ts, raw := sharedFixture(t, "stock_record.json", "stockRecord")
	for i := 0; i < 2; i++ { // a replay is idempotent
		if err := ingest.SaveStockRecord(context.Background(), g.env.queries, g.device, ts, raw); err != nil {
			t.Fatal(err)
		}
	}
	got := getRecords[stockRecRow](t, g, url, g.admin)
	if len(got.Rows) != 2 {
		t.Fatalf("stock record rows = %+v, want 2", got.Rows)
	}
	r := got.Rows[1]
	if r.RecordID != "stock-1762153200000" || r.Tank != 2 || r.Trigger == nil || *r.Trigger != "SCHEDULED" {
		t.Errorf("row: %+v", r)
	}
	// A measured 0 stays 0 — only an absent key is null.
	if r.WaterLevelMm == nil || *r.WaterLevelMm != 0 || r.WeightKg == nil || *r.WeightKg != 9063.6 {
		t.Errorf("tank 2 readings: %+v", r)
	}

	// An unreadable report is counted, not hidden, and still proves the device reports this kind.
	if err := ingest.SaveStockRecord(context.Background(), g.env.queries, g.device, time.Now().UnixMilli()-1000, "StockSave{id=1}"); err != nil {
		t.Fatal(err)
	}
	if got := getRecords[stockRecRow](t, g, url, g.admin); got.Unparsable != 1 {
		t.Errorf("unparsable = %d, want 1", got.Unparsable)
	}
}

func TestShiftStockRecords_EmployeeSeesOnlyGrantedTanksFilteredBeforePaging(t *testing.T) {
	g := newGrantEnv(t)
	g.saveShift(t, "shift_event_closed.json")
	ts, raw := sharedFixture(t, "stock_record.json", "stockRecord")
	if err := ingest.SaveStockRecord(context.Background(), g.env.queries, g.device, ts, raw); err != nil {
		t.Fatal(err)
	}
	if code, body := g.set(t, g.platform, g.empID, g.device, 2); code != http.StatusOK {
		t.Fatalf("grant tank 2: %d %s", code, body)
	}

	shiftURL := "/api/shift-records?device=" + g.device + recordsWindow
	stockURL := "/api/stock-records?device=" + g.device + recordsWindow

	// limit=1 with tank 1 sorted first: the employee's first page must still be tank 2, not empty.
	s := getRecords[shiftRow](t, g, shiftURL+"&limit=1", g.emp)
	if len(s.Rows) != 1 || s.Rows[0].Tank != 2 || s.Truncated {
		t.Errorf("employee shift page: %+v, want only tank 2", s)
	}
	k := getRecords[stockRecRow](t, g, stockURL, g.emp)
	if len(k.Rows) != 1 || k.Rows[0].Tank != 2 {
		t.Errorf("employee stock rows: %+v, want only tank 2", k.Rows)
	}

	for _, url := range []string{shiftURL, stockURL} {
		c1, b1 := g.call(t, "GET", url+"&tank=1", g.emp, nil)
		c9, b9 := g.call(t, "GET", url+"&tank=9", g.emp, nil)
		if c1 != http.StatusForbidden || c9 != http.StatusForbidden || b1 != b9 || !strings.Contains(b1, "tank_not_authorized") {
			t.Errorf("%s ungranted tank: %d %s / non-existent %d %s", url, c1, b1, c9, b9)
		}
	}

	// The admin's tank filter is applied before paging.
	a := getRecords[shiftRow](t, g, shiftURL+"&tank=2&limit=1", g.admin)
	if len(a.Rows) != 1 || a.Rows[0].Tank != 2 {
		t.Errorf("admin tank=2: %+v", a.Rows)
	}
}

// TestShiftStockRecords_ThroughThingsBoard drives the real path: device telemetry over ThingsBoard's
// device API, the ingester's live subscription and reconcile, then the API. The OPEN report is sent
// after the CLOSED one, as an offline replay would, and must not win.
func TestShiftStockRecords_ThroughThingsBoard(t *testing.T) {
	env, _, device, token := newStockEnv(t)
	envelope := func(file, key string) []byte {
		ts, raw := sharedFixture(t, file, key)
		return []byte(`{"ts":` + strconv.FormatInt(ts, 10) + `,"values":{"` + key + `":` + raw + `}}`)
	}
	postDeviceTelemetry(t, token, envelope("shift_event_closed.json", "shiftEvent"))
	postDeviceTelemetry(t, token, envelope("shift_event_open.json", "shiftEvent"))
	postDeviceTelemetry(t, token, envelope("stock_record.json", "stockRecord"))

	shiftURL := "/api/shift-records?device=" + device + recordsWindow
	stockURL := "/api/stock-records?device=" + device + recordsWindow
	deadline := time.Now().Add(30 * time.Second)
	for {
		var shifts recordList[shiftRow]
		var stock recordList[stockRecRow]
		json.Unmarshal(env.authed("GET", shiftURL, nil).Body.Bytes(), &shifts)
		json.Unmarshal(env.authed("GET", stockURL, nil).Body.Bytes(), &stock)
		if len(shifts.Rows) == 2 && len(stock.Rows) == 2 {
			for _, r := range shifts.Rows {
				if r.Status != "CLOSED" || r.OutVolumeL == nil {
					t.Errorf("the older OPEN replay won: %+v", r)
				}
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("records never reached the API: shifts %+v, stock %+v", shifts, stock)
		}
		time.Sleep(300 * time.Millisecond)
	}
}

// TestRecords_NewOilAndWeightFields: the optional fields the device added for cloud#85 reach
// /api/oil-in-out and /api/alarms, and are null — not 0 — on reports that lack them.
func TestRecords_NewOilAndWeightFields(t *testing.T) {
	g := newGrantEnv(t)
	ctx := context.Background()
	for _, f := range []string{"stock_event_in_delivered_weight.json", "stock_event_out.json"} {
		ts, raw := sharedFixture(t, f, "stockEvent")
		if err := ingest.SaveStockEvent(ctx, g.env.queries, g.device, ts, raw); err != nil {
			t.Fatal(err)
		}
	}
	for _, f := range []string{"alarm_active_oil_type.json", "alarm_cleared.json"} {
		ts, raw := sharedFixture(t, f, "alarm")
		if _, _, err := ingest.SaveAlarmEvent(ctx, g.env.queries, g.device, ts, raw); err != nil {
			t.Fatal(err)
		}
	}

	_, body := g.call(t, "GET", "/api/oil-in-out?device="+g.device+recordsWindow, g.admin, nil)
	var stock struct {
		Rows []struct {
			Direction         string   `json:"direction"`
			DeliveredWeightKg *float64 `json:"deliveredWeightKg"`
			OilTypeIndex      *int     `json:"oilTypeIndex"`
		} `json:"rows"`
	}
	json.Unmarshal([]byte(body), &stock)
	if len(stock.Rows) != 2 {
		t.Fatalf("oil-in-out: %s", body)
	}
	for _, r := range stock.Rows {
		switch r.Direction {
		case "IN":
			if r.DeliveredWeightKg == nil || *r.DeliveredWeightKg != 15472.8 || r.OilTypeIndex == nil || *r.OilTypeIndex != 1 {
				t.Errorf("IN row: %s", body)
			}
		case "OUT":
			if r.DeliveredWeightKg != nil || r.OilTypeIndex != nil {
				t.Errorf("OUT row from older firmware should carry nulls: %s", body)
			}
		}
	}
	if !strings.Contains(body, `"deliveredWeightKg":null`) {
		t.Errorf("absent deliveredWeightKg must be an explicit null: %s", body)
	}

	_, body = g.call(t, "GET", "/api/alarms?device="+g.device+recordsWindow, g.admin, nil)
	var alarms []struct {
		Status       string  `json:"status"`
		OilType      *string `json:"oilType"`
		OilTypeIndex *int    `json:"oilTypeIndex"`
	}
	json.Unmarshal([]byte(body), &alarms)
	if len(alarms) != 2 {
		t.Fatalf("alarms: %s", body)
	}
	for _, a := range alarms {
		if a.Status == "ACTIVE" && (a.OilType == nil || *a.OilType != "0#" || a.OilTypeIndex == nil || *a.OilTypeIndex != 1) {
			t.Errorf("ACTIVE alarm oil: %s", body)
		}
		if a.Status == "CLEARED" && (a.OilType != nil || a.OilTypeIndex != nil) {
			t.Errorf("CLEARED from older firmware should carry nulls: %s", body)
		}
	}
}
