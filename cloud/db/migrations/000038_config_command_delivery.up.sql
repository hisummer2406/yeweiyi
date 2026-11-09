-- 远程配置下发的提交保护（openspec change config-command-delivery，#3）。
--   1. control_commands：客户端提交标识（幂等）、受理时期望的油站、配置目标（同目标在途互斥）。
--   2. devices：设备级配置命令白名单（与部署级 allowConfigCommands 共同生效）。
--   3. device_settings：设备上报的整机设置镜像（出入库判定阈值）。

ALTER TABLE control_commands
    ADD COLUMN request_id TEXT
        CONSTRAINT control_commands_request_id_check CHECK (request_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    ADD COLUMN expected_station_id BIGINT,
    ADD COLUMN config_target TEXT
        CONSTRAINT control_commands_config_target_check CHECK (config_target ~ '^(tank:[0-9]{1,2}|device)$');

COMMENT ON COLUMN control_commands.request_id IS
    '客户端提交标识：同一 (操作人, 设备, request_id) 至多一条命令，重试/重放返回原命令且不再次派发。配置方法必填，其他方法可空。';
COMMENT ON COLUMN control_commands.expected_station_id IS
    '提交时客户端期望设备所在的油站（受理事务内已复核一致）；NULL = 期望设备不在任何油站，或非配置方法。快照，不设外键。';
COMMENT ON COLUMN control_commands.config_target IS
    '配置命令的目标：tank:<罐号>（config.setTankSettings）或 device（config.setDeviceSettings）；其他方法 NULL。';

-- 操作人被删除后 operator_id 变 NULL，NULL 不参与唯一性——此时已无人能再重放。
CREATE UNIQUE INDEX control_commands_request_id_key
    ON control_commands (operator_id, device_name, request_id)
    WHERE request_id IS NOT NULL;

-- 同一设备同一配置目标同时至多一条未终态命令；命令终态或过期后索引项自动消失。
CREATE UNIQUE INDEX control_commands_config_in_flight_key
    ON control_commands (device_name, config_target)
    WHERE config_target IS NOT NULL AND status IN ('pending', 'sent', 'received', 'executing');

ALTER TABLE devices
    ADD COLUMN config_commands_enabled_at TIMESTAMPTZ,
    ADD COLUMN config_commands_enabled_by BIGINT REFERENCES users (id) ON DELETE SET NULL;

COMMENT ON COLUMN devices.config_commands_enabled_at IS
    '平台管理员开放该设备接收远程配置命令的时刻；NULL = 未开放。与部署级 allowConfigCommands 同时为真才放行。换客户时清空。';
COMMENT ON COLUMN devices.config_commands_enabled_by IS '开放该设备配置命令的平台管理员。';

CREATE TABLE device_settings (
    device_name                    TEXT        PRIMARY KEY REFERENCES devices (device_name) ON DELETE CASCADE,
    in_stock_height_threshold_mm   INT,
    in_stock_time_threshold_sec    INT,
    out_stock_height_threshold_mm  INT,
    out_stock_time_threshold_sec   INT,
    reported_at                    TIMESTAMPTZ NOT NULL,
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE device_settings IS
    '设备上报的整机设置镜像（出入库判定阈值），由 internal/ingest 从 ThingsBoard 客户端属性写入；云端不改配置值。低频配置，不是连续遥测。';
COMMENT ON COLUMN device_settings.in_stock_height_threshold_mm IS '入库判定高度阈值，mm。NULL = 设备未上报（或类型不对）；设备以 0 表示未配置，原样保存。';
COMMENT ON COLUMN device_settings.in_stock_time_threshold_sec IS '入库判定时间阈值，秒。';
COMMENT ON COLUMN device_settings.out_stock_height_threshold_mm IS '出库判定高度阈值，mm。';
COMMENT ON COLUMN device_settings.out_stock_time_threshold_sec IS '出库判定时间阈值，秒。';
COMMENT ON COLUMN device_settings.reported_at IS '这些属性在 ThingsBoard 的最近更新时间（取四个键中最新者）。';
