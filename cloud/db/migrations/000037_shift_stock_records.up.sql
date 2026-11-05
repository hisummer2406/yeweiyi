-- 交班与库存记录（cloud#85，契约 docs/shift-stock-records-contract.md）。与 stock_events 同为业务记录：业务库是长期
-- 事实来源，永久保存。每条记录一行表头（折叠、归属、原文）+ 每罐一行（按罐授权、筛选、分页在 SQL 里完成）。
-- 同一记录多次上报时信封 ts 最大的一份胜出，胜出时整组替换罐行（班内增删罐、手工修改都以最新一份为准）。

CREATE TABLE shift_records (
    device_name TEXT        NOT NULL,
    shift_id    TEXT        NOT NULL,
    status      TEXT        NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    begin_at    TIMESTAMPTZ NOT NULL,
    end_at      TIMESTAMPTZ,
    env_ts      BIGINT      NOT NULL,
    payload     JSONB       NOT NULL,
    customer_id BIGINT      REFERENCES customers (id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, shift_id)
);
CREATE INDEX shift_records_device_time ON shift_records (device_name, begin_at);

COMMENT ON TABLE shift_records IS '交班记录表头（设备协议 V1 shiftEvent）。以 (device_name, shift_id) 为键幂等写入，信封 ts 最大的一份胜出。永久保存。';
COMMENT ON COLUMN shift_records.device_name IS '设备名，不设外键：设备删除（换机、转移）后记录仍保留；只有彻底删除（cloud#83）会清掉。';
COMMENT ON COLUMN shift_records.shift_id    IS '设备生成的班标识 shift-{beginTimeMs}，开始与结束两次上报相同。';
COMMENT ON COLUMN shift_records.status      IS 'OPEN = 进行中（无结束数据）；CLOSED = 已交班。';
COMMENT ON COLUMN shift_records.begin_at    IS '接班时刻（载荷 beginTimeMs），接口按它列窗口与排序。';
COMMENT ON COLUMN shift_records.end_at      IS '交班时刻（载荷 endTimeMs）；进行中为 NULL。';
COMMENT ON COLUMN shift_records.env_ts      IS '胜出那一份报告的 ThingsBoard 信封 ts（毫秒）：设备生成该报文的时刻，补投原样保留。';
COMMENT ON COLUMN shift_records.payload     IS '胜出那一份报告的原始 JSON。';
COMMENT ON COLUMN shift_records.customer_id IS '首次写入时设备所属的客户；之后的重报不改它。接口只返回与设备当前客户一致的记录。';

CREATE TABLE shift_record_tanks (
    device_name TEXT    NOT NULL,
    shift_id    TEXT    NOT NULL,
    tank        INT     NOT NULL CHECK (tank BETWEEN 1 AND 64),
    data        JSONB   NOT NULL,
    PRIMARY KEY (device_name, shift_id, tank),
    FOREIGN KEY (device_name, shift_id) REFERENCES shift_records (device_name, shift_id) ON DELETE CASCADE
);

COMMENT ON TABLE shift_record_tanks IS '交班记录的每罐数据，随表头胜出的那一份整组替换。';
COMMENT ON COLUMN shift_record_tanks.tank IS '协议罐号（1 起）。';
COMMENT ON COLUMN shift_record_tanks.data IS '载荷 tanks[] 中该罐的原始对象；缺键即缺失值，不是 0。';

CREATE TABLE stock_records (
    device_name TEXT        NOT NULL,
    record_id   TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    env_ts      BIGINT      NOT NULL,
    payload     JSONB       NOT NULL,
    customer_id BIGINT      REFERENCES customers (id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_name, record_id)
);
CREATE INDEX stock_records_device_time ON stock_records (device_name, occurred_at);

COMMENT ON TABLE stock_records IS '库存记录表头（设备协议 V1 stockRecord）：设备一次库存保存（周期或手动）。以 (device_name, record_id) 为键幂等写入，信封 ts 最大的一份胜出。永久保存。';
COMMENT ON COLUMN stock_records.device_name IS '设备名，不设外键，同 shift_records。';
COMMENT ON COLUMN stock_records.record_id   IS '设备生成的批次标识 stock-{tsMs}。';
COMMENT ON COLUMN stock_records.occurred_at IS '库存生成时刻（载荷 tsMs），接口按它列窗口与排序。';
COMMENT ON COLUMN stock_records.env_ts      IS '胜出那一份报告的信封 ts（毫秒）。';
COMMENT ON COLUMN stock_records.payload     IS '胜出那一份报告的原始 JSON。';
COMMENT ON COLUMN stock_records.customer_id IS '首次写入时设备所属的客户，同 shift_records。';

CREATE TABLE stock_record_tanks (
    device_name TEXT  NOT NULL,
    record_id   TEXT  NOT NULL,
    tank        INT   NOT NULL CHECK (tank BETWEEN 1 AND 64),
    data        JSONB NOT NULL,
    PRIMARY KEY (device_name, record_id, tank),
    FOREIGN KEY (device_name, record_id) REFERENCES stock_records (device_name, record_id) ON DELETE CASCADE
);

COMMENT ON TABLE stock_record_tanks IS '库存记录的每罐数据，随表头胜出的那一份整组替换。';
COMMENT ON COLUMN stock_record_tanks.tank IS '协议罐号（1 起）。';
COMMENT ON COLUMN stock_record_tanks.data IS '载荷 tanks[] 中该罐的原始对象；缺键即缺失值，不是 0。';

-- 读不出来的交班/库存原文同样进 record_rejects。
ALTER TABLE record_rejects DROP CONSTRAINT record_rejects_kind_check;
ALTER TABLE record_rejects ADD CONSTRAINT record_rejects_kind_check
    CHECK (kind IN ('stock', 'alarm', 'shift', 'stock_record'));
COMMENT ON COLUMN record_rejects.kind IS 'stock = 出入库；alarm = 报警；shift = 交班；stock_record = 库存记录。';
