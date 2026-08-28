-- name: BindUserDevice :exec
-- Idempotent: scanning the same QR code twice (or the same device already bound) is a no-op, not
-- an error — matches this project's general "safe to redeliver" preference for write paths.
INSERT INTO user_devices (user_id, device_name) VALUES ($1, $2)
ON CONFLICT (user_id, device_name) DO NOTHING;

-- name: UnbindUserDevice :exec
DELETE FROM user_devices WHERE user_id = $1 AND device_name = $2;

-- name: ListDevicesForUser :many
SELECT d.* FROM devices d
JOIN user_devices ud ON ud.device_name = d.device_name
WHERE ud.user_id = $1
ORDER BY d.device_name;

-- name: UserOwnsDevice :one
-- Backs every device-scoped handler's authorization check (see internal/api's requireDeviceOwnership).
SELECT EXISTS (
    SELECT 1 FROM user_devices WHERE user_id = $1 AND device_name = $2
) AS owns;
