-- name: CreateControlCommand :one
INSERT INTO control_commands (
    command_id, device_name, method, args,
    operator_id, operator, reason, expires_at
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
RETURNING *;

-- name: GetControlCommand :one
SELECT * FROM control_commands WHERE command_id = $1;

-- Status transitions are all guarded by the *current* status rather than written unconditionally.
-- Nothing else enforces the state machine — a late ACK arriving after the sweep already expired a
-- command would otherwise resurrect it, and a redelivered result would overwrite a terminal row.
-- Each of these returns 0 rows when the guard rejects the move, which callers treat as "already
-- moved on", not as an error.

-- name: MarkControlCommandSent :one
UPDATE control_commands
SET status = 'sent', sent_at = now(), updated_at = now()
WHERE command_id = $1 AND status = 'pending'
RETURNING *;

-- name: MarkControlCommandAcked :one
-- Applies the device's ACK: 'received' (accepted, not yet done) or 'rejected' (terminal).
-- Only a command we have actually sent can be acked.
UPDATE control_commands
SET status       = $2,
    error_code   = $3,
    error_reason = $4,
    acked_at     = now(),
    finished_at  = CASE WHEN $2 = 'rejected' THEN now() ELSE finished_at END,
    updated_at   = now()
WHERE command_id = $1
  AND status IN ('pending', 'sent')
RETURNING *;

-- name: MarkControlCommandFinished :one
-- Applies the device's asynchronous outcome. Guarded so that a terminal row stays terminal: a
-- duplicate cmdResult, or one arriving after expiry, must not rewrite the record.
UPDATE control_commands
SET status        = $2,
    error_code    = $3,
    error_reason  = $4,
    result_detail = $5,
    finished_at   = CASE WHEN $2 = 'executing' THEN finished_at ELSE now() END,
    updated_at    = now()
WHERE command_id = $1
  AND status IN ('sent', 'received', 'executing')
RETURNING *;

-- name: ExpireStaleControlCommands :many
-- The cloud-side half of the validity window: anything past its expiry that never reached a
-- terminal state is closed as 'expired'. The device enforces the same rule independently on
-- arrival, so this is about the command *record*, not about preventing the action.
UPDATE control_commands
SET status = 'expired', finished_at = now(), updated_at = now()
WHERE expires_at < now()
  AND status IN ('pending', 'sent', 'received', 'executing')
RETURNING *;

-- name: ListControlCommandsForDevice :many
SELECT * FROM control_commands
WHERE device_name = $1
ORDER BY issued_at DESC
LIMIT $2 OFFSET $3;
