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
