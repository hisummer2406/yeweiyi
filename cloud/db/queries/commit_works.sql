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
