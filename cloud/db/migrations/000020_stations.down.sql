DROP INDEX IF EXISTS devices_station_id_idx;
ALTER TABLE devices
    DROP CONSTRAINT IF EXISTS devices_station_needs_customer,
    DROP CONSTRAINT IF EXISTS devices_station_customer_fk,
    DROP COLUMN IF EXISTS station_id;
DROP TABLE IF EXISTS stations;
