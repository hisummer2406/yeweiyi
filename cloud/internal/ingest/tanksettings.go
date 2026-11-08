package ingest

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// 油罐配置镜像（cloud#70 第一阶段）：设备是配置的权威来源，联网和变更时把罐设置页内容作为客户端属性
// （tank{N}.*）上报，这里读出后写入 tank_settings，供 GET /api/devices/:device/tank-settings 只读返回。
//
// 为什么轮询而不是订阅：遥测订阅 WebSocket 不带属性，而属性只在设备重连或设置变化时才变；订阅建立时
// 立即读一次（等于"设备连接时"更新），之后定时重读（等于"属性变更时"更新，延迟不超过一个间隔）。
// 云端不在这里写任何配置值——镜像只由设备上报驱动。

// tankSettingsLoop syncs once immediately and then on a timer until ctx ends.
func (i *Ingester) tankSettingsLoop(ctx context.Context, deviceName, deviceID string) {
	i.syncTankSettings(ctx, deviceName, deviceID)
	ticker := time.NewTicker(i.cfg.TankSettingsInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			i.syncTankSettings(ctx, deviceName, deviceID)
		}
	}
}

// syncTankSettings runs one pass and returns how many tanks it saw. A failed read changes nothing, so
// an unreachable ThingsBoard leaves the last mirror in place (which is the point of keeping one).
func (i *Ingester) syncTankSettings(ctx context.Context, deviceName, deviceID string) int {
	if i.cfg.Queries == nil {
		return 0
	}
	kvs, err := i.cfg.TB.AttributesWithTS(ctx, deviceID, tbclient.ScopeClient, nil)
	if err != nil {
		i.cfg.Logf("ingest: %s: read tank settings: %v", deviceName, err)
		return 0
	}
	attrs := make([]protocol.Attribute, 0, len(kvs))
	for _, kv := range kvs {
		attrs = append(attrs, protocol.Attribute{Key: kv.Key, Value: kv.Value, TS: kv.LastUpdateTs})
	}
	i.syncTankCalibration(ctx, deviceName, kvs)
	tanks, skipped := protocol.ParseTankSettings(attrs)
	if len(skipped) > 0 {
		i.cfg.Logf("ingest: %s: tank settings: skipped unusable attributes %v", deviceName, skipped)
	}
	for _, t := range tanks {
		if err := SaveTankSettings(ctx, i.cfg.Queries, deviceName, t); err != nil {
			i.cfg.Logf("ingest: %s: save tank %d settings: %v", deviceName, t.Tank, err)
		}
	}
	// Device-level settings (config-command-delivery) come from the same read: same source, same
	// cadence, same "the device is the source of truth" rule.
	if ds, ok, skipped := protocol.ParseDeviceSettings(attrs); ok {
		if len(skipped) > 0 {
			i.cfg.Logf("ingest: %s: device settings: skipped unusable attributes %v", deviceName, skipped)
		}
		if err := SaveDeviceSettings(ctx, i.cfg.Queries, deviceName, ds); err != nil {
			i.cfg.Logf("ingest: %s: save device settings: %v", deviceName, err)
		}
	}
	return len(tanks)
}

// SaveDeviceSettings upserts the device-level mirror. Idempotent: same reportedAt is a no-op.
func SaveDeviceSettings(ctx context.Context, q *db.Queries, deviceName string, s protocol.DeviceSettings) error {
	reported := time.Now()
	if s.ReportedAtMs > 0 {
		reported = time.UnixMilli(s.ReportedAtMs)
	}
	return q.UpsertDeviceSettings(ctx, db.UpsertDeviceSettingsParams{
		DeviceName:                deviceName,
		InStockHeightThresholdMm:  int4(s.InStockHeightThresholdMm),
		InStockTimeThresholdSec:   int4(s.InStockTimeThresholdSec),
		OutStockHeightThresholdMm: int4(s.OutStockHeightThresholdMm),
		OutStockTimeThresholdSec:  int4(s.OutStockTimeThresholdSec),
		ReportedAt:                pgtype.Timestamptz{Time: reported, Valid: true},
	})
}

// SaveTankSettings upserts one tank's mirror. Idempotent: a repeat with the same reportedAt is a no-op.
func SaveTankSettings(ctx context.Context, q *db.Queries, deviceName string, t protocol.TankSettings) error {
	reported := time.Now()
	if t.ReportedAtMs > 0 {
		reported = time.UnixMilli(t.ReportedAtMs)
	}
	return q.UpsertTankSettings(ctx, db.UpsertTankSettingsParams{
		DeviceName:           deviceName,
		Tank:                 int32(t.Tank),
		TankHeightMm:         int4(t.TankHeightMm),
		SensorLengthMm:       float8(t.SensorLengthMm),
		TankShape:            int4(t.TankShape),
		OilType:              text(t.OilType),
		OilTypeIndex:         int4(t.OilTypeIndex),
		AlarmHighHighLevelMm: int4(t.AlarmHighHighLevelMm),
		AlarmHighLevelMm:     int4(t.AlarmHighLevelMm),
		AlarmLowLevelMm:      int4(t.AlarmLowLevelMm),
		AlarmLowLowLevelMm:   int4(t.AlarmLowLowLevelMm),
		AlarmHighWaterMm:     int4(t.AlarmHighWaterMm),
		AlarmHighTempC:       int4(t.AlarmHighTempC),
		AlarmLowTempC:        int4(t.AlarmLowTempC),
		LevelCorrectionMm:    float8(t.LevelCorrectionMm),
		WaterCorrectionMm:    float8(t.WaterCorrectionMm),
		TempCorrectionC:      float8(t.TempCorrectionC),
		Density:              float8(t.Density),
		CapacityL:            float8(t.CapacityL),
		ReportedAt:           pgtype.Timestamptz{Time: reported, Valid: true},
	})
}

func int4(v *int32) pgtype.Int4 {
	if v == nil {
		return pgtype.Int4{}
	}
	return pgtype.Int4{Int32: *v, Valid: true}
}

func float8(v *float64) pgtype.Float8 {
	if v == nil {
		return pgtype.Float8{}
	}
	return pgtype.Float8{Float64: *v, Valid: true}
}

func text(v *string) pgtype.Text {
	if v == nil {
		return pgtype.Text{}
	}
	return pgtype.Text{String: *v, Valid: true}
}

// SyncTankSettingsForTest runs one pass synchronously so tests need not wait out the ticker.
func (i *Ingester) SyncTankSettingsForTest(ctx context.Context, deviceName, deviceID string) int {
	return i.syncTankSettings(ctx, deviceName, deviceID)
}

// syncTankCalibration mirrors each tank's calibration curve (cloud#14) from the same attribute read. A
// curve that fails validation (see protocol.ParseTankCalibration) is logged and left out: the previous
// mirror stays, rather than a corrupt table replacing a good one.
func (i *Ingester) syncTankCalibration(ctx context.Context, deviceName string, kvs []tbclient.AttributeKV) {
	attrs := make(map[string]any, len(kvs))
	ts := make(map[string]int64, len(kvs))
	for _, kv := range kvs {
		attrs[kv.Key] = kv.Value
		ts[kv.Key] = kv.LastUpdateTs
	}
	for n := 1; n <= protocol.MaxDeviceTankSettings; n++ {
		cal, ok, err := protocol.ParseTankCalibration(attrs, n)
		if err != nil {
			i.cfg.Logf("ingest: %s: tank %d calibration ignored: %v", deviceName, n, err)
			continue
		}
		if !ok {
			continue
		}
		reported := max(ts[protocol.TankKey(n, protocol.AttrCalibrationHeightMm)], ts[protocol.TankKey(n, protocol.AttrCalibrationVolumeL)])
		at := time.Now()
		if reported > 0 {
			at = time.UnixMilli(reported)
		}
		if err := i.cfg.Queries.UpsertTankCalibration(ctx, db.UpsertTankCalibrationParams{
			DeviceName: deviceName, Tank: int32(n), HeightMm: cal.HeightMm, VolumeL: cal.VolumeL,
			ReportedAt: pgtype.Timestamptz{Time: at, Valid: true},
		}); err != nil {
			i.cfg.Logf("ingest: %s: save tank %d calibration: %v", deviceName, n, err)
		}
	}
}
