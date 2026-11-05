-- 出入库手工补录（cloud#69）：小程序用户在云端新增一条漏报的记录。补录记录与设备上报的记录同表同读路径，
-- 但必须能区分来源，且只有云端生成的才能撤销——设备上报的记录永远不碰。
-- 撤销是软删除（deleted_at）：出入库是业务记录，业务库是长期事实来源（CLAUDE.md 约束 5），不物理删除；
-- 读路径排除已撤销的行，审计日志另存完整内容。
ALTER TABLE stock_events
    ADD COLUMN origin     TEXT        NOT NULL DEFAULT 'device' CHECK (origin IN ('device', 'cloud')),
    ADD COLUMN created_by BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by BIGINT      REFERENCES users (id) ON DELETE SET NULL;

COMMENT ON COLUMN stock_events.origin IS 'device = 设备经 ThingsBoard 上报（含设备端补录 BuLuFragment）；cloud = 小程序用户经云端接口补录。只有 cloud 的行可被撤销。';
COMMENT ON COLUMN stock_events.created_by IS '云端补录的操作人；设备上报的行为 NULL。用户被删除后置空，审计日志仍保留操作人名称。';
COMMENT ON COLUMN stock_events.deleted_at IS '撤销时间（软删除）。非 NULL 的行不出现在任何读路径里。';
COMMENT ON COLUMN stock_events.deleted_by IS '撤销操作人。';
