package tbclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

// TelemetryUpdate is one batch of telemetry ThingsBoard pushed to a subscriber.
//
// Values holds the newest point per key in the batch, in the same string-valued shape the REST API
// uses, so it feeds protocol.ParseSnapshot / protocol.ParseAlarmEvent unchanged. TS is the newest
// timestamp in the batch; KeyTS is each key's own point timestamp (the envelope ts the device
// stamped), which consumers that persist a key's value need — TS alone belongs to whichever key in
// the batch happened to be newest.
type TelemetryUpdate struct {
	SubscriptionID int
	TS             int64
	Values         map[string]any
	KeyTS          map[string]int64
}

// EnvTS is key's own point timestamp, falling back to the batch's newest when the update carries no
// per-key timestamps (a hand-built one in a test).
func (u TelemetryUpdate) EnvTS(key string) int64 {
	if ts, ok := u.KeyTS[key]; ok && ts > 0 {
		return ts
	}
	return u.TS
}

// TelemetryStream is a live subscription to one device's telemetry. This is the push path that
// replaces the old design's per-device polling entirely: the device reports on its own 30-second
// timer and pushes instant events between ticks, and this delivers them as they land.
type TelemetryStream struct {
	Updates <-chan TelemetryUpdate
	// Err is closed-with-a-value when the stream dies. Always check it after Updates closes;
	// a dropped WebSocket is normal operationally and the caller is expected to resubscribe.
	Err <-chan error

	conn   *websocket.Conn
	closed atomic.Bool
}

// Close tears the subscription down. Safe to call more than once.
func (s *TelemetryStream) Close() error {
	if s.closed.Swap(true) {
		return nil
	}
	return s.conn.Close()
}

// wsCmd is ThingsBoard's unified WebSocket command envelope (/api/ws, ThingsBoard 3.5+).
type wsCmd struct {
	AuthCmd *wsAuthCmd  `json:"authCmd,omitempty"`
	Cmds    []wsDataCmd `json:"cmds,omitempty"`
}

type wsAuthCmd struct {
	CmdID int    `json:"cmdId"`
	Token string `json:"token"`
}

type wsDataCmd struct {
	Type       string `json:"type"` // "TIMESERIES"
	CmdID      int    `json:"cmdId"`
	EntityType string `json:"entityType"`
	EntityID   string `json:"entityId"`
	Scope      string `json:"scope"` // "LATEST_TELEMETRY"
	Keys       string `json:"keys,omitempty"`
}

// wsUpdate is ThingsBoard's push message. data maps a key to [[ts, value], ...] pairs, where the
// value is always a JSON string — the same stringification as the REST API.
type wsUpdate struct {
	SubscriptionID int                 `json:"subscriptionId"`
	CmdID          int                 `json:"cmdId"`
	ErrorCode      int                 `json:"errorCode"`
	ErrorMsg       string              `json:"errorMsg"`
	Data           map[string][][2]any `json:"data"`
	LatestValues   map[string]any      `json:"latestValues"`
}

// SubscribeTelemetry opens a live telemetry subscription for one device.
//
// keys may be nil to receive every key the device reports, which is what the ingestion path wants:
// it must see instant alarm and command-result events without having to know in advance how many
// tanks a device has.
//
// The caller owns the returned stream and must Close it. Cancelling ctx also tears it down.
func (c *Client) SubscribeTelemetry(ctx context.Context, deviceID string, keys []string) (*TelemetryStream, error) {
	token, err := c.Token(ctx)
	if err != nil {
		return nil, err
	}

	wsURL := strings.Replace(strings.Replace(c.cfg.BaseURL, "https://", "wss://", 1), "http://", "ws://", 1) + "/api/ws"

	dialer := websocket.Dialer{HandshakeTimeout: 15 * time.Second}
	conn, resp, err := dialer.DialContext(ctx, wsURL, http.Header{})
	if err != nil {
		status := ""
		if resp != nil {
			status = fmt.Sprintf(" (HTTP %d)", resp.StatusCode)
		}
		return nil, fmt.Errorf("thingsboard: dial %s%s: %w", wsURL, status, err)
	}

	// ThingsBoard authenticates the socket by an in-band authCmd rather than by the
	// X-Authorization header it uses everywhere else — a WebSocket handshake from a browser cannot
	// set custom headers, so the whole API works this way.
	if err := conn.WriteJSON(wsCmd{AuthCmd: &wsAuthCmd{CmdID: 0, Token: token}}); err != nil {
		conn.Close()
		return nil, fmt.Errorf("thingsboard: ws auth: %w", err)
	}

	sub := wsDataCmd{
		Type:       "TIMESERIES",
		CmdID:      1,
		EntityType: "DEVICE",
		EntityID:   deviceID,
		Scope:      "LATEST_TELEMETRY",
	}
	if len(keys) > 0 {
		sub.Keys = strings.Join(keys, ",")
	}
	if err := conn.WriteJSON(wsCmd{Cmds: []wsDataCmd{sub}}); err != nil {
		conn.Close()
		return nil, fmt.Errorf("thingsboard: ws subscribe: %w", err)
	}

	updates := make(chan TelemetryUpdate, 32)
	errc := make(chan error, 1)
	stream := &TelemetryStream{Updates: updates, Err: errc, conn: conn}

	// Cancelling ctx has to close the connection from the outside: a blocked ReadMessage does not
	// observe a context on its own.
	go func() {
		<-ctx.Done()
		stream.Close()
	}()

	go func() {
		defer close(updates)
		defer close(errc)
		for {
			_, raw, err := conn.ReadMessage()
			if err != nil {
				if stream.closed.Load() || errors.Is(ctx.Err(), context.Canceled) {
					return // deliberate shutdown, not a failure
				}
				// An unclean close is also how ThingsBoard rejects a bad in-band auth token in
				// practice, so this is not necessarily "our side dropped the connection" — clear
				// the cached token (if it is still the one this connection used — see
				// invalidateToken's comment on why that comparison matters with many subscriptions
				// sharing one Client) so a caller's reconnect does not hand the same rejected value
				// straight back to a fresh socket. Harmless to do for an ordinary network drop too:
				// it just costs one extra login on the next attempt.
				c.invalidateToken(token)
				errc <- fmt.Errorf("thingsboard: ws read: %w", err)
				return
			}

			var u wsUpdate
			if err := json.Unmarshal(raw, &u); err != nil {
				continue // ThingsBoard also sends non-data frames (auth acks); ignore what we cannot type
			}
			if u.ErrorCode != 0 {
				// Covers both an explicit auth-rejection ack and any other subscription-level
				// error ThingsBoard reports in-band; see the comment above ReadMessage's error
				// path for why invalidating here is safe even when the cause was not auth.
				c.invalidateToken(token)
				errc <- fmt.Errorf("thingsboard: ws subscription error %d: %s", u.ErrorCode, u.ErrorMsg)
				return
			}
			if len(u.Data) == 0 {
				continue
			}

			ts, values, keyTS := flattenWSData(u.Data)
			select {
			case updates <- TelemetryUpdate{SubscriptionID: u.SubscriptionID, TS: ts, Values: values, KeyTS: keyTS}:
			case <-ctx.Done():
				return
			}
		}
	}()

	return stream, nil
}

// flattenWSData reduces ThingsBoard's {key: [[ts, value], ...]} to the newest point per key.
func flattenWSData(data map[string][][2]any) (int64, map[string]any, map[string]int64) {
	var newest int64
	values := make(map[string]any, len(data))
	keyTSs := make(map[string]int64, len(data))
	for key, points := range data {
		var keyTS int64
		for _, p := range points {
			ts, err := toMillis(p[0])
			if err != nil {
				continue
			}
			if ts >= keyTS {
				keyTS, values[key] = ts, p[1]
			}
		}
		keyTSs[key] = keyTS
		if keyTS > newest {
			newest = keyTS
		}
	}
	return newest, values, keyTSs
}

func toMillis(v any) (int64, error) {
	switch n := v.(type) {
	case float64:
		return int64(n), nil
	case json.Number:
		return n.Int64()
	default:
		return 0, fmt.Errorf("timestamp is %T, not a number", v)
	}
}
