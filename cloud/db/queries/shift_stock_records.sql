-- 交班与库存记录（迁移 000037，cloud#85，契约 docs/shift-stock-records-contract.md）。写入全部幂等：
-- 表头按信封 ts 最大者胜（相同则保留先写入的），罐行随后由 Sync*RecordTanks 与表头载荷对齐。

-- name: UpsertShiftRecord :execrows
-- 返回 1 = 本份胜出（新插入或替换了更旧的一份）；0 = 已有同样新或更新的一份，表头不变。之后调用方一律执行 SyncShiftRecordTanks。
-- customer_id 取首次写入时设备所属的客户，之后不改。删除栅栏与重放截止同 UpsertStockEvent。
INSERT INTO shift_records (device_name, shift_id, status, begin_at, end_at, env_ts, payload, customer_id)
SELECT
    sqlc.arg('device_name'), sqlc.arg('shift_id'), sqlc.arg('status'),
    to_timestamp(sqlc.arg('begin_ms')::BIGINT / 1000.0),
    CASE WHEN sqlc.narg('end_ms')::BIGINT IS NULL THEN NULL ELSE to_timestamp(sqlc.narg('end_ms')::BIGINT / 1000.0) END,
    sqlc.arg('env_ts'), sqlc.arg('payload'),
    (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
WHERE device_record_writable(sqlc.arg('device_name')::TEXT, sqlc.arg('env_ts')::BIGINT)
ON CONFLICT (device_name, shift_id) DO UPDATE SET
    status     = EXCLUDED.status,
    begin_at   = EXCLUDED.begin_at,
    end_at     = EXCLUDED.end_at,
    env_ts     = EXCLUDED.env_ts,
    payload    = EXCLUDED.payload,
    updated_at = now()
WHERE EXCLUDED.env_ts > shift_records.env_ts;

-- name: SyncShiftRecordTanks :exec
-- 让罐行与表头里胜出的那份载荷一致：删掉载荷里已没有的罐，插入或更新其余的罐，内容没变的不写。每次写入都执行，
-- 所以表头写入后进程中断、或两次写入并发，下一次写入（含回补重放）都会收敛到同一结果。解析器已保证罐号合法且不重复。
WITH want AS (
    SELECT h.device_name, h.shift_id, (e->>'tank')::INT AS tank, e AS data
    FROM shift_records h, jsonb_array_elements(h.payload->'tanks') e
    WHERE h.device_name = sqlc.arg('device_name')::TEXT AND h.shift_id = sqlc.arg('shift_id')::TEXT
), gone AS (
    DELETE FROM shift_record_tanks t
    WHERE t.device_name = sqlc.arg('device_name')::TEXT AND t.shift_id = sqlc.arg('shift_id')::TEXT
      AND NOT EXISTS (SELECT 1 FROM want w WHERE w.tank = t.tank)
)
INSERT INTO shift_record_tanks (device_name, shift_id, tank, data)
SELECT device_name, shift_id, tank, data FROM want
ON CONFLICT (device_name, shift_id, tank) DO UPDATE SET data = EXCLUDED.data
WHERE shift_record_tanks.data IS DISTINCT FROM EXCLUDED.data;

-- name: UpsertStockRecord :execrows
-- 同 UpsertShiftRecord。
INSERT INTO stock_records (device_name, record_id, occurred_at, env_ts, payload, customer_id)
SELECT
    sqlc.arg('device_name'), sqlc.arg('record_id'), to_timestamp(sqlc.arg('occurred_ms')::BIGINT / 1000.0),
    sqlc.arg('env_ts'), sqlc.arg('payload'),
    (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
WHERE device_record_writable(sqlc.arg('device_name')::TEXT, sqlc.arg('env_ts')::BIGINT)
ON CONFLICT (device_name, record_id) DO UPDATE SET
    occurred_at = EXCLUDED.occurred_at,
    env_ts      = EXCLUDED.env_ts,
    payload     = EXCLUDED.payload,
    updated_at  = now()
WHERE EXCLUDED.env_ts > stock_records.env_ts;

-- name: SyncStockRecordTanks :exec
-- 同 SyncShiftRecordTanks。
WITH want AS (
    SELECT h.device_name, h.record_id, (e->>'tank')::INT AS tank, e AS data
    FROM stock_records h, jsonb_array_elements(h.payload->'tanks') e
    WHERE h.device_name = sqlc.arg('device_name')::TEXT AND h.record_id = sqlc.arg('record_id')::TEXT
), gone AS (
    DELETE FROM stock_record_tanks t
    WHERE t.device_name = sqlc.arg('device_name')::TEXT AND t.record_id = sqlc.arg('record_id')::TEXT
      AND NOT EXISTS (SELECT 1 FROM want w WHERE w.tank = t.tank)
)
INSERT INTO stock_record_tanks (device_name, record_id, tank, data)
SELECT device_name, record_id, tank, data FROM want
ON CONFLICT (device_name, record_id, tank) DO UPDATE SET data = EXCLUDED.data
WHERE stock_record_tanks.data IS DISTINCT FROM EXCLUDED.data;

-- name: ListShiftRecordTanks :many
-- 每 (班, 罐) 一行。授权（user_may_access_tank）、客户归属、时间窗（按接班时刻）与可选罐筛选全部在 LIMIT/OFFSET 之前生效。
-- asc = 按接班时刻升序，否则降序；同一班内按罐号升序。调用方多取一行判断是否截断。
SELECT h.shift_id, h.status, h.begin_at, h.end_at, t.tank, t.data
FROM shift_records h
JOIN shift_record_tanks t ON t.device_name = h.device_name AND t.shift_id = h.shift_id
WHERE h.device_name = sqlc.arg('device_name')
  AND h.begin_at >= sqlc.arg('begin_time') AND h.begin_at < sqlc.arg('end_time')
  AND h.customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
  AND user_may_access_tank(sqlc.arg('user_id'), h.device_name, t.tank)
  AND (sqlc.narg('tank')::INT IS NULL OR t.tank = sqlc.narg('tank')::INT)
ORDER BY
    CASE WHEN sqlc.arg('asc')::BOOLEAN THEN h.begin_at END ASC,
    CASE WHEN NOT sqlc.arg('asc')::BOOLEAN THEN h.begin_at END DESC,
    h.shift_id, t.tank
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: ListStockRecordTanks :many
-- 每 (库存批次, 罐) 一行；规则同 ListShiftRecordTanks，时间窗按库存生成时刻。
SELECT h.record_id, h.occurred_at, COALESCE(h.payload->>'trigger', '')::TEXT AS trigger, t.tank, t.data
FROM stock_records h
JOIN stock_record_tanks t ON t.device_name = h.device_name AND t.record_id = h.record_id
WHERE h.device_name = sqlc.arg('device_name')
  AND h.occurred_at >= sqlc.arg('begin_time') AND h.occurred_at < sqlc.arg('end_time')
  AND h.customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT)
  AND user_may_access_tank(sqlc.arg('user_id'), h.device_name, t.tank)
  AND (sqlc.narg('tank')::INT IS NULL OR t.tank = sqlc.narg('tank')::INT)
ORDER BY
    CASE WHEN sqlc.arg('asc')::BOOLEAN THEN h.occurred_at END ASC,
    CASE WHEN NOT sqlc.arg('asc')::BOOLEAN THEN h.occurred_at END DESC,
    h.record_id, t.tank
LIMIT sqlc.arg('lim') OFFSET sqlc.arg('off');

-- name: RecordKindReported :one
-- 设备是否上报过该类记录（含读不出来的原文）：false 时接口给 syncStatus=UNSUPPORTED，客户端显示"设备暂不支持"
-- 而不是"暂无记录"。按设备判定，不看客户归属：这是设备固件的能力，不是谁的数据。kind 取 'shift' 或 'stock_record'。
SELECT (
    (sqlc.arg('kind')::TEXT = 'shift'
        AND EXISTS (SELECT 1 FROM shift_records s WHERE s.device_name = sqlc.arg('device_name')::TEXT))
 OR (sqlc.arg('kind')::TEXT = 'stock_record'
        AND EXISTS (SELECT 1 FROM stock_records r WHERE r.device_name = sqlc.arg('device_name')::TEXT))
 OR EXISTS (SELECT 1 FROM record_rejects j WHERE j.kind = sqlc.arg('kind')::TEXT AND j.device_name = sqlc.arg('device_name')::TEXT)
)::BOOLEAN AS reported;

-- name: CountRecordRejects :one
-- 窗口内（按信封 ts）读不出来的交班/库存原文条数。无法归属到罐，按整机计（同出入库的 unparsable）。
SELECT COUNT(*)::INT AS n FROM record_rejects j
WHERE j.kind = sqlc.arg('kind')::TEXT AND j.device_name = sqlc.arg('device_name')::TEXT
  AND j.env_ts >= sqlc.arg('begin_ms')::BIGINT AND j.env_ts < sqlc.arg('end_ms')::BIGINT
  AND j.customer_id IS NOT DISTINCT FROM (SELECT d.customer_id FROM devices d WHERE d.device_name = sqlc.arg('device_name')::TEXT);
