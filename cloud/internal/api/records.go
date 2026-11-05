package api

import (
	"errors"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
)

// recordWindow authorizes the caller for p.device and returns the time window of business records
// (出入库, 报警) that caller may read. Shared by /api/oil-in-out and /api/alarms.
//
// Authorization is decided first, by the one rule (user_may_access_device), so an unauthorized
// caller cannot tell "not yours" from "does not exist". Only then is the window narrowed:
//
//   - **Lower bound: the moment the device joined its current customer** (devices.customer_assigned_at,
//     set by a trigger). The records are the previous owner's business data and must not be readable
//     by the new one; it applies to platform admins too, so the answer for a device does not depend on
//     who asks. The extra assignTxTimeout is the same allowance telemetry/history makes: the stamp is
//     taken at the UPDATE but the transfer only becomes visible at COMMIT, so a record closed in
//     between could still be the old owner's.
//   - **Upper bound: the database-clock instant of the authorization decision.** If the device is
//     reassigned between the decision and the read, a caller authorized as the old owner must not
//     see what the new owner's device recorded afterwards. Records are timestamped by the device
//     clock, so a device running ahead of the server hides its newest few records until the next
//     request; that is the price, and it is bounded by the clock skew.
//
// A device with no business row (provisioned straight in ThingsBoard) has no assignment to clip to
// and, since only bound devices are ingested, no records; it answers an empty window rather than 404.
func recordWindow(c *gin.Context, deps Deps, p listParams) (begin, end pgtype.Timestamptz, ok bool) {
	authAt, ok := requireDeviceOwnershipAt(c, deps, p.device)
	if !ok {
		return begin, end, false
	}

	b, e := p.begin.Time, p.end.Time
	row, err := deps.Queries.GetDevice(c.Request.Context(), p.device)
	switch {
	case err == nil && row.CustomerAssignedAt.Valid:
		if from := row.CustomerAssignedAt.Time.Add(assignTxTimeout); from.After(b) {
			b = from
		}
	case err != nil && !errors.Is(err, pgx.ErrNoRows):
		internalError(c, err)
		return begin, end, false
	}
	if e.After(authAt) {
		e = authAt
	}
	if !b.Before(e) {
		// Nothing this caller may see. An inverted window is a valid empty query, not an error.
		e = b
	}
	return pgtype.Timestamptz{Time: b.UTC(), Valid: true}, pgtype.Timestamptz{Time: e.UTC(), Valid: true}, true
}

// recordsBackfilled reports whether the device's initial takeover from ThingsBoard has completed
// (record_backfills). Until it has, the stored history may be only what arrived live, and a reader
// must not be told it is whole.
func recordsBackfilled(c *gin.Context, deps Deps, device string) (bool, error) {
	return deps.Queries.HasRecordBackfill(c.Request.Context(), device)
}
