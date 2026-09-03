// internal/api had zero automated test coverage (CLAUDE.md's Build section flagged this
// explicitly) — every other package with real logic (internal/tbclient, internal/control,
// internal/partitions) has integration tests against the local dev stack; this is that same
// pattern applied to the REST layer: real Postgres/Redis, a real gin.Engine driven through
// httptest, no mocks.
package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/store/db"
)

func testDatabaseURL() string {
	if v := os.Getenv("DATABASE_URL"); v != "" {
		return v
	}
	return "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"
}

func testRedisAddr() string {
	if v := os.Getenv("REDIS_ADDR"); v != "" {
		return v
	}
	return "localhost:6379"
}

// noopPublisher never actually needs to succeed for these tests — every write-back test here only
// checks that a command reaches `pending` (the HTTP layer's job), not that it gets published (that's

// testEnv wires one real gin.Engine against the local dev Postgres/Redis, plus a bcrypt-seeded user
// so tests can get a real JWT through the real /api/login handler rather than minting one by hand.
type testEnv struct {
	t        *testing.T
	router   http.Handler
	pool     *pgxpool.Pool
	rdb      *redis.Client
	queries  *db.Queries
	username string
	token    string
}

const testUserPasswordHash = "$2a$10$meNXe5U2v9ec5cW0fIv2teBFiwN88qoxXndhnk.WZd.h/MimCSZ5u" // bcrypt("e2e-test-pass")

func newTestEnv(t *testing.T) *testEnv {
	return newTestEnvWith(t, nil)
}

// newTestEnvWith lets a test add dependencies the base environment leaves nil — TB and Control,
// which most tests do not need and which would otherwise force every test to stand up ThingsBoard.
func newTestEnvWith(t *testing.T, customize func(*api.Deps)) *testEnv {
	t.Helper()
	ctx := context.Background()

	pool, err := pgxpool.New(ctx, testDatabaseURL())
	if err != nil {
		t.Fatalf("connect postgres: %v", err)
	}
	t.Cleanup(pool.Close)

	rdb := redis.NewClient(&redis.Options{Addr: testRedisAddr()})
	t.Cleanup(func() { rdb.Close() })

	queries := db.New(pool)

	deps := api.Deps{
		Queries:   queries,
		Redis:     rdb,
		Pool:      pool,
		JWTSecret: []byte("api-test-secret"),
	}
	if customize != nil {
		customize(&deps)
	}
	router := api.NewRouter(deps)

	username := "api-test-user-" + t.Name()
	if _, err := pool.Exec(ctx, `INSERT INTO users (username, password_hash) VALUES ($1, $2) ON CONFLICT DO NOTHING`, username, testUserPasswordHash); err != nil {
		t.Fatalf("seed user: %v", err)
	}
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), `DELETE FROM users WHERE username = $1`, username); err != nil {
			t.Errorf("cleanup user: %v", err)
		}
	})

	env := &testEnv{t: t, router: router, pool: pool, rdb: rdb, queries: queries, username: username}
	env.token = env.login(username, "e2e-test-pass")
	return env
}

// setRole changes the seeded user's role and re-logs-in, because the role travels in the JWT and an
// already-issued token keeps the role it was minted with.
func (e *testEnv) setRole(role string) {
	e.t.Helper()
	if _, err := e.pool.Exec(context.Background(),
		`UPDATE users SET role = $1 WHERE username = $2`, role, e.username); err != nil {
		e.t.Fatalf("set role: %v", err)
	}
	e.token = e.login(e.username, "e2e-test-pass")
}

// secondUserToken creates and logs in a second, independent user sharing env's router/database —
// for tests that need two distinct users' relationship to the same device, where env.token is the
// "first" user's.
func (e *testEnv) secondUserToken() string {
	e.t.Helper()
	ctx := context.Background()
	username := "api-test-user2-" + e.t.Name()
	if _, err := e.pool.Exec(ctx, `INSERT INTO users (username, password_hash) VALUES ($1, $2) ON CONFLICT DO NOTHING`, username, testUserPasswordHash); err != nil {
		e.t.Fatalf("seed second user: %v", err)
	}
	e.t.Cleanup(func() {
		if _, err := e.pool.Exec(context.Background(), `DELETE FROM users WHERE username = $1`, username); err != nil {
			e.t.Errorf("cleanup second user: %v", err)
		}
	})
	return e.login(username, "e2e-test-pass")
}

func (e *testEnv) login(username, password string) string {
	e.t.Helper()
	body, _ := json.Marshal(map[string]string{"username": username, "password": password})
	rec := e.do("POST", "/api/login", nil, body)
	if rec.Code != http.StatusOK {
		e.t.Fatalf("login: expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		e.t.Fatalf("decode login response: %v", err)
	}
	return resp.Token
}

// do issues an HTTP request straight into the router (no real listener/socket — httptest.Recorder),
// attaching the Authorization header only when token is non-empty.
func (e *testEnv) do(method, path string, token []byte, body []byte) *httptest.ResponseRecorder {
	e.t.Helper()
	var reader *bytes.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	} else {
		reader = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, path, reader)
	req.Header.Set("Content-Type", "application/json")
	if token != nil {
		req.Header.Set("Authorization", "Bearer "+string(token))
	}
	rec := httptest.NewRecorder()
	e.router.ServeHTTP(rec, req)
	return rec
}

func (e *testEnv) authed(method, path string, body []byte) *httptest.ResponseRecorder {
	return e.do(method, path, []byte(e.token), body)
}

func (e *testEnv) seedDevice(deviceName string) {
	e.t.Helper()
	if _, err := e.pool.Exec(context.Background(), `INSERT INTO devices (device_name) VALUES ($1) ON CONFLICT DO NOTHING`, deviceName); err != nil {
		e.t.Fatalf("seed device %s: %v", deviceName, err)
	}
	e.t.Cleanup(func() {
		if _, err := e.pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			e.t.Errorf("cleanup device %s: %v", deviceName, err)
		}
	})
}

func (e *testEnv) bindDevice(deviceName string) {
	e.t.Helper()
	if _, err := e.pool.Exec(context.Background(), `
		INSERT INTO user_devices (user_id, device_name)
		SELECT id, $2 FROM users WHERE username = $1
		ON CONFLICT DO NOTHING`, e.username, deviceName); err != nil {
		e.t.Fatalf("bind device %s: %v", deviceName, err)
	}
}

// ownDevice seeds a device and binds it to env's user in one call — the common case for tests that
// exercise a handler's actual logic rather than its authorization check.
func (e *testEnv) ownDevice(deviceName string) {
	e.t.Helper()
	e.seedDevice(deviceName)
	e.bindDevice(deviceName)
}

func jsonBody(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("marshal body: %v", err)
	}
	return b
}

// assertNoLegacyTelemetryTables pins the data boundary structurally.
//
// These assertions used to count rows in `alarms`/`oil_in_out`/`commit_works` and require zero.
// The tables were dropped when the legacy stack was removed (migration 000011), so the property is
// now stronger and cheaper to state: they must not exist at all. A future change that reintroduces
// a local copy of telemetry — the duplicate time-series store the architecture exists to avoid —
// fails here rather than quietly working.
func assertNoLegacyTelemetryTables(t *testing.T, env *testEnv) {
	t.Helper()
	for _, table := range []string{"alarms", "oil_in_out", "commit_works", "write_back_commands"} {
		var exists *string
		if err := env.pool.QueryRow(context.Background(),
			`SELECT to_regclass('public.' || $1)::text`, table).Scan(&exists); err != nil {
			t.Fatalf("check %s: %v", table, err)
		}
		if exists != nil {
			t.Errorf("table %q exists again; telemetry and stock history must live in ThingsBoard, "+
				"not be copied into the business database", table)
		}
	}
}
