-- 远程修改罐高与低温报警的能力位（#65，2026-11-23）。
ALTER TABLE device_settings ADD COLUMN tank_height_low_temp_sync INT;
COMMENT ON COLUMN device_settings.tank_height_low_temp_sync IS '设备上报的罐高/低温远程修改能力；1 = 支持随 config.setTankSettings 写入 tankHeightMm、alarmLowTempC 且低温报警已接通，NULL = 旧固件（拒绝这两个字段）。';
