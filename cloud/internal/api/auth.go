package api

import (
	"context"
	"errors"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"golang.org/x/crypto/bcrypt"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// defaultTokenTTL is used when Deps.TokenTTL is left zero (tests, and any caller that does not
// care). Production sets it from config — see config.example.yaml's auth.tokenTTL. The token only
// proves who the caller is; their role, customer and disabled flag are read from the database on
// every request (authMiddleware), so none of those changes waits for a token to expire.
const defaultTokenTTL = 24 * time.Hour

// defaultMaxSessionAge is how long sliding renewal may keep a login alive, counted from the password
// login, when Deps.MaxSessionAge is zero. Production sets it from auth.maxSessionAge.
const defaultMaxSessionAge = 30 * 24 * time.Hour

// renewedTokenHeader carries the replacement token. It is only ever set on a successful, authorized
// response: clients that do not know about it are unaffected.
const renewedTokenHeader = "X-Renewed-Token"

// loginRequest: exactly one of Username (platform admin) and Phone (customer admin). The server does
// not guess which one a bare string is; the client decides and says so (contract §4.1).
type loginRequest struct {
	Username string `json:"username"`
	Phone    string `json:"phone"`
	Password string `json:"password"`
}

// Machine-readable codes for the `code` field of auth responses. Clients branch on these, not on the
// message and not on the status alone: the same 401 can mean "wrong password" or "no such binding".
const (
	codeInvalidRequest     = "invalid_request"
	codeInvalidCredentials = "invalid_credentials"
	codeInvalidPhone       = "invalid_phone"
	// codeCustomerNotOnboarded: the user's customer is still being configured (cloud#60). It is a
	// business notice, deliberately not the bare "not authorized" that clients read as a revoked account.
	codeCustomerNotOnboarded = "customer_not_onboarded"
	// codeSessionInvalid: the bearer token is missing, malformed, tampered with or expired (cloud#78).
	// Distinct from invalid_credentials / wechat_code_invalid, which are login attempts.
	codeSessionInvalid = "session_invalid"
)

// Codes that tell the outcomes of a telemetry read apart (cloud#79, cloud#28). All three are only reachable
// after the ownership check, so they reveal nothing to a caller who may not touch the device. The
// statuses are unchanged; clients that ignore `code` keep working.
const (
	codeTelemetryNotReported      = "telemetry_not_reported"      // authorized, provisioned, never reported
	codeTelemetryExpired          = "telemetry_expired"           // reported once, but longer ago than ThingsBoard keeps the snapshot (cloud#28); body carries lastReportedAt
	codeDeviceNotProvisioned      = "device_not_provisioned"      // registered here, absent from ThingsBoard
	codeDevicePlatformUnavailable = "device_platform_unavailable" // ThingsBoard unreachable
)

// msgSessionInvalid is the one text for every token-caused 401, on HTTP routes and /ws/alarms alike.
const msgSessionInvalid = "登录失效，请重新登录"

// abortSessionInvalid ends a request whose token cannot be used. The 401 status stays: clients treat a
// 401 on a protected route as "session over".
func abortSessionInvalid(c *gin.Context) {
	c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": msgSessionInvalid, "code": codeSessionInvalid})
}

// amr values: how the session's authAt was earned.
const (
	amrPassword = "pwd"
	amrWeChat   = "wx"
)

// apiClaims carries the user's id alongside the standard claims. The Role claim is still issued, but
// nothing trusts it any more: authMiddleware loads the user row on every request and puts *that*
// role into the request context. It used to be trusted (to save a query), which meant a demoted
// administrator kept administrator-level control methods until their token expired.
//
// Subject is the user's id as a decimal string. It used to be the username; customer-side accounts
// have no username of their own any more (their "username" is the phone number, which we do not
// want sitting in every token), so clients must read identity from the login response or
// GET /api/me, never from this claim.
type apiClaims struct {
	UserID int64  `json:"uid"`
	Role   string `json:"role"`
	// AuthAt is when the user last authenticated in full — typed their password, or had a WeChat
	// code verified (unix seconds). Renewal carries it forward unchanged, which is what lets
	// MaxSessionAge bound a sliding session. Tokens issued before this claim existed lack it;
	// sessionStart falls back to their iat.
	AuthAt int64 `json:"authAt,omitempty"`
	// AMR is how that authentication happened ("pwd" or "wx"). Informational: nothing authorizes on it.
	AMR string `json:"amr,omitempty"`
	jwt.RegisteredClaims
}

// dummyHash is compared against when there is no usable password hash, so that "no such account",
// "wrong role" and "wrong password" cost the same bcrypt and cannot be told apart by timing.
var (
	dummyHashOnce sync.Once
	dummyHash     []byte
)

func timingDummyHash() []byte {
	dummyHashOnce.Do(func() {
		dummyHash, _ = bcrypt.GenerateFromPassword([]byte("timing-equalizer"), bcrypt.DefaultCost)
	})
	return dummyHash
}

// customerNotOnboarded reports whether user belongs to a customer that has not completed onboarding.
// Platform admins have no customer and are never blocked. Callers must have authenticated the user
// first, so the answer cannot be used to probe which accounts or customers exist.
func customerNotOnboarded(ctx context.Context, q *db.Queries, user db.User) (bool, error) {
	if user.Role == control.RolePlatformAdmin || !user.CustomerID.Valid {
		return false, nil
	}
	cust, err := q.GetCustomer(ctx, user.CustomerID.Int64)
	if err != nil {
		return false, err // fail closed: an unreadable status must not become a login
	}
	return cust.OnboardingStatus != customerActive, nil
}

const customerActive = "active"

func abortNotOnboarded(c *gin.Context) {
	c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "customer not onboarded", "code": codeCustomerNotOnboarded})
}

// apiError writes {"error": msg, "code": code}. New auth responses carry a code; older endpoints keep
// their error-only body.
func apiError(c *gin.Context, status int, code, msg string) {
	c.JSON(status, gin.H{"error": msg, "code": code})
}

func loginHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req loginRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			apiError(c, http.StatusBadRequest, codeInvalidRequest, err.Error())
			return
		}
		byUsername := strings.TrimSpace(req.Username) != ""
		byPhone := strings.TrimSpace(req.Phone) != ""
		if byUsername == byPhone || req.Password == "" {
			apiError(c, http.StatusBadRequest, codeInvalidRequest, "send password and exactly one of username or phone")
			return
		}

		ctx := c.Request.Context()
		var (
			user  db.User
			found bool
			// phoneLogin selects which accounts this field may log in as. phone: a customer admin or staff member
			// with a mainland number and a password. username: any account that has no phone (platform admins and username
			// accounts, cloud#77) — never a phone account, whose username column merely mirrors its number.
			phoneLogin bool
			acctKey    string
			err        error
		)
		if byPhone {
			phone, ok := normalizePhone(req.Phone)
			if !ok {
				apiError(c, http.StatusBadRequest, codeInvalidPhone, "invalid phone number")
				return
			}
			phoneLogin = true
			acctKey = phoneLoginKey(phone)
			user, err = deps.Queries.GetUserByPhone(ctx, pgtype.Text{String: phone, Valid: true})
		} else {
			acctKey = usernameLoginKey(req.Username)
			user, err = deps.Queries.GetUserByUsername(ctx, strings.TrimSpace(req.Username))
		}
		acctCounter, ipCounter := loginKeys(c, acctKey)
		// Before the credentials are looked at, and for unknown identifiers too (see loginlimit.go).
		if wait := loginBlocked(ctx, deps, acctCounter, ipCounter); wait > 0 {
			c.Header("Retry-After", strconv.Itoa(int(wait.Seconds())+1))
			apiError(c, http.StatusTooManyRequests, codeLoginRateLimited, "too many failed attempts; try again later")
			return
		}
		switch {
		case err == nil:
			found = true
		case errors.Is(err, pgx.ErrNoRows):
		default:
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		hash := timingDummyHash()
		var roleOK bool
		if phoneLogin {
			// A phone staff member only gets here once an administrator has set a password (cloud#77).
			roleOK = found && (user.Role == control.RoleCustomerAdmin || user.Role == control.RoleCustomerUser)
		} else {
			roleOK = found && !user.Phone.Valid
		}
		// A staff account without a password (phone-based, WeChat only) has no hash and is never usable.
		usable := roleOK && user.PasswordHash.Valid
		if usable {
			hash = []byte(user.PasswordHash.String)
		}
		// Always one bcrypt, whatever the account. Disabled and wrong-role accounts get the same
		// answer as a wrong password, and only after the comparison, so the response does not reveal
		// which accounts exist, are staff, or are disabled.
		if bcrypt.CompareHashAndPassword(hash, []byte(req.Password)) != nil || !usable || user.DisabledAt.Valid {
			loginFailed(ctx, deps, acctCounter, ipCounter)
			apiError(c, http.StatusUnauthorized, codeInvalidCredentials, "invalid username or password")
			return
		}
		loginSucceeded(ctx, deps, acctCounter)

		// After the credentials are proven, so this answer is only ever given to the account's owner.
		if blocked, err := customerNotOnboarded(ctx, deps.Queries, user); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		} else if blocked {
			abortNotOnboarded(c)
			return
		}

		now := time.Now()
		token, _, err := issueToken(deps, user.ID, user.Role, amrPassword, now, now)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"token": token, "user": toUserResponse(user, true)})
	}
}

// meHandler returns the caller's own record. It exists because the token no longer says who the user
// is in a form clients may show or key local storage by (see apiClaims.Subject).
func meHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		id, _ := c.Get("userID")
		user, err := deps.Queries.GetUserByID(c.Request.Context(), id.(int64))
		if err != nil {
			// authMiddleware has just loaded this row; a miss here means it was deleted in between.
			if errors.Is(err, pgx.ErrNoRows) {
				c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "not authorized"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, toUserResponse(user, true))
	}
}

// issueToken signs a token for a user whose session began at authAt. The expiry is a full tokenTTL
// from now, but never past authAt+MaxSessionAge: that cap is what makes "renew while in use" safe.
func issueToken(deps Deps, userID int64, role, amr string, authAt, now time.Time) (string, time.Time, error) {
	exp := now.Add(deps.tokenTTL())
	if limit := authAt.Add(deps.maxSessionAge()); limit.Before(exp) {
		exp = limit
	}
	claims := apiClaims{
		UserID: userID,
		Role:   role,
		AuthAt: authAt.Unix(),
		AMR:    amr,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   strconv.FormatInt(userID, 10),
			ExpiresAt: jwt.NewNumericDate(exp),
			IssuedAt:  jwt.NewNumericDate(now),
		},
	}
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(deps.JWTSecret)
	return token, exp, err
}

// renewToken returns a replacement for a still-valid token that is in the last half of its life, or
// "" when no renewal is due. Callers must already have checked that the user exists and is enabled.
// It never extends a session past authAt+MaxSessionAge: a token that cannot gain time is not renewed.
func renewToken(deps Deps, claims *apiClaims, userID int64, role string, now time.Time) string {
	if claims.ExpiresAt == nil {
		return ""
	}
	exp := claims.ExpiresAt.Time
	if exp.Sub(now) >= deps.tokenTTL()/2 {
		return ""
	}
	authAt, ok := claims.sessionStart()
	if !ok {
		return ""
	}
	token, newExp, err := issueToken(deps, userID, role, claims.AMR, authAt, now)
	if err != nil {
		// Renewal is a convenience; the request itself was authorized and should still succeed.
		log.Printf("auth: renew token for user %d: %v", userID, err)
		return ""
	}
	if !newExp.After(exp) {
		return ""
	}
	return token
}

// sessionStart is when the full authentication (password or WeChat) behind this token happened.
func (c *apiClaims) sessionStart() (time.Time, bool) {
	if c.AuthAt > 0 {
		return time.Unix(c.AuthAt, 0), true
	}
	if c.IssuedAt != nil {
		return c.IssuedAt.Time, true
	}
	return time.Time{}, false
}

// authMiddleware checks the bearer token and then the user's *current* row: a user who has been
// deleted or disabled is refused on every protected route, whatever their still-unexpired token says.
// Doing it here, once, rather than in each handler is deliberate — "disabled takes effect
// immediately" has to hold for routes that are added later too, and a per-handler check is exactly
// the kind of thing that gets forgotten (unbind was).
func authMiddleware(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		raw, ok := strings.CutPrefix(header, "Bearer ")
		if !ok || raw == "" {
			abortSessionInvalid(c)
			return
		}

		claims := &apiClaims{}
		token, err := jwt.ParseWithClaims(raw, claims, func(t *jwt.Token) (interface{}, error) {
			return deps.JWTSecret, nil
		}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
		if err != nil || !token.Valid {
			abortSessionInvalid(c)
			return
		}

		user, err := deps.Queries.GetUserByID(c.Request.Context(), claims.UserID)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "not authorized"})
				return
			}
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if user.DisabledAt.Valid {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "not authorized"})
			return
		}

		if blocked, err := customerNotOnboarded(c.Request.Context(), deps.Queries, user); err != nil {
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		} else if blocked {
			abortNotOnboarded(c)
			return
		}

		// Only after the user checks above: a disabled or deleted user never gets a fresh token.
		if renewed := renewToken(deps, claims, user.ID, user.Role, time.Now()); renewed != "" {
			c.Header(renewedTokenHeader, renewed)
		}

		// From the database row, not the claims: a role change must apply to the very next request.
		c.Set("username", user.Username)
		c.Set("userID", user.ID)
		c.Set("role", user.Role)
		c.Next()
	}
}
