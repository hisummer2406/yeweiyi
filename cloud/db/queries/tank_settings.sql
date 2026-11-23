-- 设备上报的油罐配置镜像（迁移 000033，cloud#70）。只由 internal/ingest 写入，API 只读。

-- name: UpsertTankSettings :exec
-- 整行以设备最新上报为准；内容与 reported_at 都没变才不写。不能只比 reported_at：ThingsBoard 逐键存属性，一次读取可能
-- 新旧键混杂且时间已是新的，只比时间会让下一次完整读取被跳过、镜像停在半新状态（2026-11-24）。
INSERT INTO tank_settings (
    device_name, tank, tank_height_mm, sensor_length_mm, tank_shape, oil_type, oil_type_index,
    alarm_high_high_level_mm, alarm_high_level_mm, alarm_low_level_mm, alarm_low_low_level_mm,
    alarm_high_water_mm, alarm_high_temp_c, alarm_low_temp_c,
    level_correction_mm, water_correction_mm, temp_correction_c, density, capacity_l, oil_color, reported_at
) VALUES (
    sqlc.arg('device_name'), sqlc.arg('tank'), sqlc.narg('tank_height_mm'), sqlc.narg('sensor_length_mm'),
    sqlc.narg('tank_shape'), sqlc.narg('oil_type'), sqlc.narg('oil_type_index'),
    sqlc.narg('alarm_high_high_level_mm'), sqlc.narg('alarm_high_level_mm'), sqlc.narg('alarm_low_level_mm'),
    sqlc.narg('alarm_low_low_level_mm'), sqlc.narg('alarm_high_water_mm'), sqlc.narg('alarm_high_temp_c'),
    sqlc.narg('alarm_low_temp_c'), sqlc.narg('level_correction_mm'), sqlc.narg('water_correction_mm'),
    sqlc.narg('temp_correction_c'), sqlc.narg('density'), sqlc.narg('capacity_l'), sqlc.narg('oil_color'), sqlc.arg('reported_at')
)
ON CONFLICT (device_name, tank) DO UPDATE SET
    tank_height_mm = EXCLUDED.tank_height_mm,
    sensor_length_mm = EXCLUDED.sensor_length_mm,
    tank_shape = EXCLUDED.tank_shape,
    oil_type = EXCLUDED.oil_type,
    oil_type_index = EXCLUDED.oil_type_index,
    alarm_high_high_level_mm = EXCLUDED.alarm_high_high_level_mm,
    alarm_high_level_mm = EXCLUDED.alarm_high_level_mm,
    alarm_low_level_mm = EXCLUDED.alarm_low_level_mm,
    alarm_low_low_level_mm = EXCLUDED.alarm_low_low_level_mm,
    alarm_high_water_mm = EXCLUDED.alarm_high_water_mm,
    alarm_high_temp_c = EXCLUDED.alarm_high_temp_c,
    alarm_low_temp_c = EXCLUDED.alarm_low_temp_c,
    level_correction_mm = EXCLUDED.level_correction_mm,
    water_correction_mm = EXCLUDED.water_correction_mm,
    temp_correction_c = EXCLUDED.temp_correction_c,
    density = EXCLUDED.density,
    capacity_l = EXCLUDED.capacity_l,
    oil_color = EXCLUDED.oil_color,
    reported_at = EXCLUDED.reported_at,
    updated_at = now()
WHERE (tank_settings.tank_height_mm, tank_settings.sensor_length_mm, tank_settings.tank_shape, tank_settings.oil_type, tank_settings.oil_type_index, tank_settings.alarm_high_high_level_mm, tank_settings.alarm_high_level_mm, tank_settings.alarm_low_level_mm, tank_settings.alarm_low_low_level_mm, tank_settings.alarm_high_water_mm, tank_settings.alarm_high_temp_c, tank_settings.alarm_low_temp_c, tank_settings.level_correction_mm, tank_settings.water_correction_mm, tank_settings.temp_correction_c, tank_settings.density, tank_settings.capacity_l, tank_settings.oil_color, tank_settings.reported_at)
    IS DISTINCT FROM (EXCLUDED.tank_height_mm, EXCLUDED.sensor_length_mm, EXCLUDED.tank_shape, EXCLUDED.oil_type, EXCLUDED.oil_type_index, EXCLUDED.alarm_high_high_level_mm, EXCLUDED.alarm_high_level_mm, EXCLUDED.alarm_low_level_mm, EXCLUDED.alarm_low_low_level_mm, EXCLUDED.alarm_high_water_mm, EXCLUDED.alarm_high_temp_c, EXCLUDED.alarm_low_temp_c, EXCLUDED.level_correction_mm, EXCLUDED.water_correction_mm, EXCLUDED.temp_correction_c, EXCLUDED.density, EXCLUDED.capacity_l, EXCLUDED.oil_color, EXCLUDED.reported_at);

-- name: ListTankSettings :many
-- 一台设备的全部罐镜像（罐号升序）。员工只能看被授权的罐，由调用方按 ListGrantedTanks 过滤。
SELECT * FROM tank_settings WHERE device_name = $1 ORDER BY tank;
