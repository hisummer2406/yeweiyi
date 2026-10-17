package tbclient

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"yeweiyi-cloud/internal/protocol"
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

	// Value is a pointer because this ThingsBoard answers a key that was never reported with
	// [{"ts":<now>,"value":null}] rather than omitting it. Decoding that null into a plain string
	// would yield "" with a timestamp, which callers read as a real, unparsable value.
	var raw map[string][]struct {
		TS    int64   `json:"ts"`
		Value *string `json:"value"`
	}
	if err := c.do(ctx, http.MethodGet, path, nil, &raw); err != nil {
		return nil, fmt.Errorf("latest timeseries for %s: %w", deviceID, err)
	}

	out := make(map[string]TSValue, len(raw))
	for k, points := range raw {
		if len(points) > 0 && points[0].Value != nil {
			out[k] = TSValue{TS: points[0].TS, Value: *points[0].Value}
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

// LatestSnapshot returns a *consistent* current tank snapshot for a device, unlike LatestValues:
// LatestValues merges each requested key's own most recent point independently, with no guarantee
// they were all reported together, and no way to tell that a key simply stopped being reported.
//
// Concretely, this closes a real bug: a device always publishes every field of one snapshot
// atomically under one shared ts, but ThingsBoard tracks each key's "latest value" independently
// and forever — so after a device is reconfigured from, say, two tanks down to one, tank2's keys
// keep their last-ever reported values as their own "latest" with no expiry. A caller that asked
// LatestValues for a fixed, generously-sized key list (SnapshotKeys(8), say) would then merge that
// stale tank2 data back in on every read, forever, stamped with whatever other key's timestamp
// happened to be newest — silently resurrecting a tank that no longer exists.
//
// LatestSnapshot avoids that by anchoring on the tankCount key's own latest point, which pins both
// the snapshot's timestamp and how many "tankN.*" key groups are still current, then re-reads every
// field of exactly that snapshot at that exact timestamp instead of trusting each key's independent
// cache. Returns ts=0 and an empty map, with no error, for a device that has never reported.
func (c *Client) LatestSnapshot(ctx context.Context, deviceID string) (int64, map[string]any, error) {
	anchor, err := c.LatestTimeseries(ctx, deviceID, []string{protocol.KeyTankCount, protocol.KeySnapshotSeq})
	if err != nil {
		return 0, nil, fmt.Errorf("latest snapshot anchor for %s: %w", deviceID, err)
	}
	tc, ok := anchor[protocol.KeyTankCount]
	if !ok {
		return 0, map[string]any{}, nil // never reported
	}
	tankCount, err := strconv.Atoi(strings.TrimSpace(tc.Value))
	if err != nil {
		return 0, nil, fmt.Errorf("latest snapshot for %s: %s value %q is not an integer: %w", deviceID, protocol.KeyTankCount, tc.Value, err)
	}
	// tankCount is device-reported and untrusted — an anomalous, corrupted or compromised device
	// could report a huge value once, and it would then sit as ThingsBoard's "latest" for that key
	// indefinitely. Without this bound, every subsequent LatestSnapshot call would build a
	// SnapshotKeys list (12 keys per tank) and a query string sized off that number, unbounded —
	// found in review of PR #11.
	if tankCount < 0 || tankCount > protocol.MaxTankCount {
		return 0, nil, fmt.Errorf("latest snapshot for %s: %s value %d is outside the allowed range [0, %d]",
			deviceID, protocol.KeyTankCount, tankCount, protocol.MaxTankCount)
	}

	snapshotTs := tc.TS
	keys := protocol.SnapshotKeys(tankCount)
	// endMs is exclusive on ThingsBoard's history API, so +1 to include the anchor instant itself.
	history, err := c.Timeseries(ctx, deviceID, TimeseriesQuery{Keys: keys, StartMs: snapshotTs, EndMs: snapshotTs + 1, Limit: 1})
	if err != nil {
		return 0, nil, fmt.Errorf("latest snapshot for %s: %w", deviceID, err)
	}

	values := make(map[string]any, len(history))
	for k, points := range history {
		for _, p := range points {
			// Guard against a key whose only point in the window is not actually the anchor
			// instant (should not happen given the [snapshotTs, snapshotTs+1) window, but a
			// mismatched point silently rejoining a foreign snapshot is exactly the bug this
			// method exists to avoid, so it is worth checking rather than trusting the range).
			if p.TS == snapshotTs {
				values[k] = p.Value
				break
			}
		}
	}
	return snapshotTs, values, nil
}

// TimeseriesQuery describes a history read.
type TimeseriesQuery struct {
	Keys           []string
	StartMs, EndMs int64
	// Limit caps the points ThingsBoard returns, per key.
	Limit int
	// Descending asks ThingsBoard for the NEWEST points first.
	//
	// This has to be pushed down to the server rather than sorted client-side: with a limit in
	// play, ascending returns the oldest N in the range and descending the newest N. Those are
	// different sets, not different orders, so reversing an ascending page would silently answer
	// "the most recent alarms" with the oldest ones.
	Descending bool

	// Agg and IntervalMs ask ThingsBoard to aggregate server-side: one point per IntervalMs bucket,
	// reduced by Agg ("AVG", "MIN", "MAX", ...). Empty Agg means raw points.
	//
	// Behaviour measured against CE 4.2.1.1 (issue #24), not assumed, because it differs from the raw
	// path in ways that matter to callers:
	//   - Limit is ignored: the point count is (EndMs-StartMs)/IntervalMs, so the caller must bound
	//     the range/interval ratio itself.
	//   - Descending is ignored: aggregated results always come back ascending.
	//   - Each point's TS is the bucket MIDPOINT (bucket start + IntervalMs/2), not its start.
	//   - Buckets are anchored at StartMs, and a bucket with no samples is omitted rather than
	//     returned empty.
	Agg        string
	IntervalMs int64
}

// Timeseries returns each key's history within the query's time range.
func (c *Client) Timeseries(ctx context.Context, deviceID string, q TimeseriesQuery) (map[string][]TSValue, error) {
	if len(q.Keys) == 0 {
		return map[string][]TSValue{}, nil
	}
	if q.Limit <= 0 {
		q.Limit = 1000
	}
	order := "ASC"
	if q.Descending {
		order = "DESC"
	}

	values := url.Values{
		"keys":    {strings.Join(q.Keys, ",")},
		"startTs": {strconv.FormatInt(q.StartMs, 10)},
		"endTs":   {strconv.FormatInt(q.EndMs, 10)},
		"limit":   {strconv.Itoa(q.Limit)},
		"orderBy": {order},
		// Without this ThingsBoard applies its own aggregation defaults to numeric series; we want
		// the raw points as stored.
		"useStrictDataTypes": {"false"},
	}
	if q.Agg != "" {
		values.Set("agg", q.Agg)
		values.Set("interval", strconv.FormatInt(q.IntervalMs, 10))
	}
	path := "/api/plugins/telemetry/DEVICE/" + url.PathEscape(deviceID) + "/values/timeseries" + query(values)

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
	kvs, err := c.AttributesWithTS(ctx, deviceID, scope, keys)
	if err != nil {
		return nil, err
	}
	out := make(map[string]any, len(kvs))
	for _, kv := range kvs {
		out[kv.Key] = kv.Value
	}
	return out, nil
}

// AttributesWithTS is Attributes keeping each attribute's lastUpdateTs, for callers that need to say
// when the device last reported a value (the tank settings mirror's reportedAt).
func (c *Client) AttributesWithTS(ctx context.Context, deviceID, scope string, keys []string) ([]AttributeKV, error) {
	q := url.Values{}
	if len(keys) > 0 {
		q.Set("keys", strings.Join(keys, ","))
	}
	path := "/api/plugins/telemetry/DEVICE/" + url.PathEscape(deviceID) + "/values/attributes/" + scope + query(q)

	var raw []AttributeKV
	if err := c.do(ctx, http.MethodGet, path, nil, &raw); err != nil {
		return nil, fmt.Errorf("attributes(%s) for %s: %w", scope, deviceID, err)
	}
	return raw, nil
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
