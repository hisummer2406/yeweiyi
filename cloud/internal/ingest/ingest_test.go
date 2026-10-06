package ingest_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/ingest"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// This is the whole stack at once: a real simulated device on a real ThingsBoard, a real
// PostgreSQL-backed control state machine, and the real ingester wiring the two together. It is
// what GitHub issue #10's "一台模拟设备完成端到端读取和 RPC/ACK 闭环" means in practice.
//
// Requires both stacks:
//
//	docker compose up -d
//	docker compose -f docker-compose.thingsboard.yml up -d

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

type stack struct {
	pool    *pgxpool.Pool
	rdb     *redis.Client
	q       *db.Queries
	tb      *tbclient.Client
	ctl     *control.Service
	ing     *ingest.Ingester
	device  string
	tbDevID string
	sim     *devicesim.Device
	actor   control.Actor
}

func newStack(t *testing.T, cfg control.Config) *stack {
	t.Helper()
	ctx := context.Background()

	// --- ThingsBoard ---
	if conn, err := net.DialTimeout("tcp", "localhost:8081", 2*time.Second); err != nil {
		t.Skipf("ThingsBoard not reachable (%v); start it with: docker compose -f docker-compose.thingsboard.yml up -d", err)
	} else {
		conn.Close()
	}
	tb, err := tbclient.New(tbclient.Config{
		BaseURL:  envOr("TB_BASE_URL", "http://localhost:8081"),
		Username: envOr("TB_USERNAME", "tenant@thingsboard.org"),
		Password: envOr("TB_PASSWORD", "tenant"),
	})
	if err != nil {
		t.Fatalf("tbclient.New: %v", err)
	}
	if err := tb.Login(ctx); err != nil {
		t.Skipf("ThingsBoard login failed: %v", err)
	}

	// --- Postgres / Redis ---
	pool, err := pgxpool.New(ctx, envOr("DATABASE_URL", "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"))
	if err != nil || pool.Ping(ctx) != nil {
		t.Skipf("no local postgres; start it with: docker compose up -d")
	}
	t.Cleanup(pool.Close)

	rdb := redis.NewClient(&redis.Options{Addr: envOr("REDIS_ADDR", "localhost:6379")})
	if err := rdb.Ping(ctx).Err(); err != nil {
		t.Skipf("no local redis (%v); start it with: docker compose up -d", err)
	}
	t.Cleanup(func() { rdb.Close() })

	q := db.New(pool)

	// --- provision the device on both sides ---
	suffix := fmt.Sprintf("%d", time.Now().UnixNano())
	deviceName := "ing-dev-" + suffix
	username := "ing-user-" + suffix

	tbDev, err := tb.EnsureDevice(ctx, deviceName, tbclient.DefaultDeviceProfile)
	if err != nil {
		t.Fatalf("provision in ThingsBoard: %v", err)
	}
	t.Cleanup(func() { tb.DeleteDevice(context.Background(), tbDev.ID.ID) })

	creds, err := tb.GetDeviceCredentials(ctx, tbDev.ID.ID)
	if err != nil {
		t.Fatalf("credentials: %v", err)
	}

	var userID int64
	if err := pool.QueryRow(ctx,
		`INSERT INTO users (username, password_hash, role, customer_id) VALUES ($1,'x',$2::text,
			CASE WHEN $2::text = 'platform_admin' THEN NULL ELSE (SELECT id FROM customers WHERE name = 'default') END) RETURNING id`,
		username, control.RoleCustomerAdmin).Scan(&userID); err != nil {
		t.Fatalf("seed user: %v", err)
	}
	if err := q.EnsureDevice(ctx, deviceName); err != nil {
		t.Fatalf("seed device: %v", err)
	}
	if _, err := pool.Exec(ctx, `UPDATE devices SET customer_id = (SELECT id FROM customers WHERE name = 'default') WHERE device_name = $1`, deviceName); err != nil {
		t.Fatalf("assign device to default customer: %v", err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO user_devices (user_id, device_name) VALUES ($1,$2)`, userID, deviceName); err != nil {
		t.Fatalf("bind: %v", err)
	}
	t.Cleanup(func() {
		c := context.Background()
		pool.Exec(c, `DELETE FROM audit_log WHERE device_name = $1`, deviceName)
		pool.Exec(c, `DELETE FROM control_commands WHERE device_name = $1`, deviceName)
		pool.Exec(c, `DELETE FROM user_devices WHERE user_id = $1`, userID)
		pool.Exec(c, `DELETE FROM devices WHERE device_name = $1`, deviceName)
		pool.Exec(c, `DELETE FROM users WHERE id = $1`, userID)
	})

	// --- the simulated device ---
	sim := devicesim.New(devicesim.Config{
		BrokerURL:        envOr("TB_MQTT_URL", "tcp://localhost:1884"),
		AccessToken:      creds.CredentialsID,
		Tanks:            1,
		SnapshotInterval: 10 * time.Minute, // long, so nothing rides the snapshot timer
		Logf:             t.Logf,
	})
	connCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	if err := sim.Connect(connCtx); err != nil {
		t.Fatalf("device connect: %v", err)
	}
	t.Cleanup(sim.Disconnect)

	ctl := control.New(q, tb, cfg)
	ing := ingest.New(ingest.Config{
		TB: tb, Control: ctl, Redis: rdb, Queries: q,
		ResubscribeDelay: time.Second,
		Logf:             t.Logf,
	})

	return &stack{
		pool: pool, rdb: rdb, q: q, tb: tb, ctl: ctl, ing: ing,
		device: deviceName, tbDevID: tbDev.ID.ID, sim: sim,
		actor: control.Actor{UserID: userID, Username: username, Role: control.RoleCustomerAdmin},
	}
}

// TestFullLoop_CommandReachesTerminalStateViaIngest is the #10 acceptance loop: an operator issues a
// command, the device ACKs it, the device later reports the real outcome, and the ingester drives
// the command row to a terminal state — with no polling and no second MQTT channel anywhere.
func TestFullLoop_CommandReachesTerminalStateViaIngest(t *testing.T) {
	s := newStack(t, control.Config{})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	s.ing.StartDevice(ctx, s.device)
	waitForSubscription(t, s, ctx)

	cmd, err := s.ctl.Issue(ctx, control.IssueRequest{
		DeviceName: s.device,
		Method:     protocol.MethodAlarmMute,
		Args:       json.RawMessage(`{"tank":1}`),
		Reason:     "端到端验证，值班室确认",
		Actor:      s.actor,
	})
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}

	// The ACK only means the device accepted it.
	if cmd.Status != protocol.CmdStatusReceived {
		t.Fatalf("after ACK status = %q, want %q", cmd.Status, protocol.CmdStatusReceived)
	}
	if protocol.IsTerminal(cmd.Status) {
		t.Fatal("an ACK must not be terminal")
	}

	// The device reports the real outcome moments later; the ingester must carry it into the row.
	commandID := uuidText(cmd.CommandID.Bytes)
	final := awaitStatus(t, s, commandID, 25*time.Second, protocol.CmdStatusSucceeded)

	if !final.FinishedAt.Valid {
		t.Error("a terminal command must have finished_at set")
	}
	if !final.AckedAt.Valid {
		t.Error("acked_at should have been recorded")
	}
	t.Logf("full loop: pending -> sent -> received -> %s", final.Status)

	// And the whole thing must be auditable end to end.
	rows, err := s.q.ListAuditLogForCommand(ctx, cmd.CommandID)
	if err != nil {
		t.Fatalf("audit for command: %v", err)
	}
	seen := map[string]bool{}
	for _, r := range rows {
		seen[r.Action] = true
	}
	for _, want := range []string{"control.issue", "control.ack", "control.result"} {
		if !seen[want] {
			t.Errorf("audit trail is missing %q (have %v)", want, seen)
		}
	}
}

// TestFullLoop_AlarmFansOutToOperators covers the other ingestion branch: an alarm reaches the
// Redis channel /ws/alarms serves, without waiting for the snapshot cycle.
func TestFullLoop_AlarmFansOutToOperators(t *testing.T) {
	s := newStack(t, control.Config{})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sub := s.rdb.Subscribe(ctx, ingest.AlarmChannel)
	defer sub.Close()
	if _, err := sub.Receive(ctx); err != nil {
		t.Fatalf("redis subscribe: %v", err)
	}
	messages := sub.Channel()

	s.ing.StartDevice(ctx, s.device)
	waitForSubscription(t, s, ctx)

	start := time.Now()
	active, err := s.sim.RaiseAlarm(1, protocol.AlarmHighHighLevel, protocol.SeverityCritical, "1号罐高高液位报警", 1980.4)
	if err != nil {
		t.Fatalf("RaiseAlarm: %v", err)
	}

	deadline := time.After(20 * time.Second)
	for {
		select {
		case msg := <-messages:
			var got map[string]any
			if err := json.Unmarshal([]byte(msg.Payload), &got); err != nil {
				t.Fatalf("fanout payload: %v", err)
			}
			if got["alarmId"] != active.AlarmID {
				continue // an alarm from another concurrently-running test
			}
			if got["deviceName"] != s.device {
				t.Errorf("deviceName = %v, want %s", got["deviceName"], s.device)
			}
			if got["type"] != protocol.AlarmHighHighLevel || got["status"] != protocol.AlarmStatusActive {
				t.Errorf("alarm mangled in transit: %v", got)
			}
			latency := time.Since(start)
			if latency >= protocol.SnapshotIntervalSec*time.Second {
				t.Errorf("alarm fanout took %s, no faster than the %ds snapshot cycle", latency, protocol.SnapshotIntervalSec)
			}
			t.Logf("alarm reached operators in %s via device→ThingsBoard→ingest→Redis", latency)
			return
		case <-deadline:
			t.Fatal("alarm never reached the operator fan-out channel")
		}
	}
}

// TestIngest_DoesNotPersistTelemetry pins the data boundary that issue #10 is emphatic about:
// telemetry lives in ThingsBoard only. A future change that starts copying snapshots into the
// business database — recreating the duplicate time-series store — fails here.
func TestIngest_DoesNotPersistTelemetry(t *testing.T) {
	s := newStack(t, control.Config{})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	s.ing.StartDevice(ctx, s.device)
	waitForSubscription(t, s, ctx)

	for i := 0; i < 3; i++ {
		if _, err := s.sim.PublishSnapshot(); err != nil {
			t.Fatalf("PublishSnapshot: %v", err)
		}
		time.Sleep(400 * time.Millisecond)
	}
	time.Sleep(2 * time.Second) // give the ingester every chance to (wrongly) write something

	// The legacy telemetry tables were dropped with the old stack (migration 000011), so the
	// boundary is now structural: there is nowhere for the ingester to write telemetry even if it
	// tried. A change that recreates one of these tables fails here.
	for _, table := range []string{"alarms", "oil_in_out", "commit_works"} {
		var exists *string
		if err := s.pool.QueryRow(ctx, `SELECT to_regclass('public.' || $1)::text`, table).Scan(&exists); err != nil {
			t.Fatalf("check %s: %v", table, err)
		}
		if exists != nil {
			t.Errorf("table %q exists again; telemetry must stay in ThingsBoard only", table)
		}
	}

	// ...while the same data is readable from ThingsBoard, which is where it belongs.
	ts, values, err := s.tb.LatestValues(ctx, s.tbDevID, protocol.SnapshotKeys(1))
	if err != nil {
		t.Fatalf("read telemetry back from ThingsBoard: %v", err)
	}
	snap, err := protocol.ParseSnapshot(ts, values)
	if err != nil {
		t.Fatalf("ParseSnapshot: %v", err)
	}
	if len(snap.Tanks) != 1 || snap.SnapshotSeq < 3 {
		t.Errorf("ThingsBoard should hold the snapshots: %+v", snap)
	}
}

// TestControl_CrossTenantAccessIsRejected covers #10's "跨租户访问和控制被后端拒绝": an administrator
// of a *different customer* — even a customer_admin, who may control every device of their own
// customer — is refused, and the refusal is audited under the intruder's customer.
func TestControl_CrossTenantAccessIsRejected(t *testing.T) {
	s := newStack(t, control.Config{})
	ctx := context.Background()

	var customerID int64
	if err := s.pool.QueryRow(ctx,
		`INSERT INTO customers (name) VALUES ($1) RETURNING id`,
		fmt.Sprintf("intruder-co-%d", time.Now().UnixNano())).Scan(&customerID); err != nil {
		t.Fatalf("seed customer: %v", err)
	}

	var intruderID int64
	username := fmt.Sprintf("intruder-%d", time.Now().UnixNano())
	if err := s.pool.QueryRow(ctx,
		`INSERT INTO users (username, password_hash, role, customer_id) VALUES ($1,'x',$2,$3) RETURNING id`,
		username, control.RoleCustomerAdmin, customerID).Scan(&intruderID); err != nil {
		t.Fatalf("seed intruder: %v", err)
	}
	// LIFO: the audit rows and the user must go before the customer they reference.
	t.Cleanup(func() {
		c := context.Background()
		s.pool.Exec(c, `DELETE FROM customers WHERE id = $1`, customerID)
	})
	t.Cleanup(func() {
		c := context.Background()
		s.pool.Exec(c, `DELETE FROM audit_log WHERE actor = $1`, username)
		s.pool.Exec(c, `DELETE FROM users WHERE id = $1`, intruderID)
	})

	_, err := s.ctl.Issue(ctx, control.IssueRequest{
		DeviceName: s.device,
		Method:     protocol.MethodAlarmMute,
		Reason:     "越权尝试",
		Actor:      control.Actor{UserID: intruderID, Username: username, Role: control.RoleCustomerAdmin},
	})
	if err == nil {
		t.Fatal("an administrator of another customer must not be able to control the device")
	}

	// The attempt must be on the record — a denied cross-tenant control attempt is precisely the
	// kind of event an audit log exists for — and filed under the intruder's customer.
	var audited int
	if err := s.pool.QueryRow(ctx,
		`SELECT count(*) FROM audit_log WHERE actor = $1 AND outcome = 'denied' AND customer_id = $2`,
		username, customerID).Scan(&audited); err != nil {
		t.Fatalf("count audit: %v", err)
	}
	if audited == 0 {
		t.Error("the denied cross-tenant attempt was not audited under the intruder's customer")
	}
}

// --- helpers ---

func waitForSubscription(t *testing.T, s *stack, ctx context.Context) {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		for _, name := range s.ing.ActiveDevices() {
			if name == s.device {
				time.Sleep(1500 * time.Millisecond) // let the WS subscription actually establish
				return
			}
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatal("the ingester never subscribed to the device")
}

func awaitStatus(t *testing.T, s *stack, commandID string, timeout time.Duration, want string) db.ControlCommand {
	t.Helper()
	deadline := time.Now().Add(timeout)
	var last db.ControlCommand
	for time.Now().Before(deadline) {
		cmds, err := s.q.ListControlCommandsForDeviceUnscoped(context.Background(), db.ListControlCommandsForDeviceUnscopedParams{
			DeviceName: s.device, Lim: 20, Off: 0,
		})
		if err != nil {
			t.Fatalf("list commands: %v", err)
		}
		for _, c := range cmds {
			if uuidText(c.CommandID.Bytes) == commandID {
				last = c
				if c.Status == want {
					return c
				}
			}
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("command %s never reached %q (last status %q)", commandID, want, last.Status)
	return db.ControlCommand{}
}

func uuidText(b [16]byte) string {
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// TestOnlineStatus_FollowsThingsBoardActive covers the field /api/devices serves as is_online. The
// old writer died with the Alink stack, so for a while nothing set it and every device read offline.
// The device is genuinely connected (newStack connects it), so ThingsBoard reports it active.
func TestOnlineStatus_FollowsThingsBoardActive(t *testing.T) {
	s := newStack(t, control.Config{})
	ctx := context.Background()

	online := func() (bool, bool) {
		var on bool
		var lastOn, lastOff *time.Time
		if err := s.pool.QueryRow(ctx,
			`SELECT is_online, last_online_at, last_offline_at FROM devices WHERE device_name = $1`, s.device).
			Scan(&on, &lastOn, &lastOff); err != nil {
			t.Fatalf("read device: %v", err)
		}
		return on, lastOn != nil && lastOff == nil
	}

	if on, _ := online(); on {
		t.Fatal("a freshly seeded device must start offline")
	}

	// ThingsBoard flips `active` shortly after the device connects, not at the instant of connect.
	deadline := time.Now().Add(20 * time.Second)
	for {
		s.ing.SyncOnlineForTest(ctx)
		if on, _ := online(); on || time.Now().After(deadline) {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	on, stamped := online()
	if !on {
		t.Fatal("device is connected to ThingsBoard but is_online stayed false")
	}
	if !stamped {
		t.Error("going online should set last_online_at and leave last_offline_at empty")
	}

	// Syncing again with nothing changed must not move the timestamps.
	var before time.Time
	if err := s.pool.QueryRow(ctx, `SELECT last_online_at FROM devices WHERE device_name = $1`, s.device).Scan(&before); err != nil {
		t.Fatal(err)
	}
	time.Sleep(1100 * time.Millisecond)
	s.ing.SyncOnlineForTest(ctx)
	var after time.Time
	if err := s.pool.QueryRow(ctx, `SELECT last_online_at FROM devices WHERE device_name = $1`, s.device).Scan(&after); err != nil {
		t.Fatal(err)
	}
	if !after.Equal(before) {
		t.Errorf("last_online_at moved from %v to %v although nothing changed", before, after)
	}

	// A device deleted from ThingsBoard drops out of the listing and must not stay online forever.
	if err := s.tb.DeleteDevice(ctx, s.tbDevID); err != nil {
		t.Fatalf("delete device in ThingsBoard: %v", err)
	}
	s.ing.SyncOnlineForTest(ctx)
	if on, _ := online(); on {
		t.Error("device no longer exists in ThingsBoard but is still marked online")
	}
}

// TestCommandReconcile_RecoversAResultTheLiveSubscriptionNeverSaw is the compensation for a lost
// result. The ingester is deliberately not subscribed, so the device's cmdResult reaches
// ThingsBoard and nobody here hears it — the same outcome as a dropped socket, a batch that
// coalesced it away, or a database that was down while it was applied. Without the reconcile pass
// the command would sit at 'received' until the sweep expired it, and the audit trail would say the
// outcome was unknown when ThingsBoard held the answer all along.
func TestCommandReconcile_RecoversAResultTheLiveSubscriptionNeverSaw(t *testing.T) {
	s := newStack(t, control.Config{})
	ctx := context.Background()

	cmd, err := s.ctl.Issue(ctx, control.IssueRequest{
		DeviceName: s.device,
		Method:     protocol.MethodAlarmMute,
		Args:       json.RawMessage(`{"tank":1}`),
		Reason:     "回读补偿验证",
		Actor:      s.actor,
	})
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if cmd.Status != protocol.CmdStatusReceived {
		t.Fatalf("after ACK status = %q, want %q", cmd.Status, protocol.CmdStatusReceived)
	}
	commandID := uuidText(cmd.CommandID.Bytes)

	// The device reports its outcome shortly after the ACK; give ThingsBoard a moment to hold it.
	deadline := time.Now().Add(25 * time.Second)
	recorded := 0
	for recorded == 0 && time.Now().Before(deadline) {
		time.Sleep(500 * time.Millisecond)
		recorded = s.ing.ReconcileCommands(ctx)
	}
	if recorded == 0 {
		t.Fatal("the reconcile pass never found the device's result in ThingsBoard")
	}

	final := awaitStatus(t, s, commandID, 5*time.Second, protocol.CmdStatusSucceeded)
	if !final.FinishedAt.Valid {
		t.Error("a terminal command must have finished_at set")
	}

	// Nothing is open any more, and repeating the pass must change nothing.
	if again := s.ing.ReconcileCommands(ctx); again != 0 {
		t.Errorf("a second pass recorded %d more result(s); the guard should make it a no-op", again)
	}
	var results int
	if err := s.pool.QueryRow(ctx,
		`SELECT count(*) FROM audit_log WHERE command_id = $1 AND action = 'control.result'`, cmd.CommandID).Scan(&results); err != nil {
		t.Fatalf("count result audits: %v", err)
	}
	if results != 1 {
		t.Errorf("control.result audited %d times, want exactly 1", results)
	}
}
