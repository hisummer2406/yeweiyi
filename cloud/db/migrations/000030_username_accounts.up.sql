-- 客户侧账号可用独立用户名 + 密码登录（#77，国外用户无微信）。
-- 客户侧账号二选一：手机号账号（username = phone，保持 #59 原样）或用户名账号（phone 为 NULL，必须有密码）。
-- 放宽 000021 的两条 CHECK；同样用 NOT VALID，只约束之后的写入。

ALTER TABLE users DROP CONSTRAINT users_phone_role_check;
ALTER TABLE users DROP CONSTRAINT users_password_role_check;

ALTER TABLE users
    ADD CONSTRAINT users_phone_role_check
    CHECK (
        (role = 'platform_admin' AND phone IS NULL)
        OR (role <> 'platform_admin' AND phone IS NOT NULL AND username = phone)
        OR (role <> 'platform_admin' AND phone IS NULL
            AND username ~ '^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$'
            AND username !~ '^1[3-9][0-9]{9}$')
    ) NOT VALID,
    ADD CONSTRAINT users_password_role_check
    CHECK (
        role <> 'customer_user' AND password_hash IS NOT NULL
        OR role = 'customer_user' AND (
            (phone IS NOT NULL AND password_hash IS NULL)
            OR (phone IS NULL AND password_hash IS NOT NULL)
        )
    ) NOT VALID;

-- 用户名全局唯一且不区分大小写（登录时也按小写匹配）。已有平台管理员若仅大小写不同，这里会失败，需人工处理。
CREATE UNIQUE INDEX users_username_lower_key ON users (lower(username));

ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN users.must_change_password IS
    '密码由管理员设置或重置后为 true（初始密码），本人改密后清除。只是给客户端的提示，服务端不据此拦截请求。';
COMMENT ON COLUMN users.phone IS
    '规范化手机号（中国大陆 11 位）。平台管理员为 NULL；客户侧账号要么有手机号（username 与之相等），要么是用户名账号（phone 为 NULL）；全局唯一。';
COMMENT ON COLUMN users.password_hash IS
    'bcrypt 哈希。平台管理员、客户管理员、用户名账号必填；手机号员工必须为 NULL，只能经微信登录。';
