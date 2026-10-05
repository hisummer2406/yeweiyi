package api

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5"
	"golang.org/x/crypto/bcrypt"
)

// defaultTokenTTL is used when Deps.TokenTTL is left zero (tests, and any caller that does not
// care). Production sets it from config — see config.example.yaml's auth.tokenTTL, which also
// explains the one behavioural consequence: the user's role travels inside the token, so a role
// change does not take effect until the token expires or the user logs in again.
const defaultTokenTTL = 24 * time.Hour

type loginRequest struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password" binding:"required"`
}

// apiClaims carries the user's id alongside the standard claims, added 2026-08-29 so
// requireDeviceOwnership (devices.go) can check user_devices without an extra GetUserByUsername
// round trip per request. Role was added 2026-09-01 for the same reason: control authorization
// (internal/control) is role-tiered, and re-reading the user on every command would put a database
// round trip in front of every control action.
//
// Consequence worth knowing: a role change does not take effect until the user's next login, since
// the old token keeps its old role until it expires (tokenTTL, 24h). Acceptable for role
// *promotion*; if a demotion ever needs to be immediate, this is the thing to revisit.
type apiClaims struct {
	UserID int64  `json:"uid"`
	Role   string `json:"role"`
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

		claims := apiClaims{
			UserID: user.ID,
			Role:   user.Role,
			RegisteredClaims: jwt.RegisteredClaims{
				Subject:   user.Username,
				ExpiresAt: jwt.NewNumericDate(time.Now().Add(deps.tokenTTL())),
				IssuedAt:  jwt.NewNumericDate(time.Now()),
			},
		}
		token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(deps.JWTSecret)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"token": token})
	}
}

func authMiddleware(secret []byte) gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		raw, ok := strings.CutPrefix(header, "Bearer ")
		if !ok || raw == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing bearer token"})
			return
		}

		claims := &apiClaims{}
		token, err := jwt.ParseWithClaims(raw, claims, func(t *jwt.Token) (interface{}, error) {
			return secret, nil
		}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired token"})
			return
		}

		c.Set("username", claims.Subject)
		c.Set("userID", claims.UserID)
		c.Set("role", claims.Role)
		c.Next()
	}
}
