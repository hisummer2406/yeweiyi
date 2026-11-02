-- 镜像补上油罐公称容积（cloud#71）：config.setTankSettings 可改 capacityL，镜像里没有它，
-- 小程序就无法确认"设备已按提交值生效"。设备端早已作为客户端属性 tank{N}.capacityL 上报。
ALTER TABLE tank_settings ADD COLUMN capacity_l DOUBLE PRECISION;
COMMENT ON COLUMN tank_settings.capacity_l IS '油罐公称容积，L（客户端属性 capacityL）。NULL = 设备未上报。';
