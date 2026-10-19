package api

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"golang.org/x/crypto/bcrypt"

	"yeweiyi-cloud/internal/store/db"
)

const (
	codeWrongPassword     = "wrong_password"
	codePasswordUnchanged = "password_unchanged"
)

type changePasswordRequest struct {
	OldPassword string `json:"oldPassword"`
	NewPassword string `json:"newPassword"`
}

// changePasswordHandler lets an account with a password change its own (cloud#77). A wrong old
// password is a 400 `wrong_password`, deliberately not a 401: clients read a 401 on a protected route
// as "the session is over" and would log the user out for a typo. Guessing the old password is
// throttled like guessing at login, since a stolen token must not be a way around that limit.
func changePasswordHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req changePasswordRequest
		if err := c.ShouldBindJSON(&req); err != nil || req.OldPassword == "" || req.NewPassword == "" {
			apiError(c, http.StatusBadRequest, codeInvalidRequest, "send oldPassword and newPassword")
			return
		}
		if !validPassword(req.NewPassword) {
			apiError(c, http.StatusBadRequest, "invalid_password", "password must be 8-72 bytes")
			return
		}
		if req.OldPassword == req.NewPassword {
			apiError(c, http.StatusBadRequest, codePasswordUnchanged, "the new password must differ from the old one")
			return
		}

		id, _ := c.Get("userID")
		ctx := c.Request.Context()
		user, err := deps.Queries.GetUserByID(ctx, id.(int64))
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "not authorized"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if !user.PasswordHash.Valid {
			apiError(c, http.StatusConflict, codeNoPasswordForRole, "this account has no password")
			return
		}

		acctCounter, ipCounter := loginKeys(c, userIDLoginKey(user.ID))
		if wait := loginBlocked(ctx, deps, acctCounter, ipCounter); wait > 0 {
			c.Header("Retry-After", strconv.Itoa(int(wait.Seconds())+1))
			apiError(c, http.StatusTooManyRequests, codeLoginRateLimited, "too many failed attempts; try again later")
			return
		}
		if bcrypt.CompareHashAndPassword([]byte(user.PasswordHash.String), []byte(req.OldPassword)) != nil {
			loginFailed(ctx, deps, acctCounter, ipCounter)
			apiError(c, http.StatusBadRequest, codeWrongPassword, "the current password is wrong")
			return
		}
		hash, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		var updated db.User
		err = withTx(ctx, deps, func(q *db.Queries) error {
			// Lock and compare again: a reset or a concurrent change since the check above must not be
			// overwritten by a password the caller proved knowledge of an older one for.
			cur, err := q.GetUserForUpdate(ctx, user.ID)
			if err != nil {
				return err
			}
			if cur.DisabledAt.Valid || cur.PasswordHash != user.PasswordHash {
				return verdictError{status: http.StatusConflict, msg: "the password changed meanwhile; sign in again", code: "password_changed"}
			}
			if _, err := q.SetUserPassword(ctx, db.SetUserPasswordParams{
				ID: user.ID, PasswordHash: pgtype.Text{String: string(hash), Valid: true}, MustChangePassword: false,
			}); err != nil {
				return err
			}
			updated = cur
			updated.MustChangePassword = false
			// Neither password is recorded.
			return auditAdmin(ctx, q, cur, "user.change-password", "", cur.CustomerID, "allowed",
				map[string]any{"userId": cur.ID})
		})
		if err != nil {
			var v verdictError
			if errors.As(err, &v) {
				apiError(c, v.status, v.code, v.msg)
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		loginSucceeded(ctx, deps, acctCounter)
		c.JSON(http.StatusOK, toUserResponse(updated, true))
	}
}
