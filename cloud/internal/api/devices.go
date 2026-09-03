// User<->device binding and the authorization check every device-scoped handler now runs. Closes
// the gap CLAUDE.md flagged 2026-08-29 ("No user↔device authorization model at all").
package api

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"golang.org/x/crypto/bcrypt"

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

// bindDeviceRequest is bindDeviceHandler's body. PairingToken must be a secret minted for
// deviceName via mintPairingSecretHandler — see bindDeviceHandler's doc comment for why deviceName
// alone is no longer sufficient, and why this is not the device's ThingsBoard access token.
type bindDeviceRequest struct {
	DeviceName   string `json:"deviceName" binding:"required"`
	PairingToken string `json:"pairingToken" binding:"required"`
}

// bindDeviceHandler is the mini-program's device-pairing endpoint (product decision, 2026-08-29:
// binding happens by scanning a QR code printed/displayed on the physical device). The trust model
// this is meant to express is possession of the physical device — but until round 5's fix, that was
// never actually checked at all: the handler accepted any client-supplied deviceName verbatim and
// EnsureDevice+BindUserDevice'd it unconditionally, so any logged-in user who could guess or observe
// a real deviceName (e.g. "tank-farm-014", enumerable by pattern) could bind themselves to somebody
// else's device. Found in review of PR #11 (round 5, plain PR comment — GitHub does not let the PR's
// own author submit a formal "request changes" review on it).
//
// Round 5's fix required a pairingToken and verified it against ThingsBoard's own
// GetDeviceCredentials — reusing the device's real ThingsBoard access token as the pairing proof.
// **That fix was itself a P1, found in the very next review round**: the ThingsBoard access token
// IS the device's MQTT identity (its passwordless MQTT username — see internal/devicesim's
// SetUsername(AccessToken)). Putting it in a QR code and verifying it as a *pairing* credential
// meant anyone who photographed the code, or captured it from a proxy/client log, could connect to
// MQTT *as the device* — forge telemetry, alarms and cmdResults — not merely bind themselves to it
// in this backend. A pairing credential leaking should only cost a binding; this one could cost the
// device's entire on-wire identity.
//
// Fixed properly this time by giving pairing its own secret, fully independent of ThingsBoard: see
// mintPairingSecretHandler (mints and returns it once, platform_admin only) and
// device_pairing_secrets (migration 000012, stores only a bcrypt hash — never the plaintext).
// verifyPairingToken below checks pairingToken against that hash and nothing else; ThingsBoard is
// not consulted at all, so a leaked pairing secret only ever grants "bind this device to my
// account", never "speak for this device on MQTT". This also decouples binding from ThingsBoard's
// own availability, consistent with the rest of this package's "business endpoints work even
// without ThingsBoard configured" convention (see deps.TB == nil checks elsewhere in this file).
//
// Deliberately NOT single-use: this project's product decision (CLAUDE.md, 2026-08-29 authorization
// pass) is that more than one staff member can legitimately bind the same device from the same
// physical QR code over time (e.g. multiple technicians on one tank), so a secret that could only be
// redeemed once would break that on the very first successful bind. Minting always rotates, so a
// leaked or lost secret is fixed by re-minting (which does not evict any already-bound user), not by
// making every pairing single-use.
func bindDeviceHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req bindDeviceRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		ctx := c.Request.Context()

		ok, err := verifyPairingToken(ctx, deps, req.DeviceName, req.PairingToken)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if !ok {
			// Deliberately the same response whether deviceName has no pairing secret minted at all
			// or one is minted but pairingToken is wrong — see verifyPairingToken's doc comment for
			// why distinguishing the two would turn this endpoint into a device-name oracle.
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

// bcryptCompareHashAndPassword indirects bcrypt.CompareHashAndPassword through a package variable
// so pairing_timing_test.go (the one internal, white-box test file in this package — see its own
// doc comment for why) can count how many times verifyPairingToken actually invokes bcrypt, without
// asserting on wall-clock timing. Not swapped anywhere outside that test.
var bcryptCompareHashAndPassword = bcrypt.CompareHashAndPassword

// dummyPairingSecretHash is a fixed bcrypt hash with no real secret behind it. verifyPairingToken
// compares against it on the "no secret has ever been minted for this deviceName" path — see that
// function's doc comment for why a real comparison has to happen there too, not just on the
// "wrong token" path.
var dummyPairingSecretHash = mustHashDummyPairingSecret()

func mustHashDummyPairingSecret() string {
	hash, err := bcrypt.GenerateFromPassword([]byte("yeweiyi-cloud-fixed-dummy-value-for-pairing-timing-parity"), bcrypt.DefaultCost)
	if err != nil {
		// bcrypt.GenerateFromPassword only errors on a cost outside its documented range or a
		// too-long input; bcrypt.DefaultCost and a fixed short string can never trigger either, so
		// this can only mean bcrypt itself is broken — not a condition any caller could recover
		// from, and this runs at package init, before any request has been served.
		panic("api: failed to hash the fixed dummy pairing secret: " + err.Error())
	}
	return string(hash)
}

// verifyPairingToken confirms pairingToken matches deviceName's currently-minted pairing secret.
//
// The two failure causes — no secret has ever been minted for deviceName, and one was minted but
// pairingToken is wrong — are deliberately collapsed into a single `false, nil` outcome rather than
// reported separately. This mirrors requireDeviceOwnership's/userMayAccessDevice's own
// "authorization before existence" rule (CLAUDE.md invariant 6): if a wrong token on a real,
// provisioned device answered differently than any token on a device nobody has ever minted a
// secret for, an attacker could enumerate real device names by bind attempts alone.
//
// **Both causes must also cost the same to answer, not just look the same** — found in review of
// PR #11 (round 7). The first version of this function returned immediately on
// pgx.ErrNoRows, paying no bcrypt cost at all, while the wrong-token path always ran
// bcrypt.CompareHashAndPassword — a deliberately expensive operation (that expense is the whole
// point of using bcrypt over a plain hash). Even with byte-identical response bodies, the
// no-such-secret path answered measurably faster, letting a remote timing measurement recover the
// same "is this a real device name" oracle the identical response text was meant to deny. Fixed by
// comparing against dummyPairingSecretHash on the no-secret path too, so both branches invoke
// bcryptCompareHashAndPassword exactly once regardless of which one is taken.
//
// bcrypt.CompareHashAndPassword is already constant-time with respect to its two arguments (it
// derives a fresh hash from the candidate and compares digests, not bytes of the candidate itself
// against a stored secret), so no separate subtle.ConstantTimeCompare is needed the way round 5's
// now-replaced ThingsBoard-token comparison used one — that property is about comparing two known
// digests, not about equalizing the cost of two different code paths, which is what this fix does.
func verifyPairingToken(ctx context.Context, deps Deps, deviceName, pairingToken string) (bool, error) {
	if pairingToken == "" {
		return false, nil
	}

	hash, err := deps.Queries.GetDevicePairingSecretHash(ctx, deviceName)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			bcryptCompareHashAndPassword([]byte(dummyPairingSecretHash), []byte(pairingToken))
			return false, nil
		}
		return false, err
	}

	if err := bcryptCompareHashAndPassword([]byte(hash), []byte(pairingToken)); err != nil {
		return false, nil
	}
	return true, nil
}

// mintPairingSecretHandler is POST /api/devices/:device/pairing-secret — the provisioning-time
// counterpart to bindDeviceHandler above. Whoever physically sets a device up (an installer, not an
// end customer) calls this once, authenticated as a platform_admin, and encodes the returned
// pairingSecret into the QR code printed/displayed on the device alongside its deviceName. The
// plaintext is returned exactly once, in this response, and never stored — only its bcrypt hash is
// (device_pairing_secrets, migration 000012).
//
// Restricted to platform_admin deliberately, not just any authenticated user: this mints a *fresh*
// credential capable of binding deviceName to whoever holds it, for a deviceName the caller supplies
// directly in the URL with no ownership check of any kind (there is nothing to own yet — that is
// what minting is for). Without the role gate, any logged-in customer_user could mint-then-bind
// themselves onto someone else's already-provisioned device, silently reopening the exact takeover
// round 5 closed. platform_admin is already the trusted operator tier everywhere else in this
// codebase (control.Service.authorize grants it every device unconditionally), so gating a
// provisioning action to it is consistent, not a new trust boundary.
//
// Calls EnsureDevice (business DB) first so device_pairing_secrets' foreign key to devices always
// holds — deviceName may not have a `devices` row yet if this runs before the device has ever
// connected to anything.
func mintPairingSecretHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		role, _ := c.Get("role")
		if role != control.RolePlatformAdmin {
			c.JSON(http.StatusForbidden, gin.H{"error": "only a platform administrator may mint a device pairing secret"})
			return
		}

		device := c.Param("device")
		ctx := c.Request.Context()

		if err := deps.Queries.EnsureDevice(ctx, device); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		raw := make([]byte, 32)
		if _, err := rand.Read(raw); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		secret := base64.RawURLEncoding.EncodeToString(raw)

		hash, err := bcrypt.GenerateFromPassword([]byte(secret), bcrypt.DefaultCost)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		if err := deps.Queries.UpsertDevicePairingSecret(ctx, db.UpsertDevicePairingSecretParams{
			DeviceName: device,
			SecretHash: string(hash),
		}); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"deviceName": device, "pairingSecret": secret})
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
