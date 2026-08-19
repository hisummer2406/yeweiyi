package queue_test

import (
	"context"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"yeweiyi-cloud/internal/queue"
	"yeweiyi-cloud/internal/store/db"
)

// fakePublisher records PublishCommand calls instead of talking to a real MQTT broker —
// internal/mqtt doesn't exist yet (blocked on Aliyun IoT credentials, see CLAUDE.md), so this is
// the only Publisher implementation available until then.
type fakePublisher struct {
	mu    sync.Mutex
	calls []fakePublishCall
}

type fakePublishCall struct {
	deviceName  string
	commandID   int64
	commandType string
	payload     string
}

func (f *fakePublisher) PublishCommand(_ context.Context, deviceName string, commandID int64, commandType string, payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, fakePublishCall{deviceName, commandID, commandType, string(payload)})
	return nil
}

func testDatabaseURL() string {
	if v := os.Getenv("DATABASE_URL"); v != "" {
		return v
	}
	return "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"
}

// TestEnqueueWriteBack_PublishesAndMarksSent exercises the property CLAUDE.md picked river for:
// a command enqueued via EnqueueWriteBack gets picked up, "published" (here: recorded by
// fakePublisher), and the write_back_commands row transitions pending -> sent.
func TestEnqueueWriteBack_PublishesAndMarksSent(t *testing.T) {
	ctx := context.Background()

	pool, err := pgxpool.New(ctx, testDatabaseURL())
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	// Registered before the row-deleting cleanup below so it runs after it (t.Cleanup is LIFO) —
	// otherwise the pool closes before the delete queries run.
	t.Cleanup(pool.Close)

	const deviceName = "queue-test-device"
	queries := db.New(pool)

	if _, err := pool.Exec(ctx, `INSERT INTO devices (device_name) VALUES ($1) ON CONFLICT DO NOTHING`, deviceName); err != nil {
		t.Fatalf("seed device: %v", err)
	}
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), `DELETE FROM write_back_commands WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup write_back_commands: %v", err)
		}
		if _, err := pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup devices: %v", err)
		}
	})

	pub := &fakePublisher{}
	riverClient, err := queue.NewClient(queue.Config{
		Pool:      pool,
		Queries:   queries,
		Publisher: pub,
	})
	if err != nil {
		t.Fatalf("new river client: %v", err)
	}
	if err := riverClient.Start(ctx); err != nil {
		t.Fatalf("start: %v", err)
	}
	defer riverClient.Stop(ctx)

	commandID, err := queue.EnqueueWriteBack(ctx, pool, riverClient, deviceName, "oil_can_set", []byte(`{"heightOilAlarm":80}`))
	if err != nil {
		t.Fatalf("enqueue: %v", err)
	}

	deadline := time.Now().Add(5 * time.Second)
	var status string
	for time.Now().Before(deadline) {
		cmd, err := queries.GetWriteBackCommand(ctx, commandID)
		if err != nil {
			t.Fatalf("get command: %v", err)
		}
		status = cmd.Status
		if status == "sent" {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}

	if status != "sent" {
		t.Fatalf("expected status sent, got %q", status)
	}

	pub.mu.Lock()
	defer pub.mu.Unlock()
	if len(pub.calls) != 1 {
		t.Fatalf("expected 1 publish call, got %d", len(pub.calls))
	}
	if pub.calls[0].deviceName != deviceName || pub.calls[0].commandType != "oil_can_set" {
		t.Fatalf("unexpected publish call: %+v", pub.calls[0])
	}
}

// TestTTLSweepWorker_MarksExpiredCommandsFailed exercises §5.3's TTL sweep directly (bypassing
// river's periodic scheduler, which only fires hourly by default) against a command backdated past
// a short test TTL.
func TestTTLSweepWorker_MarksExpiredCommandsFailed(t *testing.T) {
	ctx := context.Background()

	pool, err := pgxpool.New(ctx, testDatabaseURL())
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Cleanup(pool.Close)

	const deviceName = "queue-ttl-test-device"
	queries := db.New(pool)

	if _, err := pool.Exec(ctx, `INSERT INTO devices (device_name) VALUES ($1) ON CONFLICT DO NOTHING`, deviceName); err != nil {
		t.Fatalf("seed device: %v", err)
	}
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), `DELETE FROM write_back_commands WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup write_back_commands: %v", err)
		}
		if _, err := pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup devices: %v", err)
		}
	})

	var commandID int64
	err = pool.QueryRow(ctx, `
		INSERT INTO write_back_commands (device_name, command_type, payload, status, created_at, updated_at)
		VALUES ($1, 'oil_can_set', '{}', 'pending', now() - interval '1 hour', now() - interval '1 hour')
		RETURNING id`, deviceName).Scan(&commandID)
	if err != nil {
		t.Fatalf("seed expired command: %v", err)
	}

	worker := &queue.TTLSweepWorker{Queries: queries, TTL: time.Minute}
	if err := worker.Work(ctx, nil); err != nil {
		t.Fatalf("sweep: %v", err)
	}

	cmd, err := queries.GetWriteBackCommand(ctx, commandID)
	if err != nil {
		t.Fatalf("get command: %v", err)
	}
	if cmd.Status != "failed" {
		t.Fatalf("expected status failed, got %q", cmd.Status)
	}
}
