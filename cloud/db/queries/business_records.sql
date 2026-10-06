-- 出入库与报警的业务库副本（迁移 000017，issue #29）。写入全部幂等：回补与实时摄取并发、补投重复、
-- 乱序到达都收敛到同一结果。

-- name: UpsertStockEvent :exec
-- customer_id 取首次写入时设备所属的客户，之后的重放与修正版不改它（归属由写入时的事实决定，不看设备自报的时间戳）。
-- 同一 (设备, eventId) 只保留信封 ts 最大的一份；相同则保留先写入的，结果与到达顺序无关。
-- 不同信封 ts 记入 report_ts（不论是否胜出），重放同一份不会重复计数。
INSERT INTO stock_events (device_name, event_id, synthetic_id, occurred_at, env_ts, report_ts, payload, customer_id)
VALUES (
    sqlc.arg('device_name'), sqlc.arg('event_id'), sqlc.arg('synthetic_id'),
    to_timestamp(sqlc.arg('occurred_ms')::BIGINT / 1000.0), sqlc.arg('env_ts'),
    ARRAY[sqlc.arg('env_ts')::BIGINT], sqlc.arg('payload'),
    (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
)
ON CONFLICT (device_name, event_id) DO UPDATE SET
    synthetic_id = CASE WHEN EXCLUDED.env_ts > stock_events.env_ts THEN EXCLUDED.synthetic_id ELSE stock_events.synthetic_id END,
    occurred_at  = CASE WHEN EXCLUDED.env_ts > stock_events.env_ts THEN EXCLUDED.occurred_at  ELSE stock_events.occurred_at  END,
    payload      = CASE WHEN EXCLUDED.env_ts > stock_events.env_ts THEN EXCLUDED.payload      ELSE stock_events.payload      END,
    env_ts       = GREATEST(EXCLUDED.env_ts, stock_events.env_ts),
    report_ts    = CASE WHEN EXCLUDED.env_ts = ANY(stock_events.report_ts)
                        THEN stock_events.report_ts
                        ELSE array_append(stock_events.report_ts, EXCLUDED.env_ts) END,
    updated_at   = now();

-- name: InsertAlarmEvent :exec
-- 同一份重放不改变已有行。
INSERT INTO alarm_events (device_name, alarm_id, status, synthetic_id, occurred_at, env_ts, payload, customer_id)
VALUES (
    sqlc.arg('device_name'), sqlc.arg('alarm_id'), sqlc.arg('status'), sqlc.arg('synthetic_id'),
    to_timestamp(sqlc.arg('occurred_ms')::BIGINT / 1000.0), sqlc.arg('env_ts'), sqlc.arg('payload'),
    (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
)
ON CONFLICT (device_name, alarm_id, status) DO NOTHING;

-- name: InsertRecordReject :exec
INSERT INTO record_rejects (kind, device_name, env_ts, reason, raw, customer_id)
VALUES (sqlc.arg('kind'), sqlc.arg('device_name'), sqlc.arg('env_ts'), sqlc.arg('reason'), sqlc.arg('raw'),
        (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT))
ON CONFLICT (kind, device_name, env_ts, raw_hash) DO NOTHING;

-- name: ListStockEventsAsc :many
-- 按记录关闭时刻落在 [begin, end) 取，再按同一时刻排序；调用方多取一行判断是否截断。
SELECT payload, synthetic_id, occurred_at, cardinality(report_ts)::INT AS report_count
FROM stock_events s
WHERE s.device_name = sqlc.arg('device_name')
  AND occurred_at >= sqlc.arg('begin_time') AND occurred_at < sqlc.arg('end_time')
  AND customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
ORDER BY occurred_at ASC, event_id ASC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: ListStockEventsDesc :many
SELECT payload, synthetic_id, occurred_at, cardinality(report_ts)::INT AS report_count
FROM stock_events s
WHERE s.device_name = sqlc.arg('device_name')
  AND occurred_at >= sqlc.arg('begin_time') AND occurred_at < sqlc.arg('end_time')
  AND customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
ORDER BY occurred_at DESC, event_id DESC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: SummarizeStockRejects :one
-- 窗口内（按信封 ts）读不出来的记录。cutoff_ms = 0 表示未配置 stock.legacyFormatBefore，此时一律算当下
-- 故障；否则早于它的算已封口的旧格式空洞（legacy）。sample 取窗口内第一条当下故障的原文。
SELECT
    COUNT(*) FILTER (WHERE reason = 'invalid_source')::INT AS invalid_source,
    COUNT(*) FILTER (WHERE reason = 'unparsable' AND sqlc.arg('cutoff_ms')::BIGINT > 0
                       AND env_ts < sqlc.arg('cutoff_ms')::BIGINT)::INT AS legacy,
    COUNT(*) FILTER (WHERE reason = 'unparsable' AND NOT (sqlc.arg('cutoff_ms')::BIGINT > 0
                       AND env_ts < sqlc.arg('cutoff_ms')::BIGINT))::INT AS unparsable,
    COALESCE((SELECT r.raw FROM record_rejects r
              WHERE r.kind = 'stock' AND r.device_name = sqlc.arg('device_name') AND r.reason = 'unparsable'
                AND r.env_ts >= sqlc.arg('begin_ms') AND r.env_ts < sqlc.arg('end_ms')
                AND NOT (sqlc.arg('cutoff_ms')::BIGINT > 0 AND r.env_ts < sqlc.arg('cutoff_ms')::BIGINT)
                AND r.customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
              ORDER BY r.env_ts ASC, r.id ASC LIMIT 1), '')::TEXT AS sample
FROM record_rejects
WHERE kind = 'stock' AND device_name = sqlc.arg('device_name')
  AND env_ts >= sqlc.arg('begin_ms') AND env_ts < sqlc.arg('end_ms')
  AND customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT);

-- name: ListAlarmEventsAsc :many
SELECT payload, synthetic_id, occurred_at
FROM alarm_events a
WHERE a.device_name = sqlc.arg('device_name')
  AND occurred_at >= sqlc.arg('begin_time') AND occurred_at < sqlc.arg('end_time')
  AND customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
ORDER BY occurred_at ASC, alarm_id ASC, status ASC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: ListAlarmEventsDesc :many
SELECT payload, synthetic_id, occurred_at
FROM alarm_events a
WHERE a.device_name = sqlc.arg('device_name')
  AND occurred_at >= sqlc.arg('begin_time') AND occurred_at < sqlc.arg('end_time')
  AND customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
ORDER BY occurred_at DESC, alarm_id DESC, status DESC
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: HasRecordBackfill :one
SELECT EXISTS (SELECT 1 FROM record_backfills WHERE device_name = $1)::BOOLEAN AS done;

-- name: MarkRecordBackfill :exec
INSERT INTO record_backfills (device_name) VALUES ($1) ON CONFLICT (device_name) DO NOTHING;
