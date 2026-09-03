-- name: CreateControlCommand :one
-- issued_at is supplied by the caller rather than defaulted to now(), so that it and expires_at come
-- from the SAME clock. Letting the column default to the database's now() while the application
-- computes expires_at from its own clock means the two can disagree by however long the round trip
-- takes — which violates the expires_at > issued_at constraint outright once the command TTL is
-- shorter than that, and silently shortens every command's real validity window even when it does
-- not. The envelope sent to the device carries the same issued_at, so device and cloud agree too.
INSERT INTO control_commands (
    command_id, device_name, method, args,
    operator_id, operator, reason, issued_at, expires_at
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
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
-- duplicate cmdResult, or one arriving after expiry, must not rewrite the record. Also guarded on
-- device_name, which the ingestion subscription determines from the connection the message arrived
-- on, not from anything inside the payload: without this, device B reporting a commandId that
-- belongs to device A would be able to complete A's command and have the audit trail misattribute
-- the result to B, breaking the per-device isolation the rest of this schema assumes.
UPDATE control_commands
SET status        = $3,
    error_code    = $4,
    error_reason  = $5,
    result_detail = $6,
    finished_at   = CASE WHEN $3 = 'executing' THEN finished_at ELSE now() END,
    updated_at    = now()
WHERE command_id = $1
  AND device_name = $2
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
