-- 回到设备级绑定（user_devices）判定。回滚后 customer_user 的可见性重新由 user_devices 决定。
CREATE OR REPLACE FUNCTION user_may_access_device(p_user_id BIGINT, p_device_name TEXT)
RETURNS BOOLEAN
LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM users u
        WHERE u.id = p_user_id
          AND u.disabled_at IS NULL
          AND (
              u.role = 'platform_admin'
              OR EXISTS (
                  SELECT 1
                  FROM devices d
                  WHERE d.device_name = p_device_name
                    AND d.customer_id = u.customer_id
                    AND (
                        u.role = 'customer_admin'
                        OR EXISTS (
                            SELECT 1 FROM user_devices ud
                            WHERE ud.user_id = u.id AND ud.device_name = d.device_name
                        )
                    )
              )
          )
    )
$$;

COMMENT ON FUNCTION user_may_access_device(BIGINT, TEXT) IS
    '该用户能否访问该设备：平台管理员任意设备（含不存在的）；客户管理员本客户全部设备；客户普通用户本客户内已绑定设备；已停用或不存在的用户一律否。设备访问规则的唯一定义。';

DROP FUNCTION IF EXISTS user_may_access_tank(BIGINT, TEXT, INT);
DROP TABLE IF EXISTS user_tank_grants;
