package api

import (
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// GET /api/devices/:device/tanks/:tank/calibration (cloud#14) returns the tank's height→volume
// calibration curve as the device last reported it. Like tank-settings it is a read-only mirror served
// from the business database (the device is the source of truth; the cloud never writes it), so it
// answers while the device is offline and `reportedAt` says how old it is. The device republishes it
// only when it reconnects, so a table changed on site shows up here after its next reconnect.
//
// Visibility: the device-level check first, then the tank-level one (tank_not_authorized for an
// employee without a grant on this tank), then the tank number is validated.
func tankCalibrationHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		if !requireDeviceOwnership(c, deps, device) {
			return
		}
		tank, err := strconv.Atoi(c.Param("tank"))
		if err != nil || tank < 1 || tank > protocol.MaxDeviceTankSettings {
			if isEmployee(c) {
				// Same answer as a tank that exists but is not granted: no way to probe tank numbers.
				apiError(c, http.StatusForbidden, codeTankNotAuthorized, "not authorized for this tank")
				return
			}
			apiError(c, http.StatusBadRequest, "tank_invalid", "tank 必须是 1～16 的整数")
			return
		}
		if !requireTankAccess(c, deps, device, tank) {
			return
		}
		row, err := deps.Queries.GetTankCalibration(c.Request.Context(), db.GetTankCalibrationParams{DeviceName: device, Tank: int32(tank)})
		if errors.Is(err, pgx.ErrNoRows) {
			apiError(c, http.StatusNotFound, "calibration_not_reported", "该罐尚未上报标定表")
			return
		}
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{
			"deviceName": device, "tank": tank, "reportedAt": row.ReportedAt.Time.UTC().Truncate(time.Millisecond),
			"pointCount": len(row.HeightMm), "heightMm": row.HeightMm, "volumeL": row.VolumeL,
		})
	}
}
