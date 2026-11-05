package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"testing"

	"yeweiyi-cloud/internal/control"
)

type registerResp struct {
	DeviceName string `json:"deviceName"`
	CustomerID int64  `json:"customerId"`
	Created    bool   `json:"created"`
}

func (e *testEnv) register(token, device string, customerID int64) (*registerResp, int) {
	e.t.Helper()
	rec := e.do("POST", "/api/devices", []byte(token), jsonBody(e.t, map[string]any{"deviceName": device, "customerId": customerID}))
	var out registerResp
	if rec.Code == http.StatusOK || rec.Code == http.StatusCreated {
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			e.t.Fatalf("decode %q: %v", rec.Body.String(), err)
		}
	}
	return &out, rec.Code
}

func (e *testEnv) cleanupDevice(name string) {
	e.t.Cleanup(func() {
		e.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, name)
	})
}

// TestRegisterDevice_IsIdempotent: first call creates (201), repeats return the same row (200),
// never a second row and never a pairing secret.
func TestRegisterDevice_IsIdempotent(t *testing.T) {
	env := newTestEnv(t)
	admin := env.otherUserToken(control.RolePlatformAdmin)
	customer := env.newCustomer("reg")
	device := "ZCT" + newBindDeviceName(t)
	env.cleanupDevice(device)

	got, code := env.register(admin, device, customer)
	if code != http.StatusCreated || !got.Created || got.CustomerID != customer || got.DeviceName != device {
		t.Fatalf("first register: code=%d body=%+v", code, got)
	}
	got, code = env.register(admin, device, customer)
	if code != http.StatusOK || got.Created || got.CustomerID != customer {
		t.Fatalf("repeat register: code=%d body=%+v", code, got)
	}

	var devices, secrets int
	env.pool.QueryRow(context.Background(), `SELECT count(*) FROM devices WHERE device_name = $1`, device).Scan(&devices)
	env.pool.QueryRow(context.Background(), `SELECT count(*) FROM device_pairing_secrets WHERE device_name = $1`, device).Scan(&secrets)
	if devices != 1 || secrets != 0 {
		t.Fatalf("devices=%d secrets=%d, want 1 and 0", devices, secrets)
	}
}

func TestRegisterDevice_ConcurrentCallsYieldOneRow(t *testing.T) {
	env := newTestEnv(t)
	admin := env.otherUserToken(control.RolePlatformAdmin)
	customer := env.newCustomer("race")
	device := "ZCT" + newBindDeviceName(t)
	env.cleanupDevice(device)

	var wg sync.WaitGroup
	var mu sync.Mutex
	created := 0
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			got, code := env.register(admin, device, customer)
			if code != http.StatusOK && code != http.StatusCreated {
				t.Errorf("code %d", code)
			}
			if got.Created {
				mu.Lock()
				created++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if created != 1 {
		t.Fatalf("created reported %d times, want exactly 1", created)
	}
}

func TestRegisterDevice_OtherCustomersDeviceIsConflictAndNotMoved(t *testing.T) {
	env := newTestEnv(t)
	admin := env.otherUserToken(control.RolePlatformAdmin)
	a, b := env.newCustomer("a"), env.newCustomer("b")
	device := "ZCT" + newBindDeviceName(t)
	env.cleanupDevice(device)

	if _, code := env.register(admin, device, a); code != http.StatusCreated {
		t.Fatalf("seed: %d", code)
	}
	if _, code := env.register(admin, device, b); code != http.StatusConflict {
		t.Fatalf("want 409, got %d", code)
	}
	var owner int64
	env.pool.QueryRow(context.Background(), `SELECT customer_id FROM devices WHERE device_name = $1`, device).Scan(&owner)
	if owner != a {
		t.Fatalf("device moved to %d, want %d", owner, a)
	}
}

func TestRegisterDevice_ClaimsUnassignedDevice(t *testing.T) {
	env := newTestEnv(t)
	admin := env.otherUserToken(control.RolePlatformAdmin)
	customer := env.newCustomer("claim")
	device := "ZCT" + newBindDeviceName(t)
	env.cleanupDevice(device)
	env.seedDeviceIn(device, 0)

	got, code := env.register(admin, device, customer)
	if code != http.StatusOK || got.Created || got.CustomerID != customer {
		t.Fatalf("claim: code=%d body=%+v", code, got)
	}
}

func TestRegisterDevice_Validation(t *testing.T) {
	env := newTestEnv(t) // customer_user
	customer := env.defaultCustomerID()
	device := "ZCT" + newBindDeviceName(t)
	env.cleanupDevice(device)

	if _, code := env.register(env.token, device, customer); code != http.StatusForbidden {
		t.Fatalf("customer_user: want 403, got %d", code)
	}
	var n int
	env.pool.QueryRow(context.Background(), `SELECT count(*) FROM devices WHERE device_name = $1`, device).Scan(&n)
	if n != 0 {
		t.Fatal("a refused registration must not create a device")
	}

	admin := env.otherUserToken(control.RolePlatformAdmin)
	if _, code := env.register(admin, device, customer+1_000_000); code != http.StatusBadRequest {
		t.Fatalf("unknown customer: want 400, got %d", code)
	}
	if _, code := env.register(admin, "  ", customer); code != http.StatusBadRequest {
		t.Fatalf("blank name: want 400, got %d", code)
	}
	if _, code := env.register(admin, "a/b", customer); code != http.StatusBadRequest {
		t.Fatalf("slash in name: want 400, got %d", code)
	}
}
