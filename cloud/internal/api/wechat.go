package api

import (
	"errors"
	"log"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/wechat"
)

// Codes of the WeChat login responses (contract §4.3, §4.4). None of them is a 403 "not authorized":
// clients treat that body as "your account is gone" and log the user out, which is wrong for a
// login attempt that simply was not accepted.
const (
	codeWeChatUnavailable   = "wechat_unavailable"
	codeWeChatUpstream      = "wechat_upstream_error"
	codeWeChatCodeInvalid   = "wechat_code_invalid"
	codeWeChatNotBound      = "wechat_not_bound"
	codeWeChatConflict      = "wechat_identity_conflict"
	codePhoneNotProvisioned = "phone_not_provisioned"
	codePhoneUnsupported    = "phone_unsupported"
	codeAccountDisabled     = "account_disabled"
)

type wechatLoginRequest struct {
	LoginCode string `json:"loginCode"`
	PhoneCode string `json:"phoneCode"`
}

// weChatCallFailed turns an error from the WeChat client into the contract's response.
func weChatCallFailed(c *gin.Context, err error) {
	switch {
	case errors.Is(err, wechat.ErrInvalidCode), errors.Is(err, wechat.ErrAppMismatch):
		apiError(c, http.StatusUnauthorized, codeWeChatCodeInvalid, "the WeChat code is invalid, expired or already used")
	default:
		// Logged without the error text's payload: wechat.* errors carry no URL or secret by design.
		log.Printf("wechat: upstream failure: %v", err)
		apiError(c, http.StatusBadGateway, codeWeChatUpstream, "WeChat could not be reached")
	}
}

// signInWeChatUser issues the session for a user whose WeChat identity was just verified. This is a
// full authentication, so authAt is now (contract §3.2).
func signInWeChatUser(c *gin.Context, deps Deps, user db.User) {
	now := time.Now()
	token, _, err := issueToken(deps, user.ID, user.Role, amrWeChat, now, now)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"token": token, "user": toUserResponse(user, true)})
}

// wechatLoginHandler restores a session for an already-bound WeChat identity. It never creates or
// binds anything: an unbound identity is told to run the phone authorization instead.
func wechatLoginHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if deps.WeChat == nil {
			apiError(c, http.StatusServiceUnavailable, codeWeChatUnavailable, "WeChat login is not enabled on this server")
			return
		}
		var req wechatLoginRequest
		if err := c.ShouldBindJSON(&req); err != nil || req.LoginCode == "" {
			apiError(c, http.StatusBadRequest, codeInvalidRequest, "loginCode is required")
			return
		}
		ctx := c.Request.Context()

		openid, err := deps.WeChat.OpenID(ctx, req.LoginCode)
		if err != nil {
			weChatCallFailed(c, err)
			return
		}
		identity, err := deps.Queries.GetWechatIdentity(ctx, db.GetWechatIdentityParams{
			Appid: deps.WeChat.AppID(), Openid: openid,
		})
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				apiError(c, http.StatusUnauthorized, codeWeChatNotBound, "this WeChat account is not bound to a user yet")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		user, err := deps.Queries.GetUserByID(ctx, identity.UserID)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) { // cannot happen (ON DELETE CASCADE), but fail closed
				apiError(c, http.StatusUnauthorized, codeWeChatNotBound, "this WeChat account is not bound to a user yet")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if user.DisabledAt.Valid {
			apiError(c, http.StatusForbidden, codeAccountDisabled, "this account has been disabled")
			return
		}
		if err := deps.Queries.TouchWechatLogin(ctx, identity.ID); err != nil {
			log.Printf("wechat: touch last_login for identity %d: %v", identity.ID, err)
		}
		signInWeChatUser(c, deps, user)
	}
}

// bindOutcome is what the binding transaction decided; anything but bindOK rolls back.
type bindOutcome int

const (
	bindOK bindOutcome = iota
	bindNotProvisioned
	bindDisabled
	bindConflict
)

type bindResult struct {
	outcome bindOutcome
	user    db.User
}

// wechatPhoneLoginHandler is the first-time flow: the user authorizes their phone number in the
// mini program, the server verifies both codes with WeChat, and binds the openid to the account that
// was provisioned for that number. It never creates an account and never moves an identity.
func wechatPhoneLoginHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if deps.WeChat == nil {
			apiError(c, http.StatusServiceUnavailable, codeWeChatUnavailable, "WeChat login is not enabled on this server")
			return
		}
		// Only the two codes are read. There is no field to send a phone number or openid in, so a
		// client cannot assert either: both come from WeChat.
		var req wechatLoginRequest
		if err := c.ShouldBindJSON(&req); err != nil || req.LoginCode == "" || req.PhoneCode == "" {
			apiError(c, http.StatusBadRequest, codeInvalidRequest, "loginCode and phoneCode are required")
			return
		}
		ctx := c.Request.Context()

		// Both WeChat calls happen before any row is locked: the lock is held only for DB work.
		openid, err := deps.WeChat.OpenID(ctx, req.LoginCode)
		if err != nil {
			weChatCallFailed(c, err)
			return
		}
		verified, err := deps.WeChat.PhoneNumber(ctx, req.PhoneCode)
		if err != nil {
			weChatCallFailed(c, err)
			return
		}
		if verified.CountryCode != "" && verified.CountryCode != "86" {
			apiError(c, http.StatusBadRequest, codePhoneUnsupported, "only mainland China phone numbers are supported")
			return
		}
		phone, ok := normalizePhone(verified.Pure)
		if !ok {
			apiError(c, http.StatusBadRequest, codePhoneUnsupported, "only mainland China phone numbers are supported")
			return
		}
		appid := deps.WeChat.AppID()

		var res bindResult
		err = withTx(ctx, deps, func(q *db.Queries) error {
			user, err := q.GetUserByPhoneForUpdate(ctx, pgtype.Text{String: phone, Valid: true})
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					res = bindResult{outcome: bindNotProvisioned}
					return nil
				}
				return err
			}
			res.user = user
			// A phone number only ever belongs to a customer-side account (users_phone_role_check);
			// the role test is belt and braces.
			if user.Role == control.RolePlatformAdmin {
				res.outcome = bindNotProvisioned
				return nil
			}
			if user.DisabledAt.Valid {
				res.outcome = bindDisabled
				return nil
			}

			// Is this openid already someone's, or this user already someone's?
			byOpenID, errOpen := q.GetWechatIdentity(ctx, db.GetWechatIdentityParams{Appid: appid, Openid: openid})
			if errOpen != nil && !errors.Is(errOpen, pgx.ErrNoRows) {
				return errOpen
			}
			_, errUser := q.GetWechatIdentityByUser(ctx, user.ID)
			if errUser != nil && !errors.Is(errUser, pgx.ErrNoRows) {
				return errUser
			}
			switch {
			case errOpen == nil && byOpenID.UserID == user.ID:
				// Same user, same openid: a retry, or a re-authorization. Idempotent success.
				return q.TouchWechatLogin(ctx, byOpenID.ID)
			case errOpen == nil || errUser == nil:
				// The openid belongs to another user, or this user has another openid. Never
				// overwritten, never moved: that is how an account would be taken over.
				res.outcome = bindConflict
				return nil
			}
			if _, err := q.CreateWechatIdentity(ctx, db.CreateWechatIdentityParams{
				UserID: user.ID, Appid: appid, Openid: openid,
			}); err != nil {
				return err
			}
			// Neither the openid nor the phone number goes into the audit detail.
			return auditAdmin(ctx, q, user, "auth.wechat_bind", "", user.CustomerID, "allowed",
				map[string]any{"userId": user.ID, "role": user.Role})
		})
		if err != nil {
			if isPgCode(err, "23505") {
				// A concurrent first login for the same openid or user won the UNIQUE race and
				// committed after our reads. We cannot tell whose it is from here without retrying
				// the whole decision, so refuse; the client's retry takes the idempotent path.
				apiError(c, http.StatusConflict, codeWeChatConflict, "this WeChat account or user is already bound; please retry")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		switch res.outcome {
		case bindNotProvisioned:
			apiError(c, http.StatusNotFound, codePhoneNotProvisioned, "this phone number has no account; contact your administrator")
		case bindDisabled:
			apiError(c, http.StatusForbidden, codeAccountDisabled, "this account has been disabled")
		case bindConflict:
			if err := auditAdmin(ctx, deps.Queries, res.user, "auth.wechat_bind", "", res.user.CustomerID, "denied",
				map[string]any{"userId": res.user.ID, "reason": "identity conflict"}); err != nil {
				log.Printf("wechat: audit identity conflict for user %d: %v", res.user.ID, err)
			}
			apiError(c, http.StatusConflict, codeWeChatConflict, "this WeChat account or user is already bound to a different identity")
		default:
			signInWeChatUser(c, deps, res.user)
		}
	}
}
