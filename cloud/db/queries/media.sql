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

-- name: ListDeviceMediaSlotUsage :many
-- 设备各动态位置是否仍在用（#27）。在用 = 某罐镜像的 oil_type_index 就是该位置，或有指向该位置的 config.setTankSettings
-- 命令尚未被镜像覆盖：未终态的一律算在用（设备可能已开始执行）；succeeded/expired 的在该罐镜像晚于命令签发前也算在用
-- （镜像还没跟上，expired 可能已执行）。rejected/failed 设备未生效（全有或全无），不算。不在用的位置可回收给别的介质。
SELECT s.slot, s.media_id,
    (EXISTS (
        SELECT 1 FROM tank_settings ts WHERE ts.device_name = s.device_name AND ts.oil_type_index = s.slot
    ) OR EXISTS (
        SELECT 1 FROM control_commands cc
        LEFT JOIN tank_settings ts ON ts.device_name = cc.device_name AND cc.config_target = 'tank:' || ts.tank
        WHERE cc.device_name = s.device_name
          AND cc.method = 'config.setTankSettings'
          AND cc.args -> 'oilTypeIndex' = to_jsonb(s.slot::int)
          AND (cc.status IN ('pending', 'sent', 'received', 'executing')
               OR (cc.status IN ('succeeded', 'expired') AND (ts.reported_at IS NULL OR ts.reported_at <= cc.issued_at)))
    ))::boolean AS in_use
FROM device_media_slots s
WHERE s.device_name = $1
ORDER BY s.slot;

-- name: DeleteDeviceMediaSlot :exec
DELETE FROM device_media_slots WHERE device_name = $1 AND slot = $2;
