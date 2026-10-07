package api_test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"

	"yeweiyi-cloud/internal/api"
)

const (
	renewTTL        = time.Hour
	renewMaxSession = 24 * time.Hour
	renewHeader     = "X-Renewed-Token"
	// Any protected route will do: renewal happens in authMiddleware, before the handler.
	renewProbePath = "/api/devices"
)

// renewEnv is a test environment with a short token lifetime and a short absolute session cap, so a
// test can place a hand-minted token anywhere relative to both without waiting.
func renewEnv(t *testing.T) *testEnv {
	t.Helper()
	return newTestEnvWith(t, func(d *api.Deps) {
		d.TokenTTL = renewTTL
		d.MaxSessionAge = renewMaxSession
	})
}

func (e *testEnv) userID() int64 {
	e.t.Helper()
	var id int64
	if err := e.pool.QueryRow(context.Background(), `SELECT id FROM users WHERE username = $1`, e.username).Scan(&id); err != nil {
		e.t.Fatalf("look up user id: %v", err)
	}
	return id
}

// mintToken signs a token by hand with the test secret. authAt == nil leaves the claim out, which
// is what a token issued before this feature looks like.
func (e *testEnv) mintToken(issuedAt, expiresAt time.Time, authAt *time.Time) string {
	e.t.Helper()
	claims := jwt.MapClaims{
		"uid": e.userID(),
		"sub": e.username,
		"iat": issuedAt.Unix(),
		"exp": expiresAt.Unix(),
	}
	if authAt != nil {
		claims["authAt"] = authAt.Unix()
	}
	signed, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte("api-test-secret"))
	if err != nil {
		e.t.Fatalf("sign token: %v", err)
	}
	return signed
}

func parseTestToken(t *testing.T, raw string) jwt.MapClaims {
	t.Helper()
	claims := jwt.MapClaims{}
	if _, err := jwt.ParseWithClaims(raw, claims, func(*jwt.Token) (any, error) {
		return []byte("api-test-secret"), nil
	}, jwt.WithValidMethods([]string{"HS256"})); err != nil {
		t.Fatalf("renewed token does not verify: %v", err)
	}
	return claims
}

func claimTime(t *testing.T, c jwt.MapClaims, key string) time.Time {
	t.Helper()
	v, ok := c[key].(float64)
	if !ok {
		t.Fatalf("claim %q missing or not a number: %v", key, c[key])
	}
	return time.Unix(int64(v), 0)
}

func within(t *testing.T, name string, got, want time.Time, slack time.Duration) {
	t.Helper()
	if d := got.Sub(want); d < -slack || d > slack {
		t.Fatalf("%s = %s, want %s ±%s", name, got, want, slack)
	}
}

func (e *testEnv) probe(token string) (code int, renewed string) {
	rec := e.do("GET", renewProbePath, []byte(token), nil)
	return rec.Code, rec.Header().Get(renewHeader)
}

func TestLogin_TokenCarriesAuthAt(t *testing.T) {
	env := renewEnv(t)
	claims := parseTestToken(t, env.login(env.username, "e2e-test-pass"))
	within(t, "authAt", claimTime(t, claims, "authAt"), time.Now(), 5*time.Second)
}

func TestRenewal_NearExpiryIssuesWorkingToken(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()
	authAt := now.Add(-2 * time.Hour)
	old := env.mintToken(now.Add(-50*time.Minute), now.Add(10*time.Minute), &authAt) // 10m of 1h left

	code, renewed := env.probe(old)
	if code == http.StatusUnauthorized || code == http.StatusForbidden {
		t.Fatalf("near-expiry token should still work, got %d", code)
	}
	if renewed == "" {
		t.Fatalf("expected %s on a token with less than half its lifetime left", renewHeader)
	}

	claims := parseTestToken(t, renewed)
	within(t, "exp", claimTime(t, claims, "exp"), now.Add(renewTTL), 5*time.Second)
	within(t, "authAt", claimTime(t, claims, "authAt"), authAt, time.Second)
	if int64(claims["uid"].(float64)) != env.userID() || claims["sub"] != env.username {
		t.Fatalf("renewed token changed identity: %v", claims)
	}

	// The renewed token has to pass authMiddleware, not merely parse.
	if code, _ := env.probe(renewed); code == http.StatusUnauthorized || code == http.StatusForbidden {
		t.Fatalf("renewed token was refused: %d", code)
	}
}

func TestRenewal_PlentyOfLifetimeLeftDoesNotRenew(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()
	authAt := now.Add(-5 * time.Minute)
	tok := env.mintToken(now.Add(-5*time.Minute), now.Add(55*time.Minute), &authAt)

	if code, renewed := env.probe(tok); renewed != "" {
		t.Fatalf("did not expect a renewed token (status %d)", code)
	}
}

func TestRenewal_FreshLoginDoesNotRenew(t *testing.T) {
	env := renewEnv(t)
	if _, renewed := env.probe(env.token); renewed != "" {
		t.Fatal("a token issued by /api/login a moment ago must not be renewed")
	}
}

func TestRenewal_ExpiredTokenIsRefused(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()
	authAt := now.Add(-3 * time.Hour)
	tok := env.mintToken(now.Add(-2*time.Hour), now.Add(-time.Minute), &authAt)

	code, renewed := env.probe(tok)
	if code != http.StatusUnauthorized || renewed != "" {
		t.Fatalf("expired token: want 401 and no renewal, got %d renewed=%t", code, renewed != "")
	}
}

func TestRenewal_DisabledUserDoesNotRenew(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()
	authAt := now.Add(-2 * time.Hour)
	tok := env.mintToken(now.Add(-50*time.Minute), now.Add(10*time.Minute), &authAt)
	if _, err := env.pool.Exec(context.Background(), `UPDATE users SET disabled_at = now() WHERE username = $1`, env.username); err != nil {
		t.Fatalf("disable user: %v", err)
	}

	code, renewed := env.probe(tok)
	if code != http.StatusForbidden || renewed != "" {
		t.Fatalf("disabled user: want 403 and no renewal, got %d renewed=%t", code, renewed != "")
	}
}

func TestRenewal_DeletedUserDoesNotRenew(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()
	authAt := now.Add(-2 * time.Hour)
	tok := env.mintToken(now.Add(-50*time.Minute), now.Add(10*time.Minute), &authAt)
	if _, err := env.pool.Exec(context.Background(), `DELETE FROM users WHERE username = $1`, env.username); err != nil {
		t.Fatalf("delete user: %v", err)
	}

	code, renewed := env.probe(tok)
	if code != http.StatusForbidden || renewed != "" {
		t.Fatalf("deleted user: want 403 and no renewal, got %d renewed=%t", code, renewed != "")
	}
}

func TestRenewal_StopsAtAbsoluteSessionCap(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()
	// The session started 24h-5m ago, so the cap falls 5m from now — before this token's own exp.
	authAt := now.Add(-renewMaxSession + 5*time.Minute)
	tok := env.mintToken(now.Add(-50*time.Minute), now.Add(10*time.Minute), &authAt)

	code, renewed := env.probe(tok)
	if code == http.StatusUnauthorized || code == http.StatusForbidden {
		t.Fatalf("token is still valid, got %d", code)
	}
	if renewed != "" {
		t.Fatal("a session at its cap must not be extended")
	}
}

func TestRenewal_ClampsNewExpiryToSessionCap(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()
	// Cap is 30m away: later than this token's exp (10m) but earlier than now+TTL (1h).
	authAt := now.Add(-renewMaxSession + 30*time.Minute)
	tok := env.mintToken(now.Add(-50*time.Minute), now.Add(10*time.Minute), &authAt)

	_, renewed := env.probe(tok)
	if renewed == "" {
		t.Fatal("expected a renewal that extends up to the cap")
	}
	claims := parseTestToken(t, renewed)
	within(t, "exp", claimTime(t, claims, "exp"), authAt.Add(renewMaxSession), 2*time.Second)
}

func TestRenewal_LegacyTokenWithoutAuthAtUsesIssuedAt(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()

	// Issued 23h55m ago: the cap (iat+24h) is 5m away, before exp, so no renewal.
	capped := env.mintToken(now.Add(-renewMaxSession+5*time.Minute), now.Add(10*time.Minute), nil)
	if _, renewed := env.probe(capped); renewed != "" {
		t.Fatal("legacy token past its cap must not renew")
	}

	// Issued 50m ago: renews, and the renewed token records iat as its authAt.
	iat := now.Add(-50 * time.Minute)
	legacy := env.mintToken(iat, now.Add(10*time.Minute), nil)
	_, renewed := env.probe(legacy)
	if renewed == "" {
		t.Fatal("legacy token near expiry should renew")
	}
	within(t, "authAt", claimTime(t, parseTestToken(t, renewed), "authAt"), iat, time.Second)
}
