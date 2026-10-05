-- 多租户（客户）模型，阶段 1：客户实体、用户/设备归属、历史记录的客户快照。
-- 租户只存在于本业务库，不映射 ThingsBoard customer（见 openspec 变更
-- implement-multitenant-support-with-customer-isolation 的 design D1）。

CREATE TABLE customers (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE customers IS
    '客户（租户）。用户与设备各自至多属于一个客户；平台管理员不属于任何客户。';
COMMENT ON COLUMN customers.name IS '客户名称，全局唯一';

-- 存量数据（含私有化的单客户部署）全部落入 default，升级后行为不变。
INSERT INTO customers (name) VALUES ('default');

-- 用户：平台管理员 customer_id 为空，其他角色必须属于一个客户。
-- 先加列、回填，再加 CHECK，否则存量行会让约束添加失败。
ALTER TABLE users
    ADD COLUMN customer_id BIGINT REFERENCES customers (id) ON DELETE RESTRICT;

UPDATE users
SET customer_id = (SELECT id FROM customers WHERE name = 'default')
WHERE role <> 'platform_admin';

ALTER TABLE users
    ADD CONSTRAINT users_customer_role_check
    CHECK ((role = 'platform_admin') = (customer_id IS NULL));

CREATE INDEX users_customer_id_idx ON users (customer_id);

COMMENT ON COLUMN users.customer_id IS
    '用户所属客户。平台管理员为 NULL，其余角色必须非空（users_customer_role_check）。';

-- 设备：NULL 表示已登记但尚未分配给任何客户，此时非平台管理员不可见、不可绑定。
ALTER TABLE devices
    ADD COLUMN customer_id BIGINT REFERENCES customers (id) ON DELETE RESTRICT;

UPDATE devices
SET customer_id = (SELECT id FROM customers WHERE name = 'default');

CREATE INDEX devices_customer_id_idx ON devices (customer_id);

COMMENT ON COLUMN devices.customer_id IS
    '设备所属客户；NULL=已登记未分配。访问判定见 db/queries/user_devices.sql 的 UserMayAccessDevice。';

-- 历史记录：写入时快照一个客户，而不是 JOIN 设备当前归属——设备改归属后历史应留在当时的客户
-- 名下。取值规则：操作者所属客户；操作者是平台管理员（无客户）时取设备当时的客户，这样客户
-- 管理员审计时能看到平台运维对自己设备做过什么；设备不存在则为 NULL（被拒绝的越权尝试可能
-- 指向不存在的设备）。设备/系统触发的行（结果上报、过期清扫）取对应命令的客户。
-- 外键用 RESTRICT：有历史的客户不能被删除，否则审计里会留下指向已不存在客户的悬空 id。
ALTER TABLE control_commands
    ADD COLUMN customer_id BIGINT REFERENCES customers (id) ON DELETE RESTRICT;

UPDATE control_commands c
SET customer_id = COALESCE(
    (SELECT u.customer_id FROM users u WHERE u.id = c.operator_id),
    (SELECT d.customer_id FROM devices d WHERE d.device_name = c.device_name)
);

COMMENT ON COLUMN control_commands.customer_id IS
    '下发时的客户快照：操作者所属客户，平台管理员操作则取设备当时的客户。设备日后改归属不会改变它。';

ALTER TABLE audit_log
    ADD COLUMN customer_id BIGINT REFERENCES customers (id) ON DELETE RESTRICT;

UPDATE audit_log a
SET customer_id = COALESCE(
    (SELECT u.customer_id FROM users u WHERE u.id = a.actor_id),
    (SELECT c.customer_id FROM control_commands c WHERE c.command_id = a.command_id),
    (SELECT d.customer_id FROM devices d WHERE d.device_name = a.device_name)
);

CREATE INDEX audit_log_customer_occurred_idx ON audit_log (customer_id, occurred_at DESC);

COMMENT ON COLUMN audit_log.customer_id IS
    '记录产生时的客户快照：操作者所属客户；平台管理员/设备/系统触发的记录取对应命令或设备当时的客户；设备不存在则为 NULL。';
