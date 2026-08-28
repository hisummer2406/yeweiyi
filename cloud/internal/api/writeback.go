// Write-back endpoints: the REST entry points design doc §5.2 describes but that nothing called
// until now — internal/queue.EnqueueWriteBack (the pending→sent hop) has existed and been tested
// since 2026-08-20, but no handler ever invoked it. CLAUDE.md's "The device is the final arbiter"
// rule means every payload here describes the full target state, never a delta — safe to redeliver,
// and superseded by whatever the device actually reports back once it acks (not built yet, see
// CLAUDE.md "Device-side work this project depends on").
package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/queue"
	"yeweiyi-cloud/internal/store/db"
)

// oilInOutCorrectionPayload mirrors internal/mqtt.OilInOutPayload's wire field names (device's own
// Java field names/typos: beginHeigth/endHeigth/beginVolum/endVolum), not this table's DB column
// names — the payload stored here is meant to go out over MQTT unchanged once a Publisher exists,
// so it needs to already be in the shape the device's requestUnloadOil-equivalent write handler will
// expect (not built on-device yet — see CLAUDE.md — so this is our best forward-compatible guess,
// not a confirmed contract).
type oilInOutCorrectionPayload struct {
	Device        string  `json:"device" binding:"required"`
	DeviceLocalID int32   `json:"deviceLocalId" binding:"required"`
	OilID         int     `json:"oilId"`
	JieZhi        string  `json:"jieZhi" binding:"required"`
	Time          string  `json:"time" binding:"required"`
	BeginHeigth   float64 `json:"beginHeigth"`
	EndHeigth     float64 `json:"endHeigth"`
	BeginVolum    float64 `json:"beginVolum"`
	EndVolum      float64 `json:"endVolum"`
	BeginWeight   float64 `json:"beginWeight"`
	EndWeight     float64 `json:"endWeight"`
}

func correctOilInOutHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req oilInOutCorrectionPayload
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		if !requireDeviceOwnership(c, deps, req.Device) {
			return
		}

		ctx := c.Request.Context()
		if _, err := deps.Queries.GetLatestOilInOutByDeviceLocalID(ctx, db.GetLatestOilInOutByDeviceLocalIDParams{
			DeviceName:    req.Device,
			DeviceLocalID: req.DeviceLocalID,
		}); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusNotFound, gin.H{"error": "no oil-in-out record with that device+deviceLocalId"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		// Re-marshal without the "device" field — it's already the write_back_commands row's
		// device_name column, no need to duplicate it inside payload too.
		payload, err := json.Marshal(struct {
			DeviceLocalID int32   `json:"deviceLocalId"`
			OilID         int     `json:"oilId"`
			JieZhi        string  `json:"jieZhi"`
			Time          string  `json:"time"`
			BeginHeigth   float64 `json:"beginHeigth"`
			EndHeigth     float64 `json:"endHeigth"`
			BeginVolum    float64 `json:"beginVolum"`
			EndVolum      float64 `json:"endVolum"`
			BeginWeight   float64 `json:"beginWeight"`
			EndWeight     float64 `json:"endWeight"`
		}{req.DeviceLocalID, req.OilID, req.JieZhi, req.Time, req.BeginHeigth, req.EndHeigth, req.BeginVolum, req.EndVolum, req.BeginWeight, req.EndWeight})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		enqueueWriteBack(c, deps, req.Device, "oil_in_out_correction", payload)
	}
}

// shiftCorrectionPayload mirrors internal/mqtt.CommitWorkPayload's wire field names — see
// oilInOutCorrectionPayload's doc comment above for why.
type shiftCorrectionPayload struct {
	Device        string `json:"device" binding:"required"`
	DeviceLocalID int32  `json:"deviceLocalId" binding:"required"`
	WorkID        int    `json:"workId"`
	Time          string `json:"time" binding:"required"`
	EndTime       string `json:"endTime"`
	Info          string `json:"info"`
}

func correctShiftHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req shiftCorrectionPayload
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		if !requireDeviceOwnership(c, deps, req.Device) {
			return
		}

		ctx := c.Request.Context()
		if _, err := deps.Queries.GetShiftByDeviceLocalID(ctx, db.GetShiftByDeviceLocalIDParams{
			DeviceName:    req.Device,
			DeviceLocalID: req.DeviceLocalID,
		}); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusNotFound, gin.H{"error": "no shift record with that device+deviceLocalId"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		payload, err := json.Marshal(struct {
			DeviceLocalID int32  `json:"deviceLocalId"`
			WorkID        int    `json:"workId"`
			Time          string `json:"time"`
			EndTime       string `json:"endTime"`
			Info          string `json:"info"`
		}{req.DeviceLocalID, req.WorkID, req.Time, req.EndTime, req.Info})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		enqueueWriteBack(c, deps, req.Device, "shift_correction", payload)
	}
}

// writeOilCanSetHandler accepts an opaque JSON body rather than a typed struct like the two
// handlers above: unlike oil-in-out/shift (whose wire shape is confirmed from real device source —
// see internal/mqtt/payloads.go), requestTankTable/requestTankSet/requestProbeSet have no handling
// logic on-device at all yet (CLAUDE.md "Device-side work this project depends on") — there is no
// real contract to be typed against, only a guess. Storing whatever the caller sends verbatim avoids
// baking in a wrong guess; retype this once the device side actually implements those topics.
func writeOilCanSetHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		// requireDeviceOwnership implies the device exists (user_devices.device_name FKs to
		// devices(device_name)), so there's no separate "unknown device" check needed here anymore —
		// an unbound-or-nonexistent device both fail ownership the same way (see that function's
		// doc comment for why that's deliberate, not an oversight).
		if !requireDeviceOwnership(c, deps, device) {
			return
		}

		body, err := c.GetRawData()
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		if !json.Valid(body) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "body must be valid JSON"})
			return
		}

		enqueueWriteBack(c, deps, device, "oil_can_set", body)
	}
}

func enqueueWriteBack(c *gin.Context, deps Deps, device, commandType string, payload []byte) {
	commandID, err := queue.EnqueueWriteBack(c.Request.Context(), deps.Pool, deps.RiverClient, device, commandType, payload)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"id": commandID, "status": "pending"})
}

func listWriteBackCommandsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Query("device")
		if device == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "device query param is required"})
			return
		}
		if !requireDeviceOwnership(c, deps, device) {
			return
		}

		limit := int32(defaultLimit)
		if v := c.Query("limit"); v != "" {
			n, err := strconv.Atoi(v)
			if err != nil || n <= 0 || n > maxLimit {
				c.JSON(http.StatusBadRequest, gin.H{"error": "limit must be an integer between 1 and 500"})
				return
			}
			limit = int32(n)
		}

		offset := int32(0)
		if v := c.Query("offset"); v != "" {
			n, err := strconv.Atoi(v)
			if err != nil || n < 0 {
				c.JSON(http.StatusBadRequest, gin.H{"error": "offset must be a non-negative integer"})
				return
			}
			offset = int32(n)
		}

		rows, err := deps.Queries.ListWriteBackCommandsForDevice(c.Request.Context(), db.ListWriteBackCommandsForDeviceParams{
			DeviceName: device,
			Limit:      limit,
			Offset:     offset,
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		out := make([]writeBackCommandView, len(rows))
		for i, r := range rows {
			out[i] = writeBackCommandView{
				ID:          r.ID,
				DeviceName:  r.DeviceName,
				CommandType: r.CommandType,
				Payload:     json.RawMessage(r.Payload),
				Status:      r.Status,
				Attempts:    r.Attempts,
				CreatedAt:   r.CreatedAt,
				UpdatedAt:   r.UpdatedAt,
			}
		}
		c.JSON(http.StatusOK, gin.H{"rows": out})
	}
}

// writeBackCommandView re-shapes db.WriteBackCommand for JSON output: Payload as db.WriteBackCommand
// stores it is a Go []byte, which encoding/json base64-encodes by default — since the column is
// already JSONB, embedding it as json.RawMessage instead gives the client readable, structured JSON.
type writeBackCommandView struct {
	ID          int64              `json:"id"`
	DeviceName  string             `json:"device_name"`
	CommandType string             `json:"command_type"`
	Payload     json.RawMessage    `json:"payload"`
	Status      string             `json:"status"`
	Attempts    int32              `json:"attempts"`
	CreatedAt   pgtype.Timestamptz `json:"created_at"`
	UpdatedAt   pgtype.Timestamptz `json:"updated_at"`
}
