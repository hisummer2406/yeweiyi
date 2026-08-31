-- Documentation-only migration: attaches COMMENT ON TABLE/COLUMN to every app-owned table so the
-- schema is self-describing in psql (\d+)/pgAdmin without needing CLAUDE.md open. No DDL changes.

COMMENT ON TABLE users IS '共享账号表，PC 远程端与小程序共用同一套账号/JWT 体系（CLAUDE.md "Tech stack"）。';
COMMENT ON COLUMN users.id IS '云端自增主键。';
COMMENT ON COLUMN users.username IS '登录用户名，唯一。';
COMMENT ON COLUMN users.password_hash IS 'bcrypt 密码哈希，不存明文。';
COMMENT ON COLUMN users.created_at IS '账号创建时间。';

COMMENT ON TABLE devices IS '设备在线状态表，记录 thing/event/isOline 的最新状态（design doc §4.2）。';
COMMENT ON COLUMN devices.device_name IS '设备名（阿里云 IoT deviceName），主键，MQTT topic 中的设备标识。';
COMMENT ON COLUMN devices.is_online IS '当前是否在线，由 isOline 事件（含遗嘱消息）更新。';
COMMENT ON COLUMN devices.last_online_at IS '最近一次上线时间。';
COMMENT ON COLUMN devices.last_offline_at IS '最近一次离线时间。';
COMMENT ON COLUMN devices.updated_at IS '该行最近一次更新时间。';

COMMENT ON TABLE alarms IS '报警记录，镜像设备端 Alarm 表（design doc §2），按 time 做月度 RANGE 分区。';
COMMENT ON COLUMN alarms.id IS '云端自增 id（非设备本地 id，仅在本表内唯一，需与 time 组成主键以满足分区约束）。';
COMMENT ON COLUMN alarms.device_name IS '所属设备名，关联 devices.device_name。';
COMMENT ON COLUMN alarms.device_local_id IS '设备本地（SQLite 自增）记录 id，仅在单设备内唯一，跨设备不可作为全局主键。';
COMMENT ON COLUMN alarms.time IS '设备本地事件时间（原始 "yyyy-MM-dd HH:mm" 无秒无时区，已按 Asia/Shanghai 转换为 UTC 存储）。';
COMMENT ON COLUMN alarms.info IS '报警信息文本，对应设备端 Alarm.info。';
COMMENT ON COLUMN alarms.guanhao IS '罐号，对应设备端 Alarm.guanHao（MQTT JSON 字段名 guanHao，与本列名不同，见 CLAUDE.md "MQTT contract"）。';
COMMENT ON COLUMN alarms.jiezhi IS '对应设备端 Alarm.jieZhi（MQTT JSON 字段名 jieZhi）；具体业务含义未在设计文档中给出，字段名照抄设备端。';
COMMENT ON COLUMN alarms.sync_status IS '云端同步状态标记，非设备端字段。';
COMMENT ON COLUMN alarms.received_at IS '云端实际接收到该条 MQTT 消息的时间，可能晚于 time（设备离线补报时会明显滞后）。';

COMMENT ON TABLE oil_in_out IS '进出油记录，镜像设备端 OilInOut 表（design doc §2），分区方式同 alarms。';
COMMENT ON COLUMN oil_in_out.id IS '云端自增 id，含义同 alarms.id。';
COMMENT ON COLUMN oil_in_out.device_name IS '所属设备名，关联 devices.device_name。';
COMMENT ON COLUMN oil_in_out.device_local_id IS '设备本地记录 id，含义同 alarms.device_local_id。';
COMMENT ON COLUMN oil_in_out.oil_id IS '油罐/油品 id，对应设备端 OilInOut.oilId。';
COMMENT ON COLUMN oil_in_out.jiezhi IS '对应设备端 OilInOut.jieZhi（MQTT JSON 字段名同名）；具体业务含义未在设计文档中给出。';
COMMENT ON COLUMN oil_in_out.time IS '设备本地事件时间，转换规则同 alarms.time。';
COMMENT ON COLUMN oil_in_out.begin_height IS '进/出油前液位高度，对应设备端 OilInOut.beginHeigth（设备侧字段名拼写如此，非笔误）。';
COMMENT ON COLUMN oil_in_out.end_height IS '进/出油后液位高度，对应设备端 OilInOut.endHeigth。';
COMMENT ON COLUMN oil_in_out.begin_volume IS '进/出油前体积，对应设备端 OilInOut.beginVolum。';
COMMENT ON COLUMN oil_in_out.end_volume IS '进/出油后体积，对应设备端 OilInOut.endVolum。';
COMMENT ON COLUMN oil_in_out.begin_weight IS '进/出油前重量，对应设备端 OilInOut.beginWeight。';
COMMENT ON COLUMN oil_in_out.end_weight IS '进/出油后重量，对应设备端 OilInOut.endWeight。';
COMMENT ON COLUMN oil_in_out.sync_status IS '云端同步状态标记，非设备端字段。';
COMMENT ON COLUMN oil_in_out.received_at IS '云端实际接收到该条 MQTT 消息的时间，含义同 alarms.received_at。';

COMMENT ON TABLE commit_works IS '交接班记录，镜像设备端 CommitWork 表（design doc §2）。未分区（非高写入量表）。';
COMMENT ON COLUMN commit_works.id IS '云端自增主键。';
COMMENT ON COLUMN commit_works.device_name IS '所属设备名，关联 devices.device_name。';
COMMENT ON COLUMN commit_works.device_local_id IS '设备本地记录 id，与 device_name 组成唯一键。';
COMMENT ON COLUMN commit_works.work_id IS '交接班 id，对应设备端 CommitWork.workId。';
COMMENT ON COLUMN commit_works.time IS '交接班开始时间（设备本地时间，已转 UTC）。';
COMMENT ON COLUMN commit_works.end_time IS '交接班结束时间，对应设备端 CommitWork.endTime；可为空。';
COMMENT ON COLUMN commit_works.info IS '交接班信息，原样存储设备端下发的 JSON 字符串（未解析为结构化列，见 CLAUDE.md "Open product decisions"）。MQTT JSON 字段名为 info，设备端 DB 列名为 oilinfogson，二者不同。';
COMMENT ON COLUMN commit_works.sync_status IS '云端同步状态标记，非设备端字段。';
COMMENT ON COLUMN commit_works.received_at IS '云端实际接收到该条 MQTT 消息的时间。';

COMMENT ON TABLE oil_can_sets IS '油罐/探头配置，字段级镜像设备端 OilCanSet.java（design doc §2 的列表并不完整，本表已按源码补齐全部字段，见 CLAUDE.md "Architecture"）。';
COMMENT ON COLUMN oil_can_sets.device_name IS '所属设备名，与 oil_can_id 组成主键。';
COMMENT ON COLUMN oil_can_sets.oil_can_id IS '设备内油罐编号（pk_oilCanId，单设备内取值 1-9）。';
COMMENT ON COLUMN oil_can_sets.pk_d2d_id IS '对应设备端 OilCanSet.pk_d2dId。';
COMMENT ON COLUMN oil_can_sets.pk_tan_zhen_id IS '探针 id，对应设备端 OilCanSet.pk_tanZhenId。';
COMMENT ON COLUMN oil_can_sets.oil_height IS '当前油高，对应设备端 OilCanSet.oilHeight。';
COMMENT ON COLUMN oil_can_sets.height IS '罐体高度。';
COMMENT ON COLUMN oil_can_sets.max_volume IS '罐体最大容积。';
COMMENT ON COLUMN oil_can_sets.height_oil_alarm IS '高油位报警阈值。';
COMMENT ON COLUMN oil_can_sets.hheight_oil_alarm IS '超高油位报警阈值。';
COMMENT ON COLUMN oil_can_sets.low_oil_alarm IS '低油位报警阈值。';
COMMENT ON COLUMN oil_can_sets.llow_oil_alarm IS '超低油位报警阈值。';
COMMENT ON COLUMN oil_can_sets.height_water_alarm IS '水位报警阈值，对应设备端 OilCanSet.heightWaterAlarm。';
COMMENT ON COLUMN oil_can_sets.high_temp_alarm IS '高温报警阈值。';
COMMENT ON COLUMN oil_can_sets.low_temp_alarm IS '低温报警阈值。';
COMMENT ON COLUMN oil_can_sets.oil_correction IS '油位校正值，对应设备端 OilCanSet.oilCorrection。';
COMMENT ON COLUMN oil_can_sets.water_correction IS '水位校正值，对应设备端 OilCanSet.waterCorrection。';
COMMENT ON COLUMN oil_can_sets.temp_correction IS '温度校正值，对应设备端 OilCanSet.tempCorrection。';
COMMENT ON COLUMN oil_can_sets.density IS '油品密度。';
COMMENT ON COLUMN oil_can_sets.dens_var IS '密度校正/浮动值，对应设备端 OilCanSet.densVar。';
COMMENT ON COLUMN oil_can_sets.alarm_byte IS '报警状态位掩码，对应设备端 OilCanSet.alarmByte。';
COMMENT ON COLUMN oil_can_sets.oil_type IS '油品类型编码。';
COMMENT ON COLUMN oil_can_sets.oil_color IS '油品颜色编码。';
COMMENT ON COLUMN oil_can_sets.gmt_create IS '设备端创建时间原始字符串，格式未文档化，原样存为 TEXT 不做时间解析。';
COMMENT ON COLUMN oil_can_sets.gmt_modified IS '设备端修改时间原始字符串，格式未文档化，原样存为 TEXT 不做时间解析。';
COMMENT ON COLUMN oil_can_sets.chuan_gan_qi_height IS '传感器（探头）安装高度，对应设备端 OilCanSet.chuanGanQiHight。';
COMMENT ON COLUMN oil_can_sets.gun_type IS '油枪/探头类型编码，对应设备端 OilCanSet.gunType。';
COMMENT ON COLUMN oil_can_sets.enable IS '该油罐配置是否启用。';
COMMENT ON COLUMN oil_can_sets.sync_status IS '云端同步状态标记，非设备端字段。';
COMMENT ON COLUMN oil_can_sets.received_at IS '云端实际接收到该条配置的时间。';

COMMENT ON TABLE write_back_commands IS '云端/小程序 -> 设备的写回离线队列（design doc §5.2），设备离线时暂存待下发的命令。';
COMMENT ON COLUMN write_back_commands.id IS '云端自增主键。';
COMMENT ON COLUMN write_back_commands.device_name IS '目标设备名。';
COMMENT ON COLUMN write_back_commands.command_type IS '命令类型（如修正进出油记录、修正交接班记录、下发罐/探头配置）。';
COMMENT ON COLUMN write_back_commands.payload IS '命令负载，JSON，描述目标状态而非增量（保证重试幂等）。';
COMMENT ON COLUMN write_back_commands.status IS '命令状态：pending 待发送 / sent 已发布到 MQTT / acked 设备已确认 / failed 已失败或超 TTL。';
COMMENT ON COLUMN write_back_commands.attempts IS '已尝试发布次数。';
COMMENT ON COLUMN write_back_commands.created_at IS '命令创建时间，TTL 超时判断的起点。';
COMMENT ON COLUMN write_back_commands.updated_at IS '状态最近一次变更时间。';

COMMENT ON TABLE user_devices IS '用户-设备绑定关系（多对多），小程序端通过扫描设备物理二维码建立绑定（CLAUDE.md 2026-08-29 authorization pass）。';
COMMENT ON COLUMN user_devices.user_id IS '绑定的用户 id，关联 users.id。';
COMMENT ON COLUMN user_devices.device_name IS '绑定的设备名，关联 devices.device_name。';
COMMENT ON COLUMN user_devices.created_at IS '绑定建立时间。';

COMMENT ON TABLE river_job IS 'river 队列库自带的 schema（作业表），由 riverqueue/river 管理，非本项目业务表，不要手工改结构。';
COMMENT ON TABLE river_leader IS 'river 队列库自带的 schema（leader 选举表），由 riverqueue/river 管理。';
COMMENT ON TABLE river_queue IS 'river 队列库自带的 schema（队列元数据表），由 riverqueue/river 管理。';
COMMENT ON TABLE river_notification IS 'river 队列库自带的 schema（通知表），由 riverqueue/river 管理。';
