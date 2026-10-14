DROP INDEX IF EXISTS devices_station_id_key;
CREATE INDEX IF NOT EXISTS devices_station_id_idx ON devices (station_id);

COMMENT ON COLUMN devices.station_id IS '所属油站，空 = 未分组。必须属于 devices.customer_id 同一客户（复合外键 + CHECK 保证）。设备换客户时由 SetDeviceCustomer 同时清空。';
