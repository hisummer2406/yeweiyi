-- 设备彻底删除（cloud#83，契约 docs/device-purge-contract.md）。任务表兼作栅栏：同一设备有未完成任务时，
-- 业务记录与小时汇总的写入语句一律写不进（device_record_writable），订阅/汇总/回补共用的设备清单也不再列出它。

CREATE TABLE device_purges (
    id                 BIGSERIAL   PRIMARY KEY,
    device_name        TEXT        NOT NULL,
    status             TEXT        NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'failed', 'completed')),
    step               TEXT        NOT NULL DEFAULT 'thingsboard' CHECK (step IN ('thingsboard', 'business', 'verify', 'done')),
    error              TEXT,
    counts             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    reason             TEXT        NOT NULL,
    requested_by       BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    requested_username TEXT        NOT NULL,
    customer_id        BIGINT,
    station_id         BIGINT,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    CHECK ((status = 'completed') = (finished_at IS NOT NULL)),
    CHECK ((status = 'completed') = (step = 'done'))
);

-- 同一设备至多一个未完成任务：重复发起拿到同一任务，并发发起只有一个能建成。
CREATE UNIQUE INDEX device_purges_open_key ON device_purges (device_name) WHERE status <> 'completed';
CREATE INDEX device_purges_device_idx ON device_purges (device_name, started_at DESC);

COMMENT ON TABLE device_purges IS
    '平台管理员彻底删除设备的任务与结果记录（cloud#83）。只存元数据与各类删除条数，不存任何被删记录的内容；永久保留。未完成（running/failed）的任务同时是该设备的写入栅栏。';
COMMENT ON COLUMN device_purges.device_name IS '被删除的完整设备编号。刻意不做外键：设备行在删除过程中被删掉，任务记录要留下。';
COMMENT ON COLUMN device_purges.status IS 'running 执行中；failed 停在 step 所示步骤，等再次发起；completed 全部清理并核对完成。';
COMMENT ON COLUMN device_purges.step IS '下一个要做（或失败）的步骤：thingsboard → business → verify → done。每步幂等，重试从这里继续。';
COMMENT ON COLUMN device_purges.error IS '最近一次失败的原因；成功推进后清空。';
COMMENT ON COLUMN device_purges.counts IS '各类数据删除条数（只有数字），随步骤推进写入。';
COMMENT ON COLUMN device_purges.reason IS '发起人填写的删除原因。';
COMMENT ON COLUMN device_purges.requested_by IS '发起人用户 ID；账号被删后置空，用户名快照仍在 requested_username。';
COMMENT ON COLUMN device_purges.requested_username IS '发起时发起人的用户名快照。';
COMMENT ON COLUMN device_purges.customer_id IS '发起时设备所属客户（快照，不做外键，客户以后可被删除）。';
COMMENT ON COLUMN device_purges.station_id IS '发起时设备所在油站（快照，不做外键）。';
COMMENT ON COLUMN device_purges.finished_at IS '全部完成的时刻；同编号再次登记后，信封 ts 不晚于它的记录视为旧记录重放，不写入。';

-- 业务记录能否写入：没有未完成的删除任务，且记录生成时刻（毫秒）晚于最近一次删除完成时刻。
-- p_ts_ms 为 NULL 时只看栅栏（用于回补标记这种不带时刻的写入）。
CREATE FUNCTION device_record_writable(p_device_name TEXT, p_ts_ms BIGINT)
RETURNS BOOLEAN
LANGUAGE sql STABLE AS $$
    SELECT NOT EXISTS (
               SELECT 1 FROM device_purges p
               WHERE p.device_name = p_device_name AND p.status <> 'completed')
       AND (p_ts_ms IS NULL OR NOT EXISTS (
               SELECT 1 FROM device_purges p
               WHERE p.device_name = p_device_name AND p.status = 'completed'
                 AND p.finished_at >= to_timestamp(p_ts_ms / 1000.0)))
$$;

COMMENT ON FUNCTION device_record_writable(TEXT, BIGINT) IS
    '设备删除栅栏与重放截止（cloud#83）：删除进行中一律 false；删除完成后，生成时刻不晚于完成时刻的记录为 false。';
