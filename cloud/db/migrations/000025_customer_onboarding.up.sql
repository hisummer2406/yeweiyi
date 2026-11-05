-- 客户开通状态（cloud#60）：配置中的客户，其下全部账号不能登录/续期/访问业务。
-- 升级策略：迁移时已存在的客户（含 default）一律置为 active，升级不锁任何现有客户；
-- 列默认值是 configuring，此后新建的客户必须经平台管理员完成开通。
ALTER TABLE customers
    ADD COLUMN onboarding_status TEXT NOT NULL DEFAULT 'configuring'
        CHECK (onboarding_status IN ('configuring', 'active')),
    ADD COLUMN onboarded_at TIMESTAMPTZ;

UPDATE customers SET onboarding_status = 'active', onboarded_at = now();

-- 状态与时间戳必须一致，且只能单向 configuring -> active。
ALTER TABLE customers
    ADD CONSTRAINT customers_onboarded_at_check
        CHECK ((onboarding_status = 'active') = (onboarded_at IS NOT NULL));

COMMENT ON COLUMN customers.onboarding_status IS
    '开通状态：configuring（配置中，其下账号不能登录）| active（已开通）。单向，新增资产或停用账号不会退回。';
COMMENT ON COLUMN customers.onboarded_at IS '完成开通的时间；configuring 时为空。';

-- 纵深防御：设备/罐访问判定本身也拒绝配置中客户的非平台管理员（认证中间件是第一道）。
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
                    AND EXISTS (SELECT 1 FROM customers c WHERE c.id = u.customer_id AND c.onboarding_status = 'active')
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
    '（#60）客户配置中时该客户下的非平台管理员一律否。该用户能否定位该设备：平台管理员任意设备（含不存在的）；客户管理员本客户全部设备；客户普通用户本客户内至少有一个被授权罐的设备；已停用或不存在的用户一律否。不代表可读整机——罐级判定见 user_may_access_tank。';

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
                    AND EXISTS (SELECT 1 FROM customers c WHERE c.id = u.customer_id AND c.onboarding_status = 'active')
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
    '（#60）客户配置中时该客户下的非平台管理员一律否。该用户能否查看该设备的该罐：平台管理员任意；客户管理员本客户设备的全部罐及整机级数据；客户普通用户仅本客户设备上被授权的罐，tank<=0 一律否；已停用或不存在的用户一律否。授权读数据库当前状态，不读令牌。';
