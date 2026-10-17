-- 设备上报的罐体标定表镜像（cloud#14）：高度→体积离散点（1cm 步长，几十到几百点），设备作为客户端属性
-- tank{N}.calibrationHeightMm / calibrationVolumeL 在 MQTT 连接建立时发布。与 tank_settings 同一思路：设备是
-- 权威来源，云端只存最后一次上报，供小程序罐表/罐型页只读展示，设备离线仍可读。这是低频配置而不是连续遥测。
CREATE TABLE tank_calibration (
    device_name TEXT             NOT NULL REFERENCES devices (device_name) ON DELETE CASCADE,
    tank        INT              NOT NULL CHECK (tank BETWEEN 1 AND 16),
    height_mm   DOUBLE PRECISION[] NOT NULL,
    volume_l    DOUBLE PRECISION[] NOT NULL,
    reported_at TIMESTAMPTZ      NOT NULL,
    updated_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, tank),
    CHECK (cardinality(height_mm) = cardinality(volume_l) AND cardinality(height_mm) >= 2)
);

COMMENT ON TABLE tank_calibration IS '设备上报的罐体标定表镜像：(设备名, 罐号) 一行，由 internal/ingest 写入，云端不改。曲线通过 protocol.ParseTankCalibration 校验（严格升序、体积不减、至少 2 点）后才落库。';
COMMENT ON COLUMN tank_calibration.height_mm IS '标定点高度，mm，严格升序；与 volume_l 逐位对应。';
COMMENT ON COLUMN tank_calibration.volume_l IS '标定点体积，L，不减；与 height_mm 逐位对应。';
COMMENT ON COLUMN tank_calibration.reported_at IS '两个标定属性在 ThingsBoard 中较新的更新时间（设备上报时刻）。设备只在重连时重发，现场改了标定表后云端要等下次重连才更新。';
