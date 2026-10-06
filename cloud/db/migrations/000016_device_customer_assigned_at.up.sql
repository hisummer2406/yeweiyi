-- 设备归属当前客户的起点。ThingsBoard 的遥测不带客户信息，设备改归属后新客户不应读到旧客户
-- 名下产生的历史曲线，历史查询据此把起点裁剪到 customer_assigned_at。
ALTER TABLE devices ADD COLUMN customer_assigned_at TIMESTAMPTZ;

COMMENT ON COLUMN devices.customer_assigned_at IS
    '设备归属当前客户的时刻；历史遥测查询不早于此。由触发器在 customer_id 变化时维护，不要在应用层写。迁移前转移过的设备（有 direction=out 的 device.assign 审计）回填为迁移时刻；从未改过归属的为 NULL（没有前任客户，无需裁剪）；未分配时也为 NULL。';

-- 回填：设备改过归属的唯一记录是管理接口写的 device.assign 审计。只有转移（原来有客户）才会多写一条
-- direction='out' 的前任客户一侧记录；未分配设备的首次分配只有 'in'，没有前任，不该被当成转移。审计的
-- occurred_at 是事务开始时间（now()），改归属的事务若在 GetDeviceForUpdate 上等过锁，它会早于实际
-- 转移，等锁期间旧客户的遥测就会漏给新客户。取不到准确时刻，所以保守地用迁移时刻：这些设备升级后
-- 看不到更早的历史（至多 7 天保留期内的部分），换来不泄露。没有转移审计的已分配设备要么从未转移过（含只做过首次分配的）
-- （迁移 000013 把全部设备分给 default 客户），要么是绕过接口直接改库的，后者无从得知，留 NULL。
UPDATE devices d
SET customer_assigned_at = clock_timestamp()
WHERE d.customer_id IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM audit_log a
      WHERE a.device_name = d.device_name AND a.action = 'device.assign' AND a.outcome = 'allowed'
        AND a.detail->>'direction' = 'out'
  );

-- 取 clock_timestamp() 而不是 now()：now() 是事务开始时间，而改归属的事务会先在 GetDeviceForUpdate
-- 上等行锁，等待期间旧客户产生的遥测不能算到新客户名下。
-- 用触发器而不是在各条 SQL 里写：EnsureDeviceForCustomer、SetDeviceCustomer 以及将来任何
-- 修改 customer_id 的路径都不会漏。
CREATE FUNCTION devices_touch_customer_assigned_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.customer_id IS NOT NULL THEN
            NEW.customer_assigned_at := clock_timestamp();
        END IF;
    ELSIF NEW.customer_id IS DISTINCT FROM OLD.customer_id THEN
        NEW.customer_assigned_at := CASE WHEN NEW.customer_id IS NULL THEN NULL ELSE clock_timestamp() END;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER devices_customer_assigned_at
    BEFORE INSERT OR UPDATE OF customer_id ON devices
    FOR EACH ROW EXECUTE FUNCTION devices_touch_customer_assigned_at();
