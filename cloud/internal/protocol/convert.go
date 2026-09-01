package protocol

import (
	"encoding/json"
	"fmt"
	"strconv"
)

// The coercion helpers below exist because the same logical value reaches this package in three
// different Go types depending on which side of ThingsBoard it came from:
//
//   - straight off the device's MQTT payload, decoded by encoding/json: float64 / string / bool
//     (or json.Number if the decoder was configured that way);
//   - from ThingsBoard's REST timeseries API (/api/plugins/telemetry/.../values/timeseries):
//     *always a JSON string*, even for a numeric series — "88.8", "true", "3";
//   - from a ThingsBoard WebSocket telemetry subscription: also strings, in [ts, value] pairs.
//
// Rejecting a string where a float was expected would therefore make the exact same snapshot parse
// on one path and fail on another, so every reader here accepts all three and normalises.

func toFloat(raw any) (float64, error) {
	switch v := raw.(type) {
	case float64:
		return v, nil
	case float32:
		return float64(v), nil
	case int:
		return float64(v), nil
	case int64:
		return float64(v), nil
	case json.Number:
		return v.Float64()
	case string:
		f, err := strconv.ParseFloat(v, 64)
		if err != nil {
			return 0, fmt.Errorf("not a number: %q", v)
		}
		return f, nil
	case nil:
		return 0, nil
	default:
		return 0, fmt.Errorf("expected a number, got %T", raw)
	}
}

func toInt64(raw any) (int64, error) {
	if s, ok := raw.(string); ok {
		n, err := strconv.ParseInt(s, 10, 64)
		if err == nil {
			return n, nil
		}
		// fall through: a whole number may still arrive as "3.0" over REST
	}
	f, err := toFloat(raw)
	if err != nil {
		return 0, err
	}
	return int64(f), nil
}

func toString(raw any) (string, error) {
	switch v := raw.(type) {
	case string:
		return v, nil
	case nil:
		return "", nil
	default:
		return "", fmt.Errorf("expected a string, got %T", raw)
	}
}

func toBool(raw any) (bool, error) {
	switch v := raw.(type) {
	case bool:
		return v, nil
	case string:
		b, err := strconv.ParseBool(v)
		if err != nil {
			return false, fmt.Errorf("not a boolean: %q", v)
		}
		return b, nil
	case nil:
		return false, nil
	default:
		return false, fmt.Errorf("expected a boolean, got %T", raw)
	}
}
