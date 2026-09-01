// Package protocol is the single source of truth for 设备协议 V1 — the contract Android,
// ThingsBoard CE and this Go backend all speak. See docs/protocol-v1.md for the prose spec and
// fixtures/protocol/v1/ for the shared JSON fixtures that both this package's tests and the Android
// side's tests are expected to validate against.
//
// Scope note: this package deliberately contains no Alink/Paho concepts. The device connects only
// to ThingsBoard CE over ThingsBoard's own native MQTT topics (v1/devices/me/...); there is no
// second MQTT stack and no compatibility layer with the legacy sys/{productKey}/... topic shape
// still implemented in internal/mqtt (which GitHub issue #10 removes).
//
// The types here model the *wire* shapes only. Nothing in this package talks to a network, a
// database or ThingsBoard — internal/tbclient does that, so that the protocol definition stays
// testable from fixtures alone and can be vendored into other tools unchanged.
package protocol

// Version is the protocol version this package implements. It is reported by the device as the
// `protocolVersion` client attribute and echoed in every RPC envelope, so both sides can detect a
// mismatch instead of silently misparsing.
//
// Compatibility policy (docs/protocol-v1.md §2): within a major version, a receiver MUST ignore
// JSON fields it does not recognise and MUST NOT fail on a telemetry key it has no use for. Adding
// a key, an enum member or an RPC method is a compatible change. Renaming or retyping anything, or
// changing a unit, is not — that requires a new Version.
const Version = 1
