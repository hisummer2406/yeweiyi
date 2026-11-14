package control

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"reflect"
	"regexp"
	"strconv"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// Submission protection for configuration commands (openspec change config-command-delivery, #3;
// contract: docs/config-command-contract.md). Four things a client cannot do for itself:
//
//   - requestId: one (operator, device, requestId) is at most one command, so a client that timed out
//     can resubmit without the device being written twice;
//   - expectedStationId: re-checked inside the transaction that creates the command, so a confirmation
//     made against one station's device cannot land after the station was re-equipped;
//   - one non-terminal command per config target (tank:N / device), so a result and the mirror that
//     follows can be attributed.
//
// The only gate is the deployment-wide AllowConfigCommands. A per-device whitelist existed briefly
// (migration 000038) and was removed on the user's decision (2026-11-09, migration 000039): an
// administrator opening the settings page can change it directly.

var (
	// ErrRequestIDRequired: a configuration method was submitted without requestId.
	ErrRequestIDRequired = errors.New("control: requestId is required for configuration commands")
	// ErrRequestIDInvalid: requestId is not 1–64 of [A-Za-z0-9_-].
	ErrRequestIDInvalid = errors.New("control: requestId must be 1-64 characters of [A-Za-z0-9_-]")
	// ErrRequestIDReused: the same requestId was used for different content.
	ErrRequestIDReused = errors.New("control: requestId already used for a different command")
	// ErrExpectedStationRequired: a configuration method was submitted without expectedStationId.
	ErrExpectedStationRequired = errors.New("control: expectedStationId is required for configuration commands")
	// ErrDeviceStationChanged: the device is no longer on the station the client confirmed against.
	// Returned as *StationChangedError.
	ErrDeviceStationChanged = errors.New("control: the device's station changed since the form was opened")
	// ErrConfigCommandInFlight: the target already has a non-terminal configuration command.
	// Returned as *InFlightError.
	ErrConfigCommandInFlight = errors.New("control: a configuration command for this target is still in flight")
	// ErrTransactionsUnavailable: Config.Pool is not set, so configuration commands cannot be created
	// under the locks their station check needs. A wiring mistake, never a user error.
	ErrTransactionsUnavailable = errors.New("control: configuration commands need Config.Pool")
)

// GateScopeDeployment is the scope reported with config_commands_disabled (the deployment switch is the
// only gate; the field is kept so clients written against the per-device version still parse it).
const GateScopeDeployment = "deployment"

// ConfigDisabledError is ErrConfigCommandsDisabled with the gate that refused.
type ConfigDisabledError struct{ Scope string }

func (e *ConfigDisabledError) Error() string {
	return fmt.Sprintf("%v (scope %s)", ErrConfigCommandsDisabled, e.Scope)
}
func (e *ConfigDisabledError) Unwrap() error { return ErrConfigCommandsDisabled }

// StationChangedError carries the device's current station (NULL = none). Only ever returned to a
// caller who passed the device access check, so it reveals nothing they could not already read.
type StationChangedError struct{ Current pgtype.Int8 }

func (e *StationChangedError) Error() string { return ErrDeviceStationChanged.Error() }
func (e *StationChangedError) Unwrap() error { return ErrDeviceStationChanged }

// InFlightError carries the command that holds the target.
type InFlightError struct{ CommandID string }

func (e *InFlightError) Error() string {
	return fmt.Sprintf("%v: %s", ErrConfigCommandInFlight, e.CommandID)
}
func (e *InFlightError) Unwrap() error { return ErrConfigCommandInFlight }

// Beginner is the slice of pgxpool.Pool needed to run the station check and the insert in one
// transaction.
type Beginner interface {
	Begin(ctx context.Context) (pgx.Tx, error)
}

var requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

const inFlightConstraint = "control_commands_config_in_flight_key"

// checkSubmissionFields validates requestId / expectedStationId. Runs after authorization and args
// validation, before anything is looked up or recorded.
func checkSubmissionFields(req IssueRequest) error {
	config := isConfigWriteMethod(req.Method)
	if req.RequestID == "" {
		if config {
			return ErrRequestIDRequired
		}
	} else if !requestIDPattern.MatchString(req.RequestID) {
		return ErrRequestIDInvalid
	}
	if config && !req.ExpectedStationSet {
		return ErrExpectedStationRequired
	}
	return nil
}

// configTarget is tank:<n> or device for the configuration methods, NULL for every other method. args has
// already passed protocol.ValidateArgs, which accepts any integral JSON number for tank (1, 1.0, 1e0), so
// the value is read as a number, not as text — reading it as text once let "1.0" yield NULL and slip past
// the in-flight index (Codex review, PR #13). ok=false for a configuration method means no target could
// be derived; the caller refuses rather than issuing an unprotected command.
func configTarget(method string, args json.RawMessage) (pgtype.Text, bool) {
	switch method {
	case protocol.MethodConfigDeviceSettings:
		return pgtype.Text{String: "device", Valid: true}, true
	case protocol.MethodConfigTankSettings:
		var v struct {
			Tank *float64 `json:"tank"`
		}
		if json.Unmarshal(args, &v) != nil || v.Tank == nil {
			return pgtype.Text{}, false
		}
		n := *v.Tank
		if n != math.Trunc(n) || n < 1 || n > float64(protocol.MaxDeviceTankSettings) {
			return pgtype.Text{}, false
		}
		return pgtype.Text{String: "tank:" + strconv.Itoa(int(n)), Valid: true}, true
	}
	return pgtype.Text{}, true
}

// lookupOwnSubmission finds the caller's earlier command for this requestId under the same visibility rule
// as GET .../commands?requestId= (customer-scoped: a user moved to another customer no longer sees what they
// filed under the old one — Codex review, PR #13). found=false, hidden=true: a command with this key exists
// but is not visible to the caller any more; it is answered as request_id_reused, never with its content.
func (s *Service) lookupOwnSubmission(ctx context.Context, req IssueRequest) (cmd db.ControlCommand, found, hidden bool, err error) {
	rid := pgtype.Text{String: req.RequestID, Valid: true}
	cmd, err = s.q.GetOwnControlCommandByRequestIDVisible(ctx, db.GetOwnControlCommandByRequestIDVisibleParams{
		UserID: req.Actor.UserID, RequestID: rid, DeviceName: req.DeviceName,
	})
	if err == nil {
		return cmd, true, false, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return db.ControlCommand{}, false, false, err
	}
	_, err = s.q.GetControlCommandByRequestID(ctx, db.GetControlCommandByRequestIDParams{
		OperatorID: pgtype.Int8{Int64: req.Actor.UserID, Valid: true}, DeviceName: req.DeviceName, RequestID: rid,
	})
	if errors.Is(err, pgx.ErrNoRows) {
		return db.ControlCommand{}, false, false, nil
	}
	if err != nil {
		return db.ControlCommand{}, false, false, err
	}
	// The row exists. A concurrent duplicate may have committed between the scoped read above and the
	// unscoped one, so read the scoped view once more (a fresh statement sees the committed row): only if
	// it is still invisible is it really another customer's (integration test caught this race).
	cmd, err = s.q.GetOwnControlCommandByRequestIDVisible(ctx, db.GetOwnControlCommandByRequestIDVisibleParams{
		UserID: req.Actor.UserID, RequestID: rid, DeviceName: req.DeviceName,
	})
	if err == nil {
		return cmd, true, false, nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return db.ControlCommand{}, false, true, nil
	}
	return db.ControlCommand{}, false, false, err
}

// sameSubmission compares what a replay must match: method, args (as JSON values, so key order and
// 610 vs 610.0 do not matter — the stored copy went through jsonb), reason and expected station.
func sameSubmission(cmd db.ControlCommand, req IssueRequest, args json.RawMessage) bool {
	if cmd.Method != req.Method || cmd.Reason != req.Reason {
		return false
	}
	if cmd.ExpectedStationID.Valid != req.ExpectedStationID.Valid ||
		(cmd.ExpectedStationID.Valid && cmd.ExpectedStationID.Int64 != req.ExpectedStationID.Int64) {
		return false
	}
	var a, b any
	if json.Unmarshal(cmd.Args, &a) != nil || json.Unmarshal(args, &b) != nil {
		return false
	}
	return reflect.DeepEqual(a, b)
}

// replay answers a submission whose requestId already has a command: the stored command if the
// content matches (nothing is sent again), ErrRequestIDReused otherwise.
func (s *Service) replay(ctx context.Context, existing db.ControlCommand, req IssueRequest, args json.RawMessage) (db.ControlCommand, bool, error) {
	if !sameSubmission(existing, req, args) {
		s.audit(ctx, req.Actor, "control.issue", req.DeviceName, pgtype.UUID{}, "denied",
			map[string]any{"method": req.Method, "requestId": req.RequestID, "error": "requestId reused for different content"})
		return db.ControlCommand{}, false, ErrRequestIDReused
	}
	s.audit(ctx, req.Actor, "control.replay", req.DeviceName, existing.CommandID, "allowed",
		map[string]any{"method": req.Method, "requestId": req.RequestID, "storedStatus": existing.Status})
	return existing, true, nil
}

// createConfigCommand creates a configuration command inside one transaction: station row (FOR SHARE)
// → device row (FOR SHARE) → compare station → insert. The lock order is the one
// replace-device uses, so the two serialize rather than deadlock: either the re-equip commits first and
// the check here fails, or this commits first and the command goes to the device the user confirmed.
//
// Returns (row, nil) on insert; (existing, errReplayFound) when a concurrent duplicate won the request_id
// race; the typed errors for the refusals.
func (s *Service) createConfigCommand(ctx context.Context, params db.CreateControlCommandParams, req IssueRequest) (db.ControlCommand, error) {
	if s.cfg.Pool == nil {
		return db.ControlCommand{}, ErrTransactionsUnavailable
	}
	tx, err := s.cfg.Pool.Begin(ctx)
	if err != nil {
		return db.ControlCommand{}, fmt.Errorf("begin: %w", err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after Commit
	q := s.q.WithTx(tx)

	if req.ExpectedStationID.Valid {
		// A station that no longer exists is simply not locked; the device comparison below fails.
		if _, err := q.LockStationShared(ctx, req.ExpectedStationID.Int64); err != nil && !errors.Is(err, pgx.ErrNoRows) {
			return db.ControlCommand{}, fmt.Errorf("lock station: %w", err)
		}
	}
	stationID, err := q.LockDeviceForCommand(ctx, req.DeviceName)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return db.ControlCommand{}, fmt.Errorf("%w: device %q", ErrForbidden, req.DeviceName)
		}
		return db.ControlCommand{}, fmt.Errorf("lock device: %w", err)
	}
	if stationID.Valid != req.ExpectedStationID.Valid ||
		(stationID.Valid && stationID.Int64 != req.ExpectedStationID.Int64) {
		return db.ControlCommand{}, &StationChangedError{Current: stationID}
	}

	cmd, err := q.CreateControlCommand(ctx, params)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" && pgErr.ConstraintName == inFlightConstraint {
			_ = tx.Rollback(ctx)
			held, lookErr := s.q.GetInFlightConfigCommand(ctx, db.GetInFlightConfigCommandParams{
				DeviceName: req.DeviceName, ConfigTarget: params.ConfigTarget,
			})
			if lookErr != nil {
				// Finished between the violation and the lookup; the client may simply retry.
				return db.ControlCommand{}, &InFlightError{}
			}
			return db.ControlCommand{}, &InFlightError{CommandID: uuidString(held.CommandID)}
		}
		return db.ControlCommand{}, err // includes pgx.ErrNoRows, handled by the caller
	}
	if err := tx.Commit(ctx); err != nil {
		return db.ControlCommand{}, fmt.Errorf("commit: %w", err)
	}
	return cmd, nil
}

// requiredLevel is the tier string CreateControlCommand checks against the user's current role.
func requiredLevel(p protocol.PermissionLevel) string { return string(p) }

// recordLateResult keeps a cmdResult that arrived for a command already closed (most often 'expired')
// as an audit row, once per command, without touching the command: the state machine stays as it is
// (invariant 4), but "the device did run it after all" is no longer lost. A result whose status equals
// the stored one is an ordinary redelivery and is not recorded; neither is a non-terminal 'executing'.
func (s *Service) recordLateResult(ctx context.Context, deviceName string, commandID pgtype.UUID, res protocol.CmdResult) {
	switch res.Status {
	case protocol.CmdStatusSucceeded, protocol.CmdStatusFailed, protocol.CmdStatusRejected:
	default:
		return
	}
	cmd, err := s.q.GetControlCommand(ctx, commandID)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			s.cfg.Logf("control: late cmdResult %s on %s: lookup: %v", res.CommandID, deviceName, err)
		}
		return
	}
	if cmd.DeviceName != deviceName {
		// A device reporting someone else's commandId: log only, so a device cannot fill the audit log.
		s.cfg.Logf("control: cmdResult %s from %s belongs to %s; ignored", res.CommandID, deviceName, cmd.DeviceName)
		return
	}
	if !protocol.IsTerminal(cmd.Status) || cmd.Status == res.Status {
		return
	}
	if seen, err := s.q.CommandAuditExists(ctx, db.CommandAuditExistsParams{CommandID: commandID, Action: "control.result_late"}); err != nil || seen {
		return
	}
	s.audit(ctx, Actor{Username: "device:" + deviceName, CustomerID: cmd.CustomerID}, "control.result_late", deviceName, commandID, "failed",
		map[string]any{"reportedStatus": res.Status, "storedStatus": cmd.Status, "errorCode": res.ErrorCode,
			"reason": res.Reason, "detail": json.RawMessage(res.Detail)})
}
