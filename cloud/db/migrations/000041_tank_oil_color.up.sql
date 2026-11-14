-- 罐颜色与颜色同步能力（三端同步介质设置，2026-11-14）。
ALTER TABLE tank_settings ADD COLUMN oil_color INT;
COMMENT ON COLUMN tank_settings.oil_color IS '设备上报的罐颜色，0xRRGGBB 整数；设备未设置（0）时不上报，为 NULL。旧数据可不在色板内。';

ALTER TABLE device_settings ADD COLUMN oil_color_sync INT;
COMMENT ON COLUMN device_settings.oil_color_sync IS '设备上报的颜色同步能力；1 = 支持随 config.setTankSettings 写入 oilColor，NULL = 旧固件（会静默忽略该字段）。';
