package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
)

func TestCorrectOilInOut_UnknownRecordReturns404(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-wb-oio-404-device"
	env.seedDevice(deviceName)

	body := jsonBody(t, map[string]any{
		"device": deviceName, "deviceLocalId": 999, "jieZhi": "x", "time": "2026-08-15 09:20",
	})
	rec := env.authed("POST", "/api/oil-in-out/correct", body)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestCorrectOilInOut_ExistingRecordEnqueuesPendingCommand(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-wb-oio-device"
	env.seedDevice(deviceName)

	if _, err := env.pool.Exec(context.Background(), `
		INSERT INTO oil_in_out (device_name, device_local_id, oil_id, jiezhi, time, begin_height, end_height, begin_volume, end_volume, begin_weight, end_weight)
		VALUES ($1, 7, 1, '0号柴油', '2026-08-15 09:20+08', 100, 20, 4000, 800, 3400, 700)`, deviceName); err != nil {
		t.Fatalf("seed oil_in_out: %v", err)
	}
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM oil_in_out WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup oil_in_out: %v", err)
		}
	})

	body := jsonBody(t, map[string]any{
		"device": deviceName, "deviceLocalId": 7, "oilId": 1, "jieZhi": "0号柴油", "time": "2026-08-15 09:20",
		"beginHeigth": 100, "endHeigth": 25, "beginVolum": 4000, "endVolum": 900, "beginWeight": 3400, "endWeight": 760,
	})
	rec := env.authed("POST", "/api/oil-in-out/correct", body)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp struct {
		ID     int64  `json:"id"`
		Status string `json:"status"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if resp.Status != "pending" || resp.ID == 0 {
		t.Fatalf("unexpected response: %+v", resp)
	}
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM write_back_commands WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup write_back_commands: %v", err)
		}
	})

	cmd, err := env.queries.GetWriteBackCommand(context.Background(), resp.ID)
	if err != nil {
		t.Fatalf("get command: %v", err)
	}
	if cmd.DeviceName != deviceName || cmd.CommandType != "oil_in_out_correction" || cmd.Status != "pending" {
		t.Fatalf("unexpected command row: %+v", cmd)
	}
	var payload map[string]any
	if err := json.Unmarshal(cmd.Payload, &payload); err != nil {
		t.Fatalf("payload isn't valid JSON: %v", err)
	}
	if _, hasDevice := payload["device"]; hasDevice {
		t.Fatalf("payload should not duplicate the device field: %v", payload)
	}
	if payload["deviceLocalId"] != float64(7) {
		t.Fatalf("unexpected payload: %v", payload)
	}
}

func TestCorrectShift_ExistingRecordEnqueuesPendingCommand(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-wb-shift-device"
	env.seedDevice(deviceName)

	if _, err := env.pool.Exec(context.Background(), `
		INSERT INTO commit_works (device_name, device_local_id, work_id, time, end_time, info)
		VALUES ($1, 4, 202, '2026-08-15 08:00+08', '2026-08-15 16:00+08', '{}')`, deviceName); err != nil {
		t.Fatalf("seed commit_works: %v", err)
	}
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM commit_works WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup commit_works: %v", err)
		}
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM write_back_commands WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup write_back_commands: %v", err)
		}
	})

	body := jsonBody(t, map[string]any{
		"device": deviceName, "deviceLocalId": 4, "workId": 202,
		"time": "2026-08-15 08:00", "endTime": "2026-08-15 15:30", "info": `{"note":"corrected"}`,
	})
	rec := env.authed("POST", "/api/shifts/correct", body)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestCorrectShift_UnknownRecordReturns404(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-wb-shift-404-device"
	env.seedDevice(deviceName)

	body := jsonBody(t, map[string]any{"device": deviceName, "deviceLocalId": 999, "time": "2026-08-15 08:00"})
	rec := env.authed("POST", "/api/shifts/correct", body)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestWriteOilCanSet_UnknownDeviceReturns404(t *testing.T) {
	env := newTestEnv(t)

	body := jsonBody(t, map[string]any{"oilCanId": 1})
	rec := env.authed("POST", "/api/devices/no-such-device-for-oil-can-set/oil-can-set", body)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestWriteOilCanSet_KnownDeviceEnqueuesPendingCommandWithOpaquePayload(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-wb-oilcanset-device"
	env.seedDevice(deviceName)
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM write_back_commands WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup write_back_commands: %v", err)
		}
	})

	body := jsonBody(t, map[string]any{"oilCanId": 1, "heightOilAlarm": 80})
	rec := env.authed("POST", "/api/devices/"+deviceName+"/oil-can-set", body)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected 202, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp struct {
		ID int64 `json:"id"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	cmd, err := env.queries.GetWriteBackCommand(context.Background(), resp.ID)
	if err != nil {
		t.Fatalf("get command: %v", err)
	}
	if cmd.CommandType != "oil_can_set" {
		t.Fatalf("unexpected command_type: %q", cmd.CommandType)
	}
	var payload map[string]any
	if err := json.Unmarshal(cmd.Payload, &payload); err != nil {
		t.Fatalf("payload isn't valid JSON: %v", err)
	}
	if payload["oilCanId"] != float64(1) || payload["heightOilAlarm"] != float64(80) {
		t.Fatalf("payload wasn't stored verbatim: %v", payload)
	}
}

func TestWriteOilCanSet_InvalidJSONBodyReturns400(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-wb-oilcanset-badjson-device"
	env.seedDevice(deviceName)

	rec := env.authed("POST", "/api/devices/"+deviceName+"/oil-can-set", []byte("{not valid json"))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestListWriteBack_ReturnsCommandsMostRecentFirstWithReadablePayload(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-wb-list-device"
	env.seedDevice(deviceName)
	t.Cleanup(func() {
		if _, err := env.pool.Exec(context.Background(), `DELETE FROM write_back_commands WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup write_back_commands: %v", err)
		}
	})

	first := env.authed("POST", "/api/devices/"+deviceName+"/oil-can-set", jsonBody(t, map[string]any{"seq": 1}))
	if first.Code != http.StatusAccepted {
		t.Fatalf("seed command 1: expected 202, got %d: %s", first.Code, first.Body.String())
	}
	second := env.authed("POST", "/api/devices/"+deviceName+"/oil-can-set", jsonBody(t, map[string]any{"seq": 2}))
	if second.Code != http.StatusAccepted {
		t.Fatalf("seed command 2: expected 202, got %d: %s", second.Code, second.Body.String())
	}

	rec := env.authed("GET", "/api/write-back?device="+deviceName, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp struct {
		Rows []struct {
			ID      int64          `json:"id"`
			Status  string         `json:"status"`
			Payload map[string]any `json:"payload"`
		} `json:"rows"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if len(resp.Rows) != 2 {
		t.Fatalf("expected 2 rows, got %d: %s", len(resp.Rows), rec.Body.String())
	}
	if resp.Rows[0].Payload["seq"] != float64(2) || resp.Rows[1].Payload["seq"] != float64(1) {
		t.Fatalf("expected most-recent-first order, got: %+v", resp.Rows)
	}
	for _, row := range resp.Rows {
		if row.Status != "pending" {
			t.Fatalf("expected status pending, got %q", row.Status)
		}
	}
}

func TestListWriteBack_RequiresDevice(t *testing.T) {
	env := newTestEnv(t)

	rec := env.authed("GET", "/api/write-back", nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}
