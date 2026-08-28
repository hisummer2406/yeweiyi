-- name: InsertWriteBackCommand :one
INSERT INTO write_back_commands (device_name, command_type, payload)
VALUES ($1, $2, $3)
RETURNING *;

-- name: GetWriteBackCommand :one
SELECT * FROM write_back_commands WHERE id = $1;

-- name: ListPendingWriteBackCommandsForDevice :many
-- Design doc §5.2: "取出该设备全部 pending 命令(按创建时间排序)" — must be FIFO per device.
SELECT * FROM write_back_commands
WHERE device_name = $1 AND status = 'pending'
ORDER BY created_at ASC;

-- name: MarkWriteBackCommandSent :exec
UPDATE write_back_commands
SET status = 'sent', attempts = attempts + 1, updated_at = now()
WHERE id = $1;

-- name: MarkWriteBackCommandAcked :exec
UPDATE write_back_commands
SET status = 'acked', updated_at = now()
WHERE id = $1;

-- name: MarkWriteBackCommandFailed :exec
UPDATE write_back_commands
SET status = 'failed', updated_at = now()
WHERE id = $1;

-- name: ListExpirableWriteBackCommands :many
-- Design doc §5.3: TTL sweep for commands stuck in pending/sent past the cutoff.
SELECT * FROM write_back_commands
WHERE status IN ('pending', 'sent') AND created_at < $1;

-- name: ListWriteBackCommandsForDevice :many
-- Status polling for a mini-program client that just submitted a write-back command — most-recent
-- first, unlike ListPendingWriteBackCommandsForDevice's FIFO order (that one drives delivery, this
-- one drives "what's the status of what I sent").
SELECT * FROM write_back_commands
WHERE device_name = $1
ORDER BY created_at DESC
LIMIT $2 OFFSET $3;
