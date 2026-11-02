-- name: EnsureDevice :exec
-- Every other table FKs to devices(device_name). Message ordering over MQTT isn't guaranteed, so an
-- alarm/oil-in-out/shift response can arrive before the first isOline event for a brand-new device
-- — handlers call this first so the FK insert doesn't fail.
INSERT INTO devices (device_name) VALUES ($1) ON CONFLICT (device_name) DO NOTHING;

-- name: SyncDeviceOnlineStatus :execrows
-- 按 ThingsBoard 的 active 标志同步在线状态；只在状态真的变化时才写，所以时间戳记录的是
-- 观察到变化的时刻（精度即轮询间隔），而不是每次轮询都刷新。业务库里没有的设备不会被创建。
UPDATE devices d
SET is_online = s.active,
    last_online_at  = CASE WHEN s.active THEN now() ELSE d.last_online_at END,
    last_offline_at = CASE WHEN s.active THEN d.last_offline_at ELSE now() END,
    updated_at = now()
FROM (
    SELECT unnest(sqlc.arg(device_names)::text[]) AS device_name,
           unnest(sqlc.arg(actives)::bool[])      AS active
) s
WHERE d.device_name = s.device_name
  AND d.is_online IS DISTINCT FROM s.active;

-- name: MarkDevicesOfflineExcept :execrows
-- ThingsBoard 里已经不存在的设备（被删除）不会再出现在列表里，只靠 SyncDeviceOnlineStatus 它们会
-- 永远停在在线。传入本轮在 ThingsBoard 看到的全部设备名，其余仍标记在线的一律置为离线。
UPDATE devices
SET is_online = false,
    last_offline_at = now(),
    updated_at = now()
WHERE is_online
  AND NOT (device_name = ANY(sqlc.arg(seen_names)::text[]));

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
-- A device being purged (cloud#83) is left out, so rollup, cmd/backfill and new subscriptions skip it;
-- the purge stops its running subscription itself.
SELECT device_name FROM devices d
WHERE NOT EXISTS (SELECT 1 FROM device_purges p WHERE p.device_name = d.device_name AND p.status <> 'completed')
ORDER BY device_name;

-- name: EnsureDeviceForCustomer :one
-- Provisioning-time registration (pairing-secret minting). Creates the device under the given
-- customer, or claims an existing *unassigned* one for it. A device that already belongs to a
-- different customer is left alone and its current customer_id is returned, so the caller can see
-- the mismatch and refuse: minting must never silently move a device between customers — that is
-- a reassignment, which also has to clear the old customer's user_devices bindings.
-- No row comes back while the device is being purged (cloud#83): the caller answers 409
-- device_purge_in_progress instead of reviving a row the purge is about to delete.
INSERT INTO devices (device_name, customer_id)
SELECT sqlc.arg('device_name')::TEXT, sqlc.arg('customer_id')
WHERE device_record_writable(sqlc.arg('device_name')::TEXT, NULL)
ON CONFLICT (device_name) DO UPDATE
    SET customer_id = COALESCE(devices.customer_id, EXCLUDED.customer_id)
RETURNING customer_id;

-- name: GetDeviceForUpdate :one
-- Reassignment locks the row so two concurrent reassignments cannot both clear bindings and then
-- both win.
SELECT * FROM devices WHERE device_name = $1 FOR UPDATE;

-- name: SetDeviceCustomer :exec
-- The station belongs to the old customer and must go in the same statement: devices_station_customer_fk
-- would otherwise refuse the change.
UPDATE devices SET customer_id = $2, station_id = NULL, updated_at = now() WHERE device_name = $1;

-- name: RegisterDeviceForCustomer :one
-- Idempotent operator registration (POST /api/devices). Same ownership rule as
-- EnsureDeviceForCustomer — create, or claim an unassigned device, never move an assigned one — but
-- also reports whether this call inserted the row, so the caller can answer 201 vs 200. xmax is 0
-- only on a freshly inserted tuple; a conflict-update leaves it non-zero. The caller compares the
-- returned customer_id with the requested one to detect a device owned by someone else.
-- Like EnsureDeviceForCustomer, no row comes back while the device is being purged.
INSERT INTO devices (device_name, customer_id)
SELECT sqlc.arg('device_name')::TEXT, sqlc.arg('customer_id')
WHERE device_record_writable(sqlc.arg('device_name')::TEXT, NULL)
ON CONFLICT (device_name) DO UPDATE
    SET customer_id = COALESCE(devices.customer_id, EXCLUDED.customer_id)
RETURNING customer_id, (xmax = 0) AS inserted;

-- name: SetDeviceStation :exec
-- station_id NULL puts the device back to "ungrouped". The composite foreign key rejects a station of
-- a different customer (23503) and the CHECK rejects a device with no customer (23514); the handler
-- checks both first so those are the answers it gives, and the database is the backstop for a race.
UPDATE devices SET station_id = sqlc.narg('station_id'), updated_at = now() WHERE device_name = sqlc.arg('device_name');
