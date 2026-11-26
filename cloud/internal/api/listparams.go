package api

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/protocol"
)

const (
	defaultLimit = 50
	maxLimit     = 500
)

// listParams is the shared query shape for the device history endpoints — /api/alarms (alarms.go),
// /api/oil-in-out (stock.go), /api/shift-records and /api/stock-records (shiftstock.go), all served
// from the business database (issues #29, #85). "History for one device, filtered by time range,
// paginated."
//
// The old /api/shifts stays gone (TestShiftsEndpointIsGone): it predated any shift uplink and could
// only answer an empty list. /api/shift-records replaced it once the device reported shifts. `device` is
// required — unlike the doc's draft routes, CLAUDE.md's multi-device cloud table design means there
// is no well-defined "all devices" listing, so callers must scope to one deviceName explicitly.
type listParams struct {
	device string
	begin  pgtype.Timestamptz
	end    pgtype.Timestamptz
	limit  int32
	offset int32
	asc    bool
	// Optional tank filter (#84), applied in SQL before LIMIT/OFFSET so paging and truncation describe
	// the filtered rows. Invalid = no filter. A named tank the caller may not see is refused by
	// requireListTank, never answered as an empty list.
	tank pgtype.Int4
}

func parseListParams(c *gin.Context) (listParams, bool) {
	device := c.Query("device")
	if device == "" {
		apiError(c, http.StatusBadRequest, "device_required", "device query param is required")
		return listParams{}, false
	}

	// Default window is the last 24h, not "since epoch" — matching /api/audit's own default
	// (control.go's timeRange). An epoch-to-now default combined with the default limit/ascending
	// sort below silently answered "the oldest 50 records ever" for any caller that omitted
	// beginTime, which is backwards for a history endpoint whose obvious default use is "what
	// just happened". Found still open in review of PR #11.
	begin := time.Now().Add(-24 * time.Hour).UTC()
	if v := c.Query("beginTime"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			apiError(c, http.StatusBadRequest, "invalid_time_format", "beginTime must be RFC3339")
			return listParams{}, false
		}
		begin = t
	}

	end := time.Now().UTC()
	if v := c.Query("endTime"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			apiError(c, http.StatusBadRequest, "invalid_time_format", "endTime must be RFC3339")
			return listParams{}, false
		}
		end = t
	}

	limit := int32(defaultLimit)
	if v := c.Query("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n <= 0 || n > maxLimit {
			apiError(c, http.StatusBadRequest, "invalid_paging", "limit must be an integer between 1 and 500")
			return listParams{}, false
		}
		limit = int32(n)
	}

	offset := int32(0)
	if v := c.Query("offset"); v != "" {
		// 32-bit parse: offset is an int32 query parameter; a larger value must be rejected, not wrapped.
		n, err := strconv.ParseInt(v, 10, 32)
		if err != nil || n < 0 {
			apiError(c, http.StatusBadRequest, "invalid_paging", "offset must be a non-negative integer")
			return listParams{}, false
		}
		offset = int32(n)
	}

	asc := true
	if v := c.Query("sort"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			apiError(c, http.StatusBadRequest, "invalid_paging", "sort must be a boolean")
			return listParams{}, false
		}
		asc = b
	}

	var tank pgtype.Int4
	if v := c.Query("tank"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > protocol.MaxTankCount {
			apiError(c, http.StatusBadRequest, "invalid_tank", "tank must be an integer between 1 and "+strconv.Itoa(protocol.MaxTankCount))
			return listParams{}, false
		}
		tank = pgtype.Int4{Int32: int32(n), Valid: true}
	}

	return listParams{
		tank:   tank,
		device: device,
		begin:  pgtype.Timestamptz{Time: begin, Valid: true},
		end:    pgtype.Timestamptz{Time: end, Valid: true},
		limit:  limit,
		offset: offset,
		asc:    asc,
	}, true
}

// requireListTank is the tank half of the access rule for a filtered list: after recordWindow has
// authorized the device, a named tank the caller may not view answers 403 tank_not_authorized — the same
// whether the tank exists or not, so it cannot be used to count a device's tanks (cloud#63). Without a
// filter the SQL already limits the rows to the caller's tanks.
func requireListTank(c *gin.Context, deps Deps, p listParams) bool {
	if !p.tank.Valid {
		return true
	}
	return requireTankAccess(c, deps, p.device, int(p.tank.Int32))
}
