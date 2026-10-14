-- 员工-油罐查看授权（迁移 000024，cloud#63）。授权判定本身在 SQL 函数 user_may_access_device /
-- user_may_access_tank 里，这里只是授权关系的读写。

-- name: ListTankGrantsForUser :many
SELECT user_id, device_name, tank, granted_by, created_at
FROM user_tank_grants
WHERE user_id = $1
ORDER BY device_name, tank;

-- name: ListGrantedTanks :many
-- 用户在某设备上被授权的罐号（升序）。调用方用它过滤整机快照；管理员不走这条，他们看全部。
SELECT tank FROM user_tank_grants WHERE user_id = $1 AND device_name = $2 ORDER BY tank;

-- name: InsertTankGrantIfAllowed :execrows
-- 判定与写入是同一条语句：被授权者必须是 customer_user，且设备与其同属一个客户。重复授权是 no-op
-- （ON CONFLICT），返回 0 行 = 已存在或被拒绝；调用方在同一事务里先用 GetUserForUpdate /
-- GetDeviceForUpdate 复核并区分这两种情况。
INSERT INTO user_tank_grants (user_id, device_name, tank, granted_by)
SELECT t.id, d.device_name, sqlc.arg('tank')::INT, sqlc.narg('granted_by')::BIGINT
FROM users t
JOIN devices d ON d.device_name = sqlc.arg('device_name')::TEXT AND d.customer_id = t.customer_id
WHERE t.id = sqlc.arg('user_id')
  AND t.role = 'customer_user'
ON CONFLICT (user_id, device_name, tank) DO NOTHING;

-- name: DeleteTankGrantsForUserDevice :execrows
DELETE FROM user_tank_grants WHERE user_id = $1 AND device_name = $2;

-- name: DeleteTankGrantsForUser :one
-- 与用户换客户同一个事务：旧客户下的授权不能带进新客户。返回被清除授权涉及的设备数。
WITH d AS (DELETE FROM user_tank_grants WHERE user_id = $1 RETURNING device_name)
SELECT count(DISTINCT device_name)::INT AS cleared FROM d;

-- name: DeleteTankGrantsForDevice :one
-- 与设备换客户同一个事务。返回被清除授权涉及的用户数。
WITH d AS (DELETE FROM user_tank_grants WHERE device_name = $1 RETURNING user_id)
SELECT count(DISTINCT user_id)::INT AS cleared FROM d;

-- name: ResolveDeviceForNavigation :one
-- 扫码/短码只做定位：不写任何东西。只在调用者所属客户的设备里找（精确匹配优先，再按前缀顺序），
-- 且只返回调用者本来就能定位的设备（客户管理员：本客户设备；员工：至少有一个被授权罐的设备）。
-- 无权、不存在、别的客户的设备一律无行，与不存在不可区分。平台管理员不走这里。
WITH u AS (
    SELECT users.id, users.customer_id
    FROM users
    WHERE users.id = sqlc.arg('user_id')
      AND users.disabled_at IS NULL
      AND users.role IN ('customer_admin', 'customer_user')
      AND users.customer_id IS NOT NULL
)
SELECT d.device_name
FROM devices d, u
WHERE d.customer_id = u.customer_id
  AND d.device_name = ANY(sqlc.arg('candidates')::text[])
  AND user_may_access_device(u.id, d.device_name)
ORDER BY array_position(sqlc.arg('candidates')::text[], d.device_name)
LIMIT 1;

-- name: UserMayAccessTank :one
-- Tank-level decision for one (device, tank), by the same SQL function the record queries use.
SELECT user_may_access_tank(sqlc.arg('user_id'), sqlc.arg('device_name'), sqlc.arg('tank')::INT)::bool AS allowed;

-- name: DeleteTankGrant :execrows
DELETE FROM user_tank_grants WHERE user_id = $1 AND device_name = $2 AND tank = $3;
