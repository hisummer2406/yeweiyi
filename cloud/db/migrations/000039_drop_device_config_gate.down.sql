ALTER TABLE devices
    ADD COLUMN config_commands_enabled_at TIMESTAMPTZ,
    ADD COLUMN config_commands_enabled_by BIGINT REFERENCES users (id) ON DELETE SET NULL;

COMMENT ON COLUMN devices.config_commands_enabled_at IS
    '平台管理员开放该设备接收远程配置命令的时刻；NULL = 未开放。与部署级 allowConfigCommands 同时为真才放行。换客户时清空。';
COMMENT ON COLUMN devices.config_commands_enabled_by IS '开放该设备配置命令的平台管理员。';
