-- name: UpsertOilInOut :exec
-- Ingests one row from thing/event/property/post/requestUnloadOil (design doc §4.2).
INSERT INTO oil_in_out (
    device_name, device_local_id, oil_id, jiezhi, time,
    begin_height, end_height, begin_volume, end_volume, begin_weight, end_weight
)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
ON CONFLICT (device_name, device_local_id, time) DO UPDATE SET
    oil_id = EXCLUDED.oil_id,
    jiezhi = EXCLUDED.jiezhi,
    begin_height = EXCLUDED.begin_height,
    end_height = EXCLUDED.end_height,
    begin_volume = EXCLUDED.begin_volume,
    end_volume = EXCLUDED.end_volume,
    begin_weight = EXCLUDED.begin_weight,
    end_weight = EXCLUDED.end_weight,
    received_at = now();

-- name: ListOilInOutAsc :many
SELECT * FROM oil_in_out
WHERE device_name = $1 AND time >= $2 AND time <= $3
ORDER BY time ASC
LIMIT $4 OFFSET $5;

-- name: ListOilInOutDesc :many
SELECT * FROM oil_in_out
WHERE device_name = $1 AND time >= $2 AND time <= $3
ORDER BY time DESC
LIMIT $4 OFFSET $5;
