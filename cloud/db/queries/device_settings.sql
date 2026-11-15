-- 设备上报的整机设置镜像（迁移 000038，config-command-delivery）。只由 internal/ingest 写入，API 只读。

-- name: UpsertDeviceSettings :exec
-- 整行以设备最新上报为准；reported_at 没变就不写。
INSERT INTO device_settings (
    device_name, in_stock_height_threshold_mm, in_stock_time_threshold_sec,
    out_stock_height_threshold_mm, out_stock_time_threshold_sec, custom_media_sync, oil_color_sync, reported_at
) VALUES (
    sqlc.arg('device_name'), sqlc.narg('in_stock_height_threshold_mm'), sqlc.narg('in_stock_time_threshold_sec'),
    sqlc.narg('out_stock_height_threshold_mm'), sqlc.narg('out_stock_time_threshold_sec'), sqlc.narg('custom_media_sync'), sqlc.narg('oil_color_sync'), sqlc.arg('reported_at')
)
ON CONFLICT (device_name) DO UPDATE SET
    in_stock_height_threshold_mm = EXCLUDED.in_stock_height_threshold_mm,
    in_stock_time_threshold_sec = EXCLUDED.in_stock_time_threshold_sec,
    out_stock_height_threshold_mm = EXCLUDED.out_stock_height_threshold_mm,
    out_stock_time_threshold_sec = EXCLUDED.out_stock_time_threshold_sec,
    custom_media_sync = EXCLUDED.custom_media_sync,
    oil_color_sync = EXCLUDED.oil_color_sync,
    reported_at = EXCLUDED.reported_at,
    updated_at = now()
WHERE device_settings.reported_at IS DISTINCT FROM EXCLUDED.reported_at;

-- name: GetDeviceSettings :one
SELECT * FROM device_settings WHERE device_name = $1;
