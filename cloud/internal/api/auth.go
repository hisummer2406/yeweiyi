package api

import (
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5"
	"golang.org/x/crypto/bcrypt"
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

type loginRequest struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
}

// apiClaims carries the user's id alongside the standard claims. The Role claim is still issued, but
// nothing trusts it any more: authMiddleware loads the user row on every request and puts *that*
// role into the request context. It used to be trusted (to save a query), which meant a demoted
// administrator kept administrator-level control methods until their token expired.
type apiClaims struct {
	UserID int64  `json:"uid"`
	Role   string `json:"role"`
	// AuthAt is when the user last typed their password (unix seconds). Renewal carries it forward
	// unchanged, which is what lets MaxSessionAge bound a sliding session. Tokens issued before this
	// claim existed lack it; sessionStart falls back to their iat.
	AuthAt int64 `json:"authAt,omitempty"`
	jwt.RegisteredClaims
}

func loginHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req loginRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		user, err := deps.Queries.GetUserByUsername(c.Request.Context(), req.Username)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid username or password"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)); err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid username or password"})
			return
		}
		// A disabled user gets the same answer as a wrong password, and only after the password was
		// checked, so the response does not reveal which accounts exist or are disabled.
		if user.DisabledAt.Valid {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid username or password"})
			return
		}

		now := time.Now()
		token, _, err := issueToken(deps, user.ID, user.Username, user.Role, now, now)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"token": token})
	}
}

// issueToken signs a token for a user whose session began at authAt. The expiry is a full tokenTTL
// from now, but never past authAt+MaxSessionAge: that cap is what makes "renew while in use" safe.
func issueToken(deps Deps, userID int64, username, role string, authAt, now time.Time) (string, time.Time, error) {
	exp := now.Add(deps.tokenTTL())
	if limit := authAt.Add(deps.maxSessionAge()); limit.Before(exp) {
		exp = limit
	}
	claims := apiClaims{
		UserID: userID,
		Role:   role,
		AuthAt: authAt.Unix(),
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   username,
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
func renewToken(deps Deps, claims *apiClaims, userID int64, username, role string, now time.Time) string {
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
	token, newExp, err := issueToken(deps, userID, username, role, authAt, now)
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

// sessionStart is when the password login behind this token happened.
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
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing bearer token"})
			return
		}

		claims := &apiClaims{}
		token, err := jwt.ParseWithClaims(raw, claims, func(t *jwt.Token) (interface{}, error) {
			return deps.JWTSecret, nil
		}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired token"})
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

		// Only after the user checks above: a disabled or deleted user never gets a fresh token.
		if renewed := renewToken(deps, claims, user.ID, user.Username, user.Role, time.Now()); renewed != "" {
			c.Header(renewedTokenHeader, renewed)
		}

		// From the database row, not the claims: a role change must apply to the very next request.
		c.Set("username", user.Username)
		c.Set("userID", user.ID)
		c.Set("role", user.Role)
		c.Next()
	}
}
