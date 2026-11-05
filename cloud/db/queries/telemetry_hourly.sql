-- 小时级汇总（迁移 000018，issue #30）。

-- name: UpsertTelemetryHourly :exec
-- 幂等；只在样本数不少于已存值时覆盖（迟到的补投会增加样本，TTL 会减少样本，后者不能顶掉完整值）。
-- 设备删除中、或该小时开始于该编号最近一次删除完成时刻之前，不写入（cloud#83）。
INSERT INTO telemetry_hourly (device_name, tank, field, hour_start, avg_value, min_value, max_value, samples)
SELECT
    sqlc.arg('device_name'), sqlc.arg('tank'), sqlc.arg('field'),
    to_timestamp(sqlc.arg('hour_start_ms')::BIGINT / 1000.0),
    sqlc.arg('avg_value'), sqlc.arg('min_value'), sqlc.arg('max_value'), sqlc.arg('samples')
WHERE device_record_writable(sqlc.arg('device_name')::TEXT, sqlc.arg('hour_start_ms')::BIGINT)
ON CONFLICT (device_name, tank, field, hour_start) DO UPDATE SET
    avg_value = EXCLUDED.avg_value, min_value = EXCLUDED.min_value, max_value = EXCLUDED.max_value,
    samples = EXCLUDED.samples, computed_at = now()
WHERE EXCLUDED.samples >= telemetry_hourly.samples;

-- name: LatestTelemetryHourStart :one
-- 该设备已汇总到的最后一个小时（毫秒）；从未汇总过返回 0。
SELECT COALESCE((EXTRACT(EPOCH FROM MAX(hour_start)) * 1000)::BIGINT, 0)::BIGINT AS latest_ms
FROM telemetry_hourly WHERE device_name = $1;

-- name: ListTelemetryHourly :many
-- [begin, end) 内每个字段的小时汇总，按时间升序。
SELECT field, (EXTRACT(EPOCH FROM hour_start) * 1000)::BIGINT AS hour_ms,
       avg_value, min_value, max_value
FROM telemetry_hourly
WHERE device_name = sqlc.arg('device_name') AND tank = sqlc.arg('tank')
  AND field = ANY(sqlc.arg('fields')::TEXT[])
  AND hour_start >= sqlc.arg('begin_time') AND hour_start < sqlc.arg('end_time')
ORDER BY hour_start ASC;

-- name: MaxTelemetryHourlyTank :one
-- 该设备已汇总过的最大罐号；没有汇总返回 0。
SELECT COALESCE(MAX(tank), 0)::INT AS max_tank FROM telemetry_hourly WHERE device_name = $1;
