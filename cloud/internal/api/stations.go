package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// stationResponse is shared by the list and the management endpoints. customerId is there for the
// operations console, where a platform admin sees stations of several customers.
type stationResponse struct {
	ID         int64     `json:"id"`
	CustomerID int64     `json:"customerId"`
	Name       string    `json:"name"`
	Address    string    `json:"address"`
	CreatedAt  time.Time `json:"createdAt"`
}

func toStationResponse(s db.Station) stationResponse {
	return stationResponse{ID: s.ID, CustomerID: s.CustomerID, Name: s.Name, Address: s.Address, CreatedAt: s.CreatedAt.Time}
}

// listStationsHandler is GET /api/stations. Scope is decided in SQL (ListStationsForUser) from the
// caller's current role, customer and bindings, never from the token.
func listStationsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		rows, err := deps.Queries.ListStationsForUser(c.Request.Context(), c.MustGet("userID").(int64))
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		out := make([]stationResponse, 0, len(rows))
		for _, r := range rows {
			out = append(out, toStationResponse(r))
		}
		c.JSON(http.StatusOK, gin.H{"rows": out})
	}
}

const maxStationAddressLen = 200

// mayManageCustomer: a platform admin manages any customer; a customer admin only their own.
func mayManageCustomer(actor db.User, customerID int64) bool {
	switch actor.Role {
	case control.RolePlatformAdmin:
		return true
	case control.RoleCustomerAdmin:
		return actor.CustomerID.Valid && actor.CustomerID.Int64 == customerID
	}
	return false
}

// errStationActorLost: the administrator was moved, demoted or disabled between the early check and
// the transaction. Answered like any other refusal.
var errStationActorLost = errors.New("administrator no longer authorized")

// lockStationActor locks the acting administrator against being moved, demoted or disabled for the
// rest of the transaction and returns the current row. requireAdminActor's copy was read before the
// transaction; deciding from it would let an administrator who just lost authority still commit a
// change in the old customer (same pattern as user creation in admin.go).
func lockStationActor(ctx context.Context, q *db.Queries, id int64) (db.User, error) {
	now, err := q.GetUserForShare(ctx, id)
	if errors.Is(err, pgx.ErrNoRows) {
		return db.User{}, errStationActorLost
	}
	if err != nil {
		return db.User{}, err
	}
	if now.DisabledAt.Valid || (now.Role != control.RolePlatformAdmin && now.Role != control.RoleCustomerAdmin) {
		return db.User{}, errStationActorLost
	}
	return now, nil
}

func validStationFields(name, address string) (string, string, bool) {
	name, ok := validCustomerName(name)
	address = strings.TrimSpace(address)
	return name, address, ok && utf8.RuneCountInString(address) <= maxStationAddressLen
}

type createStationRequest struct {
	CustomerID *int64 `json:"customerId"`
	Name       string `json:"name" binding:"required"`
	Address    string `json:"address"`
}

func createStationHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin && actor.Role != control.RoleCustomerAdmin {
			refuse(c, deps, actor, "station.create", "not an administrator", nil)
			return
		}
		var req createStationRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		var customerID int64
		switch {
		case req.CustomerID != nil:
			customerID = *req.CustomerID
		case actor.Role == control.RoleCustomerAdmin && actor.CustomerID.Valid:
			customerID = actor.CustomerID.Int64
		default:
			c.JSON(http.StatusBadRequest, gin.H{"error": "customerId is required"})
			return
		}
		if !mayManageCustomer(actor, customerID) {
			refuse(c, deps, actor, "station.create", "another customer", map[string]any{"customerId": customerID})
			return
		}
		name, address, ok := validStationFields(req.Name, req.Address)
		if !ok {
			c.JSON(http.StatusBadRequest, gin.H{"error": "name must be 1-100 characters and address at most 200"})
			return
		}

		ctx := c.Request.Context()
		if _, err := deps.Queries.GetCustomer(ctx, customerID); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusBadRequest, gin.H{"error": "unknown customerId"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		var created db.Station
		var denied bool
		err := withTx(ctx, deps, func(q *db.Queries) error {
			actorNow, err := lockStationActor(ctx, q, actor.ID)
			if err != nil {
				return err
			}
			if !mayManageCustomer(actorNow, customerID) {
				denied = true
				return nil
			}
			actor = actorNow
			if created, err = q.CreateStation(ctx, db.CreateStationParams{CustomerID: customerID, Name: name, Address: address}); err != nil {
				return err
			}
			// Filed under no customer, like customer.create: history rows reference customers with
			// ON DELETE RESTRICT, and this row must not make the customer undeletable. The id is in
			// the detail instead.
			return auditAdmin(ctx, q, actor, "station.create", "", pgtype.Int8{}, "allowed",
				map[string]any{"stationId": created.ID, "customerId": customerID, "name": created.Name})
		})
		if errors.Is(err, errStationActorLost) || (err == nil && denied) {
			refuse(c, deps, actor, "station.create", "authority changed", map[string]any{"customerId": customerID})
			return
		}
		if err != nil {
			if isPgCode(err, "23505") {
				c.JSON(http.StatusConflict, gin.H{"error": "a station with this name already exists in this customer"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusCreated, toStationResponse(created))
	}
}

// loadManageableStation resolves :id for update/delete. A platform admin gets an honest 404 for a
// missing station; anyone else gets the same refusal for "missing" and "another customer's", so the
// endpoint cannot be used to probe which stations exist.
func loadManageableStation(c *gin.Context, deps Deps, actor db.User, action string) (db.Station, bool) {
	id, ok := parseIDParam(c)
	if !ok {
		return db.Station{}, false
	}
	st, err := deps.Queries.GetStation(c.Request.Context(), id)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		if actor.Role == control.RolePlatformAdmin {
			c.JSON(http.StatusNotFound, gin.H{"error": "station not found"})
		} else {
			refuse(c, deps, actor, action, "no such station for this caller", map[string]any{"stationId": id})
		}
		return db.Station{}, false
	case err != nil:
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return db.Station{}, false
	}
	if !mayManageCustomer(actor, st.CustomerID) {
		refuse(c, deps, actor, action, "no such station for this caller", map[string]any{"stationId": id})
		return db.Station{}, false
	}
	return st, true
}

type updateStationRequest struct {
	Name    string `json:"name" binding:"required"`
	Address string `json:"address"`
}

func updateStationHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		st, ok := loadManageableStation(c, deps, actor, "station.update")
		if !ok {
			return
		}
		var req updateStationRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		name, address, ok := validStationFields(req.Name, req.Address)
		if !ok {
			c.JSON(http.StatusBadRequest, gin.H{"error": "name must be 1-100 characters and address at most 200"})
			return
		}

		ctx := c.Request.Context()
		var updated db.Station
		var denied bool
		err := withTx(ctx, deps, func(q *db.Queries) error {
			actorNow, err := lockStationActor(ctx, q, actor.ID)
			if err != nil {
				return err
			}
			if !mayManageCustomer(actorNow, st.CustomerID) {
				denied = true
				return nil
			}
			actor = actorNow
			if updated, err = q.UpdateStation(ctx, db.UpdateStationParams{ID: st.ID, Name: name, Address: address}); err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "station.update", "", pgtype.Int8{}, "allowed",
				map[string]any{"stationId": st.ID, "customerId": st.CustomerID, "name": name, "was": st.Name})
		})
		if errors.Is(err, errStationActorLost) || (err == nil && denied) {
			refuse(c, deps, actor, "station.update", "authority changed", map[string]any{"stationId": st.ID})
			return
		}
		if err != nil {
			if isPgCode(err, "23505") {
				c.JSON(http.StatusConflict, gin.H{"error": "a station with this name already exists in this customer"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, toStationResponse(updated))
	}
}

func deleteStationHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		st, ok := loadManageableStation(c, deps, actor, "station.delete")
		if !ok {
			return
		}
		ctx := c.Request.Context()
		var denied bool
		err := withTx(ctx, deps, func(q *db.Queries) error {
			actorNow, err := lockStationActor(ctx, q, actor.ID)
			if err != nil {
				return err
			}
			if !mayManageCustomer(actorNow, st.CustomerID) {
				denied = true
				return nil
			}
			actor = actorNow
			if _, err := q.DeleteStation(ctx, st.ID); err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "station.delete", "", pgtype.Int8{}, "allowed",
				map[string]any{"stationId": st.ID, "customerId": st.CustomerID, "name": st.Name})
		})
		if errors.Is(err, errStationActorLost) || (err == nil && denied) {
			refuse(c, deps, actor, "station.delete", "authority changed", map[string]any{"stationId": st.ID})
			return
		}
		if err != nil {
			if isPgCode(err, "23503") {
				c.JSON(http.StatusConflict, gin.H{"error": "the station still has devices; move them to another station first"})
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.Status(http.StatusNoContent)
	}
}

// assignStationRequest keeps stationId as raw JSON so "missing" (a client bug, 400) can be told apart
// from an explicit null ("ungrouped").
type assignStationRequest struct {
	StationID json.RawMessage `json:"stationId"`
}

func assignDeviceStationHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin && actor.Role != control.RoleCustomerAdmin {
			refuse(c, deps, actor, "device.station", "not an administrator", map[string]any{"device": c.Param("device")})
			return
		}
		var req assignStationRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}
		var want pgtype.Int8
		switch {
		case len(req.StationID) == 0:
			c.JSON(http.StatusBadRequest, gin.H{"error": "stationId is required (a number, or null to clear)"})
			return
		case string(req.StationID) != "null":
			var id int64
			if err := json.Unmarshal(req.StationID, &id); err != nil {
				c.JSON(http.StatusBadRequest, gin.H{"error": "stationId must be a number or null"})
				return
			}
			want = int8(id)
		}

		device := c.Param("device")
		ctx := c.Request.Context()
		var previous pgtype.Int8
		var denied, missing, noCustomer, badStation, unchanged bool
		err := withTx(ctx, deps, func(q *db.Queries) error {
			actorNow, err := lockStationActor(ctx, q, actor.ID)
			if err != nil {
				return err
			}
			actor = actorNow
			// Authorize before touching the device row (CLAUDE.md invariant 6/9): a foreign or missing
			// device must cost the caller the same, with no lock wait on another tenant's row.
			allowed, err := q.UserMayAccessDevice(ctx, db.UserMayAccessDeviceParams{UserID: actor.ID, DeviceName: device})
			if err != nil {
				return err
			}
			if !allowed {
				denied = true
				return nil
			}
			dev, err := q.GetDeviceForUpdate(ctx, device)
			if errors.Is(err, pgx.ErrNoRows) {
				missing = true
				return nil
			}
			if err != nil {
				return err
			}
			if !dev.CustomerID.Valid {
				// Only a platform admin gets here (the rule above refuses everyone else): give the real reason.
				noCustomer = true
				return nil
			}
			// The device may have moved between the check above and the lock; decide again.
			if !mayManageCustomer(actor, dev.CustomerID.Int64) {
				denied = true
				return nil
			}
			if want.Valid {
				st, err := q.GetStation(ctx, want.Int64)
				if errors.Is(err, pgx.ErrNoRows) || (err == nil && st.CustomerID != dev.CustomerID.Int64) {
					badStation = true
					return nil
				}
				if err != nil {
					return err
				}
			}
			previous = dev.StationID
			if previous == want {
				unchanged = true
				return nil
			}
			if err := q.SetDeviceStation(ctx, db.SetDeviceStationParams{DeviceName: device, StationID: want}); err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "device.station", device, dev.CustomerID, "allowed",
				map[string]any{"from": int8Ptr(previous), "to": int8Ptr(want)})
		})
		switch {
		case errors.Is(err, errStationActorLost):
			refuse(c, deps, actor, "device.station", "authority changed", map[string]any{"device": device})
		case err != nil && (isPgCode(err, "23503") || isPgCode(err, "23514")):
			// Lost a race with a station delete or a customer transfer; the checks above passed but
			// the database, which is the actual guarantee, refused.
			c.JSON(http.StatusConflict, gin.H{"error": "the device or station changed while assigning; retry"})
		case err != nil:
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		case missing && actor.Role == control.RolePlatformAdmin:
			c.JSON(http.StatusNotFound, gin.H{"error": "device not found"})
		case missing || denied:
			refuse(c, deps, actor, "device.station", "no such device for this caller", map[string]any{"device": device})
		case noCustomer:
			c.JSON(http.StatusConflict, gin.H{"error": "the device has no customer; assign it to a customer first"})
		case badStation:
			c.JSON(http.StatusBadRequest, gin.H{"error": "stationId does not name a station of the device's customer"})
		default:
			c.JSON(http.StatusOK, gin.H{"deviceName": device, "stationId": int8Ptr(want), "changed": !unchanged})
		}
	}
}
