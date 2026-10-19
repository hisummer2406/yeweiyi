-- 回滚前提：没有带密码的手机号员工。有的话先重置/清掉，不在这里静默删除密码。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE role = 'customer_user' AND phone IS NOT NULL AND password_hash IS NOT NULL) THEN
        RAISE EXCEPTION 'phone staff with a password exist; remove their passwords before rolling back';
    END IF;
END $$;

ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN users.must_change_password IS
    '密码由管理员设置或重置后为 true（初始密码），本人改密后清除。只是给客户端的提示，服务端不据此拦截请求。';

ALTER TABLE users DROP CONSTRAINT users_password_role_check;
ALTER TABLE users
    ADD CONSTRAINT users_password_role_check
    CHECK (
        role <> 'customer_user' AND password_hash IS NOT NULL
        OR role = 'customer_user' AND (
            (phone IS NOT NULL AND password_hash IS NULL)
            OR (phone IS NULL AND password_hash IS NOT NULL)
        )
    ) NOT VALID;

COMMENT ON COLUMN users.password_hash IS
    'bcrypt 哈希。平台管理员、客户管理员、用户名账号必填；手机号员工（有手机号的 customer_user）必须为 NULL，只能经微信登录。';
