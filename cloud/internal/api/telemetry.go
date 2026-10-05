package api

import (
	"errors"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// Telemetry read endpoints for the ThingsBoard single stack.
//
// These read straight through to ThingsBoard on request rather than serving a copy from the
// business database. That is the data boundary issue #10 draws: telemetry is stored once, in
// ThingsBoard, and this backend is a permission-checking, business-shaping proxy in front of it —
// not a second time-series store.
//
// The cost is that ThingsBoard being down makes these endpoints fail, which is why they answer 503
// with a clear message rather than 500. The alternative — caching every device's latest snapshot
// locally — is what the legacy `oilcan:latest:{device}` Redis key did, and it is exactly the
// duplicate-storage pattern being retired.

// latestTelemetryHandler is GET /api/devices/:device/telemetry/latest.
func latestTelemetryHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		// Authorization first, even before "is ThingsBoard configured": every device-scoped handler
		// answers a caller who may not touch the device with the same 403 regardless of anything else
		// about the deployment or the device.
		device := c.Param("device")
		if !requireDeviceOwnership(c, deps, device) {
			return
		}
		if deps.TB == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "ThingsBoard is not configured on this deployment"})
			return
		}

		ctx := c.Request.Context()
		dev, err := deps.TB.GetDeviceByName(ctx, device)
		if err != nil {
			writeThingsBoardError(c, err, device)
			return
		}

		// LatestSnapshot, not LatestValues: it anchors on the device's own most recent tankCount
		// report and re-reads exactly that snapshot's fields, so a tank the device stopped
		// reporting (after being reconfigured to fewer tanks) does not keep reappearing forever —
		// see its doc comment for why a per-key "latest value" merge gets this wrong.
		ts, values, err := deps.TB.LatestSnapshot(ctx, dev.ID.ID)
		if err != nil {
			writeThingsBoardError(c, err, device)
			return
		}
		if len(values) == 0 {
			// Authorized, provisioned, but has never reported. A real state, not an error.
			c.JSON(http.StatusNotFound, gin.H{"error": "this device has not reported any telemetry yet"})
			return
		}

		snap, err := protocol.ParseSnapshot(ts, values)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "device telemetry could not be parsed: " + err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"deviceName":  device,
			"ts":          time.UnixMilli(snap.TS).UTC(),
			"snapshotSeq": snap.SnapshotSeq,
			"tanks":       snap.Tanks,
		})
	}
}

// writeThingsBoardError distinguishes the three outcomes that matter to a client, which is the
// "ThingsBoard 暂时不可用时，API 错误和恢复行为明确" requirement in issue #10.
func writeThingsBoardError(c *gin.Context, err error, device string) {
	switch {
	case errors.Is(err, tbclient.ErrNotFound):
		// Bound in the business database but absent from ThingsBoard: a provisioning gap between
		// the two device registries, not a permission problem and not an outage.
		c.JSON(http.StatusNotFound, gin.H{"error": "device " + device + " is not provisioned in ThingsBoard"})
	case tbclient.Unavailable(err):
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"error": "the device platform is temporarily unavailable; retry shortly",
		})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
	}
}
