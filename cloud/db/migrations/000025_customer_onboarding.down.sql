-- 回到 000024 的判定（不含客户开通状态）。
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
                            SELECT 1 FROM user_tank_grants g
                            WHERE g.user_id = u.id AND g.device_name = d.device_name
                        )
                    )
              )
          )
    )
$$;

COMMENT ON FUNCTION user_may_access_device(BIGINT, TEXT) IS
    '该用户能否定位该设备：平台管理员任意设备（含不存在的）；客户管理员本客户全部设备；客户普通用户本客户内至少有一个被授权罐的设备；已停用或不存在的用户一律否。不代表可读整机——罐级判定见 user_may_access_tank。';

-- 能否查看某设备的某个罐。p_tank = 0 表示整机级（无罐号）的数据：只有管理员可见。
CREATE OR REPLACE FUNCTION user_may_access_tank(p_user_id BIGINT, p_device_name TEXT, p_tank INT)
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
                        OR (
                            COALESCE(p_tank, 0) > 0
                            AND EXISTS (
                                SELECT 1 FROM user_tank_grants g
                                WHERE g.user_id = u.id AND g.device_name = d.device_name AND g.tank = p_tank
                            )
                        )
                    )
              )
          )
    )
$$;

COMMENT ON FUNCTION user_may_access_tank(BIGINT, TEXT, INT) IS
    '该用户能否查看该设备的该罐：平台管理员任意；客户管理员本客户设备的全部罐及整机级数据；客户普通用户仅本客户设备上被授权的罐，tank<=0 一律否；已停用或不存在的用户一律否。授权读数据库当前状态，不读令牌。';

ALTER TABLE customers DROP CONSTRAINT customers_onboarded_at_check;
ALTER TABLE customers DROP COLUMN onboarded_at, DROP COLUMN onboarding_status;
