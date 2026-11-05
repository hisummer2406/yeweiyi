-- 仅更正列注释，不改结构。旧注释描述的是已删除的 Alink 栈（isOline 事件、遗嘱消息）。
COMMENT ON COLUMN devices.is_online IS '当前是否在线，取自 ThingsBoard 的设备 active 标志，由 internal/ingest 定时同步。active 表示“不活动超时内有过活动”，设备掉线后要等超时（ThingsBoard 默认 10 分钟）才变为离线。';
COMMENT ON COLUMN devices.last_online_at IS '最近一次观察到由离线变为在线的时刻，精度为同步间隔。';
COMMENT ON COLUMN devices.last_offline_at IS '最近一次观察到由在线变为离线的时刻，精度为同步间隔，且晚于设备真实掉线一个不活动超时。';
