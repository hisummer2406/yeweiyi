-- name: ListMedia :many
SELECT * FROM media ORDER BY scope DESC, customer_id NULLS FIRST, builtin_index NULLS LAST, id;

-- name: ListMediaOptionsForCustomer :many
-- 可供选择的介质：启用的公共介质，加上指定客户的启用专属介质。customer 为 NULL（设备未分配）时只有公共介质。
SELECT * FROM media
WHERE enabled AND (scope = 'public' OR customer_id = sqlc.narg('customer_id'))
ORDER BY scope DESC, builtin_index NULLS LAST, id;

-- name: CreateMedia :one
INSERT INTO media (name, name_en, scope, customer_id, updated_by)
VALUES ($1, $2, $3, $4, $5) RETURNING *;

-- name: GetMediaForUpdate :one
SELECT * FROM media WHERE id = $1 FOR UPDATE;

-- name: UpdateMedia :one
UPDATE media SET name = $2, name_en = $3, enabled = $4, updated_by = $5, updated_at = now()
WHERE id = $1 RETURNING *;

-- name: GetMedia :one
SELECT * FROM media WHERE id = $1;

-- name: ListDeviceMediaSlots :many
SELECT * FROM device_media_slots WHERE device_name = $1 ORDER BY slot;

-- name: CreateDeviceMediaSlot :exec
INSERT INTO device_media_slots (device_name, slot, media_id) VALUES ($1, $2, $3);

-- name: DeleteDeviceMediaSlots :execrows
DELETE FROM device_media_slots WHERE device_name = $1;
