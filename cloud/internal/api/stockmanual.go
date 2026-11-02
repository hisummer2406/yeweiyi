package api

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// Manual stock entry (cloud#69): an administrator adds a movement the device missed (a power cut, a
// fault) from the mini program. It inserts a NEW record into stock_events, source MANUAL, origin "cloud";
// it never rewrites anything the device reported. Withdrawing is the same: only a record this API made
// can be withdrawn, and it is a soft delete (stock records are kept for good, CLAUDE.md constraint 5).
//
//	POST   /api/devices/:device/stock-events            add (201; 200 when the same requestId is replayed)
//	DELETE /api/devices/:device/stock-events/:eventId   withdraw a cloud-made entry (204)
//
// Who: platform administrator, or a customer administrator on a device of their own customer. An employee
// (customer_user) is view-only and refused by role. Both calls are audited in the same transaction as the
// write: no audit row, no change.
//
// What the record knows: only the level before and the volume moved, plus when. The other readings are
// absent from the stored report and shown as null by /api/oil-in-out, never as 0.

const (
	maxManualReasonRunes = 200
	maxRequestIDLen      = 64
	// manualFutureSkew tolerates a client clock slightly ahead of ours. A time further ahead is a
	// mistake (or an attempt to plan a record), not a late entry.
	manualFutureSkew = time.Minute
)

type manualStockRequest struct {
	Direction     string   `json:"direction"`
	Tank          *int     `json:"tank"`
	Time          string   `json:"time"` // RFC 3339 with an offset: the moment of the movement
	BeforeLevelMm *float64 `json:"beforeLevelMm"`
	VolumeL       *float64 `json:"volumeL"` // the quantity moved, > 0 for both directions
	Reason        string   `json:"reason"`
	// RequestID makes a retry of the same submission idempotent: the same (device, requestId) always
	// names the same record. Optional; without it every call creates a new record.
	RequestID string `json:"requestId"`
}

func manualInvalid(c *gin.Context, code, msg string) {
	apiError(c, http.StatusBadRequest, code, msg)
}

func addManualStockEventHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		const action = "stock.manual_add"

		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin && actor.Role != control.RoleCustomerAdmin {
			refuse(c, deps, actor, action, "role may not add manual stock records", map[string]any{"device": device})
			return
		}
		if !requireDeviceOwnership(c, deps, device) {
			return
		}

		var req manualStockRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			manualInvalid(c, "invalid_request", err.Error())
			return
		}
		req.Direction = strings.ToUpper(strings.TrimSpace(req.Direction))
		req.Reason = strings.TrimSpace(req.Reason)
		switch {
		case req.Direction != protocol.StockIn && req.Direction != protocol.StockOut:
			manualInvalid(c, "direction_invalid", "direction 必须是 IN 或 OUT")
			return
		case req.Tank == nil || *req.Tank < 1 || *req.Tank > protocol.MaxDeviceTankSettings:
			manualInvalid(c, "tank_invalid", "tank 必须是 1～16 的整数")
			return
		case req.BeforeLevelMm == nil || !finiteNonNegative(*req.BeforeLevelMm):
			manualInvalid(c, "level_invalid", "beforeLevelMm 必须是不小于 0 的数字（毫米）")
			return
		case req.VolumeL == nil || !(*req.VolumeL > 0) || math.IsInf(*req.VolumeL, 0):
			manualInvalid(c, "volume_invalid", "volumeL 必须是大于 0 的数字（升）")
			return
		case req.Reason == "":
			manualInvalid(c, "reason_required", "reason 必填")
			return
		case len([]rune(req.Reason)) > maxManualReasonRunes:
			manualInvalid(c, "reason_invalid", "reason 过长")
			return
		case len(req.RequestID) > maxRequestIDLen:
			manualInvalid(c, "request_id_invalid", "requestId 过长")
			return
		}
		at, err := time.Parse(time.RFC3339, req.Time)
		if err != nil {
			manualInvalid(c, "time_invalid", "time 必须是带时区的 RFC 3339 时间，如 2026-10-14T08:30:00+08:00")
			return
		}
		if at.After(time.Now().Add(manualFutureSkew)) {
			manualInvalid(c, "time_in_future", "time 不能晚于现在")
			return
		}

		eventID := newManualEventID(device, req.RequestID)
		payload := manualPayload(eventID, req, at)
		if _, err := protocol.ParseStockEvent(payload); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		var (
			replayed, conflict, tooEarly bool
			stored                       []byte
		)
		err = withTx(c.Request.Context(), deps, func(q *db.Queries) error {
			dev, err := q.GetDevice(c.Request.Context(), device)
			if err != nil {
				return err
			}
			// Reads clip the window at the moment the device was assigned to its customer
			// (recordWindow); a record before that could be stored but never shown to anyone.
			if dev.CustomerAssignedAt.Valid && at.Before(dev.CustomerAssignedAt.Time.Add(assignTxTimeout)) {
				tooEarly = true
				return nil
			}
			n, err := q.InsertManualStockEvent(c.Request.Context(), db.InsertManualStockEventParams{
				DeviceName: device, EventID: eventID, OccurredMs: at.UnixMilli(), EnvTs: time.Now().UnixMilli(),
				Payload: payload, CreatedBy: pgtype.Int8{Int64: actor.ID, Valid: true},
			})
			if err != nil {
				return err
			}
			if n == 0 {
				// Same requestId again: hand back what is there, or say the id was reused for something else.
				row, err := q.GetStockEventForManage(c.Request.Context(), db.GetStockEventForManageParams{DeviceName: device, EventID: eventID})
				if err != nil {
					return err
				}
				stored = row.Payload
				replayed = true
				conflict = !sameJSON(row.Payload, payload) || row.DeletedAt.Valid
				return nil
			}
			stored = payload
			return auditAdmin(c.Request.Context(), q, actor, action, device, dev.CustomerID, "allowed", map[string]any{
				"eventId": eventID, "direction": req.Direction, "tank": *req.Tank, "time": at.UTC().Format(time.RFC3339),
				"beforeLevelMm": *req.BeforeLevelMm, "volumeL": *req.VolumeL, "reason": req.Reason, "requestId": req.RequestID,
			})
		})
		switch {
		case err != nil:
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		case tooEarly:
			manualInvalid(c, "time_before_visible", "time 早于设备归属当前客户的时间，补录后也无法在记录里看到")
		case conflict:
			apiError(c, http.StatusConflict, "request_id_reused", "requestId 已用于另一条不同的补录")
		default:
			resp, ok := stockResponseFrom(stored, false, at, "cloud")
			if !ok {
				c.JSON(http.StatusInternalServerError, gin.H{"error": "stored manual record is unreadable"})
				return
			}
			status := http.StatusCreated
			if replayed {
				status = http.StatusOK
			}
			c.JSON(status, resp)
		}
	}
}

func deleteManualStockEventHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device, eventID := c.Param("device"), c.Param("eventId")
		const action = "stock.manual_delete"

		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin && actor.Role != control.RoleCustomerAdmin {
			refuse(c, deps, actor, action, "role may not withdraw manual stock records", map[string]any{"device": device})
			return
		}
		if !requireDeviceOwnership(c, deps, device) {
			return
		}

		var missing, notDeletable bool
		err := withTx(c.Request.Context(), deps, func(q *db.Queries) error {
			ctx := c.Request.Context()
			dev, err := q.GetDevice(ctx, device)
			if err != nil {
				return err
			}
			row, err := q.GetStockEventForManage(ctx, db.GetStockEventForManageParams{DeviceName: device, EventID: eventID})
			if errors.Is(err, pgx.ErrNoRows) || (err == nil && row.CustomerID != dev.CustomerID) {
				missing = true
				return nil
			}
			if err != nil {
				return err
			}
			if row.Origin != "cloud" {
				notDeletable = true
				return nil
			}
			if row.DeletedAt.Valid {
				return nil // already withdrawn: DELETE is idempotent
			}
			if _, err := q.SoftDeleteCloudStockEvent(ctx, db.SoftDeleteCloudStockEventParams{
				DeviceName: device, EventID: eventID, DeletedBy: pgtype.Int8{Int64: actor.ID, Valid: true},
			}); err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, action, device, dev.CustomerID, "allowed", map[string]any{
				"eventId": eventID, "payload": json.RawMessage(row.Payload),
			})
		})
		switch {
		case err != nil:
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		case missing:
			apiError(c, http.StatusNotFound, "stock_event_not_found", "stock event not found")
		case notDeletable:
			apiError(c, http.StatusConflict, "stock_event_not_deletable", "只能撤销云端补录的记录，设备上报的记录不可撤销")
		default:
			c.Status(http.StatusNoContent)
		}
	}
}

func finiteNonNegative(v float64) bool { return v >= 0 && !math.IsInf(v, 0) && !math.IsNaN(v) }

// newManualEventID: manual-<24 hex>. Derived from (device, requestId) when the client gave one, so a
// retry names the same record; random otherwise. The prefix keeps it clear of the device's own ids.
func newManualEventID(device, requestID string) string {
	if requestID != "" {
		sum := sha256.Sum256([]byte(device + "\x00" + requestID))
		return "manual-" + hex.EncodeToString(sum[:12])
	}
	b := make([]byte, 12)
	_, _ = rand.Read(b)
	return "manual-" + hex.EncodeToString(b)
}

// manualPayload is the stored report. Only what the form knows is in it. For an outbound movement the
// tank's own change is negative (the convention toStockEventResponse relies on), so the same volumeL > 0
// reads as deliveredVolumeL = volumeL for both directions.
func manualPayload(eventID string, r manualStockRequest, at time.Time) []byte {
	net := *r.VolumeL
	if r.Direction == protocol.StockOut {
		net = -net
	}
	ms := at.UnixMilli()
	b, _ := json.Marshal(map[string]any{
		"eventId": eventID, "tank": *r.Tank, "direction": r.Direction, "source": protocol.SourceManual,
		"beginTimeMs": ms, "endTimeMs": ms, "tsMs": ms,
		"beginLevelMm": *r.BeforeLevelMm, "netVolumeL": net,
		"note": r.Reason,
	})
	return b
}

func sameJSON(a, b []byte) bool {
	var x, y any
	if json.Unmarshal(a, &x) != nil || json.Unmarshal(b, &y) != nil {
		return bytes.Equal(a, b)
	}
	xa, _ := json.Marshal(x)
	yb, _ := json.Marshal(y)
	return bytes.Equal(xa, yb)
}
