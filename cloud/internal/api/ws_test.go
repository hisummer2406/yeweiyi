package api_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/control"
)

// dialAlarmsWS opens a real TCP connection to env's router (httptest.NewRecorder cannot upgrade a
// WebSocket, unlike the REST tests elsewhere in this package) and attempts the /ws/alarms handshake
// for device, authenticating the same way a browser/mini-program client has to: the JWT as a `token`
// query param, since a WebSocket handshake request cannot carry a custom Authorization header.
func dialAlarmsWS(t *testing.T, env *testEnv, device string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	srv := httptest.NewServer(env.router)
	t.Cleanup(srv.Close)
	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http") + "/ws/alarms?device=" + device + "&token=" + env.token
	return websocket.DefaultDialer.Dial(wsURL, nil)
}

// TestWSAlarms_PlatformAdminCanSubscribeToUnboundDevice is the regression test for the P1 found in
// review of PR #11 (round 4): requireDeviceOwnership's platform-admin shortcut (round 3's fix) only
// covered REST handlers. wsAlarmsHandler authenticates its own JWT independently of authMiddleware —
// a WebSocket handshake can't carry the usual header — and had its own, separate unconditional
// UserOwnsDevice call that nothing routed through the round-3 fix. A platform admin could therefore
// read a device's REST data and issue commands against it, yet be refused a live alarm subscription
// for that same unbound device — exactly the path a platform on-call operator needs to work across
// every device, not just their own.
func TestWSAlarms_PlatformAdminCanSubscribeToUnboundDevice(t *testing.T) {
	env := newTestEnv(t)
	device := "ws-api-admin-unbound"
	env.seedDevice(device) // exists, but deliberately never bound to this user via user_devices
	env.setRole(control.RolePlatformAdmin)

	conn, resp, err := dialAlarmsWS(t, env, device)
	if err != nil {
		status := 0
		if resp != nil {
			status = resp.StatusCode
		}
		t.Fatalf("platform admin subscribing to an unbound device's alarms: dial failed (HTTP %d): %v", status, err)
	}
	defer conn.Close()

	// Confirm a published alarm actually arrives, not just that the handshake was accepted — a
	// broken fan-out path could still upgrade the connection and then deliver nothing.
	fanout, err := json.Marshal(map[string]any{"deviceName": device, "alarmId": "ws-test-alarm"})
	if err != nil {
		t.Fatalf("marshal fanout: %v", err)
	}

	msg := publishUntilReceived(t, conn, env, fanout)
	if string(msg) != string(fanout) {
		t.Errorf("got %s, want %s", msg, fanout)
	}
}

// TestWSAlarms_ForbiddenForUnownedDevice pins the other side of the same fix: a non-admin must still
// be refused a device nobody bound them to. Without this alongside the admin test above, a fix that
// simply let everyone through would pass just as easily as the correct one.
func TestWSAlarms_ForbiddenForUnownedDevice(t *testing.T) {
	env := newTestEnv(t)
	device := "ws-api-not-mine"
	env.seedDevice(device) // default role is customer_user, and it is never bound

	_, resp, err := dialAlarmsWS(t, env, device)
	if err == nil {
		t.Fatal("expected the handshake to be rejected for an unowned device")
	}
	if resp == nil || resp.StatusCode != http.StatusForbidden {
		status := 0
		if resp != nil {
			status = resp.StatusCode
		}
		t.Fatalf("expected 403, got %d", status)
	}
}

// publishUntilReceived publishes fanout on alarms:live repeatedly until conn delivers it (or the
// overall budget runs out), calling conn.ReadMessage exactly once.
//
// This retries the *publish*, never the *read*, and that is not a style choice: go-redis's PubSub
// subscribes to the channel asynchronously relative to Subscribe() returning, so a publish issued
// right after the WebSocket handshake completes can legitimately race the subscription actually
// taking effect server-side — but gorilla/websocket documents that once ReadMessage (via
// NextReader) returns any error, including a deadline timeout, that error is permanent and every
// later call on the same Conn returns the same cached error. A retry loop that calls ReadMessage
// again after a timeout can therefore never recover, no matter how much later the message actually
// arrives — confirmed the hard way: an earlier version of this helper retried the read and failed
// deterministically about half the time even though the server-side logs showed every single
// publish being received and written successfully. Retrying the publish side instead sidesteps the
// problem entirely, since ReadMessage is only ever called once.
func publishUntilReceived(t *testing.T, conn *websocket.Conn, env *testEnv, fanout []byte) []byte {
	t.Helper()

	stop := make(chan struct{})
	defer close(stop)
	go func() {
		publish := func() {
			if err := env.rdb.Publish(context.Background(), "alarms:live", fanout).Err(); err != nil {
				t.Logf("publish: %v", err)
			}
		}
		publish()
		ticker := time.NewTicker(200 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				publish()
			}
		}
	}()

	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, msg, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("expected the published alarm to arrive: %v", err)
	}
	return msg
}

// ---------------------------------------------------------------------------------------------
// Access is re-checked while the connection is open. Before this, authorization was decided once at
// the handshake, so a user disabled, unbound, or moved to another customer kept receiving a
// device's alarms until they chose to disconnect — and across a device transfer that is the
// previous customer reading the next customer's alarms.

// readUntilClosed reads until the server ends the connection or the budget runs out, returning every
// text message that arrived. closed is false only when the budget ran out with the connection still
// open. gorilla caches the first read error, so this is the one place a test may read in a loop.
func readUntilClosed(conn *websocket.Conn, budget time.Duration) (msgs []string, closed bool) {
	deadline := time.Now().Add(budget)
	for {
		conn.SetReadDeadline(deadline)
		_, msg, err := conn.ReadMessage()
		if err != nil {
			if ne, ok := err.(interface{ Timeout() bool }); ok && ne.Timeout() {
				return msgs, false
			}
			return msgs, true
		}
		msgs = append(msgs, string(msg))
	}
}

func alarmFanout(t *testing.T, device, id string) []byte {
	t.Helper()
	b, err := json.Marshal(map[string]any{"deviceName": device, "alarmId": id, "tank": 1})
	if err != nil {
		t.Fatalf("marshal fanout: %v", err)
	}
	return b
}

// connectedAndDelivering opens the socket for device and proves the fan-out reaches it, so a later
// "nothing arrived" means the server withheld it rather than that the subscription never worked.
func connectedAndDelivering(t *testing.T, env *testEnv, device string) *websocket.Conn {
	t.Helper()
	conn, resp, err := dialAlarmsWS(t, env, device)
	if err != nil {
		status := 0
		if resp != nil {
			status = resp.StatusCode
		}
		t.Fatalf("dial (HTTP %d): %v", status, err)
	}
	t.Cleanup(func() { conn.Close() })
	publishUntilReceived(t, conn, env, alarmFanout(t, device, "before-revoke"))
	return conn
}

// requireRevoked checks the three things a revocation must do: the alarm raised afterwards is not
// delivered, the connection is closed, and the closing is prompt.
func requireRevoked(t *testing.T, env *testEnv, conn *websocket.Conn, device string) {
	t.Helper()
	// Publish a few times: the first may land in the gap before the server notices, and the point is
	// that none of them gets through.
	for i := 0; i < 3; i++ {
		if err := env.rdb.Publish(context.Background(), "alarms:live", alarmFanout(t, device, "after-revoke")).Err(); err != nil {
			t.Fatalf("publish: %v", err)
		}
		time.Sleep(100 * time.Millisecond)
	}
	msgs, closed := readUntilClosed(conn, 5*time.Second)
	for _, m := range msgs {
		if strings.Contains(m, "after-revoke") {
			t.Fatalf("an alarm raised after access was revoked was still delivered: %s", m)
		}
	}
	if !closed {
		t.Fatal("connection stayed open after access was revoked")
	}
}

func TestWSAlarms_DisablingTheUserClosesTheStream(t *testing.T) {
	env := newTestEnv(t)
	device := "ws-revoke-disable"
	env.ownDevice(device)
	conn := connectedAndDelivering(t, env, device)

	if _, err := env.pool.Exec(context.Background(),
		`UPDATE users SET disabled_at = now() WHERE username = $1`, env.username); err != nil {
		t.Fatalf("disable user: %v", err)
	}
	requireRevoked(t, env, conn, device)
}

func TestWSAlarms_UnbindingTheDeviceClosesTheStream(t *testing.T) {
	env := newTestEnv(t)
	device := "ws-revoke-unbind"
	env.ownDevice(device)
	conn := connectedAndDelivering(t, env, device)

	if _, err := env.pool.Exec(context.Background(),
		`DELETE FROM user_tank_grants WHERE device_name = $1`, device); err != nil {
		t.Fatalf("unbind: %v", err)
	}
	requireRevoked(t, env, conn, device)
}

// The case the tenant boundary exists for: the device is handed to another customer while a user of
// the old one is still listening.
func TestWSAlarms_TransferringTheDeviceToAnotherCustomerClosesTheStream(t *testing.T) {
	env := newTestEnv(t)
	device := "ws-revoke-transfer"
	// Created before the device: cleanup runs in reverse, and the device (which ends up owned by this
	// customer) has to be deleted first.
	other := env.newCustomer("transfer-target")
	env.ownDevice(device)
	conn := connectedAndDelivering(t, env, device)

	if _, err := env.pool.Exec(context.Background(),
		`UPDATE devices SET customer_id = $1 WHERE device_name = $2`, other, device); err != nil {
		t.Fatalf("transfer device: %v", err)
	}
	requireRevoked(t, env, conn, device)
}

// With no alarm arriving at all, the periodic re-check is what ends the stream.
func TestWSAlarms_RevokedUserIsDisconnectedEvenWithoutNewAlarms(t *testing.T) {
	env := newTestEnvWith(t, func(d *api.Deps) { d.WSRevalidateInterval = 200 * time.Millisecond })
	device := "ws-revoke-idle"
	env.ownDevice(device)
	conn := connectedAndDelivering(t, env, device)

	if _, err := env.pool.Exec(context.Background(),
		`UPDATE users SET disabled_at = now() WHERE username = $1`, env.username); err != nil {
		t.Fatalf("disable user: %v", err)
	}
	if _, closed := readUntilClosed(conn, 5*time.Second); !closed {
		t.Fatal("an idle connection of a disabled user was never closed")
	}
}

// A token is only good until it expires; a stream must not outlive it.
func TestWSAlarms_ConnectionEndsWhenTheTokenExpires(t *testing.T) {
	env := newTestEnvWith(t, func(d *api.Deps) {
		d.TokenTTL = 2 * time.Second
		d.WSRevalidateInterval = time.Hour // so only the expiry can be what closes it
	})
	device := "ws-token-expiry"
	env.ownDevice(device)

	conn, _, err := dialAlarmsWS(t, env, device)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()

	start := time.Now()
	if _, closed := readUntilClosed(conn, 6*time.Second); !closed {
		t.Fatal("connection outlived its token")
	}
	if elapsed := time.Since(start); elapsed < time.Second {
		t.Errorf("closed after %s, well before the token's 2s lifetime was up", elapsed)
	}
}

// The other side of all of the above: re-checking must not cut off someone who is still entitled.
func TestWSAlarms_AuthorizedUserKeepsReceivingAcrossRevalidation(t *testing.T) {
	env := newTestEnvWith(t, func(d *api.Deps) { d.WSRevalidateInterval = 100 * time.Millisecond })
	device := "ws-still-allowed"
	env.ownDevice(device)
	conn := connectedAndDelivering(t, env, device)

	time.Sleep(time.Second) // many revalidation rounds
	msg := publishUntilReceived(t, conn, env, alarmFanout(t, device, "later"))
	if !strings.Contains(string(msg), "later") {
		t.Errorf("got %s", msg)
	}
}
