package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// Device purge (cloud#83, contract docs/device-purge-contract.md): a platform administrator deletes one
// device and all of its cloud history. The request only creates (or resumes) a task; internal/purge
// does the deleting, and the task row says how far it got.

const (
	auditDevicePurge  = "device.purge"
	maxPurgeReasonLen = 500
	recentPurgesLimit = 100
)

type purgeRequest struct {
	ConfirmDeviceName string `json:"confirmDeviceName"`
	Reason            string `json:"reason"`
}

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

// purgeDeviceHandler is POST /api/devices/:device/purge.
func purgeDeviceHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requirePlatformAdmin(c, deps)
		if !ok {
			return
		}
		if deps.Purger == nil {
			apiError(c, http.StatusServiceUnavailable, "thingsboard_unavailable",
				"device purge needs ThingsBoard, which is not configured on this deployment")
			return
		}
		device := c.Param("device")
		var req purgeRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			apiError(c, http.StatusBadRequest, "invalid_request", err.Error())
			return
		}
		// Exact match: the confirmation exists to catch a wrong digit or a dropped leading zero.
		if req.ConfirmDeviceName != device {
			apiError(c, http.StatusBadRequest, "confirm_mismatch", "confirmDeviceName must repeat the device name exactly")
			return
		}
		reason := strings.TrimSpace(req.Reason)
		if reason == "" || utf8.RuneCountInString(reason) > maxPurgeReasonLen {
			apiError(c, http.StatusBadRequest, "reason_required", "reason is required (at most 500 characters)")
			return
		}

		ctx := c.Request.Context()
		var task db.DevicePurge
		var created, missing bool
		err := withTx(ctx, deps, func(q *db.Queries) error {
			dev, err := q.GetDeviceForUpdate(ctx, device)
			if err != nil && !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
			// An unfinished task wins even when the device row is already gone: the business step
			// deleted it and a later step failed. Asking again resumes that task.
			open, err := q.GetOpenDevicePurge(ctx, device)
			if err == nil {
				task, err = q.RestartDevicePurge(ctx, open.ID)
				return err
			}
			if !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
			if dev.DeviceName == "" {
				missing = true
				return nil
			}
			task, err = q.CreateDevicePurge(ctx, db.CreateDevicePurgeParams{
				DeviceName: device, Reason: reason,
				RequestedBy:       pgtype.Int8{Int64: actor.ID, Valid: true},
				RequestedUsername: actor.Username,
				CustomerID:        dev.CustomerID, StationID: dev.StationID,
			})
			if err != nil {
				return err
			}
			created = true
			return auditAdmin(ctx, q, actor, auditDevicePurge, device, dev.CustomerID, "allowed", map[string]any{
				"purgeId": task.ID, "reason": reason,
			})
		})
		if err != nil {
			internalError(c, err)
			return
		}
		if missing {
			apiError(c, http.StatusNotFound, "device_not_found", "device not found")
			return
		}

		// Detached from the request: the purge outlives the HTTP call by design.
		deps.Purger.Start(context.WithoutCancel(ctx), task.ID)

		status := http.StatusOK
		if created {
			status = http.StatusAccepted
		}
		c.JSON(status, gin.H{"purge": toPurgeResponse(task)})
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
