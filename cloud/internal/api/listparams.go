package api

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgtype"
)

const (
	defaultLimit = 50
	maxLimit     = 500
)

// listParams is the shared query shape for the device history endpoints — /api/alarms (alarms.go)
// and /api/oil-in-out (stock.go), both of which now read through to ThingsBoard. "History for one
// device, filtered by time range, paginated."
//
// /api/shifts (交班) used to share it too. That endpoint was removed rather than migrated: 设备协议 V1
// has no shift uplink on either side, so it could only ever have returned an empty list. Leaving a
// route that answers 200 with nothing is worse than having no route — it looks like a working
// feature. See docs/protocol-v1-reconciliation.md. `device` is
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
