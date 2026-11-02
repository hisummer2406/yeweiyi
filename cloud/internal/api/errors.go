package api

import (
	"log"
	"net/http"

	"github.com/gin-gonic/gin"
)

// Codes added in the third #79 batch. The rest of the table lives next to the handlers that
// first used them (auth.go, admin.go, wechat.go, …); docs/error-codes-contract.md is the index.
const (
	codeInternalError        = "internal_error"
	codeTelemetryUnparsable  = "telemetry_unparsable"
	codeDeviceNotAuthorized  = "device_not_authorized"
	codeCommandNotAuthorized = "command_not_authorized"
	codePlatformAdminOnly    = "platform_admin_required"
	codeUnknownMethod        = "unknown_method"
	codeReasonRequired       = "reason_required"
	codePhysicalDisabled     = "physical_commands_disabled"
	// codeCommandDeliveryUnconfirmed: ThingsBoard failed while the command was being handed over.
	// It may or may not have reached the device; never present it as "failed" or "not sent".
	codeCommandDeliveryUnconfirmed = "command_delivery_unconfirmed"

	// The bare 403 {"error":"not authorized"} keeps its body, which clients match exactly to end the
	// session; these codes only tell a newer client which of the two cases it is.
	codeAccountUnavailable = "account_unavailable" // caller deleted or disabled: end the session
	codeRoleNotAllowed     = "role_not_allowed"    // caller is fine, their role may not do this: keep the session
)

const notAuthorized = "not authorized"

// internalErrorMessage is the only text a 500 carries. The underlying error (SQL, driver,
// ThingsBoard response bodies) goes to the log: it is useless to an end user and it can name
// tables, constraints or upstream hosts (#79: 不直接显示英文异常、技术栈信息或底层错误详情).
const internalErrorMessage = "internal server error"

// internalError logs err and answers 500 with a stable code and a safe message. It aborts, so it is
// also correct inside middleware; in a terminal handler aborting changes nothing.
func internalError(c *gin.Context, err error) {
	log.Printf("api: %s %s: %v", c.Request.Method, c.FullPath(), err)
	c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": internalErrorMessage, "code": codeInternalError})
}

// forbidAccount answers the bare 403 for a caller whose account is gone or disabled.
func forbidAccount(c *gin.Context) {
	c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": notAuthorized, "code": codeAccountUnavailable})
}

// refusalCode tells a role refusal from a dead account by reading the caller's row now. Refusals
// decided inside a transaction ("authority changed") may be either: the caller was demoted, or
// disabled, while the request ran. When the row cannot be read the answer is account_unavailable,
// which is what every client did with this body before the codes existed.
func refusalCode(c *gin.Context, deps Deps, actorID int64) string {
	if deps.Queries == nil {
		return codeAccountUnavailable
	}
	u, err := deps.Queries.GetUserByID(c.Request.Context(), actorID)
	if err != nil || u.DisabledAt.Valid {
		return codeAccountUnavailable
	}
	return codeRoleNotAllowed
}

// badRequest answers a request body or query that failed to bind. The binder's text names the
// offending field, which is what a developer wiring up a client needs; clients branch on the code.
func badRequest(c *gin.Context, err error) {
	apiError(c, http.StatusBadRequest, codeInvalidRequest, err.Error())
}

// writeTankIndexError maps deviceTankIndexes' status onto the same codes telemetry reads use, so
// "device not in ThingsBoard" and "ThingsBoard down" read the same on every device-data path.
func writeTankIndexError(c *gin.Context, status int, err error) {
	switch status {
	case http.StatusNotFound:
		apiError(c, status, codeDeviceNotProvisioned, "the device is not provisioned in ThingsBoard")
	case http.StatusServiceUnavailable:
		apiError(c, status, codeDevicePlatformUnavailable, "the device platform is temporarily unavailable; retry shortly")
	default:
		internalError(c, err)
	}
}
