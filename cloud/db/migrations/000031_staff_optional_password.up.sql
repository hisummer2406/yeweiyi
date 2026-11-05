-- 手机号员工（customer_user）可以由管理员设置密码（#77 补充）：不设则仍只能走微信，设了则也能用手机号 + 密码登录。
-- 用户名员工仍必须有密码。放宽 000030 的 users_password_role_check（NOT VALID，只约束之后的写入）。
-- 同时撤销 000030 里的 must_change_password：产品决定不做自助改密/强制改密，改密一律由平台管理员重置（#77）。
ALTER TABLE users DROP COLUMN IF EXISTS must_change_password;

ALTER TABLE users DROP CONSTRAINT users_password_role_check;

ALTER TABLE users
    ADD CONSTRAINT users_password_role_check
    CHECK (
        role <> 'customer_user' AND password_hash IS NOT NULL
        OR role = 'customer_user' AND (phone IS NOT NULL OR password_hash IS NOT NULL)
    ) NOT VALID;

COMMENT ON COLUMN users.password_hash IS
    'bcrypt 哈希。平台管理员、客户管理员、用户名账号必填；手机号员工可空（空则只能经微信登录），由平台管理员设置后也可用手机号 + 密码登录。';
