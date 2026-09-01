package tbclient

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// Attribute scopes, spelled as ThingsBoard spells them in REST paths.
const (
	ScopeClient = "CLIENT_SCOPE" // device-owned, written by the device
	ScopeShared = "SHARED_SCOPE" // cloud-owned, pushed down to the device
	ScopeServer = "SERVER_SCOPE" // cloud-owned, never sent to the device
)

// TSValue is one point of a ThingsBoard timeseries.
//
// Value is a string even for numeric and boolean series: ThingsBoard's REST API stringifies every
// telemetry value on the way out, regardless of how it was stored. internal/protocol's parsers
// accept that, which is what makes the same Snapshot decode identically off MQTT and off REST.
type TSValue struct {
	TS    int64  `json:"ts"`
	Value string `json:"value"`
}

// LatestTimeseries returns the most recent point for each requested key. Keys with no data at all
// are simply absent from the map rather than being an error — a device that has never reported is a
// normal state, not a failure.
//
// ThingsBoard has no "all keys" mode on this endpoint that is safe to depend on, so callers pass an
// explicit key list; protocol.SnapshotKeys builds the one for a tank snapshot.
func (c *Client) LatestTimeseries(ctx context.Context, deviceID string, keys []string) (map[string]TSValue, error) {
	if len(keys) == 0 {
		return map[string]TSValue{}, nil
	}

	q := url.Values{"keys": {strings.Join(keys, ",")}}
	path := "/api/plugins/telemetry/DEVICE/" + url.PathEscape(deviceID) + "/values/timeseries" + query(q)

	var raw map[string][]TSValue
	if err := c.do(ctx, http.MethodGet, path, nil, &raw); err != nil {
		return nil, fmt.Errorf("latest timeseries for %s: %w", deviceID, err)
	}

	out := make(map[string]TSValue, len(raw))
	for k, points := range raw {
		if len(points) > 0 {
			out[k] = points[0]
		}
	}
	return out, nil
}

// LatestValues flattens LatestTimeseries into the key/value map internal/protocol.ParseSnapshot
// wants, and returns the newest timestamp across all of the keys.
//
// Using the max rather than any single key's timestamp matters because ThingsBoard stores each key
// independently: after an instant alarm event lands between two snapshots, the keys of one logical
// snapshot still share a timestamp, but a key that stopped being reported keeps its old one.
func (c *Client) LatestValues(ctx context.Context, deviceID string, keys []string) (int64, map[string]any, error) {
	points, err := c.LatestTimeseries(ctx, deviceID, keys)
	if err != nil {
		return 0, nil, err
	}
	var ts int64
	values := make(map[string]any, len(points))
	for k, p := range points {
		values[k] = p.Value
		if p.TS > ts {
			ts = p.TS
		}
	}
	return ts, values, nil
}

// Timeseries returns a key's history in [startMs, endMs]. limit caps the number of points
// ThingsBoard returns; it applies per key.
func (c *Client) Timeseries(ctx context.Context, deviceID string, keys []string, startMs, endMs int64, limit int) (map[string][]TSValue, error) {
	if len(keys) == 0 {
		return map[string][]TSValue{}, nil
	}
	if limit <= 0 {
		limit = 1000
	}

	q := url.Values{
		"keys":               {strings.Join(keys, ",")},
		"startTs":            {strconv.FormatInt(startMs, 10)},
		"endTs":              {strconv.FormatInt(endMs, 10)},
		"limit":              {strconv.Itoa(limit)},
		"orderBy":            {"ASC"},
		"useStrictDataTypes": {"false"},
	}
	path := "/api/plugins/telemetry/DEVICE/" + url.PathEscape(deviceID) + "/values/timeseries" + query(q)

	var raw map[string][]TSValue
	if err := c.do(ctx, http.MethodGet, path, nil, &raw); err != nil {
		return nil, fmt.Errorf("timeseries for %s: %w", deviceID, err)
	}
	return raw, nil
}

// AttributeKV is one attribute as ThingsBoard returns it. Unlike telemetry, attribute values keep
// their JSON type here rather than being stringified.
type AttributeKV struct {
	Key          string `json:"key"`
	Value        any    `json:"value"`
	LastUpdateTs int64  `json:"lastUpdateTs"`
}

// Attributes reads a device's attributes in one scope. Passing no keys returns every attribute in
// that scope, which is safe here (unlike telemetry) because attributes are a small, bounded set.
func (c *Client) Attributes(ctx context.Context, deviceID, scope string, keys []string) (map[string]any, error) {
	q := url.Values{}
	if len(keys) > 0 {
		q.Set("keys", strings.Join(keys, ","))
	}
	path := "/api/plugins/telemetry/DEVICE/" + url.PathEscape(deviceID) + "/values/attributes/" + scope + query(q)

	var raw []AttributeKV
	if err := c.do(ctx, http.MethodGet, path, nil, &raw); err != nil {
		return nil, fmt.Errorf("attributes(%s) for %s: %w", scope, deviceID, err)
	}

	out := make(map[string]any, len(raw))
	for _, kv := range raw {
		out[kv.Key] = kv.Value
	}
	return out, nil
}

// SetSharedAttributes writes cloud-owned attributes that ThingsBoard pushes down to the device —
// how V1 delivers reportIntervalSec and alarmThresholds (see internal/protocol/attributes.go).
//
// This is *not* a control command and deliberately does not go through the command state machine:
// a shared attribute is a desired-state value ThingsBoard re-delivers whenever the device
// reconnects, so it needs no delivery tracking of its own. Anything requiring an acknowledged,
// audited, expiring action is an RPC instead.
func (c *Client) SetSharedAttributes(ctx context.Context, deviceID string, attrs map[string]any) error {
	if len(attrs) == 0 {
		return nil
	}
	path := "/api/plugins/telemetry/DEVICE/" + url.PathEscape(deviceID) + "/" + ScopeShared
	if err := c.do(ctx, http.MethodPost, path, attrs, nil); err != nil {
		return fmt.Errorf("set shared attributes on %s: %w", deviceID, err)
	}
	return nil
}
