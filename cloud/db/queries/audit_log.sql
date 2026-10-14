-- name: WriteAuditLog :one
INSERT INTO audit_log (
    actor_id, actor, action, device_name, command_id, outcome, detail, customer_id
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
RETURNING *;

-- name: ListAuditLogUnscoped :many
-- INTERNAL and test use only: no authorization. Anything that answers a user's request must use
-- ListAuditLogVisibleToUser.
SELECT * FROM audit_log
WHERE (sqlc.narg('device_name')::TEXT IS NULL OR device_name = sqlc.narg('device_name')::TEXT)
  AND (sqlc.narg('customer_id')::BIGINT IS NULL OR customer_id = sqlc.narg('customer_id')::BIGINT)
  AND occurred_at >= sqlc.arg('begin_time')
  AND occurred_at <= sqlc.arg('end_time')
ORDER BY occurred_at DESC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: ListAuditLogVisibleToUser :many
-- What a user may read of the audit log, with the whole policy decided in the statement that reads
-- it (see ListControlCommandsVisibleToUser for why):
--   * a disabled or unknown user reads nothing;
--   * naming a device requires access to it (user_may_access_device);
--   * a customer user reads nothing (tank-level view access only, cloud#63; audit rows are device-wide);
--   * a customer still being configured (cloud#60) reads nothing for its non-platform accounts, with or
--     without a device named (user_may_access_device only covers the named case);
--   * a platform admin sees every customer's rows, optionally narrowed by customer_id;
--   * everyone else is pinned to their own customer's rows, even when a device is named, so a device
--     that changed hands never shows its previous owner's history to the new one.
SELECT a.*
FROM audit_log a
JOIN users u ON u.id = sqlc.arg('user_id') AND u.disabled_at IS NULL
WHERE (sqlc.narg('device_name')::TEXT IS NULL OR a.device_name = sqlc.narg('device_name')::TEXT)
  AND (sqlc.narg('device_name')::TEXT IS NULL OR user_may_access_device(u.id, sqlc.narg('device_name')::TEXT))
  AND u.role <> 'customer_user'
  AND (u.role = 'platform_admin'
       OR EXISTS (SELECT 1 FROM customers c WHERE c.id = u.customer_id AND c.onboarding_status = 'active'))
  AND CASE
          WHEN u.role = 'platform_admin'
              THEN (sqlc.narg('customer_id')::BIGINT IS NULL OR a.customer_id = sqlc.narg('customer_id')::BIGINT)
          ELSE a.customer_id = u.customer_id
      END
  AND a.occurred_at >= sqlc.arg('begin_time')
  AND a.occurred_at <= sqlc.arg('end_time')
ORDER BY a.occurred_at DESC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: ListAuditLogForCommand :many
SELECT * FROM audit_log
WHERE command_id = $1
ORDER BY occurred_at ASC;
