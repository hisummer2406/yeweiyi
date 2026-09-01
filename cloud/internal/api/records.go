package api

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/store/db"
)

const (
	defaultLimit = 50
	maxLimit     = 500
)

// listParams is the shared shape behind /api/alarms (alarms.go, now read from ThingsBoard),
// /api/oil-in-out and /api/shifts: all are "history for one device, filtered by time range,
// paginated". `device` is
// required — unlike the doc's draft routes, CLAUDE.md's multi-device cloud table design means there
// is no well-defined "all devices" listing, so callers must scope to one deviceName explicitly.
type listParams struct {
	device string
	begin  pgtype.Timestamptz
	end    pgtype.Timestamptz
	limit  int32
	offset int32
	asc    bool
}

func parseListParams(c *gin.Context) (listParams, bool) {
	device := c.Query("device")
	if device == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "device query param is required"})
		return listParams{}, false
	}

	begin := time.Unix(0, 0).UTC()
	if v := c.Query("beginTime"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "beginTime must be RFC3339"})
			return listParams{}, false
		}
		begin = t
	}

	end := time.Now().UTC()
	if v := c.Query("endTime"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "endTime must be RFC3339"})
			return listParams{}, false
		}
		end = t
	}

	limit := int32(defaultLimit)
	if v := c.Query("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n <= 0 || n > maxLimit {
			c.JSON(http.StatusBadRequest, gin.H{"error": "limit must be an integer between 1 and 500"})
			return listParams{}, false
		}
		limit = int32(n)
	}

	offset := int32(0)
	if v := c.Query("offset"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 0 {
			c.JSON(http.StatusBadRequest, gin.H{"error": "offset must be a non-negative integer"})
			return listParams{}, false
		}
		offset = int32(n)
	}

	asc := true
	if v := c.Query("sort"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "sort must be a boolean"})
			return listParams{}, false
		}
		asc = b
	}

	return listParams{
		device: device,
		begin:  pgtype.Timestamptz{Time: begin, Valid: true},
		end:    pgtype.Timestamptz{Time: end, Valid: true},
		limit:  limit,
		offset: offset,
		asc:    asc,
	}, true
}

func listOilInOutHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		p, ok := parseListParams(c)
		if !ok {
			return
		}
		if !requireDeviceOwnership(c, deps, p.device) {
			return
		}

		if p.asc {
			rows, err := deps.Queries.ListOilInOutAsc(c.Request.Context(), db.ListOilInOutAscParams{
				DeviceName: p.device, Time: p.begin, Time_2: p.end, Limit: p.limit, Offset: p.offset,
			})
			respondRows(c, rows, err)
			return
		}
		rows, err := deps.Queries.ListOilInOutDesc(c.Request.Context(), db.ListOilInOutDescParams{
			DeviceName: p.device, Time: p.begin, Time_2: p.end, Limit: p.limit, Offset: p.offset,
		})
		respondRows(c, rows, err)
	}
}

func listShiftsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		p, ok := parseListParams(c)
		if !ok {
			return
		}
		if !requireDeviceOwnership(c, deps, p.device) {
			return
		}

		if p.asc {
			rows, err := deps.Queries.ListShiftsAsc(c.Request.Context(), db.ListShiftsAscParams{
				DeviceName: p.device, Time: p.begin, Time_2: p.end, Limit: p.limit, Offset: p.offset,
			})
			respondRows(c, rows, err)
			return
		}
		rows, err := deps.Queries.ListShiftsDesc(c.Request.Context(), db.ListShiftsDescParams{
			DeviceName: p.device, Time: p.begin, Time_2: p.end, Limit: p.limit, Offset: p.offset,
		})
		respondRows(c, rows, err)
	}
}

func respondRows[T any](c *gin.Context, rows []T, err error) {
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	if rows == nil {
		rows = []T{}
	}
	c.JSON(http.StatusOK, gin.H{"rows": rows})
}
