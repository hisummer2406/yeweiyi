-- 用户 2026-11-09 决定：去掉设备级配置命令白名单（000038 新增），管理员打开参数页即可直接修改；
-- 只保留部署级总闸 control.allowConfigCommands。见 docs/config-command-contract.md §1.4。
ALTER TABLE devices
    DROP COLUMN IF EXISTS config_commands_enabled_by,
    DROP COLUMN IF EXISTS config_commands_enabled_at;
