-- name: BindUserDevice :exec
-- Idempotent: scanning the same QR code twice (or the same device already bound) is a no-op, not
-- an error — matches this project's general "safe to redeliver" preference for write paths.
INSERT INTO user_devices (user_id, device_name) VALUES ($1, $2)
ON CONFLICT (user_id, device_name) DO NOTHING;

-- name: UnbindUserDevice :exec
DELETE FROM user_devices WHERE user_id = $1 AND device_name = $2;

-- name: UserDeviceBindingExists :one
-- Only answers "is there a user_devices row". It is NOT an authorization check: it ignores the
-- customer boundary and the role tiers. Every device-scoped handler must call UserMayAccessDevice.
SELECT EXISTS (
    SELECT 1 FROM user_devices WHERE user_id = $1 AND device_name = $2
) AS bound;

-- name: UserMayAccessDevice :one
-- The single source of truth for "may this user touch this device" — REST, WebSocket and control
-- commands all go through it. Decided from the database's *current* role and customer, never from
-- JWT claims, so moving a user to another customer cuts their access immediately instead of when
-- their token expires (design D3).
--
--   platform_admin  any device, including one that does not exist (authorization comes before
--                   existence — CLAUDE.md invariant 6)
--   customer_admin  every device of their own customer, bound or not
--   customer_user   a device of their own customer AND bound to it
--
-- An unassigned device (customer_id NULL) never matches a customer, because NULL = anything is not
-- true. A user id that no longer exists, or that has been disabled, matches nothing.
SELECT EXISTS (
    SELECT 1
    FROM users u
    WHERE u.id = sqlc.arg('user_id')
      AND u.disabled_at IS NULL
      AND (
          u.role = 'platform_admin'
          OR EXISTS (
              SELECT 1
              FROM devices d
              WHERE d.device_name = sqlc.arg('device_name')
                AND d.customer_id = u.customer_id
                AND (
                    u.role = 'customer_admin'
                    OR EXISTS (
                        SELECT 1 FROM user_devices ud
                        WHERE ud.user_id = u.id AND ud.device_name = d.device_name
                    )
                )
          )
      )
) AS allowed;

-- name: UserMayBindDevice :one
-- Pairing-time tenant check, run after the pairing secret has been verified. A platform admin may
-- bind anything (as before); everyone else only a device of their own customer. A device that is
-- unassigned or belongs to another customer answers false, and the handler reports that exactly
-- like a wrong secret so the endpoint cannot be used to learn which devices exist or who owns them.
SELECT EXISTS (
    SELECT 1
    FROM users u
    WHERE u.id = sqlc.arg('user_id')
      AND u.disabled_at IS NULL
      AND (
          u.role = 'platform_admin'
          OR EXISTS (
              SELECT 1 FROM devices d
              WHERE d.device_name = sqlc.arg('device_name')
                AND d.customer_id = u.customer_id
          )
      )
) AS allowed;

-- name: ListDevicesForUser :many
-- Same three-way rule as UserMayAccessDevice, expressed as a list.
SELECT d.*
FROM devices d
JOIN users u ON u.id = $1 AND u.disabled_at IS NULL
WHERE u.role = 'platform_admin'
   OR (
       d.customer_id = u.customer_id
       AND (
           u.role = 'customer_admin'
           OR EXISTS (
               SELECT 1 FROM user_devices ud
               WHERE ud.user_id = u.id AND ud.device_name = d.device_name
           )
       )
   )
ORDER BY d.device_name;

-- name: DeleteBindingsForDevice :execrows
-- Run in the same transaction as a device changing customer: bindings made under the old customer
-- must not survive into the new one.
DELETE FROM user_devices WHERE device_name = $1;

-- name: DeleteBindingsForUser :execrows
-- Run in the same transaction as a user changing customer.
DELETE FROM user_devices WHERE user_id = $1;
