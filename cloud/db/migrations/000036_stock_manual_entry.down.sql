ALTER TABLE stock_events
    DROP COLUMN IF EXISTS deleted_by,
    DROP COLUMN IF EXISTS deleted_at,
    DROP COLUMN IF EXISTS created_by,
    DROP COLUMN IF EXISTS origin;
