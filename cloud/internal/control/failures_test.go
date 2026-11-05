package control_test

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// These cover what happens when the database itself fails, as opposed to a request being refused.
// Both used to be invisible: ApplyResult reported every UPDATE error as "this result did not apply",
// and the audit writer discarded its errors, so a database outage could lose a command's outcome and
// its audit trail with nothing to show for it.

// logSink collects what the Service says it could not do.
type logSink struct {
	mu    sync.Mutex
	lines []string
}

func (l *logSink) Logf(format string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, fmt.Sprintf(format, args...))
}

func (l *logSink) contains(parts ...string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	for _, line := range l.lines {
		ok := true
		for _, p := range parts {
			if !strings.Contains(line, p) {
				ok = false
				break
			}
		}
		if ok {
			return true
		}
	}
	return false
}

func (l *logSink) dump() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return strings.Join(l.lines, "\n")
}

// failAuditWrites makes every audit_log insert for this device and action fail, the way a database
// that is down or full would — scoped to one test's device so it cannot touch anything else sharing
// the database.
func failAuditWrites(t *testing.T, device, action string) {
	t.Helper()
	pool := testPool(t)
	ctx := context.Background()
	trigger := fmt.Sprintf("zz_fail_audit_%d", time.Now().UnixNano())

	if _, err := pool.Exec(ctx, `
		CREATE OR REPLACE FUNCTION zz_test_fail_audit() RETURNS trigger AS $$
		BEGIN RAISE EXCEPTION 'injected audit failure'; END $$ LANGUAGE plpgsql`); err != nil {
		t.Fatalf("create failure function: %v", err)
	}
	if _, err := pool.Exec(ctx, fmt.Sprintf(`
		CREATE TRIGGER %s BEFORE INSERT ON audit_log FOR EACH ROW
		WHEN (NEW.device_name = '%s' AND NEW.action = '%s')
		EXECUTE FUNCTION zz_test_fail_audit()`, trigger, device, action)); err != nil {
		t.Fatalf("create failure trigger: %v", err)
	}
	t.Cleanup(func() {
		pool.Exec(context.Background(), fmt.Sprintf(`DROP TRIGGER IF EXISTS %s ON audit_log`, trigger))
	})
}

func TestApplyResult_DatabaseErrorIsReportedNotSwallowed(t *testing.T) {
	sink := &logSink{}
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{Logf: sink.Logf})
	ctx := context.Background()

	cmd, err := f.svc.Issue(ctx, issueReq(f, protocol.MethodAlarmMute))
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	res := protocol.CmdResult{
		CommandID: uuidText(cmd.CommandID.Bytes),
		Status:    protocol.CmdStatusSucceeded,
		TSMs:      time.Now().UnixMilli(),
	}

	// A context that is already cancelled stands in for any database failure: the UPDATE errors, and
	// that error is not "no such command" or "already terminal".
	dead, cancel := context.WithCancel(ctx)
	cancel()
	_, applied, err := f.svc.ApplyResult(dead, f.device, res)
	if err == nil {
		t.Fatal("a database failure was reported as a clean 'did not apply'; the result would be lost for good")
	}
	if applied {
		t.Error("nothing was written, so applied must be false")
	}
	if !sink.contains("NOT recorded", res.CommandID) {
		t.Errorf("the failure must be logged with the command it concerns; log:\n%s", sink.dump())
	}

	// And it must be recoverable: the command is still open, so the same result applies once the
	// database is back. This is what the ingest-side reconcile relies on.
	updated, applied, err := f.svc.ApplyResult(ctx, f.device, res)
	if err != nil || !applied {
		t.Fatalf("retry after the failure: applied=%v err=%v", applied, err)
	}
	if updated.Status != protocol.CmdStatusSucceeded {
		t.Errorf("status after retry = %q", updated.Status)
	}
}

// The other half of the classification: the guard refusing is still a normal, quiet outcome. A
// duplicate or unknown result must stay (false, nil) — turning it into an error would make every
// redelivered result look like an outage.
func TestApplyResult_GuardRefusalIsStillNotAnError(t *testing.T) {
	sink := &logSink{}
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{Logf: sink.Logf})
	ctx := context.Background()

	_, applied, err := f.svc.ApplyResult(ctx, f.device, protocol.CmdResult{
		CommandID: "11111111-2222-4333-8444-555555555555",
		Status:    protocol.CmdStatusSucceeded,
	})
	if err != nil || applied {
		t.Fatalf("unknown command: applied=%v err=%v, want (false, nil)", applied, err)
	}
	if sink.contains("NOT recorded") {
		t.Errorf("an unknown command is not a database failure, but it was logged as one:\n%s", sink.dump())
	}
}

func TestIssue_AuditFailureStopsTheCommandBeforeItIsSent(t *testing.T) {
	sink := &logSink{}
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{Logf: sink.Logf})
	failAuditWrites(t, f.device, "control.issue")

	_, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodAlarmMute))
	if !errors.Is(err, control.ErrAuditUnavailable) {
		t.Fatalf("err = %v, want ErrAuditUnavailable", err)
	}
	if f.sender.calls != 0 {
		t.Fatalf("the command reached the device %d time(s) without a record of who asked for it", f.sender.calls)
	}
	if !sink.contains("AUDIT WRITE FAILED", "action=control.issue", f.device) {
		t.Errorf("the failed audit write must be logged with enough to rebuild the row; log:\n%s", sink.dump())
	}

	// Nothing is left half-open: the row exists, still 'pending', and the expiry sweep closes it.
	cmds, err := f.q.ListControlCommandsForDeviceUnscoped(context.Background(),
		db.ListControlCommandsForDeviceUnscopedParams{DeviceName: f.device, Lim: 10, Off: 0})
	if err != nil {
		t.Fatalf("list commands: %v", err)
	}
	if len(cmds) != 1 || cmds[0].Status != protocol.CmdStatusPending {
		t.Fatalf("want exactly one pending command, got %+v", cmds)
	}
}

// After the command has gone out, failing the request over a missing audit row would tell the
// operator a command failed that is in fact running on the device.
func TestIssue_AuditFailureAfterSendDoesNotChangeTheOutcome(t *testing.T) {
	sink := &logSink{}
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{Logf: sink.Logf})
	failAuditWrites(t, f.device, "control.ack")

	cmd, err := f.svc.Issue(context.Background(), issueReq(f, protocol.MethodAlarmMute))
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if cmd.Status != protocol.CmdStatusReceived {
		t.Errorf("status = %q, want %q", cmd.Status, protocol.CmdStatusReceived)
	}
	if f.sender.calls != 1 {
		t.Errorf("SendCommand calls = %d", f.sender.calls)
	}
	if !sink.contains("AUDIT WRITE FAILED", "action=control.ack") {
		t.Errorf("the lost ack audit row must be logged; log:\n%s", sink.dump())
	}
}

func TestApplyResult_AuditFailureStillRecordsTheResult(t *testing.T) {
	sink := &logSink{}
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{Logf: sink.Logf})
	ctx := context.Background()

	cmd, err := f.svc.Issue(ctx, issueReq(f, protocol.MethodAlarmMute))
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	failAuditWrites(t, f.device, "control.result")

	updated, applied, err := f.svc.ApplyResult(ctx, f.device, protocol.CmdResult{
		CommandID: uuidText(cmd.CommandID.Bytes),
		Status:    protocol.CmdStatusSucceeded,
	})
	if err != nil || !applied {
		t.Fatalf("the device's outcome must be recorded even if its audit row cannot be: applied=%v err=%v", applied, err)
	}
	if updated.Status != protocol.CmdStatusSucceeded {
		t.Errorf("status = %q", updated.Status)
	}
	if !sink.contains("AUDIT WRITE FAILED", "action=control.result") {
		t.Errorf("log:\n%s", sink.dump())
	}
}

// A refusal must stay a refusal when its own audit row cannot be written: failing open here would
// mean a database problem turns "denied" into something else.
func TestIssue_DenialStandsWhenItsAuditRowCannotBeWritten(t *testing.T) {
	sink := &logSink{}
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{Logf: sink.Logf})
	failAuditWrites(t, f.device, "control.issue")

	_, err := f.svc.Issue(context.Background(), issueReq(f, "no.such.method"))
	if !errors.Is(err, control.ErrUnknownMethod) {
		t.Fatalf("err = %v, want ErrUnknownMethod", err)
	}
	if f.sender.calls != 0 {
		t.Error("a refused command must not be sent")
	}
	if !sink.contains("AUDIT WRITE FAILED") {
		t.Errorf("log:\n%s", sink.dump())
	}
}
