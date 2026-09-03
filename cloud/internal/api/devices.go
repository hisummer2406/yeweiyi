// User<->device binding and the authorization check every device-scoped handler now runs. Closes
// the gap CLAUDE.md flagged 2026-08-29 ("No user↔device authorization model at all").
package api

import (
	"context"
	"crypto/subtle"
	"net/http"

	"github.com/gin-gonic/gin"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
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

// bindDeviceRequest is bindDeviceHandler's body. PairingToken is new as of the 2026-09-04 fix below
// — see bindDeviceHandler's doc comment for why deviceName alone is no longer sufficient.
type bindDeviceRequest struct {
	DeviceName   string `json:"deviceName" binding:"required"`
	PairingToken string `json:"pairingToken" binding:"required"`
}

// bindDeviceHandler is the mini-program's device-pairing endpoint (product decision, 2026-08-29:
// binding happens by scanning a QR code printed/displayed on the physical device). The trust model
// this is meant to express is possession of the physical device — but until this fix, "possession"
// was never actually checked: the handler accepted any client-supplied deviceName verbatim and
// EnsureDevice+BindUserDevice'd it unconditionally, so any logged-in user who could guess or observe
// a real deviceName (e.g. "tank-farm-014", enumerable by pattern) could bind themselves to somebody
// else's device and, from there, read its telemetry/alarms and issue whatever control commands their
// role allows. Found in review of PR #11 (round 5, plain PR comment 2026-09-03 — GitHub does not let
// the PR's own author submit a formal "request changes" review on it).
//
// Fixed by requiring the QR code to carry the device's actual ThingsBoard access token
// (pairingToken) alongside deviceName, and verifying it against ThingsBoard's own record for that
// device (verifyPairingToken) before binding proceeds. This reuses the "one-device-one-secret"
// property the platform choice (see CLAUDE.md "Device connectivity platform") already established
// for MQTT auth — the same value a device puts in its MQTT username, read back through
// GetDeviceCredentials — rather than inventing a second, parallel secret and provisioning flow. A
// forged or guessed deviceName can no longer bind anything: an attacker would need the device's real
// credential, which requires having actually seen the physical device (or ThingsBoard's own admin
// console), the same possession property CLAUDE.md's product decision already intends.
//
// Deliberately NOT single-use: this project's product decision (CLAUDE.md, 2026-08-29 authorization
// pass) is that more than one staff member can legitimately bind the same device from the same
// physical QR code over time (e.g. multiple technicians on one tank), so a token that could only be
// redeemed once would break that on the very first successful bind. A true single-use pairing
// challenge (rotated per pairing attempt, e.g. shown on a device-local screen) would need
// device-firmware cooperation this repo doesn't own — see CLAUDE.md's "Open product decisions", QR
// content format is explicitly still open and cross-repo. What this fix closes is the actual P1: an
// attacker with no access to the physical device or its real credential cannot bind it, regardless
// of how many legitimate users can.
//
// Still calls EnsureDevice (business DB) after verification succeeds, same as before — a device
// that ThingsBoard already knows about (verification requires that) may still be new to this
// project's own `devices` table.
func bindDeviceHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if deps.TB == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "ThingsBoard is not configured on this deployment"})
			return
		}

		var req bindDeviceRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		ctx := c.Request.Context()

		ok, unavailable := verifyPairingToken(ctx, deps.TB, req.DeviceName, req.PairingToken)
		if unavailable {
			c.JSON(http.StatusServiceUnavailable, gin.H{
				"error": "the device platform is temporarily unavailable; retry shortly",
			})
			return
		}
		if !ok {
			// Deliberately the same response, whether deviceName doesn't exist in ThingsBoard at all
			// or exists but pairingToken is wrong — see verifyPairingToken's doc comment for why
			// distinguishing the two would turn this endpoint into a device-name oracle.
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid device name or pairing token"})
			return
		}

		userID := c.MustGet("userID").(int64)

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

// verifyPairingToken confirms pairingToken is the ThingsBoard access token actually issued to
// deviceName. ok is true only for an exact match against a real, currently-issued ACCESS_TOKEN
// credential; unavailable is true when the reason verification could not complete was ThingsBoard
// itself being unreachable, so the caller can report that as a 503 rather than folding a platform
// outage into "invalid credentials".
//
// ok's two failure causes — deviceName unknown to ThingsBoard, and deviceName known but
// pairingToken wrong — are deliberately collapsed into one outcome rather than reported separately.
// This mirrors requireDeviceOwnership's/userMayAccessDevice's own "authorization before existence"
// rule (CLAUDE.md invariant 6): if a wrong token on a real device answered differently than any
// token on a fake one, an attacker could enumerate real device names by bind attempts alone, which
// is exactly the oracle every other device-scoped endpoint in this package already takes care to
// avoid.
//
// Uses constant-time comparison for the same reason a password check would: CredentialsID is a
// bearer secret (it is literally what the device puts in its MQTT username), and a handler that
// returns sooner on an early mismatched byte would leak how much of a guess was correct through
// response timing.
func verifyPairingToken(ctx context.Context, tb *tbclient.Client, deviceName, pairingToken string) (ok, unavailable bool) {
	if pairingToken == "" {
		return false, false
	}

	dev, err := tb.GetDeviceByName(ctx, deviceName)
	if err != nil {
		return false, tbclient.Unavailable(err)
	}

	creds, err := tb.GetDeviceCredentials(ctx, dev.ID.ID)
	if err != nil {
		return false, tbclient.Unavailable(err)
	}
	if creds.Type != "ACCESS_TOKEN" || creds.CredentialsID == "" {
		return false, false
	}

	match := subtle.ConstantTimeCompare([]byte(creds.CredentialsID), []byte(pairingToken)) == 1
	return match, false
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
