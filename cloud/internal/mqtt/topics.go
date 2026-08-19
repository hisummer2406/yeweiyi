// Package mqtt implements the two backend responsibilities from design doc §4.1 that talk MQTT:
// subscribing to device-initiated events and polling thing/service/* on a cadence. Topic shapes
// and identifiers below are taken directly from the Android repo's BackService.java/MqttUtil.java —
// that device-side code is the mirror image of this package (it subscribes where we publish, and
// publishes where we subscribe), so it's the source of truth for topic naming, not the design doc's
// summary table (see the comments below for spots where the doc's summary didn't match the source).
package mqtt

import (
	"fmt"
	"strings"
)

// Identifiers match the string constants in BackService.java exactly — they're the topic's last
// path segment (or the segment between .../event/ and /post for isOline/msg), not free-form names.
const (
	IdentifierIsOnline         = "isOline" // sic — matches the device's own typo'd constant
	IdentifierRequestOilcan    = "requestOilcan"
	IdentifierRequestUnloadOil = "requestUnloadOil"
	IdentifierRequestAlarm     = "requestAlarm"
	IdentifierRequestOilLeak   = "requestOilLeak"
	IdentifierMsg              = "msg"
	IdentifierRequestOilStorage = "requestOilStorage"
	IdentifierRequestShift     = "requestShift"
	IdentifierRequestTankTable = "requestTankTable"
	IdentifierRequestTankSet   = "requestTankSet"
	IdentifierRequestProbeSet  = "requestProbeSet"
)

// serviceTopic mirrors MqttUtil.getService: sys/{productKey}/{deviceName}/thing/service/{id}.
// The device subscribes here; the backend publishes requests here.
func serviceTopic(productKey, deviceName, identifier string) string {
	return fmt.Sprintf("sys/%s/%s/thing/service/%s", productKey, deviceName, identifier)
}

// propertyTopic mirrors MqttUtil.getPropert: sys/{productKey}/{deviceName}/thing/event/property/post/{id}.
// The device publishes its response here; the backend subscribes here.
func propertyTopic(productKey, deviceName, identifier string) string {
	return fmt.Sprintf("sys/%s/%s/thing/event/property/post/%s", productKey, deviceName, identifier)
}

// eventTopic mirrors MqttUtil.getEvent: sys/{productKey}/{deviceName}/thing/event/{id}/post. Used
// for isOline (Will + online-status) and msg (real-time alarm push). The backend subscribes here.
func eventTopic(productKey, deviceName, identifier string) string {
	return fmt.Sprintf("sys/%s/%s/thing/event/%s/post", productKey, deviceName, identifier)
}

// propertyTopicFilter and eventTopicFilter use MQTT wildcards so the backend can subscribe once per
// product and receive every device's messages, rather than issuing a per-device Subscribe call —
// necessary at the ~10,000-device scale CLAUDE.md targets. This is a Go-backend architectural
// choice, not something mirrored from BackService.java: the device only ever handles its own single
// deviceName, so this pattern has no equivalent on that side.
func propertyTopicFilter(productKey string) string {
	return fmt.Sprintf("sys/%s/+/thing/event/property/post/+", productKey)
}

func eventTopicFilter(productKey, identifier string) string {
	return fmt.Sprintf("sys/%s/+/thing/event/%s/post", productKey, identifier)
}

// parsePropertyTopic extracts (deviceName, identifier) from a topic matching propertyTopicFilter's
// shape: sys/{productKey}/{deviceName}/thing/event/property/post/{identifier} — 8 segments.
func parsePropertyTopic(topic string) (deviceName, identifier string, ok bool) {
	parts := strings.Split(topic, "/")
	if len(parts) != 8 || parts[0] != "sys" || parts[3] != "thing" || parts[4] != "event" || parts[5] != "property" || parts[6] != "post" {
		return "", "", false
	}
	return parts[2], parts[7], true
}

// parseEventTopic extracts deviceName from a topic matching eventTopicFilter's shape:
// sys/{productKey}/{deviceName}/thing/event/{identifier}/post — 7 segments. The identifier is
// already known by the caller (one subscription per identifier), so it isn't returned.
func parseEventTopic(topic string) (deviceName string, ok bool) {
	parts := strings.Split(topic, "/")
	if len(parts) != 7 || parts[0] != "sys" || parts[3] != "thing" || parts[4] != "event" || parts[6] != "post" {
		return "", false
	}
	return parts[2], true
}
