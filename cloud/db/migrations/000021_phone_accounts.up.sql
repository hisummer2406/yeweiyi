-- 客户手机号账号（#59）：客户管理员/员工以手机号标识，员工没有网页密码。
-- 系统未上线，不迁移旧账号：约束用 NOT VALID 添加，只约束之后的写入，开发库里遗留的旧客户账号需重建。
ALTER TABLE users ADD COLUMN phone TEXT;

COMMENT ON COLUMN users.phone IS
    '规范化手机号（中国大陆 11 位）。平台管理员为 NULL，客户管理员/员工必填，且 username 与之相等；全局唯一。';

-- 员工（customer_user）没有网页密码，所以哈希必须可空。是否允许为空由下面的 CHECK 按角色限定，
-- 不用占位哈希，也不用统一默认密码。
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

COMMENT ON COLUMN users.password_hash IS
    'bcrypt 哈希。平台管理员、客户管理员必填；客户普通用户（员工）必须为 NULL，只能经微信登录。';

CREATE UNIQUE INDEX users_phone_key ON users (phone) WHERE phone IS NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT users_phone_role_check
    CHECK (
        (role = 'platform_admin' AND phone IS NULL)
        OR (role <> 'platform_admin' AND phone IS NOT NULL AND username = phone)
    ) NOT VALID,
    ADD CONSTRAINT users_phone_format_check
    CHECK (phone IS NULL OR phone ~ '^1[3-9][0-9]{9}$') NOT VALID,
    ADD CONSTRAINT users_password_role_check
    CHECK (
        (role = 'customer_user' AND password_hash IS NULL)
        OR (role <> 'customer_user' AND password_hash IS NOT NULL)
    ) NOT VALID;
