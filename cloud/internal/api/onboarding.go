package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// Customer onboarding (cloud#60, contract docs/customer-onboarding-contract.md). A customer is born
// "configuring" and its accounts cannot sign in until a platform admin completes onboarding, which
// requires at least one usable customer admin, one station, and one device placed in a station.

const (
	codeOnboardingIncomplete = "onboarding_incomplete"
	codeCustomerNotFound     = "customer_not_found"
)

type onboardingState struct {
	CustomerID int64    `json:"customerId"`
	Status     string   `json:"status"`
	Complete   bool     `json:"complete"`
	Missing    []string `json:"missing"`
	Counts     struct {
		CustomerAdmins   int64 `json:"customerAdmins"`
		Stations         int64 `json:"stations"`
		DevicesInStation int64 `json:"devicesInStation"`
	} `json:"counts"`
}

func onboardingStateFor(ctx context.Context, q *db.Queries, cust db.Customer) (onboardingState, error) {
	n, err := q.CustomerOnboardingCounts(ctx, pgtype.Int8{Int64: cust.ID, Valid: true})
	if err != nil {
		return onboardingState{}, err
	}
	st := onboardingState{CustomerID: cust.ID, Status: cust.OnboardingStatus, Missing: []string{}}
	st.Counts.CustomerAdmins, st.Counts.Stations, st.Counts.DevicesInStation = n.CustomerAdmins, n.Stations, n.DevicesInStation
	if cust.OnboardingStatus != customerActive {
		if n.CustomerAdmins == 0 {
			st.Missing = append(st.Missing, "customer_admin")
		}
		if n.Stations == 0 {
			st.Missing = append(st.Missing, "station")
		}
		if n.DevicesInStation == 0 {
			st.Missing = append(st.Missing, "device_in_station")
		}
	}
	st.Complete = len(st.Missing) == 0
	return st, nil
}

// onboardingActor resolves the caller and the customer. Only platform admins pass; everyone else gets
// the plain role refusal before the id is even looked at.
func onboardingActor(c *gin.Context, deps Deps, action string) (db.User, int64, bool) {
	actor, ok := requireAdminActor(c, deps)
	if !ok {
		return actor, 0, false
	}
	if actor.Role != control.RolePlatformAdmin {
		refuse(c, deps, actor, action, "platform admin only", nil)
		return actor, 0, false
	}
	id, ok := parseIDParam(c)
	return actor, id, ok
}

func getOnboardingHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		_, id, ok := onboardingActor(c, deps, "customer.onboarding.get")
		if !ok {
			return
		}
		ctx := c.Request.Context()
		cust, err := deps.Queries.GetCustomer(ctx, id)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				apiError(c, http.StatusNotFound, codeCustomerNotFound, "customer not found")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		st, err := onboardingStateFor(ctx, deps.Queries, cust)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, st)
	}
}

// completeOnboardingHandler locks the customer row, re-checks the conditions inside the same
// transaction, and flips the status. Repeating it on an active customer is a no-op 200.
func completeOnboardingHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, id, ok := onboardingActor(c, deps, "customer.onboard")
		if !ok {
			return
		}
		ctx := c.Request.Context()

		var (
			state    onboardingState
			notFound bool
			denied   bool
		)
		err := withTx(ctx, deps, func(q *db.Queries) error {
			cust, err := q.GetCustomerForUpdate(ctx, id)
			if err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					notFound = true
					return nil
				}
				return err
			}
			if cust.OnboardingStatus == customerActive {
				state, err = onboardingStateFor(ctx, q, cust)
				return err
			}
			state, err = onboardingStateFor(ctx, q, cust)
			if err != nil {
				return err
			}
			if !state.Complete {
				denied = true
				return nil // audited outside: a rollback would discard the denied row
			}
			cust, err = q.MarkCustomerOnboarded(ctx, id)
			if err != nil {
				return err
			}
			state, err = onboardingStateFor(ctx, q, cust)
			if err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "customer.onboard", "", pgtype.Int8{}, "allowed",
				map[string]any{"customerId": id})
		})
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		switch {
		case notFound:
			apiError(c, http.StatusNotFound, codeCustomerNotFound, "customer not found")
		case denied:
			_ = auditAdmin(ctx, deps.Queries, actor, "customer.onboard", "", pgtype.Int8{}, "denied",
				map[string]any{"customerId": id, "missing": state.Missing})
			c.JSON(http.StatusConflict, gin.H{
				"error": "onboarding is not complete", "code": codeOnboardingIncomplete, "missing": state.Missing,
			})
		default:
			c.JSON(http.StatusOK, state)
		}
	}
}
