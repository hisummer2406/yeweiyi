package api

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// GET /api/alarms, reading from ThingsBoard rather than from the business database.
//
// Alarm history used to be served from the `alarms` table, which only the legacy MQTT ingestion
// ever wrote to. Under the single stack the device publishes each alarm transition as a JSON-valued
// `alarm` telemetry key (see internal/protocol/events.go), ThingsBoard stores it, and this endpoint
// reads it back on request — the same read-through pattern as the telemetry endpoint, and the same
// reason: storing a second copy here would recreate the duplicate time-series store issue #10
// forbids.
//
// **Deliberately not using ThingsBoard's native Alarm entities.** ThingsBoard can turn a telemetry
// value into a first-class Alarm object (with acknowledge/clear state it manages itself) via a rule
// chain, which would be a better home for operator acknowledgement than anything built here. That
// rule chain is not configured yet, so alarms currently exist only as telemetry events. When it is,
// this handler should move to /api/alarm/DEVICE/{id} — the endpoint contract below is deliberately
// shaped like an alarm record rather than like a telemetry point so that swap does not change the
// response.
//
// **Breaking change from the legacy endpoint**, and unavoidable: the response no longer carries the
// device's old Java field names (`guanhao`/`jiezhi`/`info`). Those came from the Alink payloads,
// which 设备协议 V1 replaced outright. Any client reading the old shape must be updated.

type alarmResponse struct {
	AlarmID  string    `json:"alarmId"`
	Tank     int       `json:"tank"`
	Type     string    `json:"type"`
	Severity string    `json:"severity"`
	Status   string    `json:"status"`
	Text     string    `json:"text"`
	Value    float64   `json:"value,omitempty"`
	Time     time.Time `json:"time"`
}

func listAlarmsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		p, ok := parseListParams(c)
		if !ok {
			return
		}
		if !requireDeviceOwnership(c, deps, p.device) {
			return
		}
		if deps.TB == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "ThingsBoard is not configured on this deployment"})
			return
		}

		ctx := c.Request.Context()
		dev, err := deps.TB.GetDeviceByName(ctx, p.device)
		if err != nil {
			writeThingsBoardError(c, err, p.device)
			return
		}

		// offset has no equivalent in ThingsBoard's timeseries API, which pages by time window
		// rather than by row offset. Rather than silently ignoring it — which would hand a
		// paginating client the same first page forever — say so.
		if p.offset > 0 {
			c.JSON(http.StatusBadRequest, gin.H{
				"error": "offset is not supported for alarms; page by narrowing beginTime/endTime instead",
			})
			return
		}

		series, err := deps.TB.Timeseries(ctx, dev.ID.ID, tbclient.TimeseriesQuery{
			Keys:       []string{protocol.KeyAlarm},
			StartMs:    p.begin.Time.UnixMilli(),
			EndMs:      p.end.Time.UnixMilli(),
			Limit:      int(p.limit),
			Descending: !p.asc,
		})
		if err != nil {
			writeThingsBoardError(c, err, p.device)
			return
		}

		points := series[protocol.KeyAlarm]
		out := make([]alarmResponse, 0, len(points))
		for _, pt := range points {
			ev, err := protocol.ParseAlarmEvent(pt.Value)
			if err != nil {
				// One malformed record must not fail the whole listing — a device on a newer
				// protocol, or a hand-written test point, should not blank an operator's screen.
				continue
			}
			ts := ev.TSMs
			if ts == 0 {
				ts = pt.TS
			}
			out = append(out, alarmResponse{
				AlarmID:  ev.AlarmID,
				Tank:     ev.Tank,
				Type:     ev.Type,
				Severity: ev.Severity,
				Status:   ev.Status,
				Text:     ev.Text,
				Value:    ev.Value,
				Time:     time.UnixMilli(ts).UTC(),
			})
		}

		c.JSON(http.StatusOK, out)
	}
}
