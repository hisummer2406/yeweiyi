// This is the one deliberate exception to this package's otherwise universal `api_test` (black-box,
// real-infra, no-mocks) test convention — see every other *_test.go file in internal/api. A timing
// side-channel can only be pinned two ways: asserting on wall-clock duration, which is flaky under
// any load and exactly what review of PR #11 (round 7) asked this regression test to avoid, or
// counting how many times the expensive operation actually ran, which requires reaching into this
// package's unexported bcryptCompareHashAndPassword seam (devices.go). That seam exists for exactly
// this test and nothing else swaps it.
package api

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"

	"yeweiyi-cloud/internal/store/db"
)

func pairingTestDatabaseURL() string {
	if v := os.Getenv("DATABASE_URL"); v != "" {
		return v
	}
	return "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"
}

// TestVerifyPairingToken_CostsTheSameWhetherOrNotASecretExists is the regression test for the P2
// found in review of PR #11 (round 7): before this fix, verifyPairingToken returned immediately on
// pgx.ErrNoRows (no pairing secret ever minted for deviceName) without calling bcrypt at all, while
// a wrong-token-against-a-real-secret attempt always paid bcrypt's real, deliberately expensive
// cost. Both paths answered with an identical 401 status and body, but the cost difference made the
// two distinguishable by response latency alone — the same "which device names are real"
// enumeration the identical response text was meant to prevent, reopened through a side channel.
//
// Verified structurally, not by measuring elapsed time: both paths must invoke
// bcryptCompareHashAndPassword exactly once.
func TestVerifyPairingToken_CostsTheSameWhetherOrNotASecretExists(t *testing.T) {
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, pairingTestDatabaseURL())
	if err != nil || pool.Ping(ctx) != nil {
		t.Skipf("no local postgres; start it with: docker compose up -d (%v)", err)
	}
	defer pool.Close()

	deps := Deps{Queries: db.New(pool)}

	deviceWithSecret := fmt.Sprintf("api-pairing-timing-%d", time.Now().UnixNano())
	deviceWithoutSecret := fmt.Sprintf("api-pairing-timing-none-%d", time.Now().UnixNano())

	if _, err := pool.Exec(ctx, `INSERT INTO devices (device_name, customer_id) VALUES ($1, (SELECT id FROM customers WHERE name = 'default')), ($2, (SELECT id FROM customers WHERE name = 'default'))`, deviceWithSecret, deviceWithoutSecret); err != nil {
		t.Fatalf("seed devices: %v", err)
	}
	t.Cleanup(func() {
		pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name IN ($1, $2)`, deviceWithSecret, deviceWithoutSecret)
	})

	realHash, err := bcrypt.GenerateFromPassword([]byte("the-real-secret"), bcrypt.DefaultCost)
	if err != nil {
		t.Fatalf("bcrypt: %v", err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO device_pairing_secrets (device_name, secret_hash) VALUES ($1, $2)`,
		deviceWithSecret, string(realHash)); err != nil {
		t.Fatalf("seed pairing secret: %v", err)
	}
	// deviceWithoutSecret deliberately gets no row in device_pairing_secrets at all.

	orig := bcryptCompareHashAndPassword
	t.Cleanup(func() { bcryptCompareHashAndPassword = orig })
	var calls int
	bcryptCompareHashAndPassword = func(hashedPassword, password []byte) error {
		calls++
		return orig(hashedPassword, password)
	}

	calls = 0
	if ok, err := verifyPairingToken(ctx, deps, deviceWithSecret, "wrong-token"); err != nil || ok {
		t.Fatalf("verifyPairingToken(wrong token, real secret) = (%v, %v), want (false, nil)", ok, err)
	}
	if calls != 1 {
		t.Errorf("wrong-token-on-a-real-secret path called bcrypt %d times, want exactly 1", calls)
	}

	calls = 0
	if ok, err := verifyPairingToken(ctx, deps, deviceWithoutSecret, "any-token"); err != nil || ok {
		t.Fatalf("verifyPairingToken(no secret ever minted) = (%v, %v), want (false, nil)", ok, err)
	}
	if calls != 1 {
		t.Errorf("no-secret-minted path called bcrypt %d times, want exactly 1 (the dummy-hash comparison) "+
			"— zero here is the timing oracle this fix closes", calls)
	}
}
