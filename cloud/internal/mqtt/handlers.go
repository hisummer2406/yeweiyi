package mqtt

import (
	"context"
	"encoding/json"
	"log"
	"time"

	paho "github.com/eclipse/paho.mqtt.golang"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/store/db"
)

// deviceLocation is the assumed timezone for the unqualified device timestamp strings (see
// deviceTimeLayout in payloads.go). Falls back to UTC if tzdata isn't available in the runtime
// image — better to be wrong by a fixed, discoverable offset than to crash the ingestion path.
var deviceLocation = func() *time.Location {
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		log.Printf("mqtt: could not load Asia/Shanghai tzdata, falling back to UTC for device timestamps: %v", err)
		return time.UTC
	}
	return loc
}()

func parseDeviceTime(s string) (pgtype.Timestamptz, bool) {
	if s == "" {
		return pgtype.Timestamptz{}, false
	}
	t, err := time.ParseInLocation(deviceTimeLayout, s, deviceLocation)
	if err != nil {
		return pgtype.Timestamptz{}, false
	}
	return pgtype.Timestamptz{Time: t, Valid: true}, true
}

const oilcanSnapshotKeyPrefix = "oilcan:latest:"

// handlePropertyMessage receives every device's thing/event/property/post/* response (wildcard
// subscription, see propertyTopicFilter) and dispatches by the parsed identifier.
func (c *Client) handlePropertyMessage(_ paho.Client, msg paho.Message) {
	deviceName, identifier, ok := parsePropertyTopic(msg.Topic())
	if !ok {
		log.Printf("mqtt: unparseable property topic %q", msg.Topic())
		return
	}

	ctx := context.Background()

	switch identifier {
	case IdentifierRequestOilcan:
		c.handleOilcanSnapshot(ctx, deviceName, msg.Payload())
	case IdentifierRequestAlarm:
		c.handleAlarms(ctx, deviceName, msg.Payload())
	case IdentifierRequestUnloadOil:
		c.handleOilInOut(ctx, deviceName, msg.Payload())
	case IdentifierRequestShift:
		c.handleShifts(ctx, deviceName, msg.Payload())
	default:
		// requestOilLeak/requestOilStorage exist on-device (see BackService.java PushCallback) but
		// have no cloud table yet — the design doc's §二/§4.2 never covered Sideleakage/OilSaveInfo.
		// Logged, not dropped silently, so real traffic here becomes visible instead of vanishing.
		log.Printf("mqtt: no handler for property identifier %q from device %s", identifier, deviceName)
	}
}

// handleOilcanSnapshot caches the raw response bytes as-is under the same key
// internal/api/oilcan.go already reads from — this is what turns that endpoint's permanent 404
// (nothing populated the cache) into real data once this package is wired into cmd/server.
func (c *Client) handleOilcanSnapshot(ctx context.Context, deviceName string, payload []byte) {
	if err := c.cfg.Redis.Set(ctx, oilcanSnapshotKeyPrefix+deviceName, payload, 0).Err(); err != nil {
		log.Printf("mqtt: cache oilcan snapshot for %s: %v", deviceName, err)
	}
}

func (c *Client) handleAlarms(ctx context.Context, deviceName string, payload []byte) {
	var rows []AlarmPayload
	if err := json.Unmarshal(payload, &rows); err != nil {
		log.Printf("mqtt: decode requestAlarm payload from %s: %v", deviceName, err)
		return
	}

	if err := c.cfg.Queries.EnsureDevice(ctx, deviceName); err != nil {
		log.Printf("mqtt: ensure device %s: %v", deviceName, err)
		return
	}

	for _, row := range rows {
		t, ok := parseDeviceTime(row.Time)
		if !ok {
			log.Printf("mqtt: alarm from %s has unparseable time %q, skipping", deviceName, row.Time)
			continue
		}
		err := c.cfg.Queries.UpsertAlarm(ctx, db.UpsertAlarmParams{
			DeviceName:    deviceName,
			DeviceLocalID: int32(row.ID),
			Time:          t,
			Info:          row.Info,
			Guanhao:       row.GuanHao,
			Jiezhi:        row.JieZhi,
		})
		if err != nil {
			log.Printf("mqtt: upsert alarm id=%d from %s: %v", row.ID, deviceName, err)
		}
	}
}

func (c *Client) handleOilInOut(ctx context.Context, deviceName string, payload []byte) {
	var rows []OilInOutPayload
	if err := json.Unmarshal(payload, &rows); err != nil {
		log.Printf("mqtt: decode requestUnloadOil payload from %s: %v", deviceName, err)
		return
	}

	if err := c.cfg.Queries.EnsureDevice(ctx, deviceName); err != nil {
		log.Printf("mqtt: ensure device %s: %v", deviceName, err)
		return
	}

	for _, row := range rows {
		t, ok := parseDeviceTime(row.Time)
		if !ok {
			log.Printf("mqtt: oil-in-out from %s has unparseable time %q, skipping", deviceName, row.Time)
			continue
		}
		err := c.cfg.Queries.UpsertOilInOut(ctx, db.UpsertOilInOutParams{
			DeviceName:    deviceName,
			DeviceLocalID: int32(row.ID),
			OilID:         int32(row.OilID),
			Jiezhi:        row.JieZhi,
			Time:          t,
			BeginHeight:   row.BeginHeigth,
			EndHeight:     row.EndHeigth,
			BeginVolume:   row.BeginVolum,
			EndVolume:     row.EndVolum,
			BeginWeight:   row.BeginWeight,
			EndWeight:     row.EndWeight,
		})
		if err != nil {
			log.Printf("mqtt: upsert oil_in_out id=%d from %s: %v", row.ID, deviceName, err)
		}
	}
}

func (c *Client) handleShifts(ctx context.Context, deviceName string, payload []byte) {
	var rows []CommitWorkPayload
	if err := json.Unmarshal(payload, &rows); err != nil {
		log.Printf("mqtt: decode requestShift payload from %s: %v", deviceName, err)
		return
	}

	if err := c.cfg.Queries.EnsureDevice(ctx, deviceName); err != nil {
		log.Printf("mqtt: ensure device %s: %v", deviceName, err)
		return
	}

	for _, row := range rows {
		t, ok := parseDeviceTime(row.Time)
		if !ok {
			log.Printf("mqtt: shift from %s has unparseable time %q, skipping", deviceName, row.Time)
			continue
		}
		// EndTime is nullable on-device (still open when a shift hasn't been closed yet).
		endTime, _ := parseDeviceTime(row.EndTime)
		err := c.cfg.Queries.UpsertShift(ctx, db.UpsertShiftParams{
			DeviceName:    deviceName,
			DeviceLocalID: int32(row.ID),
			WorkID:        int32(row.WorkID),
			Time:          t,
			EndTime:       endTime,
			Info:          row.Info,
		})
		if err != nil {
			log.Printf("mqtt: upsert shift id=%d from %s: %v", row.ID, deviceName, err)
		}
	}
}

// handleOnlineMessage receives thing/event/isOline/post for any device (wildcard subscription) and
// updates the devices table — this is what powers §4.2's "设备在线状态" row.
func (c *Client) handleOnlineMessage(_ paho.Client, msg paho.Message) {
	deviceName, ok := parseEventTopic(msg.Topic())
	if !ok {
		log.Printf("mqtt: unparseable isOline topic %q", msg.Topic())
		return
	}

	var payload OnlineStatusPayload
	if err := json.Unmarshal(msg.Payload(), &payload); err != nil {
		log.Printf("mqtt: decode isOline payload from %s: %v", deviceName, err)
		return
	}

	ctx := context.Background()
	if err := c.cfg.Queries.EnsureDevice(ctx, deviceName); err != nil {
		log.Printf("mqtt: ensure device %s: %v", deviceName, err)
		return
	}
	err := c.cfg.Queries.UpsertDeviceOnlineStatus(ctx, db.UpsertDeviceOnlineStatusParams{
		DeviceName: deviceName,
		IsOnline:   payload.IsOnline,
	})
	if err != nil {
		log.Printf("mqtt: upsert online status for %s: %v", deviceName, err)
	}
}

// handleMsgMessage receives thing/event/msg/post — the device's real-time alarm text push (§4.2).
// Published to a Redis pub/sub channel rather than stored; internal/api/ws.go's /ws/alarms
// subscribes to this same channel and fans it out to connected clients.
func (c *Client) handleMsgMessage(_ paho.Client, msg paho.Message) {
	deviceName, ok := parseEventTopic(msg.Topic())
	if !ok {
		log.Printf("mqtt: unparseable msg topic %q", msg.Topic())
		return
	}

	var payload MsgPayload
	if err := json.Unmarshal(msg.Payload(), &payload); err != nil {
		log.Printf("mqtt: decode msg payload from %s: %v", deviceName, err)
		return
	}

	ctx := context.Background()
	fanout, err := json.Marshal(map[string]string{"deviceName": deviceName, "msg": payload.Msg})
	if err != nil {
		log.Printf("mqtt: marshal alarm fanout for %s: %v", deviceName, err)
		return
	}
	if err := c.cfg.Redis.Publish(ctx, "alarms:live", fanout).Err(); err != nil {
		log.Printf("mqtt: publish alarm fanout for %s: %v", deviceName, err)
	}
}
