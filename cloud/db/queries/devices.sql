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
