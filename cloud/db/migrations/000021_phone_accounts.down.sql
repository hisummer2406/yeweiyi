-- 回滚前提：没有无密码员工。有的话 SET NOT NULL 会失败，需先处理这些账号，不在这里静默造密码。
ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_password_role_check,
    DROP CONSTRAINT IF EXISTS users_phone_format_check,
    DROP CONSTRAINT IF EXISTS users_phone_role_check;
DROP INDEX IF EXISTS users_phone_key;
ALTER TABLE users ALTER COLUMN password_hash SET NOT NULL;
ALTER TABLE users DROP COLUMN IF EXISTS phone;
