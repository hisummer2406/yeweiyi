package api_test

import (
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

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

func fetchStock(t *testing.T, env *testEnv, path string) []map[string]any {
	t.Helper()
	rec := env.authed("GET", path, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET %s: expected 200, got %d: %s", path, rec.Code, rec.Body.String())
	}
	var out []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("decode: %v (body %s)", err, rec.Body.String())
	}
	return out
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

	if _, err := time.Parse(time.RFC3339, fmt.Sprint(r["beginTime"])); err != nil {
		t.Errorf("beginTime = %v, not RFC3339: %v", r["beginTime"], err)
	}

	assertNoLegacyTelemetryTables(t, env)
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

// TestListOilInOut_SkipsUnparseableRecords covers the state real devices are in right now: they
// publish DetailInoutRecord.toString(), a Java debug string, not JSON (zhongchengyeweiyi#39). One
// bad record must not blank the operator's screen.
func TestListOilInOut_SkipsUnparseableRecords(t *testing.T) {
	env, _, device, token := newStockEnv(t)

	now := time.Now().UnixMilli()
	// Exactly what the device sends today.
	legacy := `{"ts":` + fmt.Sprint(now-1000) + `,"values":{"stockEvent":"DetailInoutRecord{oilcanId=1, beginHeith=120.5, endHeight=30.2}"}}`
	postDeviceTelemetry(t, token, []byte(legacy))

	good := publishStock(t, token, protocol.StockEvent{
		EventID: fmt.Sprintf("in-1-%d", now), Tank: 1, Direction: protocol.StockIn,
		BeginTimeMs: now - 1000, EndTimeMs: now, NetVolumeL: 100, TSMs: now,
	})

	path := "/api/oil-in-out?device=" + device + "&beginTime=2020-01-01T00:00:00Z&endTime=2030-01-01T00:00:00Z"

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		got := fetchStock(t, env, path)
		for _, r := range got {
			if r["eventId"] == good.EventID {
				// The parseable record came through despite the malformed one sharing the key.
				return
			}
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatal("a malformed legacy record prevented the valid one from being served")
}
