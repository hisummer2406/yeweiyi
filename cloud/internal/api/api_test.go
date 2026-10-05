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
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/control"
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
	if _, err := pool.Exec(ctx, `INSERT INTO users (username, password_hash, customer_id) VALUES ($1, $2, `+defaultCustomerSQL+`) ON CONFLICT DO NOTHING`, username, testUserPasswordHash); err != nil {
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

// defaultCustomerSQL selects the customer the migration backfills everything into. Tests that do
// not care about tenancy seed their users and devices there, so they behave as they did before
// customers existed.
const defaultCustomerSQL = `(SELECT id FROM customers WHERE name = 'default')`

// setRole changes the seeded user's role and re-logs-in, because the role travels in the JWT and an
// already-issued token keeps the role it was minted with. A platform admin belongs to no customer
// (users_customer_role_check), so promoting to it clears the customer and demoting restores default.
func (e *testEnv) setRole(role string) {
	e.t.Helper()
	if _, err := e.pool.Exec(context.Background(),
		`UPDATE users SET role = $1::text,
		        customer_id = CASE WHEN $1::text = 'platform_admin' THEN NULL
		                           ELSE COALESCE(customer_id, `+defaultCustomerSQL+`) END
		 WHERE username = $2`, role, e.username); err != nil {
		e.t.Fatalf("set role: %v", err)
	}
	e.token = e.login(e.username, "e2e-test-pass")
}

// otherUserToken creates and logs in an additional, independent user in the default customer, with
// the given role ("" for customer_user) — for tests that need two distinct users' relationship to
// the same device, or a user with a different privilege tier than env's own. env.token/env.username
// remain the "first" user's. Safe to call more than once per test: each call gets its own username.
func (e *testEnv) otherUserToken(role string) string {
	e.t.Helper()
	token, _ := e.userIn(role, e.defaultCustomerID())
	return token
}

func (e *testEnv) defaultCustomerID() int64 {
	e.t.Helper()
	var id int64
	if err := e.pool.QueryRow(context.Background(), `SELECT id FROM customers WHERE name = 'default'`).Scan(&id); err != nil {
		e.t.Fatalf("look up default customer: %v", err)
	}
	return id
}

// newCustomer creates a customer for the duration of the test. Registered before any user or device
// the test creates in it, so cleanup (LIFO) removes those first; the history rows that reference the
// customer with ON DELETE RESTRICT are removed explicitly.
func (e *testEnv) newCustomer(label string) int64 {
	e.t.Helper()
	var id int64
	name := fmt.Sprintf("api-test-%s-%s-%d", label, e.t.Name(), time.Now().UnixNano())
	if err := e.pool.QueryRow(context.Background(),
		`INSERT INTO customers (name) VALUES ($1) RETURNING id`, name).Scan(&id); err != nil {
		e.t.Fatalf("seed customer: %v", err)
	}
	e.t.Cleanup(func() {
		ctx := context.Background()
		e.pool.Exec(ctx, `DELETE FROM audit_log WHERE customer_id = $1`, id)
		e.pool.Exec(ctx, `DELETE FROM control_commands WHERE customer_id = $1`, id)
		if _, err := e.pool.Exec(ctx, `DELETE FROM customers WHERE id = $1`, id); err != nil {
			e.t.Errorf("cleanup customer %d: %v", id, err)
		}
	})
	return id
}

// userIn creates a user of the given role in the given customer (ignored for platform_admin, which
// belongs to none) and returns a logged-in token and the user's id.
func (e *testEnv) userIn(role string, customerID int64) (string, int64) {
	e.t.Helper()
	if role == "" {
		role = control.RoleCustomerUser
	}
	username := fmt.Sprintf("api-test-other-%s-%d", e.t.Name(), time.Now().UnixNano())

	var customer any = customerID
	if role == control.RolePlatformAdmin {
		customer = nil
	}
	var id int64
	if err := e.pool.QueryRow(context.Background(),
		`INSERT INTO users (username, password_hash, role, customer_id) VALUES ($1, $2, $3, $4) RETURNING id`,
		username, testUserPasswordHash, role, customer).Scan(&id); err != nil {
		e.t.Fatalf("seed other user: %v", err)
	}
	e.t.Cleanup(func() {
		// Audit rows survive their actor (actor_id is SET NULL), so remove what this user caused.
		e.pool.Exec(context.Background(), `DELETE FROM audit_log WHERE actor = $1`, username)
		if _, err := e.pool.Exec(context.Background(), `DELETE FROM users WHERE id = $1`, id); err != nil {
			e.t.Errorf("cleanup other user: %v", err)
		}
	})
	return e.login(username, "e2e-test-pass"), id
}

// secondUserToken is otherUserToken with the schema's default role (customer_user).
func (e *testEnv) secondUserToken() string {
	e.t.Helper()
	return e.otherUserToken("")
}

// mintPairingSecret mints a fresh pairing secret for deviceName the way a real installer would:
// authenticated as a (throwaway) platform_admin, through the real
// POST /api/devices/:device/pairing-secret endpoint — not by inserting into
// device_pairing_secrets directly, so these tests also exercise mintPairingSecretHandler itself.
func (e *testEnv) mintPairingSecret(deviceName string) string {
	e.t.Helper()
	admin := e.otherUserToken(control.RolePlatformAdmin)

	// Minted for the default customer, like every device these tests seed.
	body, _ := json.Marshal(map[string]int64{"customerId": e.defaultCustomerID()})
	rec := e.do("POST", "/api/devices/"+deviceName+"/pairing-secret", []byte(admin), body)
	if rec.Code != http.StatusOK {
		e.t.Fatalf("mint pairing secret for %s: expected 200, got %d: %s", deviceName, rec.Code, rec.Body.String())
	}
	var resp struct {
		PairingSecret string `json:"pairingSecret"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		e.t.Fatalf("decode mint response: %v", err)
	}
	if resp.PairingSecret == "" {
		e.t.Fatal("mint response carried no pairingSecret")
	}
	return resp.PairingSecret
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
	e.seedDeviceIn(deviceName, e.defaultCustomerID())
}

// seedDeviceIn seeds a device owned by customerID; 0 leaves it unassigned (customer_id NULL).
func (e *testEnv) seedDeviceIn(deviceName string, customerID int64) {
	e.t.Helper()
	var customer any = customerID
	if customerID == 0 {
		customer = nil
	}
	if _, err := e.pool.Exec(context.Background(),
		`INSERT INTO devices (device_name, customer_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, deviceName, customer); err != nil {
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
