package ingest

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// 出入库与报警是业务记录，业务库是它们的长期事实来源（CLAUDE.md 约束 5，issue #29）；ThingsBoard
// 只是传输与短期缓冲。本文件是它们进入业务库的唯一入口，实时订阅与回补共用同一组写入函数，且全部幂等：
//
//   - 出入库按 (设备, eventId) 折叠，信封 ts 最大者胜，相同则保留先写入的；
//   - 报警按 (设备, alarmId, 状态) 唯一，重放不改变已有行；
//   - 读不出来的原文进 record_rejects，既不丢弃，也不冒充成功。
//
// 实时订阅只保证"看到的这一条"：WebSocket 一个批次里每个 key 只交付最新点，且设备离线补投的旧记录
// 可能在更新的记录之后才到。所以补采不能靠"水位线"（补投的旧 ts 早于水位线），而是每次重连以及定时
// 回读一段回看窗口（RecordReconcileLookback），靠幂等写入去重。

// Reasons mirror the CHECK constraint on record_rejects.reason.
const (
	rejectUnparsable    = "unparsable"
	rejectInvalidSource = "invalid_source"
)

// rawJSON renders a telemetry value as the bytes to keep. ThingsBoard hands JSON-valued telemetry
// back as a string over REST and as a decoded value over MQTT, so both shapes are accepted.
func rawJSON(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case []byte:
		return string(t)
	case json.RawMessage:
		return string(t)
	default:
		b, err := json.Marshal(v)
		if err != nil {
			return fmt.Sprint(v)
		}
		return string(b)
	}
}

// syntheticID derives a stable key for a record whose payload carries no id. The same envelope ts
// and the same bytes give the same key, so a replayed copy lands on the row it already made.
func syntheticID(envTS int64, raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return "noid-" + strconv.FormatInt(envTS, 10) + "-" + hex.EncodeToString(sum[:6])
}

// SaveStockEvent persists one stockEvent report. envTS is the ThingsBoard envelope timestamp — when
// the device generated the report, preserved verbatim across offline replay.
func SaveStockEvent(ctx context.Context, q *db.Queries, deviceName string, envTS int64, value any) error {
	raw := rawJSON(value)
	ev, err := protocol.ParseStockEvent(value)
	if err != nil {
		reason := rejectUnparsable
		if errors.Is(err, protocol.ErrInvalidSource) {
			reason = rejectInvalidSource
		}
		return q.InsertRecordReject(ctx, db.InsertRecordRejectParams{
			Kind: "stock", DeviceName: deviceName, EnvTs: envTS, Reason: reason, Raw: raw,
		})
	}

	occurred := ev.TSMs
	if occurred == 0 {
		occurred = envTS
	}
	id, synthetic := ev.EventID, false
	if id == "" {
		id, synthetic = syntheticID(envTS, raw), true
	}
	return q.UpsertStockEvent(ctx, db.UpsertStockEventParams{
		DeviceName: deviceName, EventID: id, SyntheticID: synthetic,
		OccurredMs: occurred, EnvTs: envTS, Payload: []byte(raw),
	})
}

// SaveAlarmEvent persists one alarm transition and returns the parsed event so the live path can
// fan it out. A record that cannot be parsed is kept in record_rejects and reported as ok=false.
func SaveAlarmEvent(ctx context.Context, q *db.Queries, deviceName string, envTS int64, value any) (ev protocol.AlarmEvent, ok bool, err error) {
	raw := rawJSON(value)
	ev, perr := protocol.ParseAlarmEvent(value)
	if perr != nil {
		return ev, false, q.InsertRecordReject(ctx, db.InsertRecordRejectParams{
			Kind: "alarm", DeviceName: deviceName, EnvTs: envTS, Reason: rejectUnparsable, Raw: raw,
		})
	}

	occurred := ev.TSMs
	if occurred == 0 {
		occurred = envTS
	}
	id, synthetic := ev.AlarmID, false
	if id == "" {
		id, synthetic = syntheticID(envTS, raw), true
	}
	err = q.InsertAlarmEvent(ctx, db.InsertAlarmEventParams{
		DeviceName: deviceName, AlarmID: id, Status: ev.Status, SyntheticID: synthetic,
		OccurredMs: occurred, EnvTs: envTS, Payload: []byte(raw),
	})
	return ev, true, err
}

// BackfillResult counts what one backfill pass read from ThingsBoard. These are points read, not
// rows created: a pass over data that is already stored reads the same points and changes nothing.
type BackfillResult struct {
	StockPoints int
	AlarmPoints int
}

const (
	backfillPageLimit = 1000
	// backfillSkew reaches a little past "now" so a point stamped by a device clock slightly ahead
	// is not skipped.
	backfillSkew = time.Hour
)

// BackfillDevice reads every stockEvent and alarm point with envelope ts >= fromMs from ThingsBoard
// and writes it to the business database. fromMs = 0 is the full backfill.
//
// Safe to run concurrently with live ingestion and with itself: every write is idempotent, so the
// two converge on the same rows whichever order they land in. That is what lets the live
// subscription start first and the backfill follow, with no gap between them.
func (i *Ingester) BackfillDevice(ctx context.Context, deviceName string, fromMs int64) (BackfillResult, error) {
	dev, err := i.cfg.TB.GetDeviceByName(ctx, deviceName)
	if err != nil {
		return BackfillResult{}, err
	}
	return i.backfillByID(ctx, deviceName, dev.ID.ID, fromMs)
}

func (i *Ingester) backfillByID(ctx context.Context, deviceName, deviceID string, fromMs int64) (res BackfillResult, err error) {
	toMs := time.Now().Add(backfillSkew).UnixMilli()

	res.StockPoints, err = i.scanKey(ctx, deviceID, protocol.KeyStockEvent, fromMs, toMs, func(pt tbclient.TSValue) error {
		return SaveStockEvent(ctx, i.cfg.Queries, deviceName, pt.TS, pt.Value)
	})
	if err != nil {
		return res, fmt.Errorf("backfill %s stockEvent: %w", deviceName, err)
	}
	res.AlarmPoints, err = i.scanKey(ctx, deviceID, protocol.KeyAlarm, fromMs, toMs, func(pt tbclient.TSValue) error {
		_, _, err := SaveAlarmEvent(ctx, i.cfg.Queries, deviceName, pt.TS, pt.Value)
		return err
	})
	if err != nil {
		return res, fmt.Errorf("backfill %s alarm: %w", deviceName, err)
	}
	return res, nil
}

// scanKey pages through one key oldest-first. A full page restarts at the last point's own ts rather
// than one past it: several points can share a millisecond, and starting past it would skip the
// ones that did not fit. Re-reading that boundary is harmless because writes are idempotent.
func (i *Ingester) scanKey(ctx context.Context, deviceID, key string, fromMs, toMs int64, fn func(tbclient.TSValue) error) (n int, err error) {
	for {
		series, err := i.cfg.TB.Timeseries(ctx, deviceID, tbclient.TimeseriesQuery{
			Keys: []string{key}, StartMs: fromMs, EndMs: toMs, Limit: backfillPageLimit,
		})
		if err != nil {
			return n, err
		}
		pts := series[key]
		for _, pt := range pts {
			if err := fn(pt); err != nil {
				return n, err
			}
			n++
		}
		if len(pts) < backfillPageLimit {
			return n, nil
		}
		next := pts[len(pts)-1].TS
		if next <= fromMs {
			next = fromMs + 1
		}
		fromMs = next
	}
}

// reconcileLoop closes the gaps the live subscription cannot: points it never saw (a batch keeps
// only the newest per key; the socket was down) and offline replays that arrive with old timestamps.
// It runs once immediately and then on a timer until ctx ends.
func (i *Ingester) reconcileLoop(ctx context.Context, deviceName, deviceID string) {
	i.reconcileOnce(ctx, deviceName, deviceID, true)
	ticker := time.NewTicker(i.cfg.RecordReconcileInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			i.reconcileOnce(ctx, deviceName, deviceID, false)
		}
	}
}

// reconcileOnce reads one lookback window. Only the pass that follows a (re)subscription logs what it
// read: every pass re-reads the same already-stored points, so logging them each time would only
// repeat itself.
func (i *Ingester) reconcileOnce(ctx context.Context, deviceName, deviceID string, announce bool) {
	if i.cfg.Queries == nil {
		return
	}
	from := time.Now().Add(-i.cfg.RecordReconcileLookback).UnixMilli()
	// A device the business database has never held a record for is taken over from the beginning:
	// whatever ThingsBoard still has is, by definition, not stored here yet.
	has, err := i.cfg.Queries.DeviceHasBusinessRecords(ctx, deviceName)
	if err != nil {
		i.cfg.Logf("ingest: %s: check stored records: %v", deviceName, err)
		return
	}
	if !has {
		from = 0
	}
	res, err := i.backfillByID(ctx, deviceName, deviceID, from)
	if err != nil {
		if ctx.Err() == nil {
			i.cfg.Logf("ingest: %s: reconcile records: %v", deviceName, err)
		}
		return
	}
	if announce && res.StockPoints+res.AlarmPoints > 0 {
		i.cfg.Logf("ingest: %s: reconciled %d stockEvent / %d alarm points", deviceName, res.StockPoints, res.AlarmPoints)
	}
}
