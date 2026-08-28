package api_test

import (
	"context"
	"net/http"
	"testing"
)

func TestOilcanLatest_NotFoundWhenNothingCached(t *testing.T) {
	env := newTestEnv(t)

	rec := env.authed("GET", "/api/oilcan/latest?device=never-polled-device", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestOilcanLatest_RequiresDevice(t *testing.T) {
	env := newTestEnv(t)

	rec := env.authed("GET", "/api/oilcan/latest", nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestOilcanLatest_ReturnsCachedSnapshotVerbatim(t *testing.T) {
	env := newTestEnv(t)
	const deviceName = "api-test-oilcan-device"
	const snapshot = `[{"id":1,"oiltype":"0号柴油","oilLevel":88.8,"connect":true,"deviceName":"api-test-oilcan-device"}]`

	ctx := context.Background()
	if err := env.rdb.Set(ctx, "oilcan:latest:"+deviceName, snapshot, 0).Err(); err != nil {
		t.Fatalf("seed redis: %v", err)
	}
	t.Cleanup(func() { env.rdb.Del(context.Background(), "oilcan:latest:"+deviceName) })

	rec := env.authed("GET", "/api/oilcan/latest?device="+deviceName, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	if rec.Body.String() != snapshot {
		t.Fatalf("expected snapshot passed through verbatim, got: %s", rec.Body.String())
	}
}
