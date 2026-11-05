-- 员工按油罐授权（cloud#63）。授权粒度是 (用户, 设备名, 罐号)：设备归属（devices.customer_id / station_id）
-- 与油罐授权相互独立，换机用新设备名即天然没有任何授权，不会因油站或罐号相同而继承。
--
-- 不转换旧的设备级绑定 user_devices：旧绑定不能作为"全部油罐"的放行依据（#63 明确要求），升级后
-- customer_user 在被客户管理员明确分配油罐之前看不到任何设备。user_devices 表原样保留（只读、不再被
-- 任何授权判定引用），供回看与回滚。
CREATE TABLE user_tank_grants (
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_name TEXT        NOT NULL REFERENCES devices (device_name) ON DELETE CASCADE,
    tank        INT         NOT NULL CHECK (tank BETWEEN 1 AND 64),
    granted_by  BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, device_name, tank)
);
CREATE INDEX user_tank_grants_device_idx ON user_tank_grants (device_name);

COMMENT ON TABLE user_tank_grants IS '员工-油罐查看授权：(用户, 设备名, 罐号)。只授予查看，不含配置/控制/物理命令。写入由 InsertTankGrantIfAllowed 在同一条语句内复核同客户与角色。';
COMMENT ON COLUMN user_tank_grants.user_id IS '被授权的用户，必须是 customer_user（客户管理员与平台管理员看全部罐，无需授权）。';
COMMENT ON COLUMN user_tank_grants.device_name IS '设备名，关联 devices.device_name。设备换客户时授权同事务清除。';
COMMENT ON COLUMN user_tank_grants.tank IS '罐号，1..64（协议 MaxTankCount）。tank=0（整机事件）不可授权，员工看不到整机级记录。';
COMMENT ON COLUMN user_tank_grants.granted_by IS '执行授权的管理员；管理员被删除后置空。';

-- 能否"定位"该设备：客户普通用户需在该设备上至少有一个被授权的罐。这只回答设备是否可达；
-- 具体能看哪个罐由 user_may_access_tank 决定，调用方不得因设备可达而返回整机数据。
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
CREATE FUNCTION user_may_access_tank(p_user_id BIGINT, p_device_name TEXT, p_tank INT)
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
