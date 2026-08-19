-- name: UpsertAlarm :exec
-- Ingests one row from thing/event/property/post/requestAlarm (design doc §4.2). Keyed on the
-- device's own (device_local_id, time) per CLAUDE.md's composite-key design — a redelivered
-- response after a reconnect just overwrites the same row instead of duplicating it.
INSERT INTO alarms (device_name, device_local_id, time, info, guanhao, jiezhi)
VALUES ($1, $2, $3, $4, $5, $6)
ON CONFLICT (device_name, device_local_id, time) DO UPDATE SET
    info = EXCLUDED.info,
    guanhao = EXCLUDED.guanhao,
    jiezhi = EXCLUDED.jiezhi,
    received_at = now();

-- name: ListAlarmsAsc :many
SELECT * FROM alarms
WHERE device_name = $1 AND time >= $2 AND time <= $3
ORDER BY time ASC
LIMIT $4 OFFSET $5;

-- name: ListAlarmsDesc :many
SELECT * FROM alarms
WHERE device_name = $1 AND time >= $2 AND time <= $3
ORDER BY time DESC
LIMIT $4 OFFSET $5;
