DELETE FROM record_rejects WHERE kind IN ('shift', 'stock_record');
ALTER TABLE record_rejects DROP CONSTRAINT record_rejects_kind_check;
ALTER TABLE record_rejects ADD CONSTRAINT record_rejects_kind_check CHECK (kind IN ('stock', 'alarm'));
COMMENT ON COLUMN record_rejects.kind IS NULL;

DROP TABLE IF EXISTS stock_record_tanks;
DROP TABLE IF EXISTS stock_records;
DROP TABLE IF EXISTS shift_record_tanks;
DROP TABLE IF EXISTS shift_records;
