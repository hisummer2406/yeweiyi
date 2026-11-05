-- 设备上报的罐体标定表镜像（迁移 000035，cloud#14）。只由 internal/ingest 写入，API 只读。

-- name: UpsertTankCalibration :exec
-- reported_at 没变就不写（设备每次重连都会重发，lastUpdateTs 随之前进）。
INSERT INTO tank_calibration (device_name, tank, height_mm, volume_l, reported_at)
VALUES (sqlc.arg('device_name'), sqlc.arg('tank'), sqlc.arg('height_mm')::float8[], sqlc.arg('volume_l')::float8[], sqlc.arg('reported_at'))
ON CONFLICT (device_name, tank) DO UPDATE SET
    height_mm = EXCLUDED.height_mm, volume_l = EXCLUDED.volume_l,
    reported_at = EXCLUDED.reported_at, updated_at = now()
WHERE tank_calibration.reported_at IS DISTINCT FROM EXCLUDED.reported_at;

-- name: GetTankCalibration :one
SELECT * FROM tank_calibration WHERE device_name = $1 AND tank = $2;
