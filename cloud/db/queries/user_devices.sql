-- 旧的设备级绑定查询（BindUserDeviceIfAllowed / BindUserDeviceByCodeIfAllowed）已在 cloud#63 中移除：
-- 扫码不再创建任何授权，授权只来自 user_tank_grants（db/queries/tank_grants.sql）。user_devices 表保留为
-- 只读历史，不再被任何授权判定引用。

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
-- commands all go through it. The rule itself lives in the SQL function user_may_access_device
-- (migration 000015) so there is exactly one definition. Decided from the database's *current* role,
-- customer and disabled flag, never from JWT claims, so moving or disabling a user takes effect
-- immediately instead of when their token expires (design D3).
--
--   platform_admin  any device, including one that does not exist (authorization comes before
--                   existence — CLAUDE.md invariant 6)
--   customer_admin  every device of their own customer, bound or not
--   customer_user   a device of their own customer AND bound to it
--
-- An unassigned device never matches a customer; a user that no longer exists, or that has been
-- disabled, matches nothing.
SELECT user_may_access_device(sqlc.arg('user_id'), sqlc.arg('device_name'))::bool AS allowed;

-- name: AuthorizeDeviceAccess :one
-- The access decision (same function) plus the DATABASE clock for the moment of that decision.
-- Callers that clip a window of ThingsBoard data by devices.customer_assigned_at must cap it at
-- decided_at, not at the API host's clock: both values are then stamped by one clock, so skew
-- between the API host and PostgreSQL cannot defeat the cap.
--
-- statement_timestamp(), not clock_timestamp(): the decision reads the statement's MVCC snapshot,
-- which is taken after the statement starts. A transfer committing in between is invisible to the
-- decision (old owner still allowed), and clock_timestamp() could then land after that commit.
-- The statement start is guaranteed not to be later than the snapshot, so the cap is never later
-- than what the decision saw.
SELECT
    user_may_access_device(sqlc.arg('user_id'), sqlc.arg('device_name'))::bool AS allowed,
    statement_timestamp()::timestamptz AS decided_at;

-- name: ResolveDeviceAccess :one
-- The access decision (via the same function) plus the pieces the caller needs to pick the customer
-- a command or audit row is filed under, read in ONE statement so they come from one database
-- state. The caller picks the snapshot: the user's own customer, or — for a platform admin, who has
-- none — the device's customer right now (NULL when the device does not exist). No row at all means
-- the user does not exist.
--
-- This is for deciding early (and for auditing denials under the right customer). It is NOT what
-- keeps a command honest: CreateControlCommand re-checks the rule inside the INSERT itself.
SELECT
    user_may_access_device(u.id, sqlc.arg('device_name'))::bool AS allowed,
    u.customer_id AS user_customer_id,
    d.customer_id AS device_customer_id
FROM users u
LEFT JOIN devices d ON d.device_name = sqlc.arg('device_name')
WHERE u.id = sqlc.arg('user_id');

-- name: ListDevicesForUser :many
-- Same three-way rule as UserMayAccessDevice, expressed as a list. A customer user sees the devices on
-- which they hold at least one tank grant (cloud#63); the tanks themselves are listed separately.
-- A customer still being configured (cloud#60) lists nothing for its non-platform accounts, exactly
-- as user_may_access_device answers, so the list and the decision agree even for a caller that does
-- not go through the HTTP middleware.
SELECT d.*
FROM devices d
JOIN users u ON u.id = $1 AND u.disabled_at IS NULL
WHERE u.role = 'platform_admin'
   OR (
       d.customer_id = u.customer_id
       AND EXISTS (SELECT 1 FROM customers c WHERE c.id = u.customer_id AND c.onboarding_status = 'active')
       AND (
           u.role = 'customer_admin'
           OR EXISTS (
               SELECT 1 FROM user_tank_grants g
               WHERE g.user_id = u.id AND g.device_name = d.device_name
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
