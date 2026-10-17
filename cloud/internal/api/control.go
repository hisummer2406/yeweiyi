package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// Remote-control REST surface (GitHub issue #10). These are the endpoints the web console's
// 二次确认 dialog calls.
//
// Authorization is not repeated here: internal/control does the whole check — role tier, device
// binding, and the physical-action interlock gate — so there is exactly one place where a control
// action can be permitted, rather than a handler and a service that must agree. This handler's job
// is to translate control's errors into status codes and nothing else.

type issueCommandRequest struct {
	Method string          `json:"method" binding:"required"`
	Args   json.RawMessage `json:"args"`
	// Reason is the operator's 二次确认 justification. Required by the protocol, by the database
	// constraint, and by internal/control — three layers, because an unexplained remote action on
	// fuel infrastructure is the thing the audit trail exists to prevent.
	Reason string `json:"reason" binding:"required"`
}

// commandResponse is the wire shape for a control command. It exists rather than returning the sqlc
// row directly because that row's pgtype fields marshal into unhelpful {"Time":...,"Valid":true}
// objects, and its `args`/`result_detail` []byte fields would be base64-encoded by encoding/json.
type commandResponse struct {
	CommandID    string          `json:"commandId"`
	DeviceName   string          `json:"deviceName"`
	Method       string          `json:"method"`
	Args         json.RawMessage `json:"args,omitempty"`
	Operator     string          `json:"operator"`
	Reason       string          `json:"reason"`
	Status       string          `json:"status"`
	Terminal     bool            `json:"terminal"`
	ErrorCode    string          `json:"errorCode,omitempty"`
	ErrorReason  string          `json:"errorReason,omitempty"`
	ResultDetail json.RawMessage `json:"resultDetail,omitempty"`
	IssuedAt     *time.Time      `json:"issuedAt,omitempty"`
	ExpiresAt    *time.Time      `json:"expiresAt,omitempty"`
	SentAt       *time.Time      `json:"sentAt,omitempty"`
	AckedAt      *time.Time      `json:"ackedAt,omitempty"`
	FinishedAt   *time.Time      `json:"finishedAt,omitempty"`
}

func toCommandResponse(c db.ControlCommand) commandResponse {
	return commandResponse{
		CommandID:  uuidString(c.CommandID),
		DeviceName: c.DeviceName,
		Method:     c.Method,
		Args:       json.RawMessage(c.Args),
		Operator:   c.Operator,
		Reason:     c.Reason,
		Status:     c.Status,
		// Terminal is computed server-side so a client never has to hard-code the status list to
		// know whether to keep polling.
		Terminal:     protocol.IsTerminal(c.Status),
		ErrorCode:    c.ErrorCode.String,
		ErrorReason:  c.ErrorReason.String,
		ResultDetail: json.RawMessage(c.ResultDetail),
		IssuedAt:     timePtr(c.IssuedAt),
		ExpiresAt:    timePtr(c.ExpiresAt),
		SentAt:       timePtr(c.SentAt),
		AckedAt:      timePtr(c.AckedAt),
		FinishedAt:   timePtr(c.FinishedAt),
	}
}

// issueCommandHandler is POST /api/devices/:device/commands.
//
// It returns 202 Accepted, never 200: by design the response only reports how far the command got
// synchronously (received, or rejected). Whether the action succeeded arrives asynchronously and is
// read back through GET. Returning 200 would invite clients to treat the response as "it worked".
func issueCommandHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if deps.Control == nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "remote control is not configured on this deployment"})
			return
		}

		var req issueCommandRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		actor, ok := actorFrom(c)
		if !ok {
			return
		}

		cmd, err := deps.Control.Issue(c.Request.Context(), control.IssueRequest{
			DeviceName: c.Param("device"),
			Method:     req.Method,
			Args:       req.Args,
			Reason:     req.Reason,
			Actor:      actor,
		})
		if err != nil {
			writeControlError(c, err)
			return
		}

		c.JSON(http.StatusAccepted, toCommandResponse(cmd))
	}
}

// writeControlError maps control's sentinel errors onto status codes.
func writeControlError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, control.ErrForbidden):
		// Deliberately indistinguishable from "no such device" — see control.ErrForbidden's
		// comment: separate codes would let a caller enumerate device names.
		c.JSON(http.StatusForbidden, gin.H{"error": "not authorized for this device or method"})
	case errors.Is(err, control.ErrPhysicalCommandsDisabled):
		// 409 rather than 403: the caller may well be entitled to this method; the deployment is
		// what is refusing, until the device-side interlocks are verified.
		c.JSON(http.StatusConflict, gin.H{
			"error": err.Error(),
			"hint":  "physical control actions are disabled until device-side interlocks are verified on real hardware",
		})
	case errors.Is(err, control.ErrInvalidArgs):
		var ae *protocol.ArgsError
		if errors.As(err, &ae) {
			c.JSON(http.StatusBadRequest, gin.H{"error": ae.Error(), "code": "invalid_args", "fields": ae.Fields})
			return
		}
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error(), "code": "invalid_args"})
	case errors.Is(err, control.ErrUnknownMethod), errors.Is(err, control.ErrReasonRequired):
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
	case errors.Is(err, control.ErrAuditUnavailable):
		// 503 for the same reason: nothing was sent, and trying again is the right response.
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "the audit trail is unavailable, so the command was not sent; try again"})
	case errors.Is(err, control.ErrPlatformUnavailable):
		// 503, and explicitly not 500: this says "the device platform is down, retry later",
		// which is the degradation behaviour issue #10 asks to be well-defined.
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": err.Error()})
	default:
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
	}
}

// listDeviceCommandsHandler is GET /api/devices/:device/commands — the status-polling endpoint.
func listDeviceCommandsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		if !requireDeviceOwnership(c, deps, device) {
			return
		}

		limit, offset := pageParams(c, 50)

		// Ownership above decides the response code (403 vs a list). What the user may actually read
		// is decided again inside the query, in the statement that reads it — access and customer
		// scope from one database state — so a user moved between the two cannot get another
		// customer's history for a device they can no longer reach.
		rows, err := deps.Queries.ListControlCommandsVisibleToUser(c.Request.Context(), db.ListControlCommandsVisibleToUserParams{
			UserID:     c.MustGet("userID").(int64),
			DeviceName: device,
			Lim:        limit,
			Off:        offset,
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		out := make([]commandResponse, 0, len(rows))
		for _, r := range rows {
			out = append(out, toCommandResponse(r))
		}
		c.JSON(http.StatusOK, gin.H{"commands": out})
	}
}

// listAuditHandler is GET /api/audit.
//
// Scope, decided from the user's current role and customer in the database:
//   - platform admin: everything, optionally narrowed by ?customerId= and/or ?device=;
//   - customer admin: their own customer's audit trail, with or without ?device=;
//   - customer user: must name a device they may access.
//
// Every non-platform caller is pinned to their own customer even when a device is named, so a
// device that changed hands never shows its previous owner's history to the new one. There is no
// unfiltered listing for anyone but a platform admin, because the audit log spans customers.
func listAuditHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		ctx := c.Request.Context()
		user, err := deps.Queries.GetUserByID(ctx, c.MustGet("userID").(int64))
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusForbidden, gin.H{"error": "not authorized"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if user.DisabledAt.Valid {
			c.JSON(http.StatusForbidden, gin.H{"error": "not authorized"})
			return
		}

		device := c.Query("device")
		var deviceFilter pgtype.Text
		if device != "" {
			if !requireDeviceOwnership(c, deps, device) {
				return
			}
			deviceFilter = pgtype.Text{String: device, Valid: true}
		} else if user.Role == control.RoleCustomerUser {
			c.JSON(http.StatusForbidden, gin.H{
				"error": "device query param is required for your role",
			})
			return
		}

		// Only a platform admin may narrow by customer; everyone else's scope is pinned to their own
		// customer inside the query itself, from the same database state that authorizes the read.
		var customerFilter pgtype.Int8
		if user.Role == control.RolePlatformAdmin {
			if raw := c.Query("customerId"); raw != "" {
				id, perr := strconv.ParseInt(raw, 10, 64)
				if perr != nil || id <= 0 {
					c.JSON(http.StatusBadRequest, gin.H{"error": "customerId must be a positive integer"})
					return
				}
				customerFilter = pgtype.Int8{Int64: id, Valid: true}
			}
		}

		begin, end, ok := timeRange(c)
		if !ok {
			return
		}
		limit, offset := pageParams(c, 100)

		rows, err := deps.Queries.ListAuditLogVisibleToUser(ctx, db.ListAuditLogVisibleToUserParams{
			UserID:     user.ID,
			DeviceName: deviceFilter,
			CustomerID: customerFilter,
			BeginTime:  pgtype.Timestamptz{Time: begin, Valid: true},
			EndTime:    pgtype.Timestamptz{Time: end, Valid: true},
			Lim:        limit,
			Off:        offset,
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		type auditResponse struct {
			OccurredAt time.Time       `json:"occurredAt"`
			Actor      string          `json:"actor"`
			Action     string          `json:"action"`
			DeviceName string          `json:"deviceName,omitempty"`
			CommandID  string          `json:"commandId,omitempty"`
			Outcome    string          `json:"outcome"`
			Detail     json.RawMessage `json:"detail,omitempty"`
		}

		out := make([]auditResponse, 0, len(rows))
		for _, r := range rows {
			entry := auditResponse{
				Actor:      r.Actor,
				Action:     r.Action,
				DeviceName: r.DeviceName.String,
				Outcome:    r.Outcome,
				Detail:     json.RawMessage(r.Detail),
			}
			if t := timePtr(r.OccurredAt); t != nil {
				entry.OccurredAt = *t
			}
			if r.CommandID.Valid {
				entry.CommandID = uuidString(r.CommandID)
			}
			out = append(out, entry)
		}
		c.JSON(http.StatusOK, gin.H{"audit": out})
	}
}

// actorFrom rebuilds the authenticated principal from the JWT claims authMiddleware stashed.
func actorFrom(c *gin.Context) (control.Actor, bool) {
	userID, ok := c.Get("userID")
	if !ok {
		// authMiddleware always sets this; missing means a route was wired without it.
		c.JSON(http.StatusInternalServerError, gin.H{"error": "no authenticated user in context"})
		return control.Actor{}, false
	}
	username, _ := c.Get("username")
	role, _ := c.Get("role")

	name, _ := username.(string)
	roleStr, _ := role.(string)
	if roleStr == "" {
		// A token issued before the role claim existed. Treat it as the least-privileged role
		// rather than guessing upward — an old token must never grant more than a new one.
		roleStr = control.RoleCustomerUser
	}

	return control.Actor{UserID: userID.(int64), Username: name, Role: roleStr}, true
}

func pageParams(c *gin.Context, defaultLimit int32) (limit, offset int32) {
	limit, offset = defaultLimit, 0
	if v := c.Query("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 500 {
			limit = int32(n)
		}
	}
	if v := c.Query("offset"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			offset = int32(n)
		}
	}
	return limit, offset
}

// timeRange parses beginTime/endTime, defaulting to the last 24 hours so the endpoint is usable
// without them.
func timeRange(c *gin.Context) (time.Time, time.Time, bool) {
	end := time.Now()
	begin := end.Add(-24 * time.Hour)

	if v := c.Query("beginTime"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "beginTime must be RFC3339"})
			return time.Time{}, time.Time{}, false
		}
		begin = t
	}
	if v := c.Query("endTime"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "endTime must be RFC3339"})
			return time.Time{}, time.Time{}, false
		}
		end = t
	}
	return begin, end, true
}

func timePtr(ts pgtype.Timestamptz) *time.Time {
	if !ts.Valid {
		return nil
	}
	t := ts.Time
	return &t
}

func uuidString(u pgtype.UUID) string {
	if !u.Valid {
		return ""
	}
	b := u.Bytes
	const hex = "0123456789abcdef"
	out := make([]byte, 0, 36)
	for i, v := range b {
		if i == 4 || i == 6 || i == 8 || i == 10 {
			out = append(out, '-')
		}
		out = append(out, hex[v>>4], hex[v&0x0f])
	}
	return string(out)
}
