-- name: CreateControlCommand :one
-- issued_at is supplied by the caller rather than defaulted to now(), so that it and expires_at come
-- from the SAME clock. Letting the column default to the database's now() while the application
-- computes expires_at from its own clock means the two can disagree by however long the round trip
-- takes — which violates the expires_at > issued_at constraint outright once the command TTL is
-- shorter than that, and silently shortens every command's real validity window even when it does
-- not. The envelope sent to the device carries the same issued_at, so device and cloud agree too.
--
-- The access rule is re-checked *inside this statement* (WHERE user_may_access_device) and the
-- customer snapshot is read here too, so the authorization and the record come from one database
-- state. Checking earlier and inserting later leaves a window — a ThingsBoard lookup sits between
-- them — in which a user or device transfer can commit, and the command would then be authorized
-- against the new state yet filed under the old customer. Zero rows back means the user may no
-- longer touch the device (or never could); the caller treats that as forbidden.
-- The snapshot is the user's own customer, or for a platform admin (who has none) the device's.
--
-- Deliberately NOT FOR SHARE, unlike the bind query: a transfer cleans up bindings, so a binding
-- inserted around it has to be ordered against that cleanup — but a transfer never touches commands,
-- so there is nothing to order against. Under READ COMMITTED this statement runs against one
-- snapshot, i.e. the command logically happens before any transfer that commits after it started,
-- and records the customer it was authorized under. What remains — the command being *sent* after
-- the device changed hands — happens after this statement and no lock here could prevent it.
INSERT INTO control_commands (
    command_id, device_name, method, args,
    operator_id, operator, reason, issued_at, expires_at, customer_id
)
SELECT sqlc.arg('command_id'), sqlc.arg('device_name'), sqlc.arg('method'), sqlc.arg('args'),
       u.id, sqlc.arg('operator'), sqlc.arg('reason'), sqlc.arg('issued_at'), sqlc.arg('expires_at'),
       COALESCE(u.customer_id, d.customer_id)
FROM users u
LEFT JOIN devices d ON d.device_name = sqlc.arg('device_name')
WHERE u.id = sqlc.arg('operator_id')
  AND user_may_access_device(u.id, sqlc.arg('device_name'))
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
--
-- 'pending' is in the guard alongside the states you would expect, and that is not a relaxation —
-- it closes a real race. Issue() calls ThingsBoard's two-way RPC synchronously and only writes
-- 'sent'/'received' into this row *after* that call returns, but the device's cmdResult travels
-- over a completely independent channel (the telemetry WebSocket, read by internal/ingest) with no
-- ordering guarantee against it. A fast device can report a result before, or just as, the RPC call
-- above returns — while this row is still 'pending' in our own bookkeeping. Without 'pending' here,
-- that UPDATE would match zero rows, ApplyResult would treat the result as inapplicable and drop it
-- silently, the later MarkControlCommandSent/Acked calls would leave the row stuck at 'received',
-- and the command would eventually expire despite the device already having reported success.
-- Accepting the result from 'pending' is safe: a device can only ever learn a commandId by having
-- actually received it over the RPC, which cannot happen before this row exists — so there is no
-- earlier state a forged or premature result could be exploiting. sent_at is backfilled (only if
-- still unset) so a command that finishes via this race does not carry a permanently-null sent_at.
UPDATE control_commands
SET status        = $3,
    error_code    = $4,
    error_reason  = $5,
    result_detail = $6,
    sent_at       = COALESCE(sent_at, now()),
    finished_at   = CASE WHEN $3 = 'executing' THEN finished_at ELSE now() END,
    updated_at    = now()
WHERE command_id = $1
  AND device_name = $2
  AND status IN ('pending', 'sent', 'received', 'executing')
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

-- name: ListControlCommandsForDeviceUnscoped :many
-- INTERNAL and test use only: no authorization and no customer scoping. Anything that answers a
-- user's request must use ListControlCommandsVisibleToUser.
SELECT * FROM control_commands
WHERE device_name = sqlc.arg('device_name')
ORDER BY issued_at DESC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: ListControlCommandsVisibleToUser :many
-- What a user may read of a device's command history, decided in the same statement that reads it:
-- the access rule (user_may_access_device) and the customer scope both come from the database state
-- this statement sees. Checking access in one statement and loading the user's customer in another
-- lets a user move between them — and then get history snapshotted under the customer they just
-- joined for a device they can no longer reach.
--
-- A customer user (tank-level view access only, cloud#63) sees no command history: commands are
-- whole-device and carry other tanks' operators and arguments.
-- A platform admin sees every command for the device. Anyone else sees only commands filed under
-- their own customer, so a device that changed hands never shows its previous owner's commands
-- (operator, reason, arguments, results) to the new one.
SELECT c.*
FROM control_commands c
JOIN users u ON u.id = sqlc.arg('user_id')
WHERE c.device_name = sqlc.arg('device_name')
  AND user_may_access_device(u.id, sqlc.arg('device_name'))
  AND u.role <> 'customer_user'
  AND (u.role = 'platform_admin' OR c.customer_id = u.customer_id)
ORDER BY c.issued_at DESC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: ListDevicesWithOpenControlCommandsUnscoped :many
-- INTERNAL: no authorization and no customer scoping — only ingest's command reconcile may call it.
-- One row per device that still has a command a result could close, with the oldest such command's
-- issue time, so the caller reads each device's telemetry history once from there. 'expired' is
-- excluded because it is terminal (MarkControlCommandFinished refuses to rewrite it), and a command
-- already past its window can no longer be repaired.
SELECT device_name, MIN(issued_at)::timestamptz AS earliest_issued_at
FROM control_commands
WHERE status IN ('pending', 'sent', 'received', 'executing')
  AND expires_at > now()
GROUP BY device_name;
