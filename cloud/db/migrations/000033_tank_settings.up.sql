-- 设备上报的油罐配置镜像（cloud#70 第一阶段）。设备是配置的权威来源：联网/变更时把罐设置页内容作为
-- ThingsBoard 客户端属性（tank{N}.*）上报，云端只落库保存最后一次上报，供小程序只读展示；本阶段云端
-- 不写入任何配置值。设备离线时仍可读最后一次镜像，reported_at 让客户端判断"这是多久前的配置"。
-- 这是低频配置，不是连续遥测，所以不违反"连续遥测不得复制到业务库"（CLAUDE.md 约束 5）。
CREATE TABLE tank_settings (
    device_name TEXT        NOT NULL REFERENCES devices (device_name) ON DELETE CASCADE,
    tank        INT         NOT NULL CHECK (tank BETWEEN 1 AND 16),

    tank_height_mm          INT,
    sensor_length_mm        DOUBLE PRECISION,
    tank_shape              INT,
    oil_type                TEXT,
    oil_type_index          INT,
    alarm_high_high_level_mm INT,
    alarm_high_level_mm     INT,
    alarm_low_level_mm      INT,
    alarm_low_low_level_mm  INT,
    alarm_high_water_mm     INT,
    alarm_high_temp_c       INT,
    alarm_low_temp_c        INT,
    level_correction_mm     DOUBLE PRECISION,
    water_correction_mm     DOUBLE PRECISION,
    temp_correction_c       DOUBLE PRECISION,
    density                 DOUBLE PRECISION,

    reported_at TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, tank)
);

COMMENT ON TABLE tank_settings IS '设备上报的油罐配置镜像：(设备名, 罐号) 一行，由 internal/ingest 从 ThingsBoard 客户端属性写入；云端不改配置值。设备停用某个罐后旧行保留。';
COMMENT ON COLUMN tank_settings.device_name IS '设备名，关联 devices.device_name；设备被删除时镜像一并删除。换机用新设备名，天然没有旧机镜像。';
COMMENT ON COLUMN tank_settings.tank IS '罐号 1..16（设备端上限）。';
COMMENT ON COLUMN tank_settings.tank_height_mm IS '罐高，mm。NULL = 设备未上报该键（或类型不对）。以下各列同理，云端不补默认值。';
COMMENT ON COLUMN tank_settings.sensor_length_mm IS '传感器长度，mm。';
COMMENT ON COLUMN tank_settings.tank_shape IS '0 卧罐 / 1 立罐。';
COMMENT ON COLUMN tank_settings.oil_type IS '油品名称，随设备界面语言变化，仅供展示；以 oil_type_index 为准。';
COMMENT ON COLUMN tank_settings.oil_type_index IS '油品序号：0 未设置，1–17 内置，18–25 自定义。';
COMMENT ON COLUMN tank_settings.alarm_high_high_level_mm IS '高高液位报警阈值，mm；0 = 不启用。';
COMMENT ON COLUMN tank_settings.alarm_high_level_mm IS '高液位报警阈值，mm；0 = 不启用。';
COMMENT ON COLUMN tank_settings.alarm_low_level_mm IS '低液位报警阈值，mm；0 = 不启用。';
COMMENT ON COLUMN tank_settings.alarm_low_low_level_mm IS '低低液位报警阈值，mm；0 = 不启用。';
COMMENT ON COLUMN tank_settings.alarm_high_water_mm IS '高水位报警阈值，mm；0 = 不启用。设备端没有低水位报警。';
COMMENT ON COLUMN tank_settings.alarm_high_temp_c IS '高温报警阈值，℃；0 = 不启用。';
COMMENT ON COLUMN tank_settings.alarm_low_temp_c IS '低温报警阈值，℃。设备端低温检测从未被调用，改了不生效。';
COMMENT ON COLUMN tank_settings.level_correction_mm IS '液位修正值，mm。';
COMMENT ON COLUMN tank_settings.water_correction_mm IS '水位修正值，mm。';
COMMENT ON COLUMN tank_settings.temp_correction_c IS '温度修正值，℃。';
COMMENT ON COLUMN tank_settings.density IS '密度，原样透传，单位未与设备端对齐（设置页标 kg/m³，实际存 0.56 这类值）。';
COMMENT ON COLUMN tank_settings.reported_at IS '该罐各配置属性在 ThingsBoard 中最近一次更新的时间（设备上报时刻）。';
COMMENT ON COLUMN tank_settings.updated_at IS '云端最后一次改动这一行的时间。';
