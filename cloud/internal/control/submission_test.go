package control_test

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// config-command-delivery (#3): submission protection for the configuration methods. Real PostgreSQL,
// fake ThingsBoard (see control_test.go).

var configOn = control.Config{AllowConfigCommands: true}

func (f *fixture) openGate(t *testing.T) {
	t.Helper()
	if _, err := f.pool.Exec(context.Background(), `UPDATE devices SET config_commands_enabled_at = now() WHERE device_name = $1`, f.device); err != nil {
		t.Fatal(err)
	}
}

func newRequestID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return fmt.Sprintf("r%x", b)
}

func tankReq(f *fixture, args, requestID string) control.IssueRequest {
	return control.IssueRequest{
		DeviceName: f.device, Method: protocol.MethodConfigTankSettings, Args: json.RawMessage(args),
		Reason: "调整高液位", Actor: f.actor, RequestID: requestID, ExpectedStationSet: true,
	}
}

func (f *fixture) auditCount(t *testing.T, action string) int {
	t.Helper()
	var n int
	if err := f.pool.QueryRow(context.Background(), `SELECT count(*) FROM audit_log WHERE device_name = $1 AND action = $2`, f.device, action).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestConfigSubmission_ReplayReturnsTheSameCommandWithoutResending(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()
	rid := newRequestID()

	first, replayed, err := f.svc.IssueDetailed(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, rid))
	if err != nil || replayed {
		t.Fatalf("first: replayed=%v err=%v", replayed, err)
	}
	if f.sender.calls != 1 {
		t.Fatalf("sender calls = %d", f.sender.calls)
	}
	// Key order and 610 vs 610.0 are the same submission.
	again, replayed, err := f.svc.IssueDetailed(ctx, tankReq(f, `{"alarmHighLevelMm":610.0,"tank":1}`, rid))
	if err != nil || !replayed {
		t.Fatalf("replay: replayed=%v err=%v", replayed, err)
	}
	if again.CommandID != first.CommandID {
		t.Errorf("replay returned another command")
	}
	if f.sender.calls != 1 {
		t.Errorf("a replay reached ThingsBoard (calls = %d)", f.sender.calls)
	}
	if f.auditCount(t, "control.replay") != 1 {
		t.Error("replay not audited")
	}
	if !again.RequestID.Valid || again.RequestID.String != rid || again.ConfigTarget.String != "tank:1" || again.ExpectedStationID.Valid {
		t.Errorf("stored context: requestId=%+v target=%+v station=%+v", again.RequestID, again.ConfigTarget, again.ExpectedStationID)
	}

	// Same requestId, different content.
	if _, _, err := f.svc.IssueDetailed(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":620}`, rid)); !errors.Is(err, control.ErrRequestIDReused) {
		t.Errorf("changed content: err = %v, want ErrRequestIDReused", err)
	}
}

// concurrentSender is a goroutine-safe stand-in for ThingsBoard.
type concurrentSender struct {
	fakeSender
	mu sync.Mutex
}

func (c *concurrentSender) SendCommand(ctx context.Context, id, method string, env protocol.Envelope, ms int64) (protocol.Ack, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.fakeSender.SendCommand(ctx, id, method, env, ms)
}

func TestConfigSubmission_ConcurrentDuplicatesCreateOneCommand(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	sender := &concurrentSender{}
	cfg := configOn
	cfg.Pool = f.pool
	svc := control.New(f.q, sender, cfg)
	rid := newRequestID()

	const n = 6
	var wg sync.WaitGroup
	ids := make([]string, n)
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			cmd, _, err := svc.IssueDetailed(context.Background(), tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, rid))
			errs[i] = err
			ids[i] = fmt.Sprintf("%x", cmd.CommandID.Bytes)
		}(i)
	}
	wg.Wait()
	for i := range errs {
		if errs[i] != nil {
			t.Fatalf("submission %d: %v", i, errs[i])
		}
		if ids[i] != ids[0] {
			t.Errorf("submission %d got command %s, want %s", i, ids[i], ids[0])
		}
	}
	if sender.calls != 1 {
		t.Errorf("ThingsBoard got %d calls, want exactly 1", sender.calls)
	}
}

func TestConfigSubmission_RequiredFields(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()
	args := `{"tank":1,"alarmHighLevelMm":610}`

	req := tankReq(f, args, "")
	if _, err := f.svc.Issue(ctx, req); !errors.Is(err, control.ErrRequestIDRequired) {
		t.Errorf("no requestId: %v", err)
	}
	req = tankReq(f, args, "has space")
	if _, err := f.svc.Issue(ctx, req); !errors.Is(err, control.ErrRequestIDInvalid) {
		t.Errorf("bad requestId: %v", err)
	}
	req = tankReq(f, args, newRequestID())
	req.ExpectedStationSet = false
	if _, err := f.svc.Issue(ctx, req); !errors.Is(err, control.ErrExpectedStationRequired) {
		t.Errorf("no expectedStationId: %v", err)
	}
	if f.sender.calls != 0 {
		t.Error("an incomplete submission reached ThingsBoard")
	}
	// Other methods do not need either field.
	if _, err := f.svc.Issue(ctx, issueReq(f, protocol.MethodAlarmMute)); err != nil {
		t.Errorf("alarm.mute without requestId: %v", err)
	}
}

func TestConfigSubmission_StationMustMatch(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()
	var station int64
	if err := f.pool.QueryRow(ctx, `INSERT INTO stations (customer_id, name) VALUES ((SELECT id FROM customers WHERE name = 'default'), $1) RETURNING id`,
		"站-"+f.device).Scan(&station); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		c := context.Background()
		f.pool.Exec(c, `UPDATE devices SET station_id = NULL WHERE device_name = $1`, f.device)
		f.pool.Exec(c, `DELETE FROM stations WHERE id = $1`, station)
	})
	if _, err := f.pool.Exec(ctx, `UPDATE devices SET station_id = $1 WHERE device_name = $2`, station, f.device); err != nil {
		t.Fatal(err)
	}

	// The form was opened while the device was on no station.
	_, err := f.svc.Issue(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, newRequestID()))
	var sc *control.StationChangedError
	if !errors.As(err, &sc) || !sc.Current.Valid || sc.Current.Int64 != station {
		t.Fatalf("err = %v (%+v), want StationChangedError with current %d", err, sc, station)
	}
	if f.sender.calls != 0 {
		t.Error("a stale confirmation reached ThingsBoard")
	}

	req := tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, newRequestID())
	req.ExpectedStationID = pgtype.Int8{Int64: station, Valid: true}
	cmd, err := f.svc.Issue(ctx, req)
	if err != nil {
		t.Fatalf("matching station: %v", err)
	}
	if !cmd.ExpectedStationID.Valid || cmd.ExpectedStationID.Int64 != station {
		t.Errorf("expected_station_id = %+v", cmd.ExpectedStationID)
	}
}

func TestConfigSubmission_OneInFlightCommandPerTarget(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()

	first, err := f.svc.Issue(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, newRequestID()))
	if err != nil || first.Status != protocol.CmdStatusReceived {
		t.Fatalf("first: %v %q", err, first.Status)
	}
	_, err = f.svc.Issue(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":620}`, newRequestID()))
	var fe *control.InFlightError
	if !errors.As(err, &fe) || fe.CommandID == "" {
		t.Fatalf("second on tank 1: err = %v, want InFlightError with the holder", err)
	}
	if calls := f.sender.calls; calls != 1 {
		t.Errorf("sender calls = %d, the refused command must not be sent", calls)
	}
	if _, err := f.svc.Issue(ctx, tankReq(f, `{"tank":2,"alarmHighLevelMm":620}`, newRequestID())); err != nil {
		t.Errorf("tank 2 in parallel: %v", err)
	}
	// Once the holder is closed the target is free again.
	if _, err := f.pool.Exec(ctx, `UPDATE control_commands SET status = 'expired', finished_at = now() WHERE command_id = $1`, first.CommandID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.svc.Issue(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":630}`, newRequestID())); err != nil {
		t.Errorf("tank 1 after the holder expired: %v", err)
	}
}

func TestConfigSubmission_TwoLevelGate(t *testing.T) {
	ctx := context.Background()
	args := `{"tank":1,"alarmHighLevelMm":610}`

	// Deployment closed.
	f := newFixture(t, control.RoleCustomerAdmin, control.Config{})
	f.openGate(t)
	var gate *control.ConfigDisabledError
	if _, err := f.svc.Issue(ctx, tankReq(f, args, newRequestID())); !errors.As(err, &gate) || gate.Scope != control.GateScopeDeployment {
		t.Errorf("deployment closed: %v", err)
	}

	// Deployment open, device not opened.
	f = newFixture(t, control.RoleCustomerAdmin, configOn)
	if _, err := f.svc.Issue(ctx, tankReq(f, args, newRequestID())); !errors.As(err, &gate) || gate.Scope != control.GateScopeDevice {
		t.Errorf("device closed: %v", err)
	}
	if f.sender.calls != 0 {
		t.Error("a gated command reached ThingsBoard")
	}

	// An employee is refused by role whatever the gates say.
	f = newFixture(t, control.RoleCustomerUser, control.Config{})
	if _, err := f.svc.Issue(ctx, tankReq(f, args, newRequestID())); !errors.Is(err, control.ErrForbidden) {
		t.Errorf("employee: %v, want ErrForbidden", err)
	}
}

// The insert itself refuses a method tier the user's current role does not have, and a closed device.
func TestConfigSubmission_InsertRechecksTierAndDeviceGate(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	ctx := context.Background()
	insert := func(level string, gate bool) error {
		id := pgtype.UUID{Valid: true}
		_, _ = rand.Read(id.Bytes[:])
		now := time.Now()
		_, err := f.q.CreateControlCommand(ctx, db.CreateControlCommandParams{
			CommandID: id, DeviceName: f.device, Method: protocol.MethodConfigDeviceSettings, Args: []byte(`{}`),
			OperatorID: f.actor.UserID, Operator: f.actor.Username, Reason: "r",
			IssuedAt: pgtype.Timestamptz{Time: now, Valid: true}, ExpiresAt: pgtype.Timestamptz{Time: now.Add(time.Minute), Valid: true},
			RequiredLevel: level, RequireDeviceEnabled: gate,
		})
		return err
	}
	if err := insert(string(protocol.PermPlatform), false); !errors.Is(err, pgx.ErrNoRows) {
		t.Errorf("customer admin, platform tier: %v, want no row", err)
	}
	if err := insert(string(protocol.PermAdmin), true); !errors.Is(err, pgx.ErrNoRows) {
		t.Errorf("device gate closed: %v, want no row", err)
	}
	f.openGate(t)
	if err := insert(string(protocol.PermAdmin), true); err != nil {
		t.Errorf("admin tier, gate open: %v", err)
	}
}

func TestConfigSubmission_ReplayRechecksAuthorization(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()
	rid := newRequestID()
	if _, err := f.svc.Issue(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, rid)); err != nil {
		t.Fatal(err)
	}
	if _, err := f.pool.Exec(ctx, `UPDATE users SET disabled_at = now() WHERE id = $1`, f.actor.UserID); err != nil {
		t.Fatal(err)
	}
	if _, replayed, err := f.svc.IssueDetailed(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, rid)); !errors.Is(err, control.ErrForbidden) || replayed {
		t.Errorf("disabled operator replay: replayed=%v err=%v, want ErrForbidden", replayed, err)
	}
}

func TestApplyResult_LateResultIsAuditedOnceWithoutChangingState(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()
	cmd, err := f.svc.Issue(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, newRequestID()))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.pool.Exec(ctx, `UPDATE control_commands SET status = 'expired', finished_at = now() WHERE command_id = $1`, cmd.CommandID); err != nil {
		t.Fatal(err)
	}
	res := protocol.CmdResult{CommandID: fmt.Sprintf("%x-%x-%x-%x-%x", cmd.CommandID.Bytes[0:4], cmd.CommandID.Bytes[4:6], cmd.CommandID.Bytes[6:8], cmd.CommandID.Bytes[8:10], cmd.CommandID.Bytes[10:16]),
		Status: protocol.CmdStatusSucceeded, Detail: json.RawMessage(`{"tank":1,"updated":["alarmHighLevelMm"]}`)}
	for i := 0; i < 2; i++ {
		if _, applied, err := f.svc.ApplyResult(ctx, f.device, res); err != nil || applied {
			t.Fatalf("late result %d: applied=%v err=%v", i, applied, err)
		}
	}
	if n := f.auditCount(t, "control.result_late"); n != 1 {
		t.Errorf("control.result_late rows = %d, want 1", n)
	}
	stored, _ := f.q.GetControlCommand(ctx, cmd.CommandID)
	if stored.Status != protocol.CmdStatusExpired {
		t.Errorf("status = %q, a late result must not change it", stored.Status)
	}
	// A device reporting someone else's command leaves no audit row.
	if _, _, err := f.svc.ApplyResult(ctx, "some-other-device", res); err != nil {
		t.Fatal(err)
	}
	if n := f.auditCount(t, "control.result_late"); n != 1 {
		t.Errorf("foreign device produced an audit row")
	}
}

// Commands created while the station is being re-equipped, under replace-device's own lock order
// (station FOR UPDATE → device FOR UPDATE): no deadlock, and every command that was created was created
// while the device really was on the station the client named.
func TestConfigSubmission_ConcurrentWithReequipDoesNotDeadlock(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()
	var station int64
	if err := f.pool.QueryRow(ctx, `INSERT INTO stations (customer_id, name) VALUES ((SELECT id FROM customers WHERE name = 'default'), $1) RETURNING id`,
		"站-"+f.device).Scan(&station); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		c := context.Background()
		f.pool.Exec(c, `UPDATE devices SET station_id = NULL WHERE device_name = $1`, f.device)
		f.pool.Exec(c, `DELETE FROM stations WHERE id = $1`, station)
	})
	sender := &concurrentSender{}
	cfg := configOn
	cfg.Pool = f.pool
	svc := control.New(f.q, sender, cfg)

	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { // re-equip loop: on/off the station, replace-device's lock order
		defer wg.Done()
		on := true
		for {
			select {
			case <-stop:
				return
			default:
			}
			tx, err := f.pool.Begin(ctx)
			if err != nil {
				return
			}
			tx.Exec(ctx, `SELECT id FROM stations WHERE id = $1 FOR UPDATE`, station)
			tx.Exec(ctx, `SELECT 1 FROM devices WHERE device_name = $1 FOR UPDATE`, f.device)
			if on {
				tx.Exec(ctx, `UPDATE devices SET station_id = $1 WHERE device_name = $2`, station, f.device)
			} else {
				tx.Exec(ctx, `UPDATE devices SET station_id = NULL WHERE device_name = $1`, f.device)
			}
			tx.Commit(ctx)
			on = !on
			time.Sleep(2 * time.Millisecond) // let submissions interleave with both states
		}
	}()

	created, refused := 0, 0
	for i := 0; i < 40; i++ {
		req := tankReq(f, fmt.Sprintf(`{"tank":%d,"alarmHighLevelMm":610}`, i%16+1), newRequestID())
		req.ExpectedStationID = pgtype.Int8{Int64: station, Valid: true}
		cmd, err := svc.Issue(ctx, req)
		switch {
		case err == nil:
			created++
			// Close it so the tank is free for the next round.
			f.pool.Exec(ctx, `UPDATE control_commands SET status = 'expired', finished_at = now() WHERE command_id = $1`, cmd.CommandID)
		case errors.Is(err, control.ErrDeviceStationChanged):
			refused++
		default:
			t.Fatalf("submission %d: %v", i, err)
		}
	}
	close(stop)
	wg.Wait()
	if ctx.Err() != nil {
		t.Fatal("timed out: lock order deadlock?")
	}
	t.Logf("created %d, refused as station changed %d", created, refused)
	if created == 0 || refused == 0 {
		t.Errorf("created %d, refused %d: the two never interleaved, so the test proved nothing", created, refused)
	}
	if created+refused != 40 {
		t.Errorf("created %d + refused %d != 40", created, refused)
	}
}

// A command that never left 'pending' (the process died between insert and send) is swept to 'expired';
// replaying its requestId returns that record and sends nothing — expired does not prove "not executed".
func TestConfigSubmission_ReplayOfExpiredCommandSendsNothing(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()
	rid := newRequestID()
	cmd, err := f.svc.Issue(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, rid))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.pool.Exec(ctx, `UPDATE control_commands SET status = 'expired', finished_at = now() WHERE command_id = $1`, cmd.CommandID); err != nil {
		t.Fatal(err)
	}
	calls := f.sender.calls
	again, replayed, err := f.svc.IssueDetailed(ctx, tankReq(f, `{"tank":1,"alarmHighLevelMm":610}`, rid))
	if err != nil || !replayed || again.Status != protocol.CmdStatusExpired || again.CommandID != cmd.CommandID {
		t.Fatalf("replay of expired: replayed=%v status=%q err=%v", replayed, again.Status, err)
	}
	if f.sender.calls != calls {
		t.Error("replaying an expired command sent it again")
	}
}

// Codex review PR #13 (P1): an integral non-literal tank ("1.0", "1e0") must land on the same target as
// "1", or it would slip past the in-flight index.
func TestConfigSubmission_IntegralTankSpellingsShareOneTarget(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	f.openGate(t)
	ctx := context.Background()
	first, err := f.svc.Issue(ctx, tankReq(f, `{"tank":1.0,"alarmHighLevelMm":610}`, newRequestID()))
	if err != nil {
		t.Fatal(err)
	}
	if first.ConfigTarget.String != "tank:1" {
		t.Fatalf("config_target = %+v, want tank:1", first.ConfigTarget)
	}
	_, err = f.svc.Issue(ctx, tankReq(f, `{"tank":1e0,"alarmHighLevelMm":620}`, newRequestID()))
	var fe *control.InFlightError
	if !errors.As(err, &fe) {
		t.Fatalf("1e0 while 1.0 is in flight: %v, want InFlightError", err)
	}
}

// Codex review PR #13 (P2): after the operator and the device both move to another customer, replaying an
// old requestId must not return the command filed under the previous customer.
func TestConfigSubmission_ReplayIsCustomerScoped(t *testing.T) {
	f := newFixture(t, control.RoleCustomerAdmin, configOn)
	ctx := context.Background()
	rid := newRequestID()
	req := issueReq(f, protocol.MethodAlarmMute)
	req.RequestID = rid
	if _, err := f.svc.Issue(ctx, req); err != nil {
		t.Fatal(err)
	}
	var other int64
	if err := f.pool.QueryRow(ctx, `INSERT INTO customers (name, onboarding_status, onboarded_at) VALUES ($1, 'active', now()) RETURNING id`,
		"ctl-replay-"+f.device).Scan(&other); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		c := context.Background()
		f.pool.Exec(c, `UPDATE devices SET customer_id = (SELECT id FROM customers WHERE name = 'default') WHERE device_name = $1`, f.device)
		f.pool.Exec(c, `UPDATE users SET customer_id = (SELECT id FROM customers WHERE name = 'default') WHERE id = $1`, f.actor.UserID)
		f.pool.Exec(c, `DELETE FROM audit_log WHERE customer_id = $1`, other)
		f.pool.Exec(c, `DELETE FROM control_commands WHERE customer_id = $1`, other)
		f.pool.Exec(c, `DELETE FROM customers WHERE id = $1`, other)
	})
	if _, err := f.pool.Exec(ctx, `UPDATE devices SET customer_id = $1 WHERE device_name = $2`, other, f.device); err != nil {
		t.Fatal(err)
	}
	if _, err := f.pool.Exec(ctx, `UPDATE users SET customer_id = $1 WHERE id = $2`, other, f.actor.UserID); err != nil {
		t.Fatal(err)
	}
	cmd, replayed, err := f.svc.IssueDetailed(ctx, req)
	if !errors.Is(err, control.ErrRequestIDReused) || replayed || cmd.CommandID.Valid {
		t.Fatalf("replay across customers: replayed=%v cmd=%v err=%v, want ErrRequestIDReused and nothing returned", replayed, cmd.CommandID.Valid, err)
	}
}
