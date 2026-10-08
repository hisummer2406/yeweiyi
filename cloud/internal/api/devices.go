// User<->device binding and the authorization check every device-scoped handler now runs. Closes
// the gap CLAUDE.md flagged 2026-08-29 ("No user↔device authorization model at all").
package api

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"golang.org/x/crypto/bcrypt"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// userMayAccessDevice is the one authorization rule every device-scoped read or write enforces —
// REST handlers (via requireDeviceOwnership), the alarm WebSocket, and (through the same SQL)
// internal/control. The rule itself lives in the UserMayAccessDevice query: platform admin → any
// device; customer admin → every device of their customer; customer user → a bound device of their
// customer.
//
// The decision reads the user's current role and customer from the database, not from the JWT, so
// moving a user between customers takes effect immediately. It takes no role argument on purpose: a
// caller that could pass its own role could also pass a stale one.
//
// It stays a single function because the callers have different shapes — wsAlarmsHandler
// authenticates its own JWT outside the gin middleware chain — and the last time they each carried
// their own copy of the rule they drifted apart (PR #11, review round 4).
func userMayAccessDevice(ctx context.Context, deps Deps, userID int64, deviceName string) (bool, error) {
	return deps.Queries.UserMayAccessDevice(ctx, db.UserMayAccessDeviceParams{UserID: userID, DeviceName: deviceName})
}

// requireDeviceOwnership checks the authenticated user (from authMiddleware's JWT claims —
// "userID" in gin's context) may access deviceName per userMayAccessDevice, writing the response
// and returning false if not. Every REST handler that reads or writes one device's data calls this
// immediately after it knows which device it's operating on, before touching that device's rows.
// Deliberately checked *before* any "does this record/device even exist" check below it in the same
// handler, so an unauthorized caller can't distinguish "not yours", "someone else's customer" and
// "doesn't exist" — otherwise the ownership boundary would leak which device_name strings are real.
// (A platform admin passing every check does not weaken this property: it protects a caller from
// enumerating *other people's* devices, and an admin is already trusted with visibility into all of
// them.)
func requireDeviceOwnership(c *gin.Context, deps Deps, deviceName string) bool {
	_, ok := requireDeviceOwnershipAt(c, deps, deviceName)
	return ok
}

// requireDeviceOwnershipAt is requireDeviceOwnership that also returns the database clock at the
// moment of the decision, for handlers that must bound what they read to "while this caller was
// allowed to see the device" (see telemetryHistoryHandler). The rule is still user_may_access_device.
func requireDeviceOwnershipAt(c *gin.Context, deps Deps, deviceName string) (time.Time, bool) {
	userID, ok := c.Get("userID")
	if !ok {
		// authMiddleware always sets this before a handler runs; ok=false means a handler was wired
		// up without it, which is a wiring bug, not a client error.
		c.JSON(http.StatusInternalServerError, gin.H{"error": "no authenticated user in context"})
		return time.Time{}, false
	}

	res, err := deps.Queries.AuthorizeDeviceAccess(c.Request.Context(), db.AuthorizeDeviceAccessParams{
		UserID: userID.(int64), DeviceName: deviceName,
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return time.Time{}, false
	}
	if !res.Allowed {
		c.JSON(http.StatusForbidden, gin.H{"error": "not authorized for this device"})
		return time.Time{}, false
	}
	return res.DecidedAt.Time, true
}

type deviceNameRequest struct {
	DeviceName string `json:"deviceName" binding:"required"`
}

// bindDeviceRequest is bindDeviceHandler's body. PairingToken must be a secret minted for
// deviceName via mintPairingSecretHandler — see bindDeviceHandler's doc comment for why deviceName
// alone is no longer sufficient, and why this is not the device's ThingsBoard access token.
type bindDeviceRequest struct {
	DeviceName   string `json:"deviceName" binding:"required"`
	PairingToken string `json:"pairingToken"`
}

// shortCodePattern is what a scanned short code may look like. Anything else is refused before it
// reaches SQL or a log line.
var shortCodePattern = regexp.MustCompile(`^[0-9A-Za-z]{1,32}$`)

// shortCodeCandidates is the ordered list of device names a scanned code may mean: the text itself
// (exact match wins), then each configured model prefix + text. Nil when the text is not a valid code.
func shortCodeCandidates(code string, prefixes []string) []string {
	if !shortCodePattern.MatchString(code) {
		return nil
	}
	out := []string{code}
	for _, p := range prefixes {
		out = append(out, p+code)
	}
	return out
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

		if req.PairingToken == "" {
			bindDeviceByCode(c, deps, req.DeviceName)
			return
		}

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

		// A valid pairing secret proves possession of the device, not that it is *this user's
		// customer's* device. The customer check and the insert are one statement (see the query), and
		// a refusal is indistinguishable from a wrong secret: a QR code from another customer's
		// device, an unassigned device and a wrong secret all get the identical 401 above.
		bound, err := deps.Queries.BindUserDeviceIfAllowed(ctx, db.BindUserDeviceIfAllowedParams{UserID: userID, DeviceName: req.DeviceName})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if !bound {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid device name or pairing token"})
			return
		}

		c.JSON(http.StatusOK, gin.H{"deviceName": req.DeviceName, "bound": true})
	}
}

// bindDeviceByCode is the credential-less path: the QR code encodes only the short code, so
// req.DeviceName is the scanned text and the caller's customer is what authorizes the bind (the
// device was assigned to that customer by an administrator). Resolution happens inside the single
// bind statement, scoped to the caller's customer; it never creates a devices row. Every refusal —
// unknown, other customer's, unassigned, ambiguous role, disabled, malformed — is the same 401 as a
// wrong pairing secret. Not single-use, and the QR format is unchanged.
func bindDeviceByCode(c *gin.Context, deps Deps, code string) {
	refuse := func() {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid device name or pairing token"})
	}
	candidates := shortCodeCandidates(code, deps.DeviceCodePrefixes)
	if candidates == nil {
		refuse()
		return
	}
	name, err := deps.Queries.BindUserDeviceByCodeIfAllowed(c.Request.Context(), db.BindUserDeviceByCodeIfAllowedParams{
		UserID:     c.MustGet("userID").(int64),
		Candidates: candidates,
	})
	if errors.Is(err, pgx.ErrNoRows) {
		refuse()
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"deviceName": name, "bound": true})
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
// The body must name the customer the device is being provisioned for ({"customerId": N}). That is
// what makes the device bindable by that customer's users and nobody else's. Minting never moves a
// device between customers: if the device already belongs to a different one, that is a
// reassignment (which also has to clear the old customer's bindings) and is refused here with 409
// rather than done silently. Re-minting for the *same* customer is the normal rotation.
//
// The device's `devices` row is created here if it does not exist yet, so device_pairing_secrets'
// foreign key always holds — deviceName may not have a row before the device has ever connected.
type mintPairingSecretRequest struct {
	CustomerID int64 `json:"customerId" binding:"required"`
}

func mintPairingSecretHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		// The caller's role comes from the database, not the token: a platform admin who has since
		// been disabled or demoted must not keep minting (and claiming devices for customers) on a
		// token that is still unexpired.
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			c.JSON(http.StatusForbidden, gin.H{"error": "only a platform administrator may mint a device pairing secret"})
			return
		}

		var req mintPairingSecretRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "customerId is required: " + err.Error()})
			return
		}

		device := c.Param("device")
		ctx := c.Request.Context()

		if _, err := deps.Queries.GetCustomer(ctx, req.CustomerID); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusBadRequest, gin.H{"error": "unknown customerId"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		owner, err := deps.Queries.EnsureDeviceForCustomer(ctx, db.EnsureDeviceForCustomerParams{
			DeviceName: device,
			CustomerID: pgtype.Int8{Int64: req.CustomerID, Valid: true},
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if !owner.Valid || owner.Int64 != req.CustomerID {
			c.JSON(http.StatusConflict, gin.H{"error": "device already belongs to a different customer"})
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

		// The ownership check above and this write are separated by the bcrypt above; the write
		// checks ownership again, in the same statement, with the device row locked.
		if _, err := deps.Queries.UpsertDevicePairingSecretForCustomer(ctx, db.UpsertDevicePairingSecretForCustomerParams{
			DeviceName: device,
			CustomerID: int8(req.CustomerID),
			SecretHash: string(hash),
		}); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusConflict, gin.H{"error": "device already belongs to a different customer"})
				return
			}
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

// deviceResponse is one row of GET /api/devices. It is spelled out rather than serializing db.Device
// so that adding a column to the table (customer_id, in migration 000013) cannot silently change the
// REST contract the mini-program reads. The field names are exactly the ones db.Device used to emit;
// whether customer_id should be exposed is a cross-repo decision, not an accident of sqlc.
type deviceResponse struct {
	DeviceName    string             `json:"device_name"`
	IsOnline      bool               `json:"is_online"`
	LastOnlineAt  pgtype.Timestamptz `json:"last_online_at"`
	LastOfflineAt pgtype.Timestamptz `json:"last_offline_at"`
	UpdatedAt     pgtype.Timestamptz `json:"updated_at"`
	StationID     *int64             `json:"station_id"`
}

// adminDeviceResponse is a row for a platform admin: the same fields plus the customer the device is
// assigned to (null while unassigned), which the admin console needs to show who owns a device.
// Customer accounts keep getting plain deviceResponse rows, so the mini-program's contract is
// unchanged (TestTenancy_ListDevicesDoesNotLeakCustomerID).
type adminDeviceResponse struct {
	deviceResponse
	CustomerID *int64 `json:"customer_id"`
}

// listDevicesHandler lets a client discover which device names it's even allowed to query — every
// other endpoint requires the caller to already know an exact device_name, which only works once
// there's a way to find out what those are first. The scope follows the same rule as
// requireDeviceOwnership: a platform admin sees every device, a customer admin their customer's,
// a customer user their bound devices within their customer.
func listDevicesHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		userID := c.MustGet("userID").(int64)
		rows, err := deps.Queries.ListDevicesForUser(c.Request.Context(), userID)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		// The role is the one the auth middleware read from the database for this request.
		role, _ := c.Get("role")
		isPlatformAdmin := role == control.RolePlatformAdmin
		out := make([]any, 0, len(rows))
		for _, r := range rows {
			row := deviceResponse{
				DeviceName:    r.DeviceName,
				IsOnline:      r.IsOnline,
				LastOnlineAt:  r.LastOnlineAt,
				LastOfflineAt: r.LastOfflineAt,
				UpdatedAt:     r.UpdatedAt,
				StationID:     int8Ptr(r.StationID),
			}
			if isPlatformAdmin {
				out = append(out, adminDeviceResponse{deviceResponse: row, CustomerID: int8Ptr(r.CustomerID)})
			} else {
				out = append(out, row)
			}
		}
		c.JSON(http.StatusOK, gin.H{"rows": out})
	}
}

// registerDeviceRequest is registerDeviceHandler's body. DeviceName is the nameplate's full factory
// code, treated as an opaque string: no prefix or length rule is enforced because the product has not
// frozen one, and leading zeros must survive.
type registerDeviceRequest struct {
	DeviceName string `json:"deviceName" binding:"required"`
	CustomerID int64  `json:"customerId" binding:"required"`
}

// registerDeviceHandler is POST /api/devices — the operator's idempotent device registration
// (platform_admin only; the role is read from the database like every admin route).
//
// It exists because pairing-secret minting is not registration: that endpoint rotates a secret on
// every call. This one touches no credential at all. Registering is what makes internal/ingest and
// internal/rollup pick the device up (they iterate the devices table), so the operator never has to
// wait for a scan.
//
// Ownership follows EnsureDeviceForCustomer: create, or claim an unassigned device, never move an
// assigned one — a different owner is a 409. The insert and the ownership read are one statement, so
// concurrent identical calls converge on one row and exactly one of them reports created.
func registerDeviceHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			c.JSON(http.StatusForbidden, gin.H{"error": "only a platform administrator may register a device"})
			return
		}

		var req registerDeviceRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "deviceName and customerId are required: " + err.Error()})
			return
		}
		name := strings.TrimSpace(req.DeviceName)
		// The name later appears in URL paths (/api/devices/:device/...), so a slash could never be
		// addressed again; reject it up front rather than register an unreachable device.
		if name == "" || strings.ContainsAny(name, "/?#\\") {
			c.JSON(http.StatusBadRequest, gin.H{"error": "deviceName must be non-empty and must not contain / ? # or \\"})
			return
		}

		ctx := c.Request.Context()
		if _, err := deps.Queries.GetCustomer(ctx, req.CustomerID); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusBadRequest, gin.H{"error": "unknown customerId"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		res, err := deps.Queries.RegisterDeviceForCustomer(ctx, db.RegisterDeviceForCustomerParams{
			DeviceName: name,
			CustomerID: pgtype.Int8{Int64: req.CustomerID, Valid: true},
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if !res.CustomerID.Valid || res.CustomerID.Int64 != req.CustomerID {
			c.JSON(http.StatusConflict, gin.H{"error": "device already belongs to a different customer"})
			return
		}

		status := http.StatusOK
		if res.Inserted {
			status = http.StatusCreated
		}
		c.JSON(status, gin.H{"deviceName": name, "customerId": req.CustomerID, "created": res.Inserted})
	}
}
