-- 连续遥测的小时级汇总（issue #30）。原始 30 秒遥测只在 ThingsBoard 保留 7 天、业务库不存（CLAUDE.md
-- 约束 5）；业务库只长期保存其小时级汇总，历史趋势超出 7 天读这张表。
--
-- 不对 devices 建外键：设备删除后汇总仍保留。

CREATE TABLE telemetry_hourly (
    device_name TEXT             NOT NULL,
    tank        INT              NOT NULL CHECK (tank >= 1),
    field       TEXT             NOT NULL,
    hour_start  TIMESTAMPTZ      NOT NULL,
    avg_value   DOUBLE PRECISION NOT NULL,
    min_value   DOUBLE PRECISION NOT NULL,
    max_value   DOUBLE PRECISION NOT NULL,
    samples     INT              NOT NULL CHECK (samples > 0),
    computed_at TIMESTAMPTZ      NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, tank, field, hour_start)
);

COMMENT ON TABLE telemetry_hourly IS
    '罐内数值型遥测的小时级汇总，由 internal/rollup 以 ThingsBoard 的聚合结果幂等写入，只含已结束的整点小时。无样本的小时没有行（离线不是 0）。业务库是长期来源，非必要不删除。原始 30 秒数据不入业务库。';
COMMENT ON COLUMN telemetry_hourly.device_name IS '设备名，与 devices.device_name 对应但不设外键。';
COMMENT ON COLUMN telemetry_hourly.tank        IS '罐号，1 起。';
COMMENT ON COLUMN telemetry_hourly.field       IS '字段名（protocol.AggregatableTankFields），不含 tankN. 前缀。';
COMMENT ON COLUMN telemetry_hourly.hour_start  IS '整点小时起点（TIMESTAMPTZ，对齐到 UTC 整点；与 ThingsBoard 以 startTs 对齐的桶一致）。';
COMMENT ON COLUMN telemetry_hourly.samples     IS '该小时内的原始样本数。重算只会在样本数不少于已存值时覆盖：原始数据被 TTL 部分删除后重算得到的是残缺值，不能顶掉完整的汇总。';
COMMENT ON COLUMN telemetry_hourly.computed_at IS '最近一次写入时刻。';
