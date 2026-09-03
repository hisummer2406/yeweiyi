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
