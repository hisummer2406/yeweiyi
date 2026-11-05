-- User<->device authorization (CLAUDE.md "Known cloud-side gaps", closed 2026-08-29). Many-to-many:
-- a device can have more than one bound user (e.g. multiple staff monitoring the same tank), and a
-- user can have more than one bound device. Pairing flow (product decision, 2026-08-29): the
-- mini-program scans a QR code printed/displayed on the physical device, which encodes the device's
-- device_name — see internal/api/devices.go's bindDeviceHandler doc comment for the trust model
-- this implies (possession of the physical device is the authorization).
CREATE TABLE user_devices (
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_name TEXT NOT NULL REFERENCES devices (device_name) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_name)
);

-- Supports "which users are bound to this device" (not used by any endpoint yet, but the natural
-- reverse lookup of the table's own primary key order — cheap to add now).
CREATE INDEX user_devices_device_name_idx ON user_devices (device_name);
