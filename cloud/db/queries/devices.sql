-- name: EnsureDevice :exec
-- Every other table FKs to devices(device_name). Message ordering over MQTT isn't guaranteed, so an
-- alarm/oil-in-out/shift response can arrive before the first isOline event for a brand-new device
-- — handlers call this first so the FK insert doesn't fail.
INSERT INTO devices (device_name) VALUES ($1) ON CONFLICT (device_name) DO NOTHING;

-- name: UpsertDeviceOnlineStatus :exec
INSERT INTO devices (device_name, is_online, last_online_at, last_offline_at, updated_at)
VALUES (
    sqlc.arg(device_name),
    sqlc.arg(is_online),
    CASE WHEN sqlc.arg(is_online)::bool THEN now() ELSE NULL END,
    CASE WHEN sqlc.arg(is_online)::bool THEN NULL ELSE now() END,
    now()
)
ON CONFLICT (device_name) DO UPDATE SET
    is_online = EXCLUDED.is_online,
    last_online_at = COALESCE(EXCLUDED.last_online_at, devices.last_online_at),
    last_offline_at = COALESCE(EXCLUDED.last_offline_at, devices.last_offline_at),
    updated_at = now();

-- name: GetDevice :one
SELECT * FROM devices WHERE device_name = $1;

-- name: ListOnlineDeviceNames :many
-- Drives the requestOilcan poll loop (design doc §4.3) — only bother polling devices we believe
-- are actually online.
SELECT device_name FROM devices WHERE is_online;

-- name: ListAllDeviceNames :many
-- Drives the ThingsBoard telemetry ingestion path (internal/ingest), which subscribes per device.
-- Unlike ListOnlineDeviceNames this deliberately includes offline devices: a subscription must
-- already exist when a device comes back, or its first snapshot after reconnecting is missed.
SELECT device_name FROM devices ORDER BY device_name;

-- name: EnsureDeviceForCustomer :one
-- Provisioning-time registration (pairing-secret minting). Creates the device under the given
-- customer, or claims an existing *unassigned* one for it. A device that already belongs to a
-- different customer is left alone and its current customer_id is returned, so the caller can see
-- the mismatch and refuse: minting must never silently move a device between customers — that is
-- a reassignment, which also has to clear the old customer's user_devices bindings.
INSERT INTO devices (device_name, customer_id) VALUES ($1, $2)
ON CONFLICT (device_name) DO UPDATE
    SET customer_id = COALESCE(devices.customer_id, EXCLUDED.customer_id)
RETURNING customer_id;

-- name: GetDeviceForUpdate :one
-- Reassignment locks the row so two concurrent reassignments cannot both clear bindings and then
-- both win.
SELECT * FROM devices WHERE device_name = $1 FOR UPDATE;

-- name: SetDeviceCustomer :exec
UPDATE devices SET customer_id = $2, updated_at = now() WHERE device_name = $1;
