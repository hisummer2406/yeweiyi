DROP INDEX IF EXISTS audit_log_customer_occurred_idx;
ALTER TABLE audit_log DROP COLUMN IF EXISTS customer_id;

ALTER TABLE control_commands DROP COLUMN IF EXISTS customer_id;

DROP INDEX IF EXISTS devices_customer_id_idx;
ALTER TABLE devices DROP COLUMN IF EXISTS customer_id;

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_customer_role_check;
DROP INDEX IF EXISTS users_customer_id_idx;
ALTER TABLE users DROP COLUMN IF EXISTS customer_id;

DROP TABLE IF EXISTS customers;
