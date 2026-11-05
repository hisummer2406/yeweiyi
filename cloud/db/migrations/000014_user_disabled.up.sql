-- 用户停用（多租户阶段 2）。停用而不是删除：用户的命令与审计记录通过 operator_id/actor_id 引用它，
-- 且停用可逆。访问判定（UserMayAccessDevice 等）读这一列，所以停用对已签发、未过期的 token 立即生效。
ALTER TABLE users ADD COLUMN disabled_at TIMESTAMPTZ;

COMMENT ON COLUMN users.disabled_at IS
    '停用时间；NULL=启用。停用后无法登录，且已签发的 token 也无法访问任何设备（判定读库）。';
