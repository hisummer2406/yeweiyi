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

// auditRefusal records a denied administrative attempt under the caller's own customer.
func auditRefusal(c *gin.Context, deps Deps, actor db.User, action, reason string, detail map[string]any) {
	if detail == nil {
		detail = map[string]any{}
	}
	detail["reason"] = reason
	// Best effort: the refusal must not turn into a 500 because the audit write failed.
	_ = auditAdmin(c.Request.Context(), deps.Queries, actor, action, "", actor.CustomerID, "denied", detail)
}

// refuse audits a denied administrative attempt and answers 403. Clients read a 403 whose body is
// exactly "not authorized" as "this account is revoked" and send the user back to the login page, so
// reserve it for the caller's role or account; a refusal about an object (someone else's, or missing)
// goes through refuseObject.
func refuse(c *gin.Context, deps Deps, actor db.User, action, reason string, detail map[string]any) {
	auditRefusal(c, deps, actor, action, reason, detail)
	c.JSON(http.StatusForbidden, gin.H{"error": "not authorized"})
}

// refuseObject audits the attempt like refuse but answers with the status and message a missing
// object would get, so another customer's object and a nonexistent one look identical to the caller
// and the client does not mistake the refusal for a revoked account.
func refuseObject(c *gin.Context, deps Deps, actor db.User, action, reason string, detail map[string]any, status int, msg string) {
	refuseObjectCode(c, deps, actor, action, reason, detail, status, msg, "")
}

// refuseObjectCode is refuseObject with a machine-readable code in the body. The code is the same
// for "another customer's" and "does not exist", so it adds nothing a caller could probe with.
func refuseObjectCode(c *gin.Context, deps Deps, actor db.User, action, reason string, detail map[string]any, status int, msg, code string) {
	auditRefusal(c, deps, actor, action, reason, detail)
	body := gin.H{"error": msg}
	if code != "" {
		body["code"] = code
	}
	c.JSON(status, body)
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
	// OnboardingStatus is "configuring" or "active" (cloud#60); OnboardedAt is null until active.
	OnboardingStatus string     `json:"onboardingStatus"`
	OnboardedAt      *time.Time `json:"onboardedAt"`
}

func toCustomerResponse(c db.Customer) customerResponse {
	r := customerResponse{ID: c.ID, Name: c.Name, CreatedAt: c.CreatedAt.Time, OnboardingStatus: c.OnboardingStatus}
	if c.OnboardedAt.Valid {
		t := c.OnboardedAt.Time
		r.OnboardedAt = &t
	}
	return r
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
			apiError(c, http.StatusBadRequest, "invalid_name", "name must be 1-100 characters")
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
				apiError(c, http.StatusConflict, "customer_name_taken", "a customer with this name already exists")
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
		apiError(c, http.StatusBadRequest, "invalid_request", "id must be a positive integer")
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
			apiError(c, http.StatusBadRequest, "invalid_name", "name must be 1-100 characters")
			return
		}

		ctx := c.Request.Context()
		current, err := deps.Queries.GetCustomer(ctx, id)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				apiError(c, http.StatusNotFound, "customer_not_found", "customer not found")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if current.Name == "default" {
			apiError(c, http.StatusConflict, "default_customer_protected", "the default customer cannot be renamed")
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
				apiError(c, http.StatusConflict, "customer_name_taken", "a customer with this name already exists")
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
				apiError(c, http.StatusNotFound, "customer_not_found", "customer not found")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if current.Name == "default" {
			apiError(c, http.StatusConflict, "default_customer_protected", "the default customer cannot be deleted")
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
				apiError(c, http.StatusConflict, "customer_not_empty", "the customer still has users, devices, stations or history and cannot be deleted")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.Status(http.StatusNoContent)
	}
}

// --- users --------------------------------------------------------------------------------------

// userResponse never carries the password hash. Username is set only for platform admins: a
// customer-side account's "username" is its phone number, which is exposed through Phone instead so
// that it can be masked.
type userResponse struct {
	ID         int64      `json:"id"`
	Username   *string    `json:"username"`
	Phone      *string    `json:"phone"`
	Role       string     `json:"role"`
	CustomerID *int64     `json:"customerId"`
	Disabled   bool       `json:"disabled"`
	DisabledAt *time.Time `json:"disabledAt,omitempty"`
	CreatedAt  time.Time  `json:"createdAt"`
}

// toUserResponse renders a user. fullPhone is true for the user themselves and for platform admins;
// everyone else sees the number masked (contract §3.1).
func toUserResponse(u db.User, fullPhone bool) userResponse {
	r := userResponse{
		ID: u.ID, Role: u.Role, CustomerID: int8Ptr(u.CustomerID),
		Disabled: u.DisabledAt.Valid, CreatedAt: u.CreatedAt.Time,
	}
	// A phone account's username column only mirrors its number, so it stays null there (contract §3.1);
	// platform admins and username accounts are identified by it.
	if u.Role == control.RolePlatformAdmin || !u.Phone.Valid {
		name := u.Username
		r.Username = &name
	}
	if u.Phone.Valid {
		phone := u.Phone.String
		if !fullPhone {
			phone = maskPhone(phone)
		}
		r.Phone = &phone
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

// createUserRequest is shaped by role (contract §4.5): a platform admin has a username and password;
// a customer admin a phone, password and customer; staff (customer_user) a phone and customer and no
// password at all. Password is a pointer so that "sent" can be told from "absent": a password sent
// for staff is an error, not something to ignore.
type createUserRequest struct {
	Username   string  `json:"username"`
	Phone      string  `json:"phone"`
	Password   *string `json:"password"`
	Role       string  `json:"role" binding:"required"`
	CustomerID *int64  `json:"customerId"`
}

// createUserHandler: only a platform admin creates accounts. A customer admin cannot create anyone,
// staff included — staff are provisioned by the platform and then assigned tanks by the customer
// admin, which are separate acts. The refusal comes before the body is read, so a forged role or
// customerId cannot draw a different answer.
func createUserHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "user.create", "only a platform admin may create users", nil)
			return
		}
		var req createUserRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			apiError(c, http.StatusBadRequest, codeInvalidRequest, err.Error())
			return
		}
		plan, bad := planUserCreation(req)
		if bad != nil {
			apiError(c, bad.status, bad.code, bad.msg)
			return
		}

		ctx := c.Request.Context()
		if plan.customerID.Valid {
			if _, err := deps.Queries.GetCustomer(ctx, plan.customerID.Int64); err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					apiError(c, http.StatusBadRequest, "unknown_customer", "unknown customerId")
					return
				}
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
				return
			}
		}

		// Hashed before anything is locked: bcrypt is slow on purpose, and it is exactly the stretch
		// during which the administrator can be moved, demoted or disabled.
		var hash pgtype.Text
		if plan.password != "" {
			h, err := bcrypt.GenerateFromPassword([]byte(plan.password), bcrypt.DefaultCost)
			if err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
				return
			}
			hash = pgtype.Text{String: string(h), Valid: true}
		}

		var created db.User
		err := withTx(ctx, deps, func(q *db.Queries) error {
			// Lock the creator against being moved, demoted or disabled for the rest of the
			// transaction and decide again on that current row. Using the row read before the hash
			// would let an administrator who lost authority in the meantime still create an account.
			actorNow, err := q.GetUserForShare(ctx, actor.ID)
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					return verdictError{status: http.StatusForbidden, msg: "not authorized"}
				}
				return err
			}
			if actorNow.DisabledAt.Valid || actorNow.Role != control.RolePlatformAdmin {
				return verdictError{status: http.StatusForbidden, audit: true, msg: "not authorized"}
			}
			if created, err = q.CreateUser(ctx, db.CreateUserParams{
				Username: plan.username, PasswordHash: hash, Role: req.Role,
				CustomerID: plan.customerID, Phone: plan.phone,
			}); err != nil {
				return err
			}
			// The phone number and the password are deliberately not written into the audit detail.
			return auditAdmin(ctx, q, actorNow, "user.create", "", plan.customerID, "allowed",
				map[string]any{"userId": created.ID, "role": created.Role})
		})
		if err != nil {
			var v verdictError
			switch {
			case errors.As(err, &v):
				respondVerdict(c, deps, actor, 0, "user.create", userVerdict(v))
			case isPgCode(err, "23505") && plan.phone.Valid:
				apiError(c, http.StatusConflict, codePhoneExists, "phone number already registered")
			case isPgCode(err, "23505"):
				apiError(c, http.StatusConflict, "username_taken", "username already exists")
			case isPgCode(err, "23503"):
				apiError(c, http.StatusBadRequest, "unknown_customer", "unknown customerId")
			default:
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			}
			return
		}
		c.JSON(http.StatusCreated, toUserResponse(created, true))
	}
}

const (
	codePhoneExists       = "phone_exists"
	codeNoPasswordForRole = "no_password_for_role"
	codeInvalidUsername   = "invalid_username"
	codeInvalidPassword   = "invalid_password"
)

type userPlan struct {
	username   string
	phone      pgtype.Text
	password   string
	customerID pgtype.Int8
}

type planError struct {
	status    int
	code, msg string
}

// planUserCreation validates a create request against the per-role shape and returns what to write.
// It decides nothing about who may create (that is the handler's job); it only enforces that each
// role gets exactly the fields it is allowed.
func planUserCreation(req createUserRequest) (userPlan, *planError) {
	bad := func(code, msg string) (userPlan, *planError) {
		return userPlan{}, &planError{status: http.StatusBadRequest, code: code, msg: msg}
	}
	switch req.Role {
	case control.RolePlatformAdmin:
		username := strings.TrimSpace(req.Username)
		switch {
		case username == "" || len([]rune(username)) > maxUsernameLen:
			return bad(codeInvalidRequest, "username must be 1-64 characters")
		case req.Phone != "" || req.CustomerID != nil:
			return bad(codeInvalidRequest, "a platform admin has no phone and belongs to no customer")
		case req.Password == nil || !validPassword(*req.Password):
			return bad(codeInvalidRequest, "password must be 8-72 bytes")
		}
		return userPlan{username: username, password: *req.Password}, nil

	case control.RoleCustomerAdmin, control.RoleCustomerUser:
		if req.CustomerID == nil {
			return bad(codeInvalidRequest, "customerId is required for this role")
		}
		// Exactly one identifier: a mainland phone (WeChat / phone login) or an independent username
		// (password login, for users without WeChat, cloud#77).
		if (req.Username != "") == (req.Phone != "") {
			return bad(codeInvalidRequest, "send exactly one of phone or username")
		}
		if req.Username != "" {
			username := strings.TrimSpace(req.Username)
			if !validAccountUsername(username) {
				return bad(codeInvalidUsername, "username must be 3-32 letters, digits, . _ - and must not look like a phone number")
			}
			if req.Password == nil || !validPassword(*req.Password) {
				return bad(codeInvalidPassword, "password must be 8-72 bytes")
			}
			return userPlan{username: username, password: *req.Password, customerID: int8(*req.CustomerID)}, nil
		}
		phone, ok := normalizePhone(req.Phone)
		if !ok {
			return bad(codeInvalidPhone, "invalid phone number")
		}
		plan := userPlan{
			username: phone, phone: pgtype.Text{String: phone, Valid: true}, customerID: int8(*req.CustomerID),
		}
		if req.Role == control.RoleCustomerUser && req.Password == nil {
			return plan, nil // WeChat only, as before
		}
		if req.Password == nil || !validPassword(*req.Password) {
			// customer_admin keeps the code it always had; the newer paths use the stable weak-password code.
			if req.Role == control.RoleCustomerAdmin {
				return bad(codeInvalidRequest, "password must be 8-72 bytes")
			}
			return bad(codeInvalidPassword, "password must be 8-72 bytes")
		}
		plan.password = *req.Password
		return plan, nil
	}
	return bad(codeInvalidRequest, "role must be platform_admin, customer_admin or customer_user")
}

// listUsersHandler: a platform admin sees everyone (optionally narrowed by ?customerId=); a
// customer admin sees their own customer's staff, masked; a customer user sees none.
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
					apiError(c, http.StatusBadRequest, "invalid_request", "customerId must be a positive integer")
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
			// A customer admin lists staff only, to choose whom to assign tanks to; other admins of
			// the customer are not theirs to enumerate. Phone numbers are masked for them.
			if actor.Role == control.RoleCustomerAdmin && r.Role != control.RoleCustomerUser {
				continue
			}
			out = append(out, toUserResponse(r, actor.Role == control.RolePlatformAdmin || r.ID == actor.ID))
		}
		c.JSON(http.StatusOK, gin.H{"users": out})
	}
}

type updateUserRequest struct {
	Disabled *bool   `json:"disabled"`
	Password *string `json:"password"`
	Phone    *string `json:"phone"`
}

// updateUserHandler disables/enables a user, resets their password and/or changes their phone number. A customer admin may only
// touch customer users of their own customer; a user in another customer, or one that does not
// exist, gets the same 404 so the endpoint is not a user-id oracle.
func updateUserHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		// Staff accounts belong to the platform: a customer admin may not enable, disable or reset
		// anyone, so this is refused before the body or the target is looked at.
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "user.update", "only a platform admin may manage users", nil)
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
		if req.Disabled == nil && req.Password == nil && req.Phone == nil {
			apiError(c, http.StatusBadRequest, "invalid_request", "nothing to change: send disabled, password and/or phone")
			return
		}
		var newPhone string
		if req.Phone != nil {
			var ok bool
			if newPhone, ok = normalizePhone(*req.Phone); !ok {
				apiError(c, http.StatusBadRequest, codeInvalidPhone, "invalid phone number")
				return
			}
		}
		if req.Password != nil && !validPassword(*req.Password) {
			apiError(c, http.StatusBadRequest, "invalid_password", "password must be 8-72 bytes")
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
				apiError(c, http.StatusNotFound, "user_not_found", "user not found")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if v := decideUserUpdate(actor, pre, disabling, req.Phone != nil); v.status != 0 {
			respondVerdict(c, deps, actor, id, "user.update", v)
			return
		}

		var updated, resetTarget db.User
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
			if actorNow.DisabledAt.Valid || actorNow.Role != control.RolePlatformAdmin {
				return verdictError{status: http.StatusForbidden, audit: true, msg: "not authorized"}
			}
			target, err := q.GetUserForUpdate(ctx, id)
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					return verdictError{status: http.StatusNotFound, msg: "user not found", code: "user_not_found"}
				}
				return err
			}
			if v := decideUserUpdate(actorNow, target, disabling, req.Phone != nil); v.status != 0 {
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
					map[string]any{"userId": id, "role": target.Role}); err != nil {
					return err
				}
			}
			if req.Phone != nil && !(target.Phone.Valid && target.Phone.String == newPhone) {
				if updated, err = q.SetUserPhone(ctx, db.SetUserPhoneParams{ID: id, Phone: pgtype.Text{String: newPhone, Valid: true}}); err != nil {
					return err
				}
				// The binding proved the old number; the new one has to be proven again by the next
				// WeChat phone login. Issued tokens are untouched: sub is the user id.
				if _, err := q.DeleteWechatIdentityByUser(ctx, id); err != nil {
					return err
				}
				if err := auditAdmin(ctx, q, actorNow, "user.change-phone", "", snapshot, "allowed",
					map[string]any{"userId": id, "role": target.Role,
						"oldPhone": maskPhone(target.Phone.String), "newPhone": maskPhone(newPhone)}); err != nil {
					return err
				}
			}
			if req.Password != nil {
				if _, err := q.SetUserPassword(ctx, db.SetUserPasswordParams{ID: id, PasswordHash: pgtype.Text{String: string(hash), Valid: true}}); err != nil {
					return err
				}
				resetTarget = target
				// The new password is never written down — only that it was reset.
				if err := auditAdmin(ctx, q, actorNow, "user.reset-password", "", snapshot, "allowed",
					map[string]any{"userId": id, "role": target.Role}); err != nil {
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
			if isPgCode(err, "23505") && req.Phone != nil {
				// Deliberately says nothing about whose number it is or which customer holds it.
				apiError(c, http.StatusConflict, codePhoneExists, "phone number already registered")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if req.Password != nil {
			// The documented way out of a login lockout: the owner now has a password nobody has been
			// guessing at.
			key := usernameLoginKey(resetTarget.Username)
			if resetTarget.Phone.Valid {
				key = phoneLoginKey(resetTarget.Phone.String)
			}
			clearLoginFailures(ctx, deps, key)
		}
		c.JSON(http.StatusOK, toUserResponse(updated, true))
	}
}

// userVerdict is the outcome of deciding whether an administrator may change a user. status 0 means
// allowed. audit marks refusals worth recording (a real 403, as opposed to a 404/400).
type userVerdict struct {
	status int
	audit  bool
	msg    string
	code   string // optional machine-readable code, added to the body when set
}

// verdictError carries a userVerdict out of a transaction so the transaction rolls back.
type verdictError userVerdict

func (e verdictError) Error() string { return e.msg }

// decideUserUpdate is the rule for an administrator changing a user. Only a platform admin gets this
// far (the handler refuses everyone else first); the actor argument is re-checked here because this
// runs again on the locked row. Nobody may disable themselves. Setting a password is allowed for every account, phone staff included.
// A phone can only be changed on a phone account: platform admins and username accounts have none,
// and giving them one would convert the account type (contract §11.1: no conversion).
func decideUserUpdate(actor, target db.User, disabling, settingPhone bool) userVerdict {
	if actor.Role != control.RolePlatformAdmin {
		return userVerdict{status: http.StatusForbidden, audit: true, msg: "not authorized"}
	}
	if disabling && target.ID == actor.ID {
		return userVerdict{status: http.StatusBadRequest, msg: "you cannot disable yourself"}
	}
	if settingPhone && (target.Role == control.RolePlatformAdmin || !target.Phone.Valid) {
		return userVerdict{status: http.StatusConflict, code: "phone_not_applicable", msg: "this account has no phone number to change"}
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
	if v.code != "" {
		apiError(c, v.status, v.code, v.msg)
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
				apiError(c, http.StatusNotFound, "user_not_found", "user not found")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if target.Role == control.RolePlatformAdmin {
			apiError(c, http.StatusBadRequest, "platform_admin_has_no_customer", "a platform admin belongs to no customer and cannot be moved")
			return
		}
		if _, err := deps.Queries.GetCustomer(ctx, req.CustomerID); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				apiError(c, http.StatusBadRequest, "unknown_customer", "unknown customerId")
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
					return verdictError{status: http.StatusNotFound, msg: "user not found", code: "user_not_found"}
				}
				return err
			}
			if locked.Role == control.RolePlatformAdmin {
				return verdictError{status: http.StatusBadRequest, msg: "a platform admin belongs to no customer and cannot be moved", code: "platform_admin_has_no_customer"}
			}
			if locked.CustomerID.Valid && locked.CustomerID.Int64 == req.CustomerID {
				moved = locked // already there: nothing to do, and the bindings stay
				return nil
			}
			if moved, err = q.MoveUserToCustomer(ctx, db.MoveUserToCustomerParams{ID: id, CustomerID: int8(req.CustomerID)}); err != nil {
				return err
			}
			// Grants made under the old customer must not survive into the new one (cloud#63).
			grantsCleared, err := q.DeleteTankGrantsForUser(ctx, id)
			if err != nil {
				return err
			}
			legacyCleared, err := q.DeleteBindingsForUser(ctx, id)
			if err != nil {
				return err
			}
			cleared := int64(grantsCleared) + legacyCleared
			// Filed under both customers so each side's admin can see the user leave / arrive.
			for _, side := range []struct {
				customer  pgtype.Int8
				direction string
			}{{locked.CustomerID, "out"}, {int8(req.CustomerID), "in"}} {
				if err := auditAdmin(ctx, q, actor, "user.move", "", side.customer, "allowed", map[string]any{
					"userId": id, "role": locked.Role, "direction": side.direction,
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
		c.JSON(http.StatusOK, toUserResponse(moved, true))
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
				apiError(c, http.StatusBadRequest, "unknown_customer", "unknown customerId")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		// devices.customer_assigned_at is stamped at the UPDATE inside this transaction but only
		// takes effect at COMMIT; the history endpoint hides one bucket after the stamp to cover that
		// gap (telemetryHistoryHandler). The allowance assumes the transaction finishes within
		// assignTxTimeout, so cap the whole transaction, row-lock wait included.
		//
		// Known limit: this is a CLIENT-side deadline. It cannot bound a COMMIT that stalls inside
		// PostgreSQL (slow storage, synchronous replication): the server may commit after the
		// deadline, and then up to one bucket of telemetry from the old customer's last moments can
		// become readable by the new one. PostgreSQL 16 has no server-side transaction_timeout (17+),
		// and closing it fully would mean locking the device row across every history read. Accepted:
		// it needs a database that is already failing, and exposes one aggregated bucket. If the
		// deadline hits during COMMIT the caller gets an error although the commit may have gone
		// through; read the device back.
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
			grantsCleared, err := q.DeleteTankGrantsForDevice(ctx, device)
			if err != nil {
				return err
			}
			legacyCleared, err := q.DeleteBindingsForDevice(ctx, device)
			if err != nil {
				return err
			}
			cleared = int64(grantsCleared) + legacyCleared
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
			apiError(c, http.StatusNotFound, "device_not_found", "device not found")
		default:
			c.JSON(http.StatusOK, gin.H{
				"deviceName": device, "customerId": req.CustomerID, "changed": !unchanged, "bindingsCleared": cleared,
			})
		}
	}
}
