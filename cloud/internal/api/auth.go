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
// care). Production sets it from config — see config.example.yaml's auth.tokenTTL. The token only
// proves who the caller is; their role, customer and disabled flag are read from the database on
// every request (authMiddleware), so none of those changes waits for a token to expire.
const defaultTokenTTL = 24 * time.Hour

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

		// From the database row, not the claims: a role change must apply to the very next request.
		c.Set("username", user.Username)
		c.Set("userID", user.ID)
		c.Set("role", user.Role)
		c.Next()
	}
}
