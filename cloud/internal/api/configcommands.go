package api

import (
	"errors"
	"net/http"
	"time"

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
//	GET /api/devices/:device/device-settings       device-level settings mirror, admins only

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
