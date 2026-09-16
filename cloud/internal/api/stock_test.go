package api_test

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/protocol"
)

// /api/oil-in-out reads through to ThingsBoard, so these use a real device publishing real
// stockEvent telemetry. newAlarmEnv (alarms_test.go) already builds exactly the environment needed:
// a router with a live ThingsBoard client plus a connected simulated device bound to the test user.

// publishStock sends one stock event as the device would, and returns it.
func publishStock(t *testing.T, deviceToken string, ev protocol.StockEvent) protocol.StockEvent {
	t.Helper()
	if ev.TSMs == 0 {
		ev.TSMs = time.Now().UnixMilli()
	}
	payload, err := ev.MarshalTelemetry()
	if err != nil {
		t.Fatalf("marshal stock event: %v", err)
	}
	postDeviceTelemetry(t, deviceToken, payload)
	return ev
}

// stockBody mirrors the endpoint's response, which is an object on both the 200 and the 502 path.
// Declared here rather than reusing the unexported handler type so a field rename in the handler
// shows up as a failing assertion instead of quietly renaming both sides at once.
type stockBody struct {
	Rows          []map[string]any `json:"rows"`
	Parsed        int              `json:"parsed"`
	Unparsable    int              `json:"unparsable"`
	LegacySkipped int              `json:"legacySkipped"`
	Folded        int              `json:"folded"`
	Truncated     bool             `json:"truncated"`
	Partial       bool             `json:"partial"`
	Error         string           `json:"error"`
	Code          string           `json:"code"`
	Device        string           `json:"device"`
	Sample        string           `json:"sample"`
	LegacyCutoff  string           `json:"legacyCutoff"`
}

func decodeStock(t *testing.T, body []byte) stockBody {
	t.Helper()
	var out stockBody
	if err := json.Unmarshal(body, &out); err != nil {
		t.Fatalf("decode: %v (body %s)", err, body)
	}
	return out
}

func fetchStock(t *testing.T, env *testEnv, path string) []map[string]any {
	t.Helper()
	rec := env.authed("GET", path, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET %s: expected 200, got %d: %s", path, rec.Code, rec.Body.String())
	}
	return decodeStock(t, rec.Body.Bytes()).Rows
}

func TestListOilInOut_ReadsThroughToThingsBoard(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now().UnixMilli()
	sent := publishStock(t, token, protocol.StockEvent{
		EventID:               fmt.Sprintf("in-1-%d", now),
		Tank:                  1,
		Direction:             protocol.StockIn,
		OilType:               "0号柴油",
		BeginTimeMs:           now - 3_600_000,
		EndTimeMs:             now,
		BeginVolumeL:          6030.0,
		EndVolumeL:            24030.0,
		BeginLevelMm:          402.0,
		EndLevelMm:            1642.5,
		NetVolumeL:            18000.0,
		DuringDeliveryVolumeL: 420.0,
		TSMs:                  now,
	})

	path := "/api/oil-in-out?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

	var got []map[string]any
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if got = fetchStock(t, env, path); len(got) > 0 {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if len(got) == 0 {
		t.Fatal("the published stock event never appeared through /api/oil-in-out")
	}

	r := got[0]
	if r["eventId"] != sent.EventID {
		t.Errorf("eventId = %v, want %s", r["eventId"], sent.EventID)
	}
	if r["direction"] != protocol.StockIn || r["oilType"] != "0号柴油" {
		t.Errorf("record mangled in transit: %v", r)
	}

	// deliveredVolumeL is the number people reconcile against: the tank gained 18000 L, but the
	// pumps dispensed 420 L during the delivery, so 18420 L actually arrived. Reporting the tank's
	// net change as the delivered quantity understates every delivery made while selling.
	if d, _ := r["deliveredVolumeL"].(float64); d != 18420.0 {
		t.Errorf("deliveredVolumeL = %v, want 18420 (net 18000 + 420 dispensed during delivery)", r["deliveredVolumeL"])
	}
	if n, _ := r["netVolumeL"].(float64); n != 18000.0 {
		t.Errorf("netVolumeL = %v, want 18000", r["netVolumeL"])
	}
	// The device omits `source` entirely for automatic detection (see protocol.StockEvent.Source);
	// the API must not surface that as a missing field, so a client never has to treat "absent" as a
	// third state alongside AUTO/MANUAL.
	if r["source"] != protocol.SourceAuto {
		t.Errorf("source = %v, want %q (normalised from the omitted wire field)", r["source"], protocol.SourceAuto)
	}

	if _, err := time.Parse(time.RFC3339, fmt.Sprint(r["beginTime"])); err != nil {
		t.Errorf("beginTime = %v, not RFC3339: %v", r["beginTime"], err)
	}

	assertNoLegacyTelemetryTables(t, env)
}

// TestListOilInOut_SurfacesManualSource covers BuLuFragment's backfill path (zhongchengyeweiyi#42
// 待办2): the device sets source=MANUAL on the wire, and the API must pass it through unchanged
// rather than normalising it away like the omitted/AUTO case above.
func TestListOilInOut_SurfacesManualSource(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now().UnixMilli()
	sent := publishStock(t, token, protocol.StockEvent{
		EventID:     fmt.Sprintf("in-1-%d", now),
		Tank:        1,
		Direction:   protocol.StockIn,
		BeginTimeMs: now - 3_600_000,
		EndTimeMs:   now,
		NetVolumeL:  1000.0,
		Source:      protocol.SourceManual,
		TSMs:        now,
	})

	path := "/api/oil-in-out?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		got := fetchStock(t, env, path)
		for _, r := range got {
			if r["eventId"] != sent.EventID {
				continue
			}
			if r["source"] != protocol.SourceManual {
				t.Errorf("source = %v, want %q", r["source"], protocol.SourceManual)
			}
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatal("the manually-backfilled stock event never appeared through /api/oil-in-out")
}

// TestListOilInOut_OutboundDeliveredVolumeIsPositive pins the sign handling: netVolumeL is
// tank-relative and therefore negative for a withdrawal, but the quantity moved is not.
func TestListOilInOut_OutboundDeliveredVolumeIsPositive(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now().UnixMilli()
	publishStock(t, token, protocol.StockEvent{
		EventID:     fmt.Sprintf("out-1-%d", now),
		Tank:        1,
		Direction:   protocol.StockOut,
		OilType:     "0号柴油",
		BeginTimeMs: now - 600_000,
		EndTimeMs:   now,
		NetVolumeL:  -5000.0,
		TSMs:        now,
	})

	path := "/api/oil-in-out?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		got := fetchStock(t, env, path)
		if len(got) > 0 {
			if d, _ := got[0]["deliveredVolumeL"].(float64); d != 5000.0 {
				t.Errorf("deliveredVolumeL = %v, want 5000 (positive quantity moved out)", got[0]["deliveredVolumeL"])
			}
			if n, _ := got[0]["netVolumeL"].(float64); n != -5000.0 {
				t.Errorf("netVolumeL = %v, want -5000 (tank-relative)", got[0]["netVolumeL"])
			}
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatal("the outbound stock event never appeared")
}

// realDeviceStockEventValue is what a device on the *current* firmware publishes for `stockEvent`:
// DetailInoutRecord.toString(), a Java debug string. Transcribed field-for-field from
// DetailInoutRecord.java:235-251 at zhongchengyeweiyi `d0c3bce` — the misspelt `beginHeith`, the
// quoted local-time strings, and the fact that toString() omits half the record's own columns
// (oilType, the water volumes, the weights, saleVolume, duringDelivery) are all faithfully what the
// device sends today, not an approximation. Publish path: InOilManager:214 → BackService:72 →
// ThingsBoardManager:168 reportRawEvent("stockEvent", …).
const realDeviceStockEventValue = "DetailInoutRecord{oilcanId=1, beginHeith=402.0, endHeight=1642.5, " +
	"beginVolume=6030.0, endVolume=24030.0, beginTime='2026-09-04 09:00:00', endTime='2026-09-04 10:00:00', " +
	"beginTcVolume=6010.0, endTcVolume=23990.0, beginWater=18.3, endWater=18.5, beginTemp=22.1, endTemp=23.4}"

func postRawStockEvent(t *testing.T, deviceToken string, tsMs int64, value string) {
	t.Helper()
	// Hand-written JSON literal, not our own marshalled structs: the point is to reproduce what
	// something *else* puts on the wire, and a struct round-trip could only ever agree with itself.
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("encode raw value: %v", err)
	}
	payload := fmt.Sprintf(`{"ts":%d,"values":{"stockEvent":%s}}`, tsMs, encoded)
	postDeviceTelemetry(t, deviceToken, []byte(payload))
}

const stockWindow = "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

// TestListOilInOut_RealDeviceFormatIsNotSilentlyReportedAsEmpty is half of the pair the PR #11
// review asked for: the format real devices publish *today*.
//
// Before this change the endpoint skipped what it could not parse and answered 200 with `[]`. For a
// depot whose every delivery is in that format, that is an empty 出入库 history rendered as a
// successful answer — indistinguishable, to the operator and to the mini-program, from "no oil
// moved". The records are not missing; they are in ThingsBoard, unreadable. The endpoint now says so.
func TestListOilInOut_RealDeviceFormatIsNotSilentlyReportedAsEmpty(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now().UnixMilli()
	postRawStockEvent(t, token, now-1000, realDeviceStockEventValue)

	path := "/api/oil-in-out?device=" + device + stockWindow

	var rec *httptest.ResponseRecorder
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec = env.authed("GET", path, nil)
		if rec.Code != http.StatusOK {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}

	if rec.Code == http.StatusOK {
		t.Fatalf("unparseable device records were served as a successful empty list: %d %s",
			rec.Code, rec.Body.String())
	}
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502; body %s", rec.Code, rec.Body.String())
	}

	body := decodeStock(t, rec.Body.Bytes())
	if body.Code != api.StockEventFormatUnsupported {
		t.Errorf("code = %q, want %q", body.Code, api.StockEventFormatUnsupported)
	}
	if body.Device != device {
		t.Errorf("device = %q, want %q", body.Device, device)
	}
	if body.Unparsable < 1 {
		t.Errorf("unparsable = %d, want at least 1", body.Unparsable)
	}
	if body.Parsed != 0 || len(body.Rows) != 0 {
		t.Errorf("parsed = %d / %d records, want 0 — nothing in this window is parseable",
			body.Parsed, len(body.Rows))
	}
	// The sample is what makes the 502 actionable rather than merely honest: it names the format.
	if !strings.Contains(body.Sample, "DetailInoutRecord") {
		t.Errorf("sample = %q, want the offending raw value", body.Sample)
	}
	if body.Rows == nil {
		t.Error("records must be an array even when empty, so a client can render it unconditionally")
	}
}

// TestListOilInOut_ValidJSONFromTheSharedFixtureIsServed is the other half: the format the protocol
// actually defines. It publishes the *shared cross-repo fixture bytes verbatim* rather than
// marshalling this repo's own structs, so a field this backend silently renames would fail here
// instead of agreeing with itself — the same rule internal/protocol/contract_test.go follows, and
// the reason the fixture exists (issue #7).
func TestListOilInOut_ValidJSONFromTheSharedFixtureIsServed(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	payload := stockFixturePayload(t, "stock_event_in.json", time.Now().UnixMilli())
	postDeviceTelemetry(t, token, payload)

	path := "/api/oil-in-out?device=" + device + stockWindow

	var got []map[string]any
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec := env.authed("GET", path, nil)
		if rec.Code == http.StatusBadGateway {
			t.Fatalf("the fixture the protocol is defined by did not parse: %s", rec.Body.String())
		}
		if rec.Code != http.StatusOK {
			t.Fatalf("GET %s: %d %s", path, rec.Code, rec.Body.String())
		}
		got = decodeStock(t, rec.Body.Bytes()).Rows
		if len(got) > 0 {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if len(got) == 0 {
		t.Fatal("the fixture stock event never appeared through /api/oil-in-out")
	}

	r := got[0]
	if r["eventId"] != "in-1-1756713600000" || r["direction"] != protocol.StockIn {
		t.Errorf("fixture record mangled in transit: %v", r)
	}
	if v, _ := r["netVolumeL"].(float64); v != 18000.0 {
		t.Errorf("netVolumeL = %v, want 18000 (from the fixture)", r["netVolumeL"])
	}
	if v, _ := r["deliveredVolumeL"].(float64); v != 18420.0 {
		t.Errorf("deliveredVolumeL = %v, want 18420 (18000 net + 420 dispensed during delivery)", r["deliveredVolumeL"])
	}
}

// TestListOilInOut_MixedWindowKeepsTheParsedRecords pins the partial case, which is what a fleet
// mid-upgrade actually looks like: one device-firmware generation on each format, or one device
// whose replay queue still holds pre-upgrade records. The answer must be neither "200 with the good
// ones" (which hides the missing ones) nor "502 with nothing" (which throws away readable data).
func TestListOilInOut_MixedWindowKeepsTheParsedRecords(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now().UnixMilli()
	postRawStockEvent(t, token, now-2000, realDeviceStockEventValue)
	postDeviceTelemetry(t, token, stockFixturePayload(t, "stock_event_in.json", now))

	path := "/api/oil-in-out?device=" + device + stockWindow

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec := env.authed("GET", path, nil)
		if rec.Code == http.StatusBadGateway {
			body := decodeStock(t, rec.Body.Bytes())
			if body.Parsed == 0 {
				// The valid record has not landed in ThingsBoard yet; keep waiting.
				time.Sleep(300 * time.Millisecond)
				continue
			}
			if body.Unparsable != 1 || body.Parsed != 1 || len(body.Rows) != 1 {
				t.Fatalf("want 1 unparsable + 1 parsed record, got %d/%d (%d records): %s",
					body.Unparsable, body.Parsed, len(body.Rows), rec.Body.String())
			}
			if body.Rows[0]["eventId"] != "in-1-1756713600000" {
				t.Errorf("the readable record was not the one returned: %v", body.Rows[0])
			}
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatal("a window holding one unreadable and one valid record never produced the degraded response")
}

// stockFixturePayload returns one cross-repo fixture's bytes as a device telemetry payload, with
// only the documentation `_comment` key removed and the envelope `ts` re-stamped so the record lands
// inside the test's own window. The `values` object — the part that is the contract — is passed
// through untouched.
func stockFixturePayload(t *testing.T, name string, tsMs int64) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "fixtures", "protocol", "v1", name))
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	var doc map[string]json.RawMessage
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("fixture %s is not JSON: %v", name, err)
	}
	values, ok := doc["values"]
	if !ok {
		t.Fatalf("fixture %s has no values object", name)
	}
	return []byte(fmt.Sprintf(`{"ts":%d,"values":%s}`, tsMs, values))
}

// The two tests below are the "新旧格式长期共存" case raised in review of PR #12.
//
// The observation behind it: shipping the device-side JSON fix converts nothing already stored.
// Every record ThingsBoard wrote before the upgrade stays a debug string forever, so a flat
// "unparseable ⇒ 502" would keep failing every window that reaches back past the upgrade — for the
// rest of the deployment's life. A permanently broken history endpoint is not an improvement on the
// empty list it replaced.
//
// `stock.legacyFormatBefore` is the only thing that can tell the two apart, because the payloads
// cannot: a pre-upgrade record and a device that never upgraded produce byte-identical values. The
// record's own timestamp is the discriminator.

// TestListOilInOut_ReplayedPreUpgradeRecordIsClassifiedByItsOwnTimestamp covers the post-upgrade
// steady state, and specifically the path review of PR #12 asked about: **a record generated before
// the upgrade but published after it**, drained from the device's offline queue.
//
// That is not an edge case, it is the expected sequence — an OTA upgrade reboots the device, and
// whatever the queue was holding is replayed once it reconnects. If classification used the moment
// ThingsBoard received the value, every one of those records would read as a device that failed to
// upgrade, and the endpoint would 502 exactly when it was supposed to stop.
//
// It does not, because the device stamps the envelope `ts` when the record is *generated* and the
// replay preserves it. Checked in the device source rather than assumed: reportRawEvent
// (ThingsBoardManager.java:168-176) builds `{"ts": System.currentTimeMillis(), "values": …}` and
// enqueues that serialized string; PendingAlarmStore.drainAndSend (:87-99) republishes the string
// verbatim, with no re-stamping anywhere on the path.
//
// This test reproduces exactly that: it publishes **now** a payload whose envelope ts is hours old,
// which is byte-for-byte what a queue drain does, and asserts the record is treated as history.
func TestListOilInOut_ReplayedPreUpgradeRecordIsClassifiedByItsOwnTimestamp(t *testing.T) {
	now := time.Now()
	// The cutoff sits between the two records: the debug string was generated before the upgrade,
	// the JSON one after it. Both are published in the next few milliseconds — the replay case.
	cutoff := now.Add(-1 * time.Hour)

	env, _, device, token := newStockEnv(t, func(d *api.Deps) {
		d.StockLegacyFormatBefore = cutoff
	})

	// Generated 2h before the cutoff, published right now: the offline-queue replay.
	postRawStockEvent(t, token, cutoff.Add(-2*time.Hour).UnixMilli(), realDeviceStockEventValue)
	postDeviceTelemetry(t, token, stockFixturePayload(t, "stock_event_in.json", now.UnixMilli()))

	path := "/api/oil-in-out?device=" + device + stockWindow

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec := env.authed("GET", path, nil)
		if rec.Code == http.StatusBadGateway {
			t.Fatalf("a record from before the configured upgrade cutoff was treated as current "+
				"breakage; this endpoint would 502 forever on historical windows: %s", rec.Body.String())
		}
		if rec.Code != http.StatusOK {
			t.Fatalf("GET %s: %d %s", path, rec.Code, rec.Body.String())
		}
		body := decodeStock(t, rec.Body.Bytes())
		if body.Parsed == 0 {
			time.Sleep(300 * time.Millisecond)
			continue
		}
		if body.LegacySkipped != 1 {
			t.Errorf("legacySkipped = %d, want 1 — a record replayed late from the device's offline "+
				"queue must be classified by when it was generated, not when it arrived; body %s",
				body.LegacySkipped, rec.Body.String())
		}
		if body.Unparsable != 0 {
			t.Errorf("unparsable = %d, want 0 — nothing is broken now", body.Unparsable)
		}
		if len(body.Rows) != 1 || body.Rows[0]["eventId"] != "in-1-1756713600000" {
			t.Errorf("the readable record was not returned: %s", rec.Body.String())
		}
		return
	}
	t.Fatal("the post-upgrade window never produced a successful response")
}

// TestListOilInOut_UnreadableRecordAfterTheCutoffStillFails is the other half, and the reason the
// cutoff is a boundary rather than a blanket amnesty: a device that should have been upgraded and
// was not is a live fault someone has to go and look at, and it must not be written off as history.
func TestListOilInOut_UnreadableRecordAfterTheCutoffStillFails(t *testing.T) {
	now := time.Now()
	cutoff := now.Add(-24 * time.Hour) // the fleet was upgraded yesterday...

	env, _, device, token := newStockEnv(t, func(d *api.Deps) {
		d.StockLegacyFormatBefore = cutoff
	})

	// ...and this device is still publishing the old format today.
	postRawStockEvent(t, token, now.UnixMilli()-1000, realDeviceStockEventValue)

	path := "/api/oil-in-out?device=" + device + stockWindow

	var rec *httptest.ResponseRecorder
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec = env.authed("GET", path, nil)
		if rec.Code != http.StatusOK {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("a device still publishing the pre-V1 format after the upgrade cutoff was not "+
			"reported as a fault: %d %s", rec.Code, rec.Body.String())
	}
	body := decodeStock(t, rec.Body.Bytes())
	if body.Unparsable != 1 || body.LegacySkipped != 0 {
		t.Errorf("unparsable = %d, legacySkipped = %d; want 1 and 0 — a record written after the "+
			"cutoff is current breakage, not history", body.Unparsable, body.LegacySkipped)
	}
	// The echoed cutoff is what lets an operator see, from the response alone, which side of the
	// boundary the endpoint thought it was on.
	if body.LegacyCutoff == "" {
		t.Error("legacyCutoff should be echoed when one is configured")
	}
}

// TestListOilInOut_UnconfiguredCutoffSaysSoInTheError pins the default. A deployment nobody has told
// about a fleet upgrade must treat every unreadable record as current breakage — but the error
// should point at the setting, or the first person to hit this has no way to know it exists.
func TestListOilInOut_UnconfiguredCutoffSaysSoInTheError(t *testing.T) {
	env, _, device, token := newStockEnv(t) // no cutoff configured

	postRawStockEvent(t, token, time.Now().UnixMilli()-1000, realDeviceStockEventValue)

	path := "/api/oil-in-out?device=" + device + stockWindow

	var rec *httptest.ResponseRecorder
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec = env.authed("GET", path, nil)
		if rec.Code != http.StatusOK {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502; body %s", rec.Code, rec.Body.String())
	}
	body := decodeStock(t, rec.Body.Bytes())
	if body.LegacyCutoff != "" {
		t.Errorf("legacyCutoff = %q, want empty when unconfigured", body.LegacyCutoff)
	}
	if !strings.Contains(body.Error, "stock.legacyFormatBefore") {
		t.Errorf("the error must name the setting that resolves it; got %q", body.Error)
	}
}

// TestListOilInOut_RecordWithoutAnEnvelopeTimestampCountsAsCurrent pins the boundary of the design
// above, so that it is a known limitation rather than something discovered in the field.
//
// The whole classification rests on the device stamping an envelope `ts`. Today's firmware does
// (see the test above), but nothing in this backend can enforce that, and a device publishing
// `{"values": {...}}` with no `ts` gets ThingsBoard's arrival time instead. A record generated
// before the upgrade and replayed after it would then be indistinguishable from a device that never
// upgraded — and the safe reading of "indistinguishable" is the loud one, so it counts as current
// breakage and returns 502.
//
// That is the correct failure direction (a real fault is never written off as history), and it is
// why docs/protocol-v1-stock-event.md §3 states the envelope ts as a protocol requirement on the
// device rather than leaving it as an incidental property of the current implementation.
func TestListOilInOut_RecordWithoutAnEnvelopeTimestampCountsAsCurrent(t *testing.T) {
	cutoff := time.Now().Add(-1 * time.Hour)

	env, _, device, token := newStockEnv(t, func(d *api.Deps) {
		d.StockLegacyFormatBefore = cutoff
	})

	// No "ts" key at all — ThingsBoard stamps arrival, which is after the cutoff.
	value, err := json.Marshal(realDeviceStockEventValue)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	postDeviceTelemetry(t, token, []byte(`{"stockEvent":`+string(value)+`}`))

	path := "/api/oil-in-out?device=" + device + stockWindow

	var rec *httptest.ResponseRecorder
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec = env.authed("GET", path, nil)
		if rec.Code != http.StatusOK {
			break
		}
		time.Sleep(300 * time.Millisecond)
	}
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("an unreadable record with no envelope ts must fall on the safe side (502): %d %s",
			rec.Code, rec.Body.String())
	}
	body := decodeStock(t, rec.Body.Bytes())
	if body.Unparsable != 1 || body.LegacySkipped != 0 {
		t.Errorf("unparsable = %d, legacySkipped = %d; want 1 and 0 — with no generation time to go "+
			"on, an unreadable record must not be written off as history",
			body.Unparsable, body.LegacySkipped)
	}
}

// TestListOilInOut_ResentEventIsFoldedToTheLatest pins issue #13: the device re-sends a stockEvent
// under the same eventId once the operator fills in duringDeliveryVolumeL. Both telemetry points
// exist in ThingsBoard, but the delivery must be listed once, with the corrected figure.
//
// Both reports carry the SAME payload tsMs (the record-close time) and differ only in the envelope
// ts, so this fails if the fold ever starts keying on tsMs — which is not a revision counter.
func TestListOilInOut_ResentEventIsFoldedToTheLatest(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now().UnixMilli()
	ev := protocol.StockEvent{
		EventID:      fmt.Sprintf("in-1-%d", now),
		Tank:         1,
		Direction:    protocol.StockIn,
		BeginTimeMs:  now - 3_600_000,
		EndTimeMs:    now - 60_000,
		BeginVolumeL: 6030.0,
		EndVolumeL:   24030.0,
		NetVolumeL:   18000.0,
		TSMs:         now - 60_000,
	}
	post := func(envelopeTS int64, e protocol.StockEvent) {
		payload, err := json.Marshal(map[string]any{
			"ts": envelopeTS, "values": map[string]any{protocol.KeyStockEvent: e},
		})
		if err != nil {
			t.Fatal(err)
		}
		postDeviceTelemetry(t, token, payload)
	}
	post(now-2000, ev)
	ev.DuringDeliveryVolumeL = 420.0
	post(now, ev)

	path := "/api/oil-in-out?device=" + device + stockWindow
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec := env.authed("GET", path, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("GET %s: %d %s", path, rec.Code, rec.Body.String())
		}
		b := decodeStock(t, rec.Body.Bytes())
		if b.Folded == 1 {
			if len(b.Rows) != 1 {
				t.Fatalf("rows = %d, want 1 after folding: %v", len(b.Rows), b.Rows)
			}
			if d, _ := b.Rows[0]["deliveredVolumeL"].(float64); d != 18420.0 {
				t.Errorf("deliveredVolumeL = %v, want 18420 from the corrected re-send", d)
			}
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatal("the re-sent event was never folded into one row")
}

// TestListOilInOut_CorrectionOutsideTheWindowIsStillResolved pins the boundary case: ThingsBoard
// selects points by envelope ts, so a window that ends between a report and its later correction
// would list the stale original, and the next window would list the corrected row again — one
// delivery counted twice across a window boundary.
func TestListOilInOut_CorrectionOutsideTheWindowIsStillResolved(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now()
	original := now.Add(-50 * time.Hour)
	correction := now.Add(-40 * time.Hour)
	ev := protocol.StockEvent{
		EventID:      fmt.Sprintf("in-1-%d", original.UnixMilli()),
		Tank:         1,
		Direction:    protocol.StockIn,
		BeginTimeMs:  original.Add(-time.Hour).UnixMilli(),
		EndTimeMs:    original.UnixMilli(),
		BeginVolumeL: 6030.0,
		EndVolumeL:   24030.0,
		NetVolumeL:   18000.0,
		TSMs:         original.UnixMilli(),
	}
	post := func(envelopeTS time.Time, e protocol.StockEvent) {
		payload, err := json.Marshal(map[string]any{
			"ts": envelopeTS.UnixMilli(), "values": map[string]any{protocol.KeyStockEvent: e},
		})
		if err != nil {
			t.Fatal(err)
		}
		postDeviceTelemetry(t, token, payload)
	}
	post(original, ev)
	ev.DuringDeliveryVolumeL = 420.0
	post(correction, ev)

	window := func(from, to time.Time) string {
		return "/api/oil-in-out?device=" + device + "&beginTime=" + from.UTC().Format(time.RFC3339) +
			"&endTime=" + to.UTC().Format(time.RFC3339)
	}
	get := func(path string, want func(stockBody) bool) stockBody {
		var b stockBody
		deadline := time.Now().Add(20 * time.Second)
		for time.Now().Before(deadline) {
			rec := env.authed("GET", path, nil)
			if rec.Code != http.StatusOK {
				t.Fatalf("GET %s: %d %s", path, rec.Code, rec.Body.String())
			}
			if b = decodeStock(t, rec.Body.Bytes()); want(b) {
				return b
			}
			time.Sleep(300 * time.Millisecond)
		}
		t.Fatalf("GET %s: never reached the expected state; last body %+v", path, b)
		return b
	}

	// Window A holds only the original (50h ago); the correction (40h ago) lies after its end.
	a := get(window(now.Add(-72*time.Hour), now.Add(-48*time.Hour)), func(b stockBody) bool { return len(b.Rows) == 1 && b.Folded == 1 })
	if d, _ := a.Rows[0]["deliveredVolumeL"].(float64); d != 18420.0 {
		t.Errorf("window ending before the correction: deliveredVolumeL = %v, want the corrected 18420", d)
	}

	// Window B holds only the correction (by report time). The movement closed in the earlier
	// window, so it must not be listed a second time.
	b := get(window(now.Add(-45*time.Hour), now.Add(-35*time.Hour)), func(stockBody) bool { return true })
	if len(b.Rows) != 0 {
		t.Errorf("window containing only the correction listed %d rows, want 0 (already counted in the earlier window): %v", len(b.Rows), b.Rows)
	}
}

// TestListOilInOut_LimitCutsRowsNotRevisions pins that `limit` applies to movements, after the fold:
// a correction sitting beyond the first `limit` raw points must still be applied to its original, and
// a cut must be announced rather than silent.
func TestListOilInOut_LimitCutsRowsNotRevisions(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now()
	post := func(envelopeTS time.Time, e protocol.StockEvent) {
		payload, err := json.Marshal(map[string]any{
			"ts": envelopeTS.UnixMilli(), "values": map[string]any{protocol.KeyStockEvent: e},
		})
		if err != nil {
			t.Fatal(err)
		}
		postDeviceTelemetry(t, token, payload)
	}
	mk := func(at time.Time, net float64) protocol.StockEvent {
		return protocol.StockEvent{
			EventID: fmt.Sprintf("in-1-%d", at.UnixMilli()), Tank: 1, Direction: protocol.StockIn,
			BeginTimeMs: at.Add(-time.Hour).UnixMilli(), EndTimeMs: at.UnixMilli(),
			NetVolumeL: net, TSMs: at.UnixMilli(),
		}
	}
	first, second := mk(now.Add(-30*time.Hour), 1000), mk(now.Add(-29*time.Hour), 2000)
	post(now.Add(-30*time.Hour), first)
	post(now.Add(-29*time.Hour), second)
	first.DuringDeliveryVolumeL = 500
	post(now.Add(-1*time.Hour), first) // correction of the OLDER movement, generated last

	path := "/api/oil-in-out?device=" + device + "&limit=1&beginTime=" +
		now.Add(-48*time.Hour).UTC().Format(time.RFC3339) + "&endTime=" + now.Add(-10*time.Hour).UTC().Format(time.RFC3339)
	// The three points become visible one by one, so poll until the whole expected state is there
	// rather than asserting on the first response that happens to be truncated.
	var last stockBody
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec := env.authed("GET", path, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("GET %s: %d %s", path, rec.Code, rec.Body.String())
		}
		last = decodeStock(t, rec.Body.Bytes())
		// The default order is ascending by time, so the cut keeps the OLDER movement — and it must
		// carry the correction that was generated after the newer one was read.
		if last.Truncated && len(last.Rows) == 1 {
			if d, _ := last.Rows[0]["deliveredVolumeL"].(float64); d == 1500.0 {
				return
			}
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("never reached truncated=true with the older movement corrected to 1500; last: %+v", last)
}

// TestListOilInOut_UnreadableRecordAfterTheWindowMarksItPartial pins that a malformed record after
// the window (possibly a correction whose eventId cannot be read) neither fails the window nor
// passes silently: the original is listed, flagged partial.
func TestListOilInOut_UnreadableRecordAfterTheWindowMarksItPartial(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now()
	original := now.Add(-50 * time.Hour)
	publishStock(t, token, protocol.StockEvent{
		EventID:      fmt.Sprintf("in-1-%d", original.UnixMilli()),
		Tank:         1,
		Direction:    protocol.StockIn,
		BeginTimeMs:  original.Add(-time.Hour).UnixMilli(),
		EndTimeMs:    original.UnixMilli(),
		BeginVolumeL: 6030.0,
		EndVolumeL:   24030.0,
		NetVolumeL:   18000.0,
		TSMs:         original.UnixMilli(),
	})
	postRawStockEvent(t, token, now.Add(-40*time.Hour).UnixMilli(), `{"eventId":`)

	path := "/api/oil-in-out?device=" + device + "&beginTime=" + now.Add(-72*time.Hour).UTC().Format(time.RFC3339) +
		"&endTime=" + now.Add(-48*time.Hour).UTC().Format(time.RFC3339)
	var b stockBody
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		rec := env.authed("GET", path, nil)
		if rec.Code != http.StatusOK {
			t.Fatalf("GET %s: %d %s", path, rec.Code, rec.Body.String())
		}
		if b = decodeStock(t, rec.Body.Bytes()); len(b.Rows) == 1 && b.Partial {
			if b.Unparsable != 0 {
				t.Errorf("unparsable = %d, want 0 (outside the window)", b.Unparsable)
			}
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("never saw 1 row with partial=true; last body %+v", b)
}
