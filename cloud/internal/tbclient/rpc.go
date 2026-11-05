package tbclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"

	"yeweiyi-cloud/internal/protocol"
)

// ErrDeviceOffline is returned when ThingsBoard could not deliver an RPC because the device has no
// live session, or because it did not answer within the request's timeout. Both mean the same thing
// to the command state machine — the command never reached "received" — so they share one error.
var ErrDeviceOffline = errors.New("thingsboard: device did not acknowledge (offline or timed out)")

// SendCommand issues a two-way RPC and returns the device's ACK.
//
// What the return value does and does not mean is the crux of the whole control design (issue #1):
// a non-error return means the device *received and accepted* the request, nothing more. Whether
// the pump actually started arrives later and asynchronously as a protocol.CmdResult on the
// telemetry stream. Treating this ACK as success is exactly the mistake the state machine exists to
// prevent — hence the deliberately un-success-like name of the returned type.
//
// A device that refuses outright answers with Status == protocol.CmdStatusRejected and an error
// code; that is a normal return here, not a Go error, because the call itself worked.
func (c *Client) SendCommand(ctx context.Context, deviceID, method string, env protocol.Envelope, timeoutMs int64) (protocol.Ack, error) {
	if err := protocol.ValidateEnvelope(method, env); err != nil {
		return protocol.Ack{}, err
	}
	if timeoutMs <= 0 {
		timeoutMs = DefaultRPCTimeoutMs
	}

	req := protocol.Request{Method: method, Params: env, Timeout: timeoutMs}
	path := "/api/rpc/twoway/" + url.PathEscape(deviceID)

	var raw json.RawMessage
	err := c.do(ctx, http.MethodPost, path, req, &raw)
	if err != nil {
		// ThingsBoard reports an undelivered two-way RPC as 408 Request Timeout (no session, or the
		// device never answered), and 504 when its own internal delivery timed out. Both are
		// "no ACK", not "ThingsBoard is broken", so neither should be treated as an outage.
		var tbErr *Error
		if errors.As(err, &tbErr) && (tbErr.StatusCode == http.StatusRequestTimeout || tbErr.StatusCode == http.StatusGatewayTimeout) {
			return protocol.Ack{}, fmt.Errorf("%w: %s", ErrDeviceOffline, tbErr.Body)
		}
		return protocol.Ack{}, err
	}

	ack, err := protocol.ParseAck(raw)
	if err != nil {
		return protocol.Ack{}, fmt.Errorf("device %s answered with an unparseable ACK: %w (body: %s)", deviceID, err, truncate(raw, 256))
	}
	if ack.CommandID != env.CommandID {
		// A device answering about a different command means the correlation this whole state
		// machine depends on is broken; better to fail loudly than to mark the wrong row acked.
		return protocol.Ack{}, fmt.Errorf("device %s acked commandId %q but %q was sent", deviceID, ack.CommandID, env.CommandID)
	}
	return ack, nil
}

// DefaultRPCTimeoutMs is how long ThingsBoard waits for the device's ACK before giving up. This
// bounds only the acknowledgement, not the execution: a reboot or a slow valve reports its outcome
// on the telemetry stream long after this expires, which is the point of splitting the two.
const DefaultRPCTimeoutMs = 10_000
