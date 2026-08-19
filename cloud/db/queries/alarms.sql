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
