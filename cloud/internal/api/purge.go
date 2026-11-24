package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// Device purge (cloud#83, contract docs/device-purge-contract.md): deletes one device and all of its
// cloud history. internal/purge does the deleting and the task row says how far it got. Since issue #69
// the web can no longer create or restart a task (see purgeDeviceHandler); the task list stays for audit.

const (
	auditDevicePurge  = "device.purge"
	recentPurgesLimit = 100
)

type purgeUser struct {
	ID       *int64 `json:"id"`
	Username string `json:"username"`
}

type purgeResponse struct {
	ID          int64            `json:"id"`
	DeviceName  string           `json:"deviceName"`
	Status      string           `json:"status"`
	Step        string           `json:"step"`
	Error       *string          `json:"error,omitempty"`
	Counts      map[string]int64 `json:"counts"`
	RequestedBy purgeUser        `json:"requestedBy"`
	Reason      string           `json:"reason"`
	CustomerID  *int64           `json:"customerId"`
	StationID   *int64           `json:"stationId"`
	StartedAt   time.Time        `json:"startedAt"`
	FinishedAt  *time.Time       `json:"finishedAt"`
}

func toPurgeResponse(p db.DevicePurge) purgeResponse {
	out := purgeResponse{
		ID: p.ID, DeviceName: p.DeviceName, Status: p.Status, Step: p.Step,
		Counts:      map[string]int64{},
		RequestedBy: purgeUser{ID: int8Ptr(p.RequestedBy), Username: p.RequestedUsername},
		Reason:      p.Reason, CustomerID: int8Ptr(p.CustomerID), StationID: int8Ptr(p.StationID),
		StartedAt: p.StartedAt.Time,
	}
	_ = json.Unmarshal(p.Counts, &out.Counts)
	if p.Error.Valid {
		out.Error = &p.Error.String
	}
	if p.FinishedAt.Valid {
		out.FinishedAt = &p.FinishedAt.Time
	}
	return out
}

// requirePlatformAdmin is the gate of every purge route: read from the database, before the body is
// parsed, with the "account" 403 for every other role (the refusal is about the role, not an object).
func requirePlatformAdmin(c *gin.Context, deps Deps) (db.User, bool) {
	actor, ok := requireAdminActor(c, deps)
	if !ok {
		return actor, false
	}
	if actor.Role != control.RolePlatformAdmin {
		refuse(c, deps, actor, auditDevicePurge, "platform admin only", map[string]any{"device": c.Param("device")})
		return actor, false
	}
	return actor, true
}

// purgeDeviceHandler is POST /api/devices/:device/purge. Starting or restarting a purge from the web is
// switched off (issue #69): everyday removal is unassigning the device from its station, which keeps its
// ThingsBoard entity, credentials and history. The route stays so an old page or a direct call gets a
// clear refusal instead of a 404; every caller is answered before the body is read and nothing is
// written except the denied audit. Existing tasks stay readable through the GET routes below, and a
// task already running when the process restarts is still resumed by internal/purge.
func purgeDeviceHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requirePlatformAdmin(c, deps)
		if !ok {
			return
		}
		auditRefusal(c, deps, actor, auditDevicePurge, "web purge disabled", map[string]any{"device": c.Param("device")})
		apiError(c, http.StatusGone, "device_purge_disabled",
			"device purge from the web is disabled; unassign the device from its station instead")
	}
}

// getDevicePurgeHandler is GET /api/device-purges/:id.
func getDevicePurgeHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if _, ok := requirePlatformAdmin(c, deps); !ok {
			return
		}
		id, err := strconv.ParseInt(c.Param("id"), 10, 64)
		if err != nil {
			apiError(c, http.StatusNotFound, "purge_not_found", "purge not found")
			return
		}
		task, err := deps.Queries.GetDevicePurge(c.Request.Context(), id)
		if errors.Is(err, pgx.ErrNoRows) {
			apiError(c, http.StatusNotFound, "purge_not_found", "purge not found")
			return
		}
		if err != nil {
			internalError(c, err)
			return
		}
		c.JSON(http.StatusOK, gin.H{"purge": toPurgeResponse(task)})
	}
}

// listDevicePurgesHandler is GET /api/device-purges[?deviceName=].
func listDevicePurgesHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if _, ok := requirePlatformAdmin(c, deps); !ok {
			return
		}
		ctx := c.Request.Context()
		var tasks []db.DevicePurge
		var err error
		if name := c.Query("deviceName"); name != "" {
			tasks, err = deps.Queries.ListDevicePurgesByDevice(ctx, name)
		} else {
			tasks, err = deps.Queries.ListRecentDevicePurges(ctx, recentPurgesLimit)
		}
		if err != nil {
			internalError(c, err)
			return
		}
		rows := make([]purgeResponse, 0, len(tasks))
		for _, t := range tasks {
			rows = append(rows, toPurgeResponse(t))
		}
		c.JSON(http.StatusOK, gin.H{"rows": rows})
	}
}

// purgeInProgress answers 409 device_purge_in_progress. Callers check only after the caller is known
// to be allowed to see the device, so the answer is no oracle for device names.
func purgeInProgress(c *gin.Context) {
	apiError(c, http.StatusConflict, "device_purge_in_progress", "the device is being deleted")
}

// devicePurging reports whether the device has an unfinished purge task.
func devicePurging(ctx context.Context, q *db.Queries, device string) (bool, error) {
	return q.DevicePurgeInProgress(ctx, device)
}
