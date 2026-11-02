-- 设备彻底删除（cloud#83，迁移 000032，契约 docs/device-purge-contract.md）。

-- name: CreateDevicePurge :one
-- 设备行须已由调用方在同一事务里锁住（GetDeviceForUpdate）。同设备已有未完成任务时唯一索引冲突（23505），
-- 调用方改读那个任务。
INSERT INTO device_purges (device_name, reason, requested_by, requested_username, customer_id, station_id)
VALUES (sqlc.arg('device_name'), sqlc.arg('reason'), sqlc.arg('requested_by'), sqlc.arg('requested_username'),
        sqlc.narg('customer_id'), sqlc.narg('station_id'))
RETURNING *;

-- name: GetDevicePurge :one
SELECT * FROM device_purges WHERE id = $1;

-- name: GetOpenDevicePurge :one
SELECT * FROM device_purges WHERE device_name = $1 AND status <> 'completed';

-- name: ListDevicePurgesByDevice :many
SELECT * FROM device_purges WHERE device_name = $1 ORDER BY started_at DESC, id DESC;

-- name: ListRecentDevicePurges :many
SELECT * FROM device_purges ORDER BY started_at DESC, id DESC LIMIT sqlc.arg('lim');

-- name: ListRunningDevicePurges :many
-- 启动时续跑：被重启打断的任务。failed 的等人工再次发起。
SELECT * FROM device_purges WHERE status = 'running' ORDER BY id;

-- name: RestartDevicePurge :one
-- 再次发起：failed → running，保留 step，从断点继续。
UPDATE device_purges SET status = 'running', error = NULL, updated_at = now()
WHERE id = $1 AND status <> 'completed'
RETURNING *;

-- name: AdvanceDevicePurge :one
-- 一步完成：合并本步条数，推进到下一步。只推进 running 任务，且只从预期的那一步推进。
UPDATE device_purges
SET step = sqlc.arg('next_step'), counts = counts || sqlc.arg('counts')::jsonb, error = NULL, updated_at = now()
WHERE id = sqlc.arg('id') AND status = 'running' AND step = sqlc.arg('step')
RETURNING *;

-- name: CompleteDevicePurge :one
UPDATE device_purges
SET status = 'completed', step = 'done', error = NULL, finished_at = now(), updated_at = now()
WHERE id = $1 AND status = 'running' AND step = 'verify'
RETURNING *;

-- name: FailDevicePurge :one
UPDATE device_purges SET status = 'failed', error = sqlc.arg('error'), updated_at = now()
WHERE id = sqlc.arg('id') AND status = 'running'
RETURNING *;

-- name: DevicePurgeInProgress :one
SELECT EXISTS (SELECT 1 FROM device_purges WHERE device_name = $1 AND status <> 'completed')::BOOLEAN AS blocked;

-- 以下按设备编号删除业务库数据，全部在 business 步骤的同一事务里执行。设备行最后删，级联带走
-- control_commands、user_tank_grants、user_devices、device_pairing_secrets（先计数再删）。

-- name: PurgeStockEvents :execrows
DELETE FROM stock_events WHERE device_name = $1;

-- name: PurgeAlarmEvents :execrows
DELETE FROM alarm_events WHERE device_name = $1;

-- name: PurgeRecordRejects :execrows
DELETE FROM record_rejects WHERE device_name = $1;

-- name: PurgeRecordBackfills :execrows
DELETE FROM record_backfills WHERE device_name = $1;

-- name: PurgeTelemetryHourly :execrows
DELETE FROM telemetry_hourly WHERE device_name = $1;

-- name: PurgeAuditLog :execrows
-- 本删除动作自身的审计（device.purge）保留：它记录的是删除，不是设备历史。
DELETE FROM audit_log WHERE device_name = sqlc.arg('device_name')::TEXT AND action <> 'device.purge';

-- name: CountDeviceCascades :one
SELECT
    (SELECT COUNT(*) FROM control_commands c WHERE c.device_name = sqlc.arg('device_name')::TEXT)::BIGINT       AS control_commands,
    (SELECT COUNT(*) FROM user_tank_grants g WHERE g.device_name = sqlc.arg('device_name')::TEXT)::BIGINT       AS tank_grants,
    (SELECT COUNT(*) FROM user_devices u WHERE u.device_name = sqlc.arg('device_name')::TEXT)::BIGINT           AS user_devices,
    (SELECT COUNT(*) FROM device_pairing_secrets s WHERE s.device_name = sqlc.arg('device_name')::TEXT)::BIGINT AS pairing_secrets;

-- name: PurgeDeviceRow :execrows
DELETE FROM devices x WHERE x.device_name = sqlc.arg('device_name')::TEXT;

-- name: CountDeviceRemnants :one
-- verify 步骤：清单里每一类都应为 0。
SELECT
    (SELECT COUNT(*) FROM stock_events x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM alarm_events x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM record_rejects x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM record_backfills x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM telemetry_hourly x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM audit_log x WHERE x.device_name = sqlc.arg('device_name')::TEXT AND action <> 'device.purge')::BIGINT
  + (SELECT COUNT(*) FROM control_commands x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM user_tank_grants x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM user_devices x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM device_pairing_secrets x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT
  + (SELECT COUNT(*) FROM devices x WHERE x.device_name = sqlc.arg('device_name')::TEXT)::BIGINT AS remnants;
