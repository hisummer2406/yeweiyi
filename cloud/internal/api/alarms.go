package api

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// GET /api/alarms — alarm history, served from the business database.
//
// Alarms are business records: the device keeps them locally and the cloud must hold them as long
// (CLAUDE.md constraint 5, issue #29). internal/ingest writes every `alarm` it sees into
// `alarm_events` — live, and by periodic and one-off backfill from ThingsBoard — and this reads it
// back. ThingsBoard is transport and short-term buffer; deleting its copy changes nothing here. The
// endpoint used to read through to ThingsBoard, which is why its telemetry TTL could not be enabled.
//
// One row per transition, as before: an ACTIVE and its later CLEARED report are two rows sharing an
// `alarmId` (the response is shaped like an alarm record, not a telemetry point, so folding them is a
// later, client-visible decision — see the open question on issue #29). Replays are idempotent.
//
// **Deliberately not using ThingsBoard's native Alarm entities.** That needs a rule chain that is not
// configured, and would put the long-term record back in ThingsBoard.
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
		begin, end, ok := recordWindow(c, deps, p)
		if !ok {
			return
		}

		type stored struct {
			payload   []byte
			synthetic bool
			occurred  time.Time
		}
		var rows []stored
		ctx := c.Request.Context()
		args := db.ListAlarmEventsAscParams{
			DeviceName: p.device, BeginTime: begin, EndTime: end, Lim: p.limit, Off: p.offset,
		}
		if p.asc {
			res, err := deps.Queries.ListAlarmEventsAsc(ctx, args)
			if err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
				return
			}
			for _, r := range res {
				rows = append(rows, stored{r.Payload, r.SyntheticID, r.OccurredAt.Time})
			}
		} else {
			res, err := deps.Queries.ListAlarmEventsDesc(ctx, db.ListAlarmEventsDescParams(args))
			if err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
				return
			}
			for _, r := range res {
				rows = append(rows, stored{r.Payload, r.SyntheticID, r.OccurredAt.Time})
			}
		}

		out := make([]alarmResponse, 0, len(rows))
		for _, r := range rows {
			ev, err := protocol.ParseAlarmEvent(r.payload)
			if err != nil {
				// Written only after a successful parse, so this means the parser got stricter since.
				// One record must not blank an operator's screen.
				continue
			}
			if r.synthetic {
				// Minted by the cloud to keep replays idempotent; not the device's id.
				ev.AlarmID = ""
			}
			out = append(out, alarmResponse{
				AlarmID:  ev.AlarmID,
				Tank:     ev.Tank,
				Type:     ev.Type,
				Severity: ev.Severity,
				Status:   ev.Status,
				Text:     ev.Text,
				Value:    ev.Value,
				Time:     r.occurred.UTC(),
			})
		}

		c.JSON(http.StatusOK, out)
	}
}
