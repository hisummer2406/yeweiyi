package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgtype"
	"golang.org/x/crypto/bcrypt"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// Tenant administration: customers, users, and which customer a device belongs to
// (openspec change implement-multitenant-support-with-customer-isolation, phase 2).
//
// Who is allowed is decided from the caller's *current* row in the database, never from the JWT
// (design D3): a role, customer or disabled flag changed a minute ago must already apply.
//
// Every change writes an audit row, in the same transaction as the change where there is one, so a
// reassignment cannot succeed without leaving a trace. Refusals are audited too.

const (
	maxNameLen     = 100
	maxUsernameLen = 64
	minPasswordLen = 8
	// bcrypt silently ignores — and newer x/crypto rejects — anything past 72 bytes.
	maxPasswordBytes = 72
)

// requireAdminActor loads the caller from the database. A caller who no longer exists or has been
// disabled is refused with the same 403 as any other unauthorized request.
func requireAdminActor(c *gin.Context, deps Deps) (db.User, bool) {
	userID, ok := c.Get("userID")
	if !ok {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "no authenticated user in context"})
		return db.User{}, false
	}
	user, err := deps.Queries.GetUserByID(c.Request.Context(), userID.(int64))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			c.JSON(http.StatusForbidden, gin.H{"error": "not authorized"})
			return db.User{}, false
		}
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return db.User{}, false
	}
	if user.DisabledAt.Valid {
		c.JSON(http.StatusForbidden, gin.H{"error": "not authorized"})
		return db.User{}, false
	}
	return user, true
}

// auditAdmin records one administrative action. customer is the snapshot to file it under; pass an
// invalid value for platform-level actions that belong to no customer.
func auditAdmin(ctx context.Context, q *db.Queries, actor db.User, action, deviceName string, customer pgtype.Int8, outcome string, detail map[string]any) error {
	_, err := q.WriteAuditLog(ctx, db.WriteAuditLogParams{
		ActorID:    pgtype.Int8{Int64: actor.ID, Valid: true},
		Actor:      actor.Username,
		Action:     action,
		DeviceName: pgtype.Text{String: deviceName, Valid: deviceName != ""},
		Outcome:    outcome,
		Detail:     mustJSON(detail),
		CustomerID: customer,
	})
	return err
}

// refuse audits a denied administrative attempt under the caller's own customer and answers 403.
func refuse(c *gin.Context, deps Deps, actor db.User, action, reason string, detail map[string]any) {
	if detail == nil {
		detail = map[string]any{}
	}
	detail["reason"] = reason
	// Best effort: the 403 must not turn into a 500 because the audit write failed.
	_ = auditAdmin(c.Request.Context(), deps.Queries, actor, action, "", actor.CustomerID, "denied", detail)
	c.JSON(http.StatusForbidden, gin.H{"error": "not authorized"})
}

func mustJSON(v map[string]any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		// Only reachable with an unmarshalable value, which every caller here avoids; an audit row
		// without a detail is better than none.
		return nil
	}
	return b
}

func isPgCode(err error, code string) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == code
}

func int8(v int64) pgtype.Int8 { return pgtype.Int8{Int64: v, Valid: true} }

func int8Ptr(v pgtype.Int8) *int64 {
	if !v.Valid {
		return nil
	}
	return &v.Int64
}

// withTx runs fn inside a transaction on deps.Pool, committing on nil and rolling back otherwise.
func withTx(ctx context.Context, deps Deps, fn func(q *db.Queries) error) error {
	if deps.Pool == nil {
		return errors.New("administration needs a database pool")
	}
	tx, err := deps.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if err := fn(deps.Queries.WithTx(tx)); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// --- customers ----------------------------------------------------------------------------------

type customerResponse struct {
	ID        int64     `json:"id"`
	Name      string    `json:"name"`
	CreatedAt time.Time `json:"createdAt"`
}

func toCustomerResponse(c db.Customer) customerResponse {
	return customerResponse{ID: c.ID, Name: c.Name, CreatedAt: c.CreatedAt.Time}
}

type customerNameRequest struct {
	Name string `json:"name" binding:"required"`
}

func validCustomerName(raw string) (string, bool) {
	name := strings.TrimSpace(raw)
	return name, name != "" && len([]rune(name)) <= maxNameLen
}

// Customer-level audit rows are filed under no customer: filing "customer.create" under the new
// customer would make that customer undeletable the moment it exists (history rows reference it
// with ON DELETE RESTRICT). The customer's id is in the detail instead.

func createCustomerHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "customer.create", "platform admin only", nil)
			return
		}
		var req customerNameRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		name, ok := validCustomerName(req.Name)
		if !ok {
			c.JSON(http.StatusBadRequest, gin.H{"error": "name must be 1-100 characters"})
			return
		}

		var created db.Customer
		err := withTx(c.Request.Context(), deps, func(q *db.Queries) error {
			var err error
			if created, err = q.CreateCustomer(c.Request.Context(), name); err != nil {
				return err
			}
			return auditAdmin(c.Request.Context(), q, actor, "customer.create", "", pgtype.Int8{}, "allowed",
				map[string]any{"customerId": created.ID, "name": created.Name})
		})
		if err != nil {
			if isPgCode(err, "23505") {
				c.JSON(http.StatusConflict, gin.H{"error": "a customer with this name already exists"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusCreated, toCustomerResponse(created))
	}
}

func listCustomersHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "customer.list", "platform admin only", nil)
			return
		}
		rows, err := deps.Queries.ListCustomers(c.Request.Context())
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		out := make([]customerResponse, 0, len(rows))
		for _, r := range rows {
			out = append(out, toCustomerResponse(r))
		}
		c.JSON(http.StatusOK, gin.H{"customers": out})
	}
}

func parseIDParam(c *gin.Context) (int64, bool) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id <= 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "id must be a positive integer"})
		return 0, false
	}
	return id, true
}

func renameCustomerHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "customer.rename", "platform admin only", nil)
			return
		}
		id, ok := parseIDParam(c)
		if !ok {
			return
		}
		var req customerNameRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		name, ok := validCustomerName(req.Name)
		if !ok {
			c.JSON(http.StatusBadRequest, gin.H{"error": "name must be 1-100 characters"})
			return
		}

		ctx := c.Request.Context()
		current, err := deps.Queries.GetCustomer(ctx, id)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusNotFound, gin.H{"error": "customer not found"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if current.Name == "default" {
			c.JSON(http.StatusConflict, gin.H{"error": "the default customer cannot be renamed"})
			return
		}

		var renamed db.Customer
		err = withTx(ctx, deps, func(q *db.Queries) error {
			var err error
			if renamed, err = q.RenameCustomer(ctx, db.RenameCustomerParams{ID: id, Name: name}); err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "customer.rename", "", pgtype.Int8{}, "allowed",
				map[string]any{"customerId": id, "from": current.Name, "to": name})
		})
		if err != nil {
			if isPgCode(err, "23505") {
				c.JSON(http.StatusConflict, gin.H{"error": "a customer with this name already exists"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, toCustomerResponse(renamed))
	}
}

func deleteCustomerHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "customer.delete", "platform admin only", nil)
			return
		}
		id, ok := parseIDParam(c)
		if !ok {
			return
		}

		ctx := c.Request.Context()
		current, err := deps.Queries.GetCustomer(ctx, id)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusNotFound, gin.H{"error": "customer not found"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if current.Name == "default" {
			c.JSON(http.StatusConflict, gin.H{"error": "the default customer cannot be deleted"})
			return
		}

		err = withTx(ctx, deps, func(q *db.Queries) error {
			if _, err := q.DeleteCustomer(ctx, id); err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "customer.delete", "", pgtype.Int8{}, "allowed",
				map[string]any{"customerId": id, "name": current.Name})
		})
		if err != nil {
			if isPgCode(err, "23503") {
				c.JSON(http.StatusConflict, gin.H{"error": "the customer still has users, devices or history and cannot be deleted"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.Status(http.StatusNoContent)
	}
}

// --- users --------------------------------------------------------------------------------------

// userResponse never carries the password hash.
type userResponse struct {
	ID         int64      `json:"id"`
	Username   string     `json:"username"`
	Role       string     `json:"role"`
	CustomerID *int64     `json:"customerId"`
	Disabled   bool       `json:"disabled"`
	DisabledAt *time.Time `json:"disabledAt,omitempty"`
	CreatedAt  time.Time  `json:"createdAt"`
}

func toUserResponse(u db.User) userResponse {
	r := userResponse{
		ID: u.ID, Username: u.Username, Role: u.Role, CustomerID: int8Ptr(u.CustomerID),
		Disabled: u.DisabledAt.Valid, CreatedAt: u.CreatedAt.Time,
	}
	if u.DisabledAt.Valid {
		t := u.DisabledAt.Time
		r.DisabledAt = &t
	}
	return r
}

func validRole(role string) bool {
	switch role {
	case control.RolePlatformAdmin, control.RoleCustomerAdmin, control.RoleCustomerUser:
		return true
	}
	return false
}

func validPassword(p string) bool { return len(p) >= minPasswordLen && len(p) <= maxPasswordBytes }

type createUserRequest struct {
	Username   string `json:"username" binding:"required"`
	Password   string `json:"password" binding:"required"`
	Role       string `json:"role" binding:"required"`
	CustomerID *int64 `json:"customerId"`
}

// createUserHandler: a platform admin creates any role; a customer admin creates only
// customer_users, only in their own customer. Nobody creates a role above their own.
func createUserHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role == control.RoleCustomerUser {
			refuse(c, deps, actor, "user.create", "customer users cannot create users", nil)
			return
		}
		var req createUserRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		username := strings.TrimSpace(req.Username)
		switch {
		case username == "" || len([]rune(username)) > maxUsernameLen:
			c.JSON(http.StatusBadRequest, gin.H{"error": "username must be 1-64 characters"})
			return
		case !validRole(req.Role):
			c.JSON(http.StatusBadRequest, gin.H{"error": "role must be platform_admin, customer_admin or customer_user"})
			return
		case !validPassword(req.Password):
			c.JSON(http.StatusBadRequest, gin.H{"error": "password must be 8-72 bytes"})
			return
		}

		ctx := c.Request.Context()

		// Fast path: refuse what is plainly not allowed before spending a bcrypt on it. Not trusted
		// for the write — planUserCreation runs again below on the locked actor row.
		if _, v := planUserCreation(actor, req); v.status != 0 {
			respondVerdict(c, deps, actor, 0, "user.create", v)
			return
		}
		if req.CustomerID != nil {
			if _, err := deps.Queries.GetCustomer(ctx, *req.CustomerID); err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					c.JSON(http.StatusBadRequest, gin.H{"error": "unknown customerId"})
					return
				}
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
				return
			}
		}

		// Hashed before anything is locked: bcrypt is slow on purpose, and it is exactly the stretch
		// during which the administrator can be moved, demoted or disabled.
		hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		var created db.User
		err = withTx(ctx, deps, func(q *db.Queries) error {
			// Lock the creator against being moved, demoted or disabled for the rest of the
			// transaction, reload them, and derive the customer and the permission to create this
			// role from that current row. Using the row read before the hash would let an
			// administrator who lost authority in the meantime still create an active account.
			actorNow, err := q.GetUserForShare(ctx, actor.ID)
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					return verdictError{status: http.StatusForbidden, msg: "not authorized"}
				}
				return err
			}
			if actorNow.DisabledAt.Valid {
				return verdictError{status: http.StatusForbidden, audit: true, msg: "not authorized"}
			}
			customer, v := planUserCreation(actorNow, req)
			if v.status != 0 {
				return verdictError(v)
			}
			if created, err = q.CreateUser(ctx, db.CreateUserParams{
				Username: username, PasswordHash: string(hash), Role: req.Role, CustomerID: customer,
			}); err != nil {
				return err
			}
			return auditAdmin(ctx, q, actorNow, "user.create", "", customer, "allowed",
				map[string]any{"userId": created.ID, "username": created.Username, "role": created.Role})
		})
		if err != nil {
			var v verdictError
			switch {
			case errors.As(err, &v):
				respondVerdict(c, deps, actor, 0, "user.create", userVerdict(v))
			case isPgCode(err, "23505"):
				c.JSON(http.StatusConflict, gin.H{"error": "username already exists"})
			case isPgCode(err, "23503"):
				c.JSON(http.StatusBadRequest, gin.H{"error": "unknown customerId"})
			default:
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			}
			return
		}
		c.JSON(http.StatusCreated, toUserResponse(created))
	}
}

// planUserCreation is the rule for an administrator creating a user: it returns the customer the new
// user belongs to, or the reason it is not allowed. A platform admin may create any role; a customer
// admin only customer users, and always in their own customer — naming a different one is refused,
// leaving it out means "mine". It is called twice, once on the row read at the start of the request
// and once on the locked row inside the transaction, and only the second answer is acted on.
func planUserCreation(actor db.User, req createUserRequest) (pgtype.Int8, userVerdict) {
	switch actor.Role {
	case control.RolePlatformAdmin:
		if req.Role == control.RolePlatformAdmin {
			if req.CustomerID != nil {
				return pgtype.Int8{}, userVerdict{status: http.StatusBadRequest, msg: "a platform admin belongs to no customer"}
			}
			return pgtype.Int8{}, userVerdict{}
		}
		if req.CustomerID == nil {
			return pgtype.Int8{}, userVerdict{status: http.StatusBadRequest, msg: "customerId is required for this role"}
		}
		return int8(*req.CustomerID), userVerdict{}
	case control.RoleCustomerAdmin:
		if req.Role != control.RoleCustomerUser ||
			!actor.CustomerID.Valid ||
			(req.CustomerID != nil && *req.CustomerID != actor.CustomerID.Int64) {
			return pgtype.Int8{}, userVerdict{
				status: http.StatusForbidden, audit: true,
				msg: "a customer admin may only create customer users in their own customer",
			}
		}
		return actor.CustomerID, userVerdict{}
	default:
		return pgtype.Int8{}, userVerdict{status: http.StatusForbidden, audit: true, msg: "customer users cannot create users"}
	}
}

// listUsersHandler: a platform admin sees everyone (optionally narrowed by ?customerId=); a
// customer admin sees their own customer's users; a customer user sees none.
func listUsersHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		var filter pgtype.Int8
		switch actor.Role {
		case control.RolePlatformAdmin:
			if raw := c.Query("customerId"); raw != "" {
				id, err := strconv.ParseInt(raw, 10, 64)
				if err != nil || id <= 0 {
					c.JSON(http.StatusBadRequest, gin.H{"error": "customerId must be a positive integer"})
					return
				}
				filter = int8(id)
			}
		case control.RoleCustomerAdmin:
			filter = actor.CustomerID
		default:
			refuse(c, deps, actor, "user.list", "customer users cannot list users", nil)
			return
		}
		rows, err := deps.Queries.ListUsers(c.Request.Context(), filter)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		out := make([]userResponse, 0, len(rows))
		for _, r := range rows {
			out = append(out, toUserResponse(r))
		}
		c.JSON(http.StatusOK, gin.H{"users": out})
	}
}

type updateUserRequest struct {
	Disabled *bool   `json:"disabled"`
	Password *string `json:"password"`
}

// updateUserHandler disables/enables a user and/or resets their password. A customer admin may only
// touch customer users of their own customer; a user in another customer, or one that does not
// exist, gets the same 404 so the endpoint is not a user-id oracle.
func updateUserHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role == control.RoleCustomerUser {
			refuse(c, deps, actor, "user.update", "customer users cannot manage users", nil)
			return
		}
		id, ok := parseIDParam(c)
		if !ok {
			return
		}
		var req updateUserRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		if req.Disabled == nil && req.Password == nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "nothing to change: send disabled and/or password"})
			return
		}
		if req.Password != nil && !validPassword(*req.Password) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "password must be 8-72 bytes"})
			return
		}

		// The password is hashed before anything is locked: bcrypt is slow on purpose, and the target
		// row must not stay locked (nor the window between check and write stay open) while it runs.
		var hash []byte
		if req.Password != nil {
			var err error
			if hash, err = bcrypt.GenerateFromPassword([]byte(*req.Password), bcrypt.DefaultCost); err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
				return
			}
		}
		disabling := req.Disabled != nil && *req.Disabled

		ctx := c.Request.Context()

		// Fast path: refuse obviously unauthorized requests before taking any lock. This decision is
		// NOT trusted for the write — it is made again below, on rows locked for the transaction.
		pre, err := deps.Queries.GetUserByID(ctx, id)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if v := decideUserUpdate(actor, pre, disabling); v.status != 0 {
			respondVerdict(c, deps, actor, id, "user.update", v)
			return
		}

		var updated db.User
		err = withTx(ctx, deps, func(q *db.Queries) error {
			// Lock the acting administrator against being demoted, moved or disabled, and the target
			// against being moved or disabled by someone else, then decide again. Without this a
			// platform admin moving the target to another customer between the check above and the
			// write below would let the old customer's administrator disable, or reset the password
			// of, a user who is no longer theirs.
			actorNow, err := q.GetUserForShare(ctx, actor.ID)
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					return verdictError{status: http.StatusForbidden, audit: false, msg: "not authorized"}
				}
				return err
			}
			if actorNow.DisabledAt.Valid || actorNow.Role == control.RoleCustomerUser {
				return verdictError{status: http.StatusForbidden, audit: true, msg: "not authorized"}
			}
			target, err := q.GetUserForUpdate(ctx, id)
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					return verdictError{status: http.StatusNotFound, msg: "user not found"}
				}
				return err
			}
			if v := decideUserUpdate(actorNow, target, disabling); v.status != 0 {
				return verdictError(v)
			}

			// A platform admin has no customer of their own, so file the action under the target's.
			snapshot := actorNow.CustomerID
			if !snapshot.Valid {
				snapshot = target.CustomerID
			}

			updated = target
			if req.Disabled != nil {
				if updated, err = q.SetUserDisabled(ctx, db.SetUserDisabledParams{ID: id, Disabled: *req.Disabled}); err != nil {
					return err
				}
				action := "user.enable"
				if *req.Disabled {
					action = "user.disable"
				}
				if err := auditAdmin(ctx, q, actorNow, action, "", snapshot, "allowed",
					map[string]any{"userId": id, "username": target.Username}); err != nil {
					return err
				}
			}
			if req.Password != nil {
				if _, err := q.SetUserPassword(ctx, db.SetUserPasswordParams{ID: id, PasswordHash: string(hash)}); err != nil {
					return err
				}
				// The new password is never written down — only that it was reset.
				if err := auditAdmin(ctx, q, actorNow, "user.reset-password", "", snapshot, "allowed",
					map[string]any{"userId": id, "username": target.Username}); err != nil {
					return err
				}
			}
			return nil
		})
		if err != nil {
			var v verdictError
			if errors.As(err, &v) {
				respondVerdict(c, deps, actor, id, "user.update", userVerdict(v))
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, toUserResponse(updated))
	}
}

// userVerdict is the outcome of deciding whether an administrator may change a user. status 0 means
// allowed. audit marks refusals worth recording (a real 403, as opposed to a 404/400).
type userVerdict struct {
	status int
	audit  bool
	msg    string
}

// verdictError carries a userVerdict out of a transaction so the transaction rolls back.
type verdictError userVerdict

func (e verdictError) Error() string { return e.msg }

// decideUserUpdate is the rule for an administrator changing a user. A customer admin may only touch
// customer users of their own customer; a user of another customer is answered exactly like one that
// does not exist, so the endpoint is not a user-id oracle. Nobody may disable themselves.
func decideUserUpdate(actor, target db.User, disabling bool) userVerdict {
	if actor.Role == control.RoleCustomerAdmin {
		if !target.CustomerID.Valid || !actor.CustomerID.Valid || target.CustomerID.Int64 != actor.CustomerID.Int64 {
			return userVerdict{status: http.StatusNotFound, msg: "user not found"}
		}
		if target.Role != control.RoleCustomerUser {
			return userVerdict{status: http.StatusForbidden, audit: true, msg: "a customer admin may only manage customer users"}
		}
	}
	if disabling && target.ID == actor.ID {
		return userVerdict{status: http.StatusBadRequest, msg: "you cannot disable yourself"}
	}
	return userVerdict{}
}

// respondVerdict writes a refusal. A 403 is audited (under the given action) and answered with the
// generic body; other statuses carry their own message. targetID is 0 when there is no target user.
func respondVerdict(c *gin.Context, deps Deps, actor db.User, targetID int64, action string, v userVerdict) {
	if v.status == http.StatusForbidden {
		if v.audit {
			detail := map[string]any{}
			if targetID != 0 {
				detail["userId"] = targetID
			}
			refuse(c, deps, actor, action, v.msg, detail)
			return
		}
		c.JSON(http.StatusForbidden, gin.H{"error": "not authorized"})
		return
	}
	c.JSON(v.status, gin.H{"error": v.msg})
}

type customerIDRequest struct {
	CustomerID int64 `json:"customerId" binding:"required"`
}

// moveUserHandler moves a user to another customer and, in the same transaction, drops their device
// bindings: bindings made under the old customer must not follow them.
func moveUserHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "user.move", "platform admin only", nil)
			return
		}
		id, ok := parseIDParam(c)
		if !ok {
			return
		}
		var req customerIDRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "customerId is required: " + err.Error()})
			return
		}

		ctx := c.Request.Context()
		target, err := deps.Queries.GetUserByID(ctx, id)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if target.Role == control.RolePlatformAdmin {
			c.JSON(http.StatusBadRequest, gin.H{"error": "a platform admin belongs to no customer and cannot be moved"})
			return
		}
		if _, err := deps.Queries.GetCustomer(ctx, req.CustomerID); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusBadRequest, gin.H{"error": "unknown customerId"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		var moved db.User
		err = withTx(ctx, deps, func(q *db.Queries) error {
			// The user row is locked and re-read here, and everything below — the no-op test, the
			// "from" customer, the audit — comes from that locked row. Using the row read before the
			// transaction would, when two platform admins move the same user at once (A→B and A→C),
			// record the second as A→C although the user was already in B.
			locked, err := q.GetUserForUpdate(ctx, id)
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					return verdictError{status: http.StatusNotFound, msg: "user not found"}
				}
				return err
			}
			if locked.Role == control.RolePlatformAdmin {
				return verdictError{status: http.StatusBadRequest, msg: "a platform admin belongs to no customer and cannot be moved"}
			}
			if locked.CustomerID.Valid && locked.CustomerID.Int64 == req.CustomerID {
				moved = locked // already there: nothing to do, and the bindings stay
				return nil
			}
			if moved, err = q.MoveUserToCustomer(ctx, db.MoveUserToCustomerParams{ID: id, CustomerID: int8(req.CustomerID)}); err != nil {
				return err
			}
			cleared, err := q.DeleteBindingsForUser(ctx, id)
			if err != nil {
				return err
			}
			// Filed under both customers so each side's admin can see the user leave / arrive.
			for _, side := range []struct {
				customer  pgtype.Int8
				direction string
			}{{locked.CustomerID, "out"}, {int8(req.CustomerID), "in"}} {
				if err := auditAdmin(ctx, q, actor, "user.move", "", side.customer, "allowed", map[string]any{
					"userId": id, "username": locked.Username, "direction": side.direction,
					"from": int8Ptr(locked.CustomerID), "to": req.CustomerID, "bindingsCleared": cleared,
				}); err != nil {
					return err
				}
			}
			return nil
		})
		if err != nil {
			var v verdictError
			if errors.As(err, &v) {
				respondVerdict(c, deps, actor, id, "user.move", userVerdict(v))
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, toUserResponse(moved))
	}
}

// --- device assignment --------------------------------------------------------------------------

// assignTxTimeout bounds a device reassignment transaction. It must stay below the smallest bucket
// the history endpoint offers (1 minute); history_internal_test.go enforces that.
const assignTxTimeout = 30 * time.Second

// assignDeviceHandler is PUT /api/devices/:device/customer: it assigns an unassigned device to a
// customer, or moves a device between customers. A move drops every binding in the same
// transaction — a technician bound under the old customer must not keep a seat on the new one's
// device. History stays with the customer it was written under (design D4).
func assignDeviceHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "device.assign", "platform admin only", map[string]any{"device": c.Param("device")})
			return
		}
		var req customerIDRequest
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

		// devices.customer_assigned_at is stamped at the UPDATE inside this transaction but only
		// takes effect at COMMIT; the history endpoint hides one bucket after the stamp to cover that
		// gap (telemetryHistoryHandler). The allowance is only sound if the transaction cannot outlast
		// it, so bound the whole transaction, row-lock wait included. If the deadline hits during
		// COMMIT the outcome is reported as an error although the commit may have gone through; the
		// caller can read the device back.
		ctx, cancel := context.WithTimeout(ctx, assignTxTimeout)
		defer cancel()

		var previous pgtype.Int8
		var cleared int64
		var unchanged, missing bool
		err := withTx(ctx, deps, func(q *db.Queries) error {
			dev, err := q.GetDeviceForUpdate(ctx, device)
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					missing = true
					return nil
				}
				return err
			}
			previous = dev.CustomerID
			if previous.Valid && previous.Int64 == req.CustomerID {
				unchanged = true
				return nil
			}
			if err := q.SetDeviceCustomer(ctx, db.SetDeviceCustomerParams{DeviceName: device, CustomerID: int8(req.CustomerID)}); err != nil {
				return err
			}
			if cleared, err = q.DeleteBindingsForDevice(ctx, device); err != nil {
				return err
			}
			sides := []struct {
				customer  pgtype.Int8
				direction string
			}{{int8(req.CustomerID), "in"}}
			if previous.Valid {
				sides = append([]struct {
					customer  pgtype.Int8
					direction string
				}{{previous, "out"}}, sides...)
			}
			for _, side := range sides {
				if err := auditAdmin(ctx, q, actor, "device.assign", device, side.customer, "allowed", map[string]any{
					"direction": side.direction, "from": int8Ptr(previous), "to": req.CustomerID, "bindingsCleared": cleared,
				}); err != nil {
					return err
				}
			}
			return nil
		})
		switch {
		case err != nil:
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		case missing:
			c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
		default:
			c.JSON(http.StatusOK, gin.H{
				"deviceName": device, "customerId": req.CustomerID, "changed": !unchanged, "bindingsCleared": cleared,
			})
		}
	}
}
