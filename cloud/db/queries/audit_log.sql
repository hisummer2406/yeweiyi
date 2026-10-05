-- name: WriteAuditLog :one
INSERT INTO audit_log (
    actor_id, actor, action, device_name, command_id, outcome, detail, customer_id
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
RETURNING *;

-- name: ListAuditLog :many
-- device_name is optional: passing NULL lists across all devices. customer_id is optional too:
-- NULL means no customer filter, which only a platform admin may ask for — every other caller's
-- handler pins it to their own customer, so a device that changed hands never exposes the previous
-- owner's history to the new one.
SELECT * FROM audit_log
WHERE (sqlc.narg('device_name')::TEXT IS NULL OR device_name = sqlc.narg('device_name')::TEXT)
  AND (sqlc.narg('customer_id')::BIGINT IS NULL OR customer_id = sqlc.narg('customer_id')::BIGINT)
  AND occurred_at >= sqlc.arg('begin_time')
  AND occurred_at <= sqlc.arg('end_time')
ORDER BY occurred_at DESC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: ListAuditLogForCommand :many
SELECT * FROM audit_log
WHERE command_id = $1
ORDER BY occurred_at ASC;
