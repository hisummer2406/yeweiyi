DROP TRIGGER IF EXISTS devices_customer_assigned_at ON devices;
DROP FUNCTION IF EXISTS devices_touch_customer_assigned_at();
ALTER TABLE devices DROP COLUMN IF EXISTS customer_assigned_at;
