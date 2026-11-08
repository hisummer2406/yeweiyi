package control

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
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
//     follows can be attributed;
//   - a per-device gate on top of the deployment-wide AllowConfigCommands.

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

// Config-command gate scopes, reported to the client so it can say "not open on this system" versus
// "not open on this device".
const (
	GateScopeDeployment = "deployment"
	GateScopeDevice     = "device"
)

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

// configTarget is tank:<n> or device for the configuration methods, NULL otherwise. args has already
// passed protocol.ValidateArgs, so tank is a valid integer.
func configTarget(method string, args json.RawMessage) pgtype.Text {
	switch method {
	case protocol.MethodConfigDeviceSettings:
		return pgtype.Text{String: "device", Valid: true}
	case protocol.MethodConfigTankSettings:
		var v struct {
			Tank json.Number `json:"tank"`
		}
		if json.Unmarshal(args, &v) == nil {
			if n, err := strconv.Atoi(v.Tank.String()); err == nil {
				return pgtype.Text{String: "tank:" + strconv.Itoa(n), Valid: true}
			}
		}
	}
	return pgtype.Text{}
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

// deviceGateOpen reads the per-device gate. A device row that does not exist reads as closed.
func (s *Service) deviceGateOpen(ctx context.Context, deviceName string) (bool, error) {
	open, err := s.q.GetDeviceConfigCommandsEnabled(ctx, deviceName)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	return open, err
}

// createConfigCommand creates a configuration command inside one transaction: station row (FOR SHARE)
// → device row (FOR SHARE) → compare station and per-device gate → insert. The lock order is the one
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
	dev, err := q.LockDeviceForCommand(ctx, req.DeviceName)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return db.ControlCommand{}, fmt.Errorf("%w: device %q", ErrForbidden, req.DeviceName)
		}
		return db.ControlCommand{}, fmt.Errorf("lock device: %w", err)
	}
	if dev.StationID.Valid != req.ExpectedStationID.Valid ||
		(dev.StationID.Valid && dev.StationID.Int64 != req.ExpectedStationID.Int64) {
		return db.ControlCommand{}, &StationChangedError{Current: dev.StationID}
	}
	if !dev.ConfigCommandsEnabledAt.Valid {
		return db.ControlCommand{}, &ConfigDisabledError{Scope: GateScopeDevice}
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
	if res.Status != protocol.CmdStatusSucceeded && res.Status != protocol.CmdStatusFailed {
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
