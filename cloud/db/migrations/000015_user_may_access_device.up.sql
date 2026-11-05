-- 设备访问规则的唯一定义。此前这条规则在 SQL 里写了两遍（REST/WebSocket 一份，命令授权连同客户
-- 快照一份），而命令的"判定"与"插入"又是两条独立语句，中间可以插进一次转移。放进函数后：
--   * UserMayAccessDevice、ResolveDeviceAccess 都调它，规则只有一份；
--   * CreateControlCommand 在 INSERT ... SELECT 的 WHERE 里再调一次，判定与插入是同一条语句，
--     看到同一个数据库状态，不会"按新客户授权、却记在旧客户名下"。
-- STABLE：同一条语句内读到一致的快照。
CREATE FUNCTION user_may_access_device(p_user_id BIGINT, p_device_name TEXT)
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
