package api

import (
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// Routes added by config-command-delivery (#3, docs/config-command-contract.md):
//
//	GET /api/devices/:device/commands/:commandId   one command
//	GET /api/devices/:device/commands?requestId=   the caller's own submission (0 or 1 rows)
//	PUT /api/devices/:device/config-commands       per-device gate, platform admin only
//	GET /api/devices/:device/device-settings       device-level settings mirror, admins only

const (
	auditConfigCommandsGate = "device.config_commands"
	maxGateReasonLen        = 200
)

// getDeviceCommandHandler is GET /api/devices/:device/commands/:commandId. Device check first
// (invariant 6); after that, a command that does not exist, belongs to another device, is not visible
// to the caller, or whose id is not a UUID is one and the same 404.
func getDeviceCommandHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		if !requireDeviceOwnership(c, deps, device) {
			return
		}
		var id pgtype.UUID
		if err := id.Scan(c.Param("commandId")); err != nil {
			commandNotFound(c)
			return
		}
		cmd, err := deps.Queries.GetControlCommandVisibleToUser(c.Request.Context(), db.GetControlCommandVisibleToUserParams{
			UserID: c.MustGet("userID").(int64), CommandID: id, DeviceName: device,
		})
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				commandNotFound(c)
				return
			}
			internalError(c, err)
			return
		}
		c.JSON(http.StatusOK, toCommandResponse(cmd))
	}
}

func commandNotFound(c *gin.Context) {
	apiError(c, http.StatusNotFound, "command_not_found", "command not found")
}

// listOwnCommandByRequestID answers GET .../commands?requestId= with the list shape (0 or 1 rows), so a
// client recovering a timed-out submission parses the same body as the history list.
func listOwnCommandByRequestID(c *gin.Context, deps Deps, device, requestID string) {
	rows := []commandResponse{}
	cmd, err := deps.Queries.GetOwnControlCommandByRequestIDVisible(c.Request.Context(), db.GetOwnControlCommandByRequestIDVisibleParams{
		UserID: c.MustGet("userID").(int64), RequestID: pgtype.Text{String: requestID, Valid: true}, DeviceName: device,
	})
	switch {
	case err == nil:
		rows = append(rows, toCommandResponse(cmd))
	case !errors.Is(err, pgx.ErrNoRows):
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"commands": rows})
}

type configGateRequest struct {
	Enabled *bool  `json:"enabled"`
	Reason  string `json:"reason"`
}

// setConfigCommandsGateHandler is PUT /api/devices/:device/config-commands. Platform admin only, decided
// from the database before the body is parsed. Idempotent; every call that passes the role check is
// audited with the state before and after.
func setConfigCommandsGateHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		device := c.Param("device")
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, auditConfigCommandsGate, "platform admin only", map[string]any{"device": device})
			return
		}
		var req configGateRequest
		if err := c.ShouldBindJSON(&req); err != nil || req.Enabled == nil {
			apiError(c, http.StatusBadRequest, "invalid_request", "body must be {\"enabled\": bool, \"reason\": string}")
			return
		}
		reason := strings.TrimSpace(req.Reason)
		if reason == "" || utf8.RuneCountInString(reason) > maxGateReasonLen {
			apiError(c, http.StatusBadRequest, "reason_required", "reason is required (at most 200 characters)")
			return
		}
		ctx := c.Request.Context()
		if purging, err := devicePurging(ctx, deps.Queries, device); err == nil && purging {
			purgeInProgress(c)
			return
		}
		row, err := deps.Queries.SetDeviceConfigCommandsEnabled(ctx, db.SetDeviceConfigCommandsEnabledParams{
			Enabled: *req.Enabled, AdminID: pgtype.Int8{Int64: actor.ID, Valid: true}, DeviceName: device,
		})
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				apiError(c, http.StatusNotFound, "device_not_found", "device not found")
				return
			}
			internalError(c, err)
			return
		}
		customer := pgtype.Int8{}
		if dev, err := deps.Queries.GetDevice(ctx, device); err == nil {
			customer = dev.CustomerID
		}
		_ = auditAdmin(ctx, deps.Queries, actor, auditConfigCommandsGate, device, customer, "allowed",
			map[string]any{"before": row.WasEnabled, "after": *req.Enabled, "reason": reason})
		c.JSON(http.StatusOK, configGateView(device, row.ConfigCommandsEnabledAt, row.ConfigCommandsEnabledBy))
	}
}

func configGateView(device string, at pgtype.Timestamptz, by pgtype.Int8) gin.H {
	var enabledAt *time.Time
	if at.Valid {
		enabledAt = &at.Time
	}
	return gin.H{"deviceName": device, "enabled": at.Valid, "enabledAt": enabledAt, "enabledBy": int8Ptr(by)}
}

type deviceSettingsView struct {
	DeviceName                string    `json:"deviceName"`
	ReportedAt                time.Time `json:"reportedAt"`
	InStockHeightThresholdMm  *int32    `json:"inStockHeightThresholdMm"`
	InStockTimeThresholdSec   *int32    `json:"inStockTimeThresholdSec"`
	OutStockHeightThresholdMm *int32    `json:"outStockHeightThresholdMm"`
	OutStockTimeThresholdSec  *int32    `json:"outStockTimeThresholdSec"`
}

// deviceSettingsHandler is GET /api/devices/:device/device-settings: the last device-level settings the
// device reported. Whole-device data, so administrators only: an employee who can locate the device
// through a tank grant gets the role 403 after the device check.
func deviceSettingsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		if !requireDeviceOwnership(c, deps, device) {
			return
		}
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role == control.RoleCustomerUser {
			refuse(c, deps, actor, "device.settings.read", "administrators only", map[string]any{"device": device})
			return
		}
		s, err := deps.Queries.GetDeviceSettings(c.Request.Context(), device)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				apiError(c, http.StatusNotFound, "device_settings_not_reported", "the device has not reported its device-level settings")
				return
			}
			internalError(c, err)
			return
		}
		c.JSON(http.StatusOK, deviceSettingsView{
			DeviceName: device, ReportedAt: s.ReportedAt.Time,
			InStockHeightThresholdMm: i32p(s.InStockHeightThresholdMm), InStockTimeThresholdSec: i32p(s.InStockTimeThresholdSec),
			OutStockHeightThresholdMm: i32p(s.OutStockHeightThresholdMm), OutStockTimeThresholdSec: i32p(s.OutStockTimeThresholdSec),
		})
	}
}
