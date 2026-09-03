// User<->device binding and the authorization check every device-scoped handler now runs. Closes
// the gap CLAUDE.md flagged 2026-08-29 ("No user↔device authorization model at all").
package api

import (
	"context"
	"net/http"

	"github.com/gin-gonic/gin"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// userMayAccessDevice is the one authorization rule every device-scoped read or write in this
// package enforces: a platform admin may touch any device (mirroring
// internal/control.Service.authorize's identical rule for issuing a command); everyone else must be
// bound to it via user_devices.
//
// This is factored out of requireDeviceOwnership specifically so that a second, differently-shaped
// caller — wsAlarmsHandler, which authenticates its own JWT independently of authMiddleware/gin
// context because a WebSocket handshake cannot carry the usual middleware chain — cannot drift from
// it again. That drift already happened once: round 3's review fixed the platform-admin shortcut
// only in requireDeviceOwnership, which covers every REST device endpoint, but wsAlarmsHandler had
// its own unconditional UserOwnsDevice call that nothing routed through the fix. The result was a
// platform admin who could read a device's REST data and issue commands against it, yet be refused
// a live alarm WebSocket for that same unbound device — found in review of PR #11 (round 4).
func userMayAccessDevice(ctx context.Context, deps Deps, userID int64, role, deviceName string) (bool, error) {
	if role == control.RolePlatformAdmin {
		return true, nil
	}
	return deps.Queries.UserOwnsDevice(ctx, db.UserOwnsDeviceParams{UserID: userID, DeviceName: deviceName})
}

// requireDeviceOwnership checks the authenticated user (from authMiddleware's JWT claims —
// "userID"/"role" in gin's context) may access deviceName per userMayAccessDevice, writing the
// response and returning false if not. Every REST handler that reads or writes one device's data
// calls this immediately after it knows which device it's operating on, before touching that
// device's rows. Deliberately checked *before* any "does this record/device even exist" check below
// it in the same handler, so an unauthorized caller can't distinguish "not yours" from "doesn't
// exist" — otherwise the ownership boundary would leak which device_name strings are real. (A
// platform admin bypassing that check entirely does not weaken this property: it protects a caller
// from enumerating *other people's* devices, and an admin is already trusted with visibility into
// all of them.)
func requireDeviceOwnership(c *gin.Context, deps Deps, deviceName string) bool {
	userID, ok := c.Get("userID")
	if !ok {
		// authMiddleware always sets this before a handler runs; ok=false means a handler was wired
		// up without it, which is a wiring bug, not a client error.
		c.JSON(http.StatusInternalServerError, gin.H{"error": "no authenticated user in context"})
		return false
	}
	role, _ := c.Get("role")
	roleStr, _ := role.(string)

	owns, err := userMayAccessDevice(c.Request.Context(), deps, userID.(int64), roleStr, deviceName)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return false
	}
	if !owns {
		c.JSON(http.StatusForbidden, gin.H{"error": "not authorized for this device"})
		return false
	}
	return true
}

type deviceNameRequest struct {
	DeviceName string `json:"deviceName" binding:"required"`
}

// bindDeviceHandler is the mini-program's device-pairing endpoint (product decision, 2026-08-29:
// binding happens by scanning a QR code printed/displayed on the physical device, which encodes
// deviceName). The trust model this implies: possession of the physical device — being able to scan
// its QR code — is what authorizes binding, the same way most consumer IoT pairing flows work.
// There is deliberately no ownership check here (unlike every other device-scoped handler) — binding
// is how ownership gets *established*, not something that requires it first. Calls EnsureDevice so
// binding a factory-fresh device that has never sent an MQTT message yet (no `devices` row) doesn't
// fail on the foreign key — this can legitimately happen if the mini-program pairing step runs
// before the device's first `isOline` announcement.
func bindDeviceHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req deviceNameRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		userID := c.MustGet("userID").(int64)
		ctx := c.Request.Context()

		if err := deps.Queries.EnsureDevice(ctx, req.DeviceName); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if err := deps.Queries.BindUserDevice(ctx, db.BindUserDeviceParams{UserID: userID, DeviceName: req.DeviceName}); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"deviceName": req.DeviceName, "bound": true})
	}
}

func unbindDeviceHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req deviceNameRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		userID := c.MustGet("userID").(int64)
		if err := deps.Queries.UnbindUserDevice(c.Request.Context(), db.UnbindUserDeviceParams{UserID: userID, DeviceName: req.DeviceName}); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"deviceName": req.DeviceName, "bound": false})
	}
}

// listDevicesHandler lets a client discover which device names it's even allowed to query — every
// other endpoint requires the caller to already know an exact device_name, which only works once
// there's a way to find out what those are first.
func listDevicesHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		userID := c.MustGet("userID").(int64)
		rows, err := deps.Queries.ListDevicesForUser(c.Request.Context(), userID)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if rows == nil {
			rows = []db.Device{}
		}
		c.JSON(http.StatusOK, gin.H{"rows": rows})
	}
}
