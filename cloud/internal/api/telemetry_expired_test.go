package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// cloud#28: after ThingsBoard's TTL deletes a snapshot's history rows its latest cache still names the
// instant. /telemetry/latest must say "expired" (with when the device last reported), not "never
// reported". Real ThingsBoard: the TTL's effect is reproduced by deleting the history rows and keeping
// the latest cache, which is what the cleanup leaves behind.
func TestLatestTelemetry_ExpiredIsDistinctFromNeverReported(t *testing.T) {
	env, sim, device, _ := newStockEnv(t)
	ctx := context.Background()
	path := "/api/devices/" + device + "/telemetry/latest"

	type body struct {
		Code           string `json:"code"`
		LastReportedAt string `json:"lastReportedAt"`
	}
	get := func() (int, body) {
		rec := env.authed("GET", path, nil)
		var b body
		_ = json.Unmarshal(rec.Body.Bytes(), &b)
		return rec.Code, b
	}

	// Never reported.
	if code, b := get(); code != http.StatusNotFound || b.Code != "telemetry_not_reported" || b.LastReportedAt != "" {
		t.Fatalf("before any report: %d %+v, want 404 telemetry_not_reported", code, b)
	}

	snap, err := sim.PublishSnapshot()
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(20 * time.Second)
	for {
		if code, _ := get(); code == http.StatusOK {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the snapshot never became readable")
		}
		time.Sleep(300 * time.Millisecond)
	}

	// The TTL's aftermath: history rows gone, latest cache intact.
	tb, err := tbclient.New(tbclient.Config{
		BaseURL:  tbEnvOr("TB_BASE_URL", "http://localhost:8081"),
		Username: tbEnvOr("TB_USERNAME", "tenant@thingsboard.org"),
		Password: tbEnvOr("TB_PASSWORD", "tenant"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := tb.Login(ctx); err != nil {
		t.Skipf("ThingsBoard login failed: %v", err)
	}
	dev, err := tb.GetDeviceByName(ctx, device)
	if err != nil {
		t.Fatal(err)
	}
	keys := append(protocol.SnapshotKeys(len(snap.Tanks)), protocol.KeyTankCount, protocol.KeySnapshotSeq)
	if err := tb.DeleteTimeseriesHistory(ctx, dev.ID.ID, keys, 0, time.Now().Add(time.Hour).UnixMilli()); err != nil {
		t.Fatal(err)
	}

	code, b := get()
	if code != http.StatusNotFound || b.Code != "telemetry_expired" {
		t.Fatalf("after the history expired: %d %+v, want 404 telemetry_expired", code, b)
	}
	at, err := time.Parse(time.RFC3339Nano, b.LastReportedAt)
	if err != nil || !at.Equal(time.UnixMilli(snap.TS)) {
		t.Errorf("lastReportedAt = %q (%v), want the snapshot's %v", b.LastReportedAt, err, time.UnixMilli(snap.TS).UTC())
	}
}
