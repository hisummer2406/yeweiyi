-- 回滚前提：没有用户名账号（phone 为 NULL 的客户侧账号）。有的话先处理，不在这里静默删除。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE role <> 'platform_admin' AND phone IS NULL) THEN
        RAISE EXCEPTION 'username accounts exist; delete or convert them before rolling back';
    END IF;
END $$;

ALTER TABLE users DROP COLUMN IF EXISTS must_change_password;
DROP INDEX IF EXISTS users_username_lower_key;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_password_role_check;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_phone_role_check;
ALTER TABLE users
    ADD CONSTRAINT users_phone_role_check
    CHECK (
        (role = 'platform_admin' AND phone IS NULL)
        OR (role <> 'platform_admin' AND phone IS NOT NULL AND username = phone)
    ) NOT VALID,
    ADD CONSTRAINT users_password_role_check
    CHECK (
        (role = 'customer_user' AND password_hash IS NULL)
        OR (role <> 'customer_user' AND password_hash IS NOT NULL)
    ) NOT VALID;
