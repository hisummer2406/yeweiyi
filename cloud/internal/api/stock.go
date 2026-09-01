package api

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// GET /api/oil-in-out — 出入库 (stock in/out) history, read through to ThingsBoard.
//
// Same pattern and same reasoning as /api/alarms: the device publishes each completed movement as a
// `stockEvent` telemetry key the moment the record closes, ThingsBoard stores it, and this reads it
// back on request. Nothing is copied into the business database, which would recreate the duplicate
// store issue #10 forbids.
//
// **Breaking change from the legacy endpoint.** The old one served rows from the `oil_in_out` table
// that only the retired Alink ingestion ever wrote, with the device's original Java field names
// (`beginHeigth`/`endHeigth`/`beginVolum`). Those are gone along with the payloads they came from.
//
// **Depends on a device-side change that is not merged yet** (zhongchengyeweiyi#39): the device
// already publishes `stockEvent` on every movement, but currently as `DetailInoutRecord.toString()`
// — a Java debug string, not JSON. Until that becomes `gson.toJson(...)`, real devices produce
// values this endpoint cannot parse. Unparseable records are skipped rather than failing the whole
// listing, so this degrades to an empty list instead of a 500 while that lands.

type stockEventResponse struct {
	EventID   string `json:"eventId"`
	Tank      int    `json:"tank"`
	Direction string `json:"direction"`
	OilType   string `json:"oilType,omitempty"`

	BeginTime time.Time `json:"beginTime"`
	EndTime   time.Time `json:"endTime"`

	BeginLevelMm  float64 `json:"beginLevelMm"`
	EndLevelMm    float64 `json:"endLevelMm"`
	BeginVolumeL  float64 `json:"beginVolumeL"`
	EndVolumeL    float64 `json:"endVolumeL"`
	BeginTempC    float64 `json:"beginTempC"`
	EndTempC      float64 `json:"endTempC"`
	BeginWeightKg float64 `json:"beginWeightKg"`
	EndWeightKg   float64 `json:"endWeightKg"`

	NetVolumeL            float64 `json:"netVolumeL"`
	DuringDeliveryVolumeL float64 `json:"duringDeliveryVolumeL,omitempty"`
	// DeliveredVolumeL is what actually moved: for an inbound delivery the tank's net gain plus
	// whatever the pumps dispensed during it. Computed here rather than left to every client,
	// because getting it wrong understates deliveries and that is the number people argue with
	// suppliers about.
	DeliveredVolumeL float64 `json:"deliveredVolumeL"`

	Time time.Time `json:"time"` // when the device closed the record
}

func toStockEventResponse(e protocol.StockEvent) stockEventResponse {
	delivered := e.NetVolumeL + e.DuringDeliveryVolumeL
	if e.Direction == protocol.StockOut {
		// Outbound: the tank's change is negative and there is no concurrent-delivery adjustment,
		// so the quantity moved is the magnitude of the drop.
		delivered = -e.NetVolumeL
	}

	return stockEventResponse{
		EventID:               e.EventID,
		Tank:                  e.Tank,
		Direction:             e.Direction,
		OilType:               e.OilType,
		BeginTime:             time.UnixMilli(e.BeginTimeMs).UTC(),
		EndTime:               time.UnixMilli(e.EndTimeMs).UTC(),
		BeginLevelMm:          e.BeginLevelMm,
		EndLevelMm:            e.EndLevelMm,
		BeginVolumeL:          e.BeginVolumeL,
		EndVolumeL:            e.EndVolumeL,
		BeginTempC:            e.BeginTempC,
		EndTempC:              e.EndTempC,
		BeginWeightKg:         e.BeginWeightKg,
		EndWeightKg:           e.EndWeightKg,
		NetVolumeL:            e.NetVolumeL,
		DuringDeliveryVolumeL: e.DuringDeliveryVolumeL,
		DeliveredVolumeL:      delivered,
		Time:                  time.UnixMilli(e.TSMs).UTC(),
	}
}

func listOilInOutHandler(deps Deps) gin.HandlerFunc {
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
		// Same limitation as /api/alarms: ThingsBoard pages by time window, not row offset.
		if p.offset > 0 {
			c.JSON(http.StatusBadRequest, gin.H{
				"error": "offset is not supported; page by narrowing beginTime/endTime instead",
			})
			return
		}

		ctx := c.Request.Context()
		dev, err := deps.TB.GetDeviceByName(ctx, p.device)
		if err != nil {
			writeThingsBoardError(c, err, p.device)
			return
		}

		series, err := deps.TB.Timeseries(ctx, dev.ID.ID, tbclient.TimeseriesQuery{
			Keys:       []string{protocol.KeyStockEvent},
			StartMs:    p.begin.Time.UnixMilli(),
			EndMs:      p.end.Time.UnixMilli(),
			Limit:      int(p.limit),
			Descending: !p.asc,
		})
		if err != nil {
			writeThingsBoardError(c, err, p.device)
			return
		}

		points := series[protocol.KeyStockEvent]
		out := make([]stockEventResponse, 0, len(points))
		for _, pt := range points {
			ev, err := protocol.ParseStockEvent(pt.Value)
			if err != nil {
				// Skip rather than fail: real devices are still publishing the pre-JSON debug
				// string (see the note at the top of this file), and one bad record must not blank
				// an operator's screen.
				continue
			}
			if ev.TSMs == 0 {
				ev.TSMs = pt.TS
			}
			out = append(out, toStockEventResponse(ev))
		}

		c.JSON(http.StatusOK, out)
	}
}
