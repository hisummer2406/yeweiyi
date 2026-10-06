-- 设备归属当前客户的起点。ThingsBoard 的遥测不带客户信息，设备改归属后新客户不应读到旧客户
-- 名下产生的历史曲线，历史查询据此把起点裁剪到 customer_assigned_at。
ALTER TABLE devices ADD COLUMN customer_assigned_at TIMESTAMPTZ;

COMMENT ON COLUMN devices.customer_assigned_at IS
    '设备归属当前客户的时刻；历史遥测查询不早于此。由触发器在 customer_id 变化时维护，不要在应用层写。迁移前已分配的设备为 NULL（归属时刻未知，不裁剪）；未分配时也为 NULL。';

-- 用触发器而不是在各条 SQL 里写：EnsureDeviceForCustomer、SetDeviceCustomer 以及将来任何
-- 修改 customer_id 的路径都不会漏。
CREATE FUNCTION devices_touch_customer_assigned_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.customer_id IS NOT NULL THEN
            NEW.customer_assigned_at := now();
        END IF;
    ELSIF NEW.customer_id IS DISTINCT FROM OLD.customer_id THEN
        NEW.customer_assigned_at := CASE WHEN NEW.customer_id IS NULL THEN NULL ELSE now() END;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER devices_customer_assigned_at
    BEFORE INSERT OR UPDATE OF customer_id ON devices
    FOR EACH ROW EXECUTE FUNCTION devices_touch_customer_assigned_at();
