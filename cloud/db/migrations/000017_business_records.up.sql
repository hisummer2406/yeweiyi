-- 出入库与报警是业务记录，业务库是长期事实来源（CLAUDE.md 约束 5，issue #29）。ThingsBoard 只是传输与短期
-- 缓冲，其中的副本可以按 TTL 删除。连续遥测（30 秒快照）不在此列，仍不入业务库。
--
-- 不对 devices 建外键：设备被删除不应连带删掉它的出入库与报警记录（非必要不删除），且订阅只覆盖已绑定的
-- 设备，但回补可能读到后来被解绑/删除的设备。

CREATE TABLE stock_events (
    device_name  TEXT        NOT NULL,
    event_id     TEXT        NOT NULL,
    synthetic_id BOOLEAN     NOT NULL DEFAULT FALSE,
    occurred_at  TIMESTAMPTZ NOT NULL,
    env_ts       BIGINT      NOT NULL,
    report_ts    BIGINT[]    NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, event_id)
);
CREATE INDEX stock_events_device_time ON stock_events (device_name, occurred_at);

COMMENT ON TABLE stock_events IS
    '出入库记录（设备协议 V1 stockEvent）。一笔移库一行，以 (device_name, event_id) 为键，幂等写入；设备用同一 eventId 重发修正版时，信封 ts 最大的一份胜出（docs/protocol-v1-stock-event.md）。业务库是长期事实来源，非必要不删除。';
COMMENT ON COLUMN stock_events.device_name  IS '设备名，与 devices.device_name 对应但不设外键，设备删除后记录仍保留。';
COMMENT ON COLUMN stock_events.event_id     IS '设备生成的事件 ID。设备未提供时由云端按 信封ts+载荷哈希 合成（synthetic_id=true），保证回补重放幂等。';
COMMENT ON COLUMN stock_events.synthetic_id IS 'event_id 是云端合成的（载荷里没有 eventId）。此类记录不参与按 eventId 折叠，接口对外返回空 eventId。';
COMMENT ON COLUMN stock_events.occurred_at  IS '记录关闭时刻（载荷 tsMs；缺省时取信封 ts），接口按它列窗口、排序并做归属裁剪。';
COMMENT ON COLUMN stock_events.env_ts       IS '胜出那一份报告的 ThingsBoard 信封 ts（毫秒）：设备生成该报文的时刻，补投原样保留。';
COMMENT ON COLUMN stock_events.report_ts    IS '收到过的全部不同信封 ts（毫秒，去重）。基数减一即被折叠掉的重复报告数；重放同一份报告不会增加它。';
COMMENT ON COLUMN stock_events.payload      IS '胜出那一份报告的原始 JSON，保真保存，接口据此还原字段，设备端后续新增的可选字段不会丢。';

CREATE TABLE alarm_events (
    device_name  TEXT        NOT NULL,
    alarm_id     TEXT        NOT NULL,
    status       TEXT        NOT NULL,
    synthetic_id BOOLEAN     NOT NULL DEFAULT FALSE,
    occurred_at  TIMESTAMPTZ NOT NULL,
    env_ts       BIGINT      NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, alarm_id, status)
);
CREATE INDEX alarm_events_device_time ON alarm_events (device_name, occurred_at);

COMMENT ON TABLE alarm_events IS
    '报警状态变迁（设备协议 V1 alarm）。产生（ACTIVE）与恢复（CLEARED）各占一行，沿用 /api/alarms 既有的逐条语义，以 (device_name, alarm_id, status) 为键幂等写入；同一份重放不改变已有行。业务库是长期事实来源，非必要不删除。';
COMMENT ON COLUMN alarm_events.alarm_id     IS '设备生成的报警 ID，ACTIVE 与 CLEARED 共用；设备未提供时云端合成（synthetic_id=true）。';
COMMENT ON COLUMN alarm_events.synthetic_id IS 'alarm_id 是云端合成的，接口对外返回空 alarmId。';
COMMENT ON COLUMN alarm_events.occurred_at  IS '设备检测到的时刻（载荷 tsMs；缺省时取信封 ts）。';
COMMENT ON COLUMN alarm_events.env_ts       IS '该条的 ThingsBoard 信封 ts（毫秒）。';
COMMENT ON COLUMN alarm_events.payload      IS '原始 JSON，保真保存。';

-- 读不出来的记录不丢弃、也不冒充成功：原文留在这里，接口按原有语义把它们计入 unparsable /
-- legacySkipped / invalidSource；固件升级后若要回填，可以从这里重新解析。
CREATE TABLE record_rejects (
    id          BIGSERIAL   PRIMARY KEY,
    kind        TEXT        NOT NULL CHECK (kind IN ('stock', 'alarm')),
    device_name TEXT        NOT NULL,
    env_ts      BIGINT      NOT NULL,
    reason      TEXT        NOT NULL CHECK (reason IN ('unparsable', 'invalid_source')),
    raw         TEXT        NOT NULL,
    raw_hash    TEXT        NOT NULL GENERATED ALWAYS AS (md5(raw)) STORED,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (kind, device_name, env_ts, raw_hash)
);
CREATE INDEX record_rejects_device ON record_rejects (kind, device_name, env_ts);

COMMENT ON TABLE record_rejects IS
    '无法解析的出入库/报警原文（如固件升级前的 DetailInoutRecord.toString()、source 取值非法的记录）。(kind, device_name, env_ts, 原文哈希) 唯一，回补重放幂等。';
COMMENT ON COLUMN record_rejects.reason   IS 'unparsable = 不是 V1 JSON；invalid_source = JSON 合法但 source 不是 AUTO/MANUAL。';
COMMENT ON COLUMN record_rejects.env_ts   IS '信封 ts（毫秒）。接口用它与 stock.legacyFormatBefore 比较，区分已封口的旧格式记录与当下故障。';
COMMENT ON COLUMN record_rejects.raw_hash IS '原文 md5，仅用于唯一约束（原文可能很长）。';
