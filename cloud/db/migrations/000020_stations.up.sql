-- 油站：客户 → 油站 → 设备 这一层。只存在于业务库，ThingsBoard 不知道油站（设备名是唯一连接点）。
CREATE TABLE stations (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT      NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    name        TEXT        NOT NULL,
    address     TEXT        NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT stations_customer_name_key UNIQUE (customer_id, name),
    -- 供 devices 的复合外键引用，见下。
    CONSTRAINT stations_customer_id_id_key UNIQUE (customer_id, id)
);

ALTER TABLE devices
    ADD COLUMN station_id BIGINT,
    -- 设备所属油站必须和设备所属客户一致。
    ADD CONSTRAINT devices_station_customer_fk
        FOREIGN KEY (customer_id, station_id) REFERENCES stations (customer_id, id),
    -- 复合外键默认 MATCH SIMPLE：任一列为 NULL 就不检查。没有这条 CHECK，没有客户的设备也能挂上油站。
    ADD CONSTRAINT devices_station_needs_customer
        CHECK (station_id IS NULL OR customer_id IS NOT NULL);

CREATE INDEX devices_station_id_idx ON devices (station_id);

COMMENT ON COLUMN devices.station_id IS '所属油站，空 = 未分组。必须属于 devices.customer_id 同一客户（复合外键 + CHECK 保证）。设备换客户时由 SetDeviceCustomer 同时清空。';

COMMENT ON TABLE stations IS '油站：客户 → 油站 → 设备 的中间层，只存在于业务库，ThingsBoard 不知道油站。';
COMMENT ON COLUMN stations.id IS '油站主键。';
COMMENT ON COLUMN stations.customer_id IS '所属客户；建立后不可更改，设备换客户时设备的 station_id 同时清空。';
COMMENT ON COLUMN stations.name IS '油站名称，同一客户下唯一。';
COMMENT ON COLUMN stations.address IS '油站地址，可为空串。';
COMMENT ON COLUMN stations.created_at IS '创建时间。';
