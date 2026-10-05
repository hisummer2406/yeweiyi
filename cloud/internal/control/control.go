// Package control owns the remote-control command state machine, its authorization rules, and the
// audit trail — the "远程控制与安全状态机" requirements of GitHub issue #1.
//
// The design rests on one distinction that the rest of this package exists to protect:
//
//	a command being *delivered* and a command being *executed* are different events,
//	learned at different times, from different messages.
//
// ThingsBoard's two-way RPC returns the device's ACK, which only says the device received and
// accepted the request. Whether the pump actually started arrives later, asynchronously, as a
// protocol.CmdResult on the telemetry stream and lands here through ApplyResult. Every status
// transition is guarded in SQL (see db/queries/control_commands.sql) so that a late or duplicated
// message cannot rewrite a terminal record.
package control

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// User roles. A full multi-tenant model (customer entities, tenant-scoped queries) is still to be
// built; these three exist now because control authorization is meaningless without them.
const (
	RolePlatformAdmin = "platform_admin"
	RoleCustomerAdmin = "customer_admin"
	RoleCustomerUser  = "customer_user"
)

// Errors callers map onto HTTP statuses.
var (
	// ErrForbidden covers both "you may not touch this device" and "your role may not issue this
	// method". They share one error deliberately: distinguishing them in the response would let a
	// caller probe which devices exist by watching the error change.
	ErrForbidden = errors.New("control: not permitted")
	// ErrUnknownMethod is a method absent from protocol.Methods.
	ErrUnknownMethod = errors.New("control: unknown method")
	// ErrReasonRequired enforces issue #1's mandatory 二次确认 justification.
	ErrReasonRequired = errors.New("control: a reason is required")
	// ErrPhysicalCommandsDisabled is returned when a method that causes a physical action is
	// requested while the interlock gate is closed. See Config.AllowPhysicalCommands.
	ErrPhysicalCommandsDisabled = errors.New("control: physical commands are disabled on this deployment")
	// ErrPlatformUnavailable means ThingsBoard could not be reached, so the command was never
	// handed over. The command row stays pending and will expire.
	ErrPlatformUnavailable = errors.New("control: device platform unavailable")
)

// Actor is who an action — and therefore its audit row — is attributed to.
type Actor struct {
	UserID   int64
	Username string
	Role     string
	// CustomerID is the customer snapshot stored on the command and its audit rows. Issue fills it in
	// for a user (the user's own customer, or for a platform admin the device's current customer);
	// the device and system actors below carry their command's customer instead.
	CustomerID pgtype.Int8
}

// Sender is the slice of tbclient.Client this package needs. Narrowing it to an interface keeps the
// authorization and state-machine logic testable without a running ThingsBoard, while the e2e tests
// still pass the real client through it.
type Sender interface {
	GetDeviceByName(ctx context.Context, name string) (tbclient.Device, error)
	SendCommand(ctx context.Context, deviceID, method string, env protocol.Envelope, timeoutMs int64) (protocol.Ack, error)
}

// Config tunes one Service.
type Config struct {
	// CommandTTL is how long a command stays valid. Both the device and the cloud-side sweep
	// enforce it. Short by design: the window exists so a command that sat through a network
	// outage cannot fire long after the operator asked for it and walked away.
	CommandTTL time.Duration
	// AckTimeout bounds how long ThingsBoard waits for the device's ACK. It does NOT bound
	// execution — a reboot reports its outcome long after this elapses.
	AckTimeout time.Duration

	// AllowPhysicalCommands gates every method flagged Physical in protocol.Methods (pump/valve
	// start-stop, reboot).
	//
	// It defaults to FALSE, and that default is the point. Issue #1 states that actions whose
	// device-side interlocks are not yet complete and verified on real hardware must not be exposed
	// to production. Since that work is not done, the safe state has to be the one you get by
	// forgetting to configure anything — a flag that defaults to "allowed" would mean a fresh
	// deployment ships able to start pumps.
	AllowPhysicalCommands bool
}

// DefaultCommandTTL mirrors the 60s used in the protocol fixtures. The real number is a product
// decision that has not been made (docs/protocol-v1.md §12), so this is a placeholder, not an answer.
const DefaultCommandTTL = 60 * time.Second

// DefaultAckTimeout matches tbclient.DefaultRPCTimeoutMs.
const DefaultAckTimeout = 10 * time.Second

// Service issues control commands and applies their outcomes.
type Service struct {
	q   *db.Queries
	tb  Sender
	cfg Config
}

// New builds a Service. A zero-valued Config is safe: TTLs fall back to the defaults and physical
// commands stay disabled.
func New(q *db.Queries, sender Sender, cfg Config) *Service {
	if cfg.CommandTTL <= 0 {
		cfg.CommandTTL = DefaultCommandTTL
	}
	if cfg.AckTimeout <= 0 {
		cfg.AckTimeout = DefaultAckTimeout
	}
	return &Service{q: q, tb: sender, cfg: cfg}
}

// IssueRequest is one operator's control request.
type IssueRequest struct {
	DeviceName string
	Method     string
	Args       json.RawMessage
	// Reason is the 二次确认 justification. Required, and carried all the way to the device and
	// into the audit record.
	Reason string
	Actor  Actor
}

// Issue authorizes, records, sends and applies the ACK for one control command.
//
// It returns the command row in whatever state it reached. A device that *refuses* the command is
// not a Go error — the call worked, and the resulting row has status "rejected" with the device's
// reason — because a refusal is information the operator must see, not a transport failure.
func (s *Service) Issue(ctx context.Context, req IssueRequest) (db.ControlCommand, error) {
	// Decided first, and in one statement: the answer to "may this user touch this device" and the
	// customer everything below is filed under must come from the same database state, so a transfer
	// that lands mid-command cannot authorize against one customer and record under another. Doing
	// it up front also means even the earliest denial below is audited under a customer.
	allowed, customerID, err := s.resolveAccess(ctx, req.Actor.UserID, req.DeviceName)
	if err != nil {
		return db.ControlCommand{}, err
	}
	req.Actor.CustomerID = customerID

	// A caller who may not touch this device is refused here, before anything else is said about the
	// request — not "unknown method", not "reason required", not the interlock. Whatever else is
	// wrong with the request is information for someone allowed to send it.
	if !allowed {
		err := fmt.Errorf("%w: device %q", ErrForbidden, req.DeviceName)
		s.audit(ctx, req.Actor, "control.issue", req.DeviceName, pgtype.UUID{}, "denied",
			map[string]any{"method": req.Method, "error": err.Error()})
		return db.ControlCommand{}, err
	}

	spec, known := protocol.Methods[req.Method]
	if !known {
		s.audit(ctx, req.Actor, "control.issue", req.DeviceName, pgtype.UUID{}, "denied",
			map[string]any{"method": req.Method, "error": "unknown method"})
		return db.ControlCommand{}, fmt.Errorf("%w: %q", ErrUnknownMethod, req.Method)
	}

	// Trim before checking: Gin's `binding:"required"` on the HTTP request only rejects a literally
	// empty string, so a whitespace-only reason ("   ") used to sail past this check and only get
	// caught by the database's CHECK(length(btrim(reason)) > 0) constraint — surfacing as a raw,
	// unrecognized DB error that writeControlError falls through to a generic 500 for, instead of
	// the ErrReasonRequired -> 400 a bad user input deserves. Found in review of PR #11. The trimmed
	// value is what gets stored and sent from here on, so the record does not carry the whitespace
	// either.
	req.Reason = strings.TrimSpace(req.Reason)
	if req.Reason == "" {
		s.audit(ctx, req.Actor, "control.issue", req.DeviceName, pgtype.UUID{}, "denied",
			map[string]any{"method": req.Method, "error": "missing reason"})
		return db.ControlCommand{}, ErrReasonRequired
	}

	// Authorization is checked before existence, everywhere. A caller who is not permitted must
	// get the same answer whether the device is someone else's real device or does not exist at
	// all, or the error codes become a device-name oracle. This mirrors the ordering already
	// established in internal/api/devices.go.
	if err := s.authorize(req.Actor, spec); err != nil {
		s.audit(ctx, req.Actor, "control.issue", req.DeviceName, pgtype.UUID{}, "denied",
			map[string]any{"method": req.Method, "error": err.Error()})
		return db.ControlCommand{}, err
	}

	dev, err := s.tb.GetDeviceByName(ctx, req.DeviceName)
	if err != nil {
		if errors.Is(err, tbclient.ErrNotFound) {
			// Authorized for a device ThingsBoard does not know about: a provisioning mismatch
			// between the two device registries, not the caller's fault.
			s.audit(ctx, req.Actor, "control.issue", req.DeviceName, pgtype.UUID{}, "failed",
				map[string]any{"method": req.Method, "error": "device not provisioned in ThingsBoard"})
			return db.ControlCommand{}, fmt.Errorf("device %q is not provisioned in ThingsBoard: %w", req.DeviceName, err)
		}
		return db.ControlCommand{}, fmt.Errorf("%w: %v", ErrPlatformUnavailable, err)
	}

	commandID, err := newUUID()
	if err != nil {
		return db.ControlCommand{}, err
	}

	args := req.Args
	if len(args) == 0 {
		args = json.RawMessage(`{}`)
	}

	issuedAt := time.Now()
	expiresAt := issuedAt.Add(s.cfg.CommandTTL)

	cmd, err := s.q.CreateControlCommand(ctx, db.CreateControlCommandParams{
		CommandID:  commandID,
		DeviceName: req.DeviceName,
		Method:     req.Method,
		Args:       args,
		OperatorID: req.Actor.UserID,
		Operator:   req.Actor.Username,
		Reason:     req.Reason,
		// Both timestamps come from this one clock reading — see CreateControlCommand's comment.
		IssuedAt:  pgtype.Timestamptz{Time: issuedAt, Valid: true},
		ExpiresAt: pgtype.Timestamptz{Time: expiresAt, Valid: true},
	})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// The insert re-checks the access rule in the same statement and found the user no
			// longer allowed — they were moved, demoted or disabled, or the device changed hands,
			// between the early decision and now. Nothing was recorded or sent.
			s.audit(ctx, req.Actor, "control.issue", req.DeviceName, pgtype.UUID{}, "denied",
				map[string]any{"method": req.Method, "error": "access changed while the command was being issued"})
			return db.ControlCommand{}, fmt.Errorf("%w: device %q", ErrForbidden, req.DeviceName)
		}
		return db.ControlCommand{}, fmt.Errorf("record command: %w", err)
	}
	// From here on everything — this audit row, the ACK, the send audit — is filed under the customer
	// the command row itself carries, which was decided in the same statement that authorized it.
	req.Actor.CustomerID = cmd.CustomerID

	s.audit(ctx, req.Actor, "control.issue", req.DeviceName, commandID, "allowed",
		map[string]any{"method": req.Method, "reason": req.Reason, "args": json.RawMessage(args)})

	env := protocol.Envelope{
		CommandID:       uuidString(commandID),
		ProtocolVersion: protocol.Version,
		Args:            args,
		IssuedAtMs:      issuedAt.UnixMilli(),
		ExpiresAtMs:     expiresAt.UnixMilli(),
		Operator:        req.Actor.Username,
		Reason:          req.Reason,
	}

	ack, err := s.tb.SendCommand(ctx, dev.ID.ID, req.Method, env, s.cfg.AckTimeout.Milliseconds())
	if err != nil {
		return s.handleSendFailure(ctx, cmd, req, err)
	}

	// ThingsBoard accepted the call and the device answered, so the command was definitely sent.
	// This may match zero rows — see the ack guard below for why — in which case cmd is refreshed
	// from the database rather than left as the pending copy created above.
	if sent, err := s.q.MarkControlCommandSent(ctx, commandID); err == nil {
		cmd = sent
	} else {
		cmd = s.refresh(ctx, cmd)
	}

	status := protocol.CmdStatusReceived
	if ack.Status == protocol.CmdStatusRejected {
		status = protocol.CmdStatusRejected
	}

	acked, err := s.q.MarkControlCommandAcked(ctx, db.MarkControlCommandAckedParams{
		CommandID:   commandID,
		Status:      status,
		ErrorCode:   text(ack.ErrorCode),
		ErrorReason: text(ack.Reason),
	})
	if err != nil {
		return s.ackGuardRefused(ctx, cmd, req, status), nil
	}

	outcome := "allowed"
	if status == protocol.CmdStatusRejected {
		outcome = "denied"
	}
	s.audit(ctx, req.Actor, "control.ack", req.DeviceName, commandID, outcome,
		map[string]any{"status": status, "errorCode": ack.ErrorCode, "reason": ack.Reason, "duplicate": ack.Duplicate})

	return acked, nil
}

// ackGuardRefused handles MarkControlCommandAcked matching zero rows: the row is no longer in a
// state an ACK may move it out of.
//
// Two very different things land here and neither the response nor the audit trail may conflate
// them. The rare one is the sweep having expired the command. The common one is the device's
// cmdResult having already closed it: Issue() waits for ThingsBoard's synchronous RPC ACK, but the
// result travels the independent telemetry WebSocket (internal/ingest), so a fast device routinely
// reports "succeeded" before this bookkeeping runs. The guard refusing the move is the state
// machine doing its job — invariant 3, a later result outranks an earlier ACK.
//
// What must not happen is what used to: returning the stale in-memory row, so the POST answers
// "pending" about a command the database already knows succeeded and a client polling
// GET .../commands one second later sees a different, better answer than the write it just made;
// and auditing the ACK as outcome "failed", which fabricates a failure in the one record that is
// supposed to say what actually happened. Nothing failed — the device accepted the command and
// then finished it.
func (s *Service) ackGuardRefused(ctx context.Context, cmd db.ControlCommand, req IssueRequest, ackStatus string) db.ControlCommand {
	cmd = s.refresh(ctx, cmd)

	outcome, note := "failed", "state guard refused the transition"
	if resultAlreadyArrived(cmd.Status) {
		outcome, note = "allowed", "result arrived before ack bookkeeping"
	}
	s.audit(ctx, req.Actor, "control.ack", req.DeviceName, cmd.CommandID, outcome,
		map[string]any{"status": ackStatus, "storedStatus": cmd.Status, "note": note})
	return cmd
}

// resultAlreadyArrived reports whether a status can only have been written by ApplyResult, i.e. by
// the device reporting back. 'rejected' is excluded because the ACK path itself writes it, and
// 'expired' because the sweep does.
func resultAlreadyArrived(status string) bool {
	switch status {
	case protocol.CmdStatusExecuting, protocol.CmdStatusSucceeded, protocol.CmdStatusFailed:
		return true
	}
	return false
}

// refresh re-reads a command row. Used on every path where a guarded UPDATE matched zero rows, so
// that what the caller gets back is the state the database actually holds rather than whatever this
// goroutine happened to be carrying. A failed read keeps the copy we have: a stale row beats no
// row, and the caller can poll.
func (s *Service) refresh(ctx context.Context, cmd db.ControlCommand) db.ControlCommand {
	stored, err := s.q.GetControlCommand(ctx, cmd.CommandID)
	if err != nil {
		return cmd
	}
	return stored
}

// handleSendFailure decides what a failed hand-off means for the command record.
//
// The distinction matters and is easy to get wrong in the unsafe direction: an un-acknowledged
// command must NOT be marked failed, because "the device did not answer" is not the same as "the
// device did not act". It may well have received the command, started the pump, and lost its
// connection before replying. Leaving it non-terminal until it either reports a result or expires
// is the only honest state.
func (s *Service) handleSendFailure(ctx context.Context, cmd db.ControlCommand, req IssueRequest, sendErr error) (db.ControlCommand, error) {
	if errors.Is(sendErr, tbclient.ErrDeviceOffline) {
		// ThingsBoard took the request and tried to deliver it; the device never acknowledged.
		// The row moves to "sent" and stays there until a result arrives or the sweep expires it.
		// The guard can refuse here too, and for the same reason as in Issue(): a device that
		// executed the command and reported its result over telemetry while the RPC call was still
		// waiting for an ACK it never sent. Refresh so the caller is told the stored state.
		if sent, err := s.q.MarkControlCommandSent(ctx, cmd.CommandID); err == nil {
			cmd = sent
		} else {
			cmd = s.refresh(ctx, cmd)
		}
		s.audit(ctx, req.Actor, "control.send", req.DeviceName, cmd.CommandID, "failed",
			map[string]any{"method": req.Method, "error": "device did not acknowledge", "storedStatus": cmd.Status})
		return cmd, nil
	}

	// ThingsBoard itself failed, so the command was probably never handed over at all. Leave it
	// pending; the sweep will expire it.
	s.audit(ctx, req.Actor, "control.send", req.DeviceName, cmd.CommandID, "failed",
		map[string]any{"method": req.Method, "error": sendErr.Error()})
	if tbclient.Unavailable(sendErr) {
		return cmd, fmt.Errorf("%w: %v", ErrPlatformUnavailable, sendErr)
	}
	return cmd, sendErr
}

// ApplyResult records the device's asynchronous outcome for a command. This is what closes the
// state machine, and it is driven by the telemetry ingestion path (internal/ingest), not by
// anything the operator does.
//
// An unknown commandId is not an error: a device may legitimately report a result for a command
// this deployment has no record of (a database restored from backup, a command issued by another
// environment against the same device). Logging and moving on beats failing the whole ingestion
// batch.
func (s *Service) ApplyResult(ctx context.Context, deviceName string, res protocol.CmdResult) (db.ControlCommand, bool, error) {
	commandID, err := parseUUID(res.CommandID)
	if err != nil {
		return db.ControlCommand{}, false, fmt.Errorf("cmdResult carried an unparseable commandId %q: %w", res.CommandID, err)
	}

	status := res.Status
	switch status {
	case protocol.CmdStatusExecuting, protocol.CmdStatusSucceeded, protocol.CmdStatusFailed:
	default:
		return db.ControlCommand{}, false, fmt.Errorf("cmdResult carried status %q, which a device may not report", status)
	}

	updated, err := s.q.MarkControlCommandFinished(ctx, db.MarkControlCommandFinishedParams{
		CommandID:    commandID,
		DeviceName:   deviceName,
		Status:       status,
		ErrorCode:    text(res.ErrorCode),
		ErrorReason:  text(res.Reason),
		ResultDetail: res.Detail,
	})
	if err != nil {
		// No such command, its status guard refused the move because it is already terminal, or
		// deviceName didn't match the command's owner (a device reporting a foreign commandId).
		// All three are normal or actively adversarial; none should abort ingestion.
		return db.ControlCommand{}, false, nil
	}

	outcome := "succeeded"
	if status == protocol.CmdStatusFailed {
		outcome = "failed"
	}
	// The actor on a result is the device, not a user — this row is the device reporting back.
	s.audit(ctx, Actor{Username: "device:" + deviceName, CustomerID: updated.CustomerID}, "control.result", deviceName, commandID, outcome,
		map[string]any{"status": status, "errorCode": res.ErrorCode, "reason": res.Reason})

	return updated, true, nil
}

// ExpireStale closes out commands that passed their validity window without reaching a terminal
// state. Returns how many were expired.
func (s *Service) ExpireStale(ctx context.Context) (int, error) {
	expired, err := s.q.ExpireStaleControlCommands(ctx)
	if err != nil {
		return 0, err
	}
	for _, cmd := range expired {
		s.audit(ctx, Actor{Username: "system", CustomerID: cmd.CustomerID}, "control.expire", cmd.DeviceName, cmd.CommandID, "failed",
			map[string]any{"method": cmd.Method, "previousStatus": cmd.Status})
	}
	return len(expired), nil
}

// RunExpirySweep runs ExpireStale on a ticker until ctx ends.
func (s *Service) RunExpirySweep(ctx context.Context, every time.Duration, logf func(string, ...any)) {
	if every <= 0 {
		every = 30 * time.Second
	}
	if logf == nil {
		logf = func(string, ...any) {}
	}

	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			n, err := s.ExpireStale(ctx)
			if err != nil {
				logf("control: expiry sweep: %v", err)
				continue
			}
			if n > 0 {
				logf("control: expired %d stale command(s)", n)
			}
		}
	}
}

// authorize applies the interlock gate and the role/method check. The device-scope decision has
// already been made — and acted on — at the top of Issue, before any of this runs.
func (s *Service) authorize(actor Actor, spec protocol.MethodSpec) error {
	// The interlock gate applies to every role, platform admins included. It is a statement about
	// the *hardware* being unverified, not about who is trusted.
	if spec.Physical && !s.cfg.AllowPhysicalCommands {
		return fmt.Errorf("%w: %s", ErrPhysicalCommandsDisabled, spec.Method)
	}

	// The role gates *which methods*; the device decision gates *which devices*.
	if !roleAllows(actor.Role, spec.Permission) {
		return fmt.Errorf("%w: role %q may not issue %s", ErrForbidden, actor.Role, spec.Method)
	}
	return nil
}

func roleAllows(role string, need protocol.PermissionLevel) bool {
	switch role {
	case RolePlatformAdmin, RoleCustomerAdmin:
		return true // both tiers
	case RoleCustomerUser:
		return need == protocol.PermControl
	default:
		return false
	}
}

// resolveAccess answers, from one database state, whether the user may touch the device and which
// customer the command and its audit rows are filed under: the user's own, or — for a platform
// admin, who belongs to none — the device's customer at this moment, so a customer reviewing their
// audit trail sees what platform staff did to their devices. A device that does not exist yields
// NULL. A user that no longer exists is simply not allowed.
func (s *Service) resolveAccess(ctx context.Context, userID int64, deviceName string) (bool, pgtype.Int8, error) {
	row, err := s.q.ResolveDeviceAccess(ctx, db.ResolveDeviceAccessParams{UserID: userID, DeviceName: deviceName})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, pgtype.Int8{}, nil
		}
		return false, pgtype.Int8{}, fmt.Errorf("resolve device access: %w", err)
	}
	customer := row.UserCustomerID
	if !customer.Valid {
		customer = row.DeviceCustomerID
	}
	return row.Allowed, customer, nil
}

// audit writes one audit row. Audit failures are deliberately swallowed rather than propagated: an
// audit write that fails must not, for example, turn a successfully-executed command into an error
// the operator sees as a failure. They are still surfaced through the returned command's absence
// of an audit row, which the audit-listing endpoint makes visible.
func (s *Service) audit(ctx context.Context, actor Actor, action, deviceName string, commandID pgtype.UUID, outcome string, detail map[string]any) {
	var encoded []byte
	if detail != nil {
		encoded, _ = json.Marshal(detail)
	}

	_, _ = s.q.WriteAuditLog(ctx, db.WriteAuditLogParams{
		ActorID:    pgtype.Int8{Int64: actor.UserID, Valid: actor.UserID != 0},
		Actor:      actor.Username,
		Action:     action,
		DeviceName: text(deviceName),
		CommandID:  commandID,
		Outcome:    outcome,
		Detail:     encoded,
		CustomerID: actor.CustomerID,
	})
}

func text(s string) pgtype.Text {
	return pgtype.Text{String: s, Valid: s != ""}
}

// newUUID returns a random (version 4) UUID. Hand-rolled rather than pulling in a dependency for
// sixteen bytes of randomness.
func newUUID() (pgtype.UUID, error) {
	var u pgtype.UUID
	if _, err := rand.Read(u.Bytes[:]); err != nil {
		return pgtype.UUID{}, fmt.Errorf("generate command id: %w", err)
	}
	u.Bytes[6] = (u.Bytes[6] & 0x0f) | 0x40 // version 4
	u.Bytes[8] = (u.Bytes[8] & 0x3f) | 0x80 // RFC 4122 variant
	u.Valid = true
	return u, nil
}

func uuidString(u pgtype.UUID) string {
	b := u.Bytes
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func parseUUID(s string) (pgtype.UUID, error) {
	var u pgtype.UUID
	if err := u.Scan(s); err != nil {
		return pgtype.UUID{}, err
	}
	return u, nil
}
