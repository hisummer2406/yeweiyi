-- name: UpsertShift :exec
-- Ingests one row from thing/event/property/post/requestShift (design doc §4.2). `info` is stored
-- as the raw JSON string the device sends (CommitWork.java's `info` field, DB column
-- `oilinfogson` — the two names differ on-device; the MQTT JSON key is `info`, matching the Java
-- field name, not the DB column name, since Gson serializes by field name with no @SerializedName
-- override).
INSERT INTO commit_works (device_name, device_local_id, work_id, time, end_time, info)
VALUES ($1, $2, $3, $4, $5, $6)
ON CONFLICT (device_name, device_local_id) DO UPDATE SET
    work_id = EXCLUDED.work_id,
    time = EXCLUDED.time,
    end_time = EXCLUDED.end_time,
    info = EXCLUDED.info,
    received_at = now();

-- name: ListShiftsAsc :many
SELECT * FROM commit_works
WHERE device_name = $1 AND time >= $2 AND time <= $3
ORDER BY time ASC
LIMIT $4 OFFSET $5;

-- name: ListShiftsDesc :many
SELECT * FROM commit_works
WHERE device_name = $1 AND time >= $2 AND time <= $3
ORDER BY time DESC
LIMIT $4 OFFSET $5;
