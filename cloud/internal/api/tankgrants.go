package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// Tank-level viewing authorization (cloud#63). An employee (customer_user) sees a tank only when a
// platform administrator granted (device_name, tank) to them (a customer administrator cannot); administrators see every tank of the
// devices in their scope. The decision is the SQL function user_may_access_tank, read from the
// database on every request — never from the token — and every read path below applies it:
//
//	GET /api/devices                         devices with at least one grant, plus grantedTanks
//	GET /api/devices/:device/tanks           tanks the caller may see
//	GET /api/devices/:device/telemetry/...   latest: granted tanks only; history: tank must be granted
//	GET /api/alarms, /api/oil-in-out         filtered in SQL (pagination and counts follow the filter)
//	/ws/alarms                               frames for ungranted tanks are never sent
//
// Whole-device data (a record with no tank, tank 0) is visible to administrators only. Commands and
// the audit trail are whole-device and not shown to an employee at all.

// codeTankNotAuthorized is the machine-readable answer to naming a tank the caller may not see. It
// is the same whether the tank exists or not, so it cannot be used to count a device's tanks.
const codeTankNotAuthorized = "tank_not_authorized"

// requireTankAccess answers 403 tank_not_authorized unless the caller may view (device, tank). The
// device-level check has already run; this is the second half of the rule.
func requireTankAccess(c *gin.Context, deps Deps, device string, tank int) bool {
	ok, err := deps.Queries.UserMayAccessTank(c.Request.Context(), db.UserMayAccessTankParams{
		UserID: c.MustGet("userID").(int64), DeviceName: device, Tank: int32(tank),
	})
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return false
	}
	if !ok {
		apiError(c, http.StatusForbidden, codeTankNotAuthorized, "not authorized for this tank")
		return false
	}
	return true
}

// isEmployee reports whether the request's caller is tank-scoped. The role is the one authMiddleware
// read from the database for this request.
func isEmployee(c *gin.Context) bool {
	role, _ := c.Get("role")
	return role == control.RoleCustomerUser
}

// grantedTankSet is the caller's granted tanks on device, for filtering a whole-device answer.
func grantedTankSet(c *gin.Context, deps Deps, device string) (map[int]bool, error) {
	rows, err := deps.Queries.ListGrantedTanks(c.Request.Context(), db.ListGrantedTanksParams{
		UserID: c.MustGet("userID").(int64), DeviceName: device,
	})
	if err != nil {
		return nil, err
	}
	out := make(map[int]bool, len(rows))
	for _, t := range rows {
		out[int(t)] = true
	}
	return out, nil
}

// deviceTankIndexes reads the device's current tank list from ThingsBoard's latest snapshot — the
// only authoritative source (the device reports its own configuration; the cloud keeps no tank
// registry). reported is false for a device that never sent a snapshot.
func deviceTankIndexes(c *gin.Context, deps Deps, device string) (indexes []int, reported bool, status int, err error) {
	if deps.TB == nil {
		return nil, false, http.StatusServiceUnavailable, errors.New("ThingsBoard is not configured on this deployment")
	}
	ctx := c.Request.Context()
	dev, err := deps.TB.GetDeviceByName(ctx, device)
	if err != nil {
		return nil, false, tbStatus(err), err
	}
	ts, values, err := deps.TB.LatestSnapshot(ctx, dev.ID.ID)
	if err != nil {
		return nil, false, tbStatus(err), err
	}
	if len(values) == 0 {
		return nil, false, http.StatusOK, nil
	}
	snap, err := protocol.ParseSnapshot(ts, values)
	if err != nil {
		return nil, false, http.StatusInternalServerError, err
	}
	for _, t := range snap.Tanks {
		indexes = append(indexes, t.Index)
	}
	sort.Ints(indexes)
	return indexes, true, http.StatusOK, nil
}

func tbStatus(err error) int {
	switch {
	case errors.Is(err, tbclient.ErrNotFound):
		return http.StatusNotFound
	case tbclient.Unavailable(err):
		return http.StatusServiceUnavailable
	}
	return http.StatusInternalServerError
}

// listDeviceTanksHandler is GET /api/devices/:device/tanks: the tanks the caller may see on the
// device. Administrators get every tank the device currently reports, which is also the list a
// customer administrator picks grants from (the cloud never accepts a tank number it has not seen the
// device report).
func listDeviceTanksHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Param("device")
		if !requireDeviceOwnership(c, deps, device) {
			return
		}
		all, reported, status, err := deviceTankIndexes(c, deps, device)
		if err != nil {
			c.JSON(status, gin.H{"error": err.Error()})
			return
		}
		tanks := all
		if isEmployee(c) {
			granted, err := grantedTankSet(c, deps, device)
			if err != nil {
				c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
				return
			}
			tanks = nil
			for _, t := range all {
				if granted[t] {
					tanks = append(tanks, t)
				}
			}
		}
		out := make([]gin.H, 0, len(tanks))
		for _, t := range tanks {
			out = append(out, gin.H{"index": t})
		}
		c.JSON(http.StatusOK, gin.H{"deviceName": device, "reported": reported, "tanks": out})
	}
}

type userTankGrantsResponse struct {
	UserID int64            `json:"userId"`
	Grants []deviceGrantRow `json:"grants"`
}

type deviceGrantRow struct {
	DeviceName string `json:"deviceName"`
	Tanks      []int  `json:"tanks"`
}

// loadGrantTarget resolves :id for the grant endpoints. Only a platform administrator manages grants
// (product decision 2026-10-14): a customer administrator is refused by role, with the account-level
// 403 and a `denied` audit row, before the body or the target is looked at.
func loadGrantTarget(c *gin.Context, deps Deps, actor db.User, action string) (db.User, bool) {
	if actor.Role != control.RolePlatformAdmin {
		refuse(c, deps, actor, action, "only a platform administrator manages tank grants", nil)
		return db.User{}, false
	}
	id, ok := parseIDParam(c)
	if !ok {
		return db.User{}, false
	}
	target, err := deps.Queries.GetUserByID(c.Request.Context(), id)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		apiError(c, http.StatusNotFound, "user_not_found", "user not found")
		return db.User{}, false
	case err != nil:
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return db.User{}, false
	}
	if target.Role != control.RoleCustomerUser {
		// Administrators see every tank of their scope; there is nothing to grant. Said plainly
		// rather than silently accepted, so a client bug is not mistaken for an applied grant.
		apiError(c, http.StatusConflict, "grant_not_applicable", "only employees (customer_user) are granted tanks")
		return db.User{}, false
	}
	return target, true
}

// listUserTankGrantsHandler is GET /api/users/:id/tank-grants.
func listUserTankGrantsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		target, ok := loadGrantTarget(c, deps, actor, "tank.grants.list")
		if !ok {
			return
		}
		rows, err := deps.Queries.ListTankGrantsForUser(c.Request.Context(), target.ID)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		out := userTankGrantsResponse{UserID: target.ID, Grants: []deviceGrantRow{}}
		for _, r := range rows {
			if n := len(out.Grants); n > 0 && out.Grants[n-1].DeviceName == r.DeviceName {
				out.Grants[n-1].Tanks = append(out.Grants[n-1].Tanks, int(r.Tank))
				continue
			}
			out.Grants = append(out.Grants, deviceGrantRow{DeviceName: r.DeviceName, Tanks: []int{int(r.Tank)}})
		}
		c.JSON(http.StatusOK, out)
	}
}

type putTankGrantsRequest struct {
	// Tanks is the complete set the employee should have on this device. Raw so that a missing key
	// (a client that never built the set) is a 400 rather than "revoke everything".
	Tanks json.RawMessage `json:"tanks"`
}

// putUserTankGrantsHandler is PUT /api/users/:id/tank-grants/:device: replace the employee's grants
// on one device with exactly the given tank set. Replacing a set is what makes it idempotent and
// race-safe (two administrators submitting the same set converge; a different set last-writer-wins
// under the target row lock), where add/remove pairs would have to be reconciled. An empty set
// revokes everything on that device.
//
// Authority is checked three times: early (cheap refusal), again under locks inside the transaction
// (the administrator or the device may have moved), and in the INSERT statement itself
// (InsertTankGrantIfAllowed refuses a non-employee or a device of another customer). Tank numbers
// the device has not reported are refused (400 invalid_tank): the client does not get to invent
// authorization objects.
func putUserTankGrantsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		target, ok := loadGrantTarget(c, deps, actor, "tank.grants.set")
		if !ok {
			return
		}
		device := c.Param("device")

		var req putTankGrantsRequest
		if err := c.ShouldBindJSON(&req); err != nil || len(req.Tanks) == 0 {
			apiError(c, http.StatusBadRequest, "invalid_request", `"tanks" is required: the full list of tank numbers (an empty list revokes all)`)
			return
		}
		var raw []int
		if err := json.Unmarshal(req.Tanks, &raw); err != nil || raw == nil {
			apiError(c, http.StatusBadRequest, "invalid_request", `"tanks" must be a list of integers`)
			return
		}
		want := map[int]bool{}
		for _, t := range raw {
			if t < 1 || t > protocol.MaxTankCount {
				apiError(c, http.StatusBadRequest, "invalid_tank", "tank numbers are integers between 1 and "+strconv.Itoa(protocol.MaxTankCount))
				return
			}
			want[t] = true
		}

		ctx := c.Request.Context()
		// The actor is a platform administrator, so existence is not an oracle here.
		dev, derr := deps.Queries.GetDevice(ctx, device)
		if errors.Is(derr, pgx.ErrNoRows) {
			apiError(c, http.StatusNotFound, "device_not_found", "device not found")
			return
		}
		if derr != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": derr.Error()})
			return
		}
		if !dev.CustomerID.Valid || !target.CustomerID.Valid || dev.CustomerID.Int64 != target.CustomerID.Int64 {
			apiError(c, http.StatusConflict, "device_customer_mismatch", "the device does not belong to the employee's customer")
			return
		}

		// Newly requested tanks must be tanks the device has reported. Revocations never need ThingsBoard.
		current, err := grantedFor(c, deps, target.ID, device)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		var added []int
		for t := range want {
			if !current[t] {
				added = append(added, t)
			}
		}
		sort.Ints(added)
		if len(added) > 0 {
			known, reported, status, err := deviceTankIndexes(c, deps, device)
			if err != nil {
				c.JSON(status, gin.H{"error": err.Error()})
				return
			}
			if !reported {
				apiError(c, http.StatusConflict, "tanks_unknown", "the device has not reported its tanks yet; grants can be made once it has")
				return
			}
			have := map[int]bool{}
			for _, t := range known {
				have[t] = true
			}
			for _, t := range added {
				if !have[t] {
					apiError(c, http.StatusBadRequest, "invalid_tank", "tank "+strconv.Itoa(t)+" is not reported by this device")
					return
				}
			}
		}

		var changed, denied, targetGone bool
		var before, after []int
		err = withTx(ctx, deps, func(q *db.Queries) error {
			actorNow, err := lockStationActor(ctx, q, actor.ID)
			if err != nil {
				return err
			}
			actor = actorNow
			tNow, err := q.GetUserForUpdate(ctx, target.ID)
			if errors.Is(err, pgx.ErrNoRows) {
				targetGone = true
				return nil
			}
			if err != nil {
				return err
			}
			dNow, err := q.GetDeviceForUpdate(ctx, device)
			if errors.Is(err, pgx.ErrNoRows) {
				targetGone = true
				return nil
			}
			if err != nil {
				return err
			}
			if tNow.Role != control.RoleCustomerUser || !tNow.CustomerID.Valid || !dNow.CustomerID.Valid ||
				tNow.CustomerID.Int64 != dNow.CustomerID.Int64 || actor.Role != control.RolePlatformAdmin {
				denied = true
				return nil
			}
			rows, err := q.ListTankGrantsForUser(ctx, target.ID)
			if err != nil {
				return err
			}
			have := map[int]bool{}
			for _, r := range rows {
				if r.DeviceName == device {
					have[int(r.Tank)] = true
				}
			}
			for t := range have {
				before = append(before, t)
				if !want[t] {
					if _, err := q.DeleteTankGrant(ctx, db.DeleteTankGrantParams{UserID: target.ID, DeviceName: device, Tank: int32(t)}); err != nil {
						return err
					}
					changed = true
				}
			}
			for t := range want {
				if have[t] {
					continue
				}
				n, err := q.InsertTankGrantIfAllowed(ctx, db.InsertTankGrantIfAllowedParams{
					UserID: target.ID, DeviceName: device, Tank: int32(t),
					GrantedBy: pgtype.Int8{Int64: actor.ID, Valid: true},
				})
				if err != nil {
					return err
				}
				if n == 0 {
					// The statement refused: the employee or device changed in a way the checks above
					// did not see. Roll back rather than report a grant that was not made.
					denied = true
					return errGrantRefused
				}
				changed = true
			}
			for t := range want {
				after = append(after, t)
			}
			sort.Ints(before)
			sort.Ints(after)
			if !changed {
				return nil
			}
			return auditAdmin(ctx, q, actor, "tank.grants.set", device, tNow.CustomerID, "allowed",
				map[string]any{"userId": target.ID, "before": before, "after": after})
		})
		switch {
		case errors.Is(err, errStationActorLost) || (err == nil && denied) || errors.Is(err, errGrantRefused):
			refuse(c, deps, actor, "tank.grants.set", "authority changed", map[string]any{"userId": target.ID, "device": device})
		case err != nil:
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		case targetGone:
			apiError(c, http.StatusNotFound, "device_not_found", "user or device not found")
		default:
			if after == nil {
				after = []int{}
			}
			c.JSON(http.StatusOK, gin.H{"userId": target.ID, "deviceName": device, "tanks": after, "changed": changed})
		}
	}
}

var errGrantRefused = errors.New("grant refused by the database guard")

// grantedFor is a target employee's granted tanks on a device as a set.
func grantedFor(c *gin.Context, deps Deps, userID int64, device string) (map[int]bool, error) {
	rows, err := deps.Queries.ListGrantedTanks(c.Request.Context(), db.ListGrantedTanksParams{UserID: userID, DeviceName: device})
	if err != nil {
		return nil, err
	}
	out := make(map[int]bool, len(rows))
	for _, t := range rows {
		out[int(t)] = true
	}
	return out, nil
}
