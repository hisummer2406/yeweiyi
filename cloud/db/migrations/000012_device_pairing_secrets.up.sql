-- Device pairing secret, closing the P1 found in review of PR #11 (round 6): the previous fix
-- (round 5) reused ThingsBoard's own device access token as the QR-code pairing proof, but that
-- value IS the device's MQTT identity (its passwordless MQTT username, see internal/devicesim's
-- SetUsername(AccessToken)). Leaking it — a photographed QR code, a proxy log, a client-side trace
-- — let an attacker impersonate the device on MQTT itself: forge telemetry, alarms and cmdResults,
-- not merely bind themselves to it.
--
-- This table holds a pairing secret deliberately unrelated to any ThingsBoard identity: minted and
-- verified entirely by this backend (see internal/api/devices.go's mintPairingSecretHandler /
-- verifyPairingToken), so leaking it can only let someone bind the device to their own account —
-- never speak for the device on the wire. Only a bcrypt hash is stored, the same way
-- users.password_hash is, since this is a bearer secret handed to whoever provisions the device
-- physically.
--
-- One row per device (PRIMARY KEY device_name): minting always rotates, replacing any previous
-- secret outright — a leaked or lost secret is fixed by minting a new one, not by adding rows.
-- Existing user_devices bindings are untouched by a rotation; only *future* bind attempts need the
-- new secret.
CREATE TABLE device_pairing_secrets (
    device_name TEXT NOT NULL PRIMARY KEY REFERENCES devices (device_name) ON DELETE CASCADE,
    secret_hash TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE device_pairing_secrets IS '设备配对密钥（哈希），与 ThingsBoard 设备凭据完全隔离，仅用于验证 /api/devices/bind 请求';
COMMENT ON COLUMN device_pairing_secrets.secret_hash IS 'bcrypt 哈希；明文仅在铸造接口的响应中返回一次，不落库';
COMMENT ON COLUMN device_pairing_secrets.created_at IS '本条密钥的铸造/轮换时间';
