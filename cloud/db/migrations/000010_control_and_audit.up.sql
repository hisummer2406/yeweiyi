-- Remote control command state machine and audit trail (GitHub issue #10, issue #1 "远程控制与安全状态机").
--
-- This is the ThingsBoard-single-stack replacement for write_back_commands, which modelled the old
-- Alink design's pending/sent/acked/failed and is left in place until internal/mqtt is removed. The
-- two differ in more than naming: the old table had no operator, no reason, no expiry and no
-- distinction between "the device took the request" and "the action actually happened", all of
-- which issue #1 requires.

-- 角色。完整的多租户模型（客户表、租户级隔离）仍未建设，这里先补上控制权限分级所必需的角色字段：
-- 没有它就无法区分"谁能改报警阈值"和"谁只能看"。
ALTER TABLE users
    ADD COLUMN role TEXT NOT NULL DEFAULT 'customer_user'
        CHECK (role IN ('platform_admin', 'customer_admin', 'customer_user'));

COMMENT ON COLUMN users.role IS
    '角色：platform_admin=平台管理员，customer_admin=客户管理员，customer_user=客户普通用户。'
    '控制命令的权限分级依据此列（见 internal/protocol 的 PermissionLevel）。';

CREATE TABLE control_commands (
    -- Minted by the backend, sent to the device inside the RPC envelope, and echoed back in both
    -- the ACK and the asynchronous result. It is the only correlation key between the three
    -- messages, so it is the primary key rather than a surrogate id — ThingsBoard's own per-call
    -- request id cannot serve here because it changes on every retry.
    command_id    UUID PRIMARY KEY,
    device_name   TEXT NOT NULL REFERENCES devices (device_name) ON DELETE CASCADE,
    method        TEXT NOT NULL,
    args          JSONB NOT NULL DEFAULT '{}'::jsonb,

    -- operator_id may become NULL if the user is deleted, but `operator` is a snapshot of the
    -- username taken at issue time and never changes. An audit trail that could be erased by
    -- deleting a user would not be an audit trail.
    operator_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    operator      TEXT NOT NULL,
    -- The mandatory 二次确认 justification. NOT NULL with a non-empty check: issue #1 requires a
    -- reason on every control action, and a column that is allowed to be blank is not a requirement.
    reason        TEXT NOT NULL CHECK (length(btrim(reason)) > 0),

    status        TEXT NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'sent', 'received', 'rejected',
                                    'executing', 'succeeded', 'failed', 'expired')),
    error_code    TEXT,
    error_reason  TEXT,
    result_detail JSONB,

    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Short validity window. The device enforces it too (it is the only side that knows when it
    -- actually received the command); this copy is what the cloud-side sweep uses.
    expires_at    TIMESTAMPTZ NOT NULL,
    sent_at       TIMESTAMPTZ,
    acked_at      TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT control_commands_expiry_after_issue CHECK (expires_at > issued_at)
);

COMMENT ON TABLE control_commands IS
    '远程控制命令及其状态机（issue #1）。状态流转单向不可回退：'
    'pending→sent→received→executing→succeeded/failed，或 →rejected/→expired。'
    'MQTT/RPC 发送成功只代表消息发出，received 表示设备已接受，'
    '真正执行结果由设备异步上报的 cmdResult 决定。';
COMMENT ON COLUMN control_commands.command_id IS '后端生成的命令唯一 ID，同时是设备幂等去重键';
COMMENT ON COLUMN control_commands.operator IS '下发时的操作者用户名快照，用户被删除后仍保留';
COMMENT ON COLUMN control_commands.reason IS '二次确认时操作员填写的原因，强制非空';
COMMENT ON COLUMN control_commands.expires_at IS '命令有效期，超时未进终态由 TTL 清扫置为 expired';

-- "这台设备最近的命令" 与状态轮询。
CREATE INDEX control_commands_device_issued_idx
    ON control_commands (device_name, issued_at DESC);

-- The expiry sweep scans only non-terminal rows, which are a small minority of the table once it
-- has any history — a partial index keeps that scan cheap regardless of how large the table grows.
CREATE INDEX control_commands_pending_expiry_idx
    ON control_commands (expires_at)
    WHERE status IN ('pending', 'sent', 'received', 'executing');

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Same reasoning as control_commands.operator: the id is a convenience, the text is the record.
    actor_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor       TEXT NOT NULL,

    action      TEXT NOT NULL,
    -- Deliberately NOT a foreign key to devices: an audit record of a *denied* attempt against a
    -- device_name that does not exist is exactly the kind of thing worth keeping, and a foreign key
    -- would refuse to store it. Same for command_id, which is absent for non-command actions.
    device_name TEXT,
    command_id  UUID,
    outcome     TEXT NOT NULL CHECK (outcome IN ('allowed', 'denied', 'succeeded', 'failed')),
    detail      JSONB
);

COMMENT ON TABLE audit_log IS
    '审计日志，长期保留，不随遥测清理。记录控制命令的下发、拒绝与最终结果，'
    '以及越权访问尝试。device_name/command_id 刻意不做外键——被拒绝的越权尝试'
    '所指向的设备可能根本不存在，而那正是最值得记录的一类事件。';

CREATE INDEX audit_log_occurred_idx ON audit_log (occurred_at DESC);
CREATE INDEX audit_log_device_idx ON audit_log (device_name, occurred_at DESC);
CREATE INDEX audit_log_command_idx ON audit_log (command_id) WHERE command_id IS NOT NULL;
