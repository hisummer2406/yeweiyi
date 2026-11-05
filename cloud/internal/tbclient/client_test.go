package tbclient_test

import (
	"context"
	"encoding/base64"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"yeweiyi-cloud/internal/tbclient"
)

// These use a stub HTTP server rather than the real ThingsBoard, because the responses they need —
// a 5xx, a malformed body — cannot be provoked from a healthy instance on demand. They run with no
// containers at all.

// TestError_ServerErrorIsUnavailable covers the branch that decides whether the business API
// returns 503. It was written after a coverage run showed (*Error).Unavailable() had never actually
// executed: every earlier test reached the "not a ThingsBoard response at all" path instead, so the
// 5xx rule itself was asserted only by reading it.
func TestError_ServerErrorIsUnavailable(t *testing.T) {
	cases := []struct {
		name        string
		status      int
		unavailable bool
	}{
		{"500 internal error is an outage", http.StatusInternalServerError, true},
		{"503 unavailable is an outage", http.StatusServiceUnavailable, true},
		{"502 bad gateway is an outage", http.StatusBadGateway, true},
		{"400 bad request is our fault, not an outage", http.StatusBadRequest, false},
		{"403 forbidden is our fault, not an outage", http.StatusForbidden, false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var loggedIn bool
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/auth/login" {
					loggedIn = true
					w.Header().Set("Content-Type", "application/json")
					w.Write([]byte(`{"token":"stub-token","refreshToken":"stub-refresh"}`))
					return
				}
				w.WriteHeader(tc.status)
				w.Write([]byte(`{"message":"stub failure"}`))
			}))
			defer srv.Close()

			c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
			if err != nil {
				t.Fatalf("New: %v", err)
			}

			_, err = c.GetDeviceCredentials(context.Background(), "any-device-id")
			if err == nil {
				t.Fatal("expected an error")
			}
			if !loggedIn {
				t.Error("the client should have logged in before the request")
			}
			if got := tbclient.Unavailable(err); got != tc.unavailable {
				t.Errorf("Unavailable(%v) = %v, want %v", err, got, tc.unavailable)
			}
			// The server's own message must survive into the error text, or an operator
			// debugging an outage has nothing to go on.
			if !contains(err.Error(), "stub failure") {
				t.Errorf("error text lost the server's message: %v", err)
			}
		})
	}
}

// TestClient_RetriesOnceAfterTokenExpiry covers the 401 re-login path. A long-running poller
// outlives many ThingsBoard JWTs (they last ~2.5h by default), so this is the difference between
// the ingestion path surviving overnight and dying silently.
func TestClient_RetriesOnceAfterTokenExpiry(t *testing.T) {
	var logins, dataCalls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/login" {
			logins++
			w.Write([]byte(`{"token":"token-v` + itoa(logins) + `","refreshToken":"r"}`))
			return
		}
		dataCalls++
		// Reject the first data request as if the token had just expired, accept the retry.
		if dataCalls == 1 {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		w.Write([]byte(`{"credentialsType":"ACCESS_TOKEN","credentialsId":"tok"}`))
	}))
	defer srv.Close()

	c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	creds, err := c.GetDeviceCredentials(context.Background(), "dev")
	if err != nil {
		t.Fatalf("the client should have recovered from the 401: %v", err)
	}
	if creds.CredentialsID != "tok" {
		t.Errorf("credentialsId = %q", creds.CredentialsID)
	}
	if logins != 2 {
		t.Errorf("logins = %d, want 2 (initial + one re-login after the 401)", logins)
	}
	if dataCalls != 2 {
		t.Errorf("data calls = %d, want 2 (original + one retry)", dataCalls)
	}
}

// TestClient_DoesNotRetryForever guards the retry above: a persistently-401 server must produce an
// error rather than an endless login loop.
func TestClient_DoesNotRetryForever(t *testing.T) {
	var logins int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/login" {
			logins++
			w.Write([]byte(`{"token":"t","refreshToken":"r"}`))
			return
		}
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	c, _ := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if _, err := c.GetDeviceCredentials(ctx, "dev"); err == nil {
		t.Fatal("expected an error from a persistently unauthorized server")
	}
	if logins > 2 {
		t.Errorf("logged in %d times; the retry must happen at most once", logins)
	}
}

// TestClient_TokenRefreshesBeforeKnownJWTExpiry covers the bug found in review of PR #11: Token()
// used to hand out the cached JWT as long as it was non-empty, never checking whether it had
// actually expired. A process that outlives a JWT's lifetime (ThingsBoard's default is ~2.5h) would
// then keep re-authenticating a WebSocket with the same stale token on every reconnect, and — absent
// a lucky REST 401 to force a refresh — telemetry and command results would stop reaching the
// ingester until the process was restarted. Token() must now notice a token is past its own exp
// claim and log in again on its own, with no help from a 401.
func TestClient_TokenRefreshesBeforeKnownJWTExpiry(t *testing.T) {
	var logins int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/auth/login" {
			w.WriteHeader(http.StatusOK)
			return
		}
		logins++
		var tok string
		if logins == 1 {
			tok = makeJWT(time.Now().Add(-1 * time.Hour)) // already expired the instant it's issued
		} else {
			tok = makeJWT(time.Now().Add(2 * time.Hour))
		}
		w.Write([]byte(`{"token":"` + tok + `","refreshToken":"r"}`))
	}))
	defer srv.Close()

	c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	ctx := context.Background()

	first, err := c.Token(ctx)
	if err != nil {
		t.Fatalf("first Token(): %v", err)
	}
	second, err := c.Token(ctx)
	if err != nil {
		t.Fatalf("second Token(): %v", err)
	}
	if second == first {
		t.Fatal("Token() kept handing out an already-expired JWT instead of refreshing it")
	}
	third, err := c.Token(ctx)
	if err != nil {
		t.Fatalf("third Token(): %v", err)
	}
	if third != second {
		t.Fatal("Token() re-logged in even though the cached token was not yet near expiry")
	}
	if logins != 2 {
		t.Errorf("logins = %d, want 2 (initial + the one forced by the expired cached token)", logins)
	}
}

// TestSubscribeTelemetry_ReconnectsWithFreshTokenAfterWSAuthError covers the WebSocket half of the
// same PR #11 finding: an in-band auth rejection on the telemetry socket must invalidate the cached
// token, not just fail the current subscription, so the ingester's next reconnect attempt logs in
// again instead of handing the same rejected token straight back to a new socket.
func TestSubscribeTelemetry_ReconnectsWithFreshTokenAfterWSAuthError(t *testing.T) {
	var logins int
	upgrader := websocket.Upgrader{}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/auth/login", func(w http.ResponseWriter, r *http.Request) {
		logins++
		// A far-future expiry, so only the explicit invalidation below (not the exp check) can be
		// responsible for a second login.
		tok := makeJWT(time.Now().Add(2 * time.Hour))
		w.Write([]byte(`{"token":"` + tok + `","refreshToken":"r"}`))
	})
	mux.HandleFunc("/api/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		if _, _, err := conn.ReadMessage(); err != nil { // the authCmd
			return
		}
		if _, _, err := conn.ReadMessage(); err != nil { // the subscribe cmd
			return
		}
		// Simulate ThingsBoard rejecting the (stub) auth token in-band.
		conn.WriteJSON(map[string]any{"cmdId": 0, "errorCode": 401, "errorMsg": "authentication failed"})
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	ctx := context.Background()

	stream, err := c.SubscribeTelemetry(ctx, "device-1", nil)
	if err != nil {
		t.Fatalf("SubscribeTelemetry: %v", err)
	}
	defer stream.Close()

	select {
	case streamErr := <-stream.Err:
		if streamErr == nil {
			t.Fatal("expected the stream to report the in-band auth rejection")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for the stream to report the auth rejection")
	}

	if logins != 1 {
		t.Fatalf("logins after the rejection = %d, want 1", logins)
	}
	if _, err := c.Token(ctx); err != nil {
		t.Fatalf("Token() after invalidation: %v", err)
	}
	if logins != 2 {
		t.Errorf("logins after re-requesting a token = %d, want 2 (the rejection must have cleared the cache)", logins)
	}
}

// TestClient_ConcurrentTokenCallsCoalesceIntoOneLogin covers the login-storm half of the PR #11
// review finding: one Client is shared by every device's telemetry subscription in
// internal/ingest, so a mass reconnect (ThingsBoard restarting, say) has many goroutines call
// Token() with an empty/expired cache at roughly the same moment. Without coalescing, each would
// fire its own /api/auth/login at a platform that may itself still be recovering — a login storm
// whose size scales with fleet size. Login() must serialize concurrent callers onto one HTTP round
// trip instead.
func TestClient_ConcurrentTokenCallsCoalesceIntoOneLogin(t *testing.T) {
	var logins int32
	started := make(chan struct{}, 1)
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/auth/login" {
			w.WriteHeader(http.StatusOK)
			return
		}
		if atomic.AddInt32(&logins, 1) == 1 {
			close(started)
		}
		<-release // hold the response open so every concurrent caller has a chance to pile up behind this one
		tok := makeJWT(time.Now().Add(2 * time.Hour))
		w.Write([]byte(`{"token":"` + tok + `","refreshToken":"r"}`))
	}))
	defer srv.Close()

	c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	const n = 50
	var wg sync.WaitGroup
	errs := make(chan error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := c.Token(context.Background())
			errs <- err
		}()
	}

	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("no login request arrived")
	}
	time.Sleep(200 * time.Millisecond) // let the other 49 goroutines reach Token() and queue up
	close(release)

	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Errorf("Token(): %v", err)
		}
	}

	if got := atomic.LoadInt32(&logins); got != 1 {
		t.Errorf("logins = %d, want 1 — %d concurrent Token() calls against an empty cache must coalesce into one login", got, n)
	}
}

// TestClient_InvalidateTokenDoesNotClearAFresherToken covers the compare-and-clear half of the same
// finding: with many subscriptions sharing one Client, a stream that was using an old token can be
// slow to notice and report its rejection. If invalidateToken cleared the cache unconditionally, that
// late report could wipe out a token some other caller had already refreshed to in the meantime,
// forcing an unnecessary extra login. It must only clear the cache when the cache still holds the
// exact token that failed.
func TestClient_InvalidateTokenDoesNotClearAFresherToken(t *testing.T) {
	var logins int
	release := make(chan struct{})
	upgrader := websocket.Upgrader{}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/auth/login", func(w http.ResponseWriter, r *http.Request) {
		logins++
		tok := makeJWT(time.Now().Add(2 * time.Hour))
		w.Write([]byte(`{"token":"` + tok + `","refreshToken":"r"}`))
	})
	mux.HandleFunc("/api/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		if _, _, err := conn.ReadMessage(); err != nil { // the authCmd
			return
		}
		if _, _, err := conn.ReadMessage(); err != nil { // the subscribe cmd
			return
		}
		<-release // held until the test has independently refreshed the token — see below
		conn.WriteJSON(map[string]any{"cmdId": 0, "errorCode": 401, "errorMsg": "authentication failed"})
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	ctx := context.Background()

	// This stream connects and authenticates with whatever is cached right now (login #1).
	stream, err := c.SubscribeTelemetry(ctx, "device-1", nil)
	if err != nil {
		t.Fatalf("SubscribeTelemetry: %v", err)
	}
	defer stream.Close()

	// Something else refreshes the cache independently while the stream above is still open — the
	// same race an in-flight REST 401 retry could cause. This is login #2; the cache now holds a
	// token the stream above was never told about.
	if err := c.Login(ctx); err != nil {
		t.Fatalf("Login: %v", err)
	}
	fresh, err := c.Token(ctx)
	if err != nil {
		t.Fatalf("Token: %v", err)
	}
	if logins != 2 {
		t.Fatalf("logins = %d, want 2 before the stream's rejection is allowed to land", logins)
	}

	// Only now let the stream's (stale-token) rejection land.
	close(release)
	select {
	case streamErr := <-stream.Err:
		if streamErr == nil {
			t.Fatal("expected the stream to report the in-band auth rejection")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for the stream to report the auth rejection")
	}

	got, err := c.Token(ctx)
	if err != nil {
		t.Fatalf("Token() after the rejection: %v", err)
	}
	if got != fresh {
		t.Fatal("a rejection from a stream using a stale token cleared the newer token the cache had already moved to")
	}
	if logins != 2 {
		t.Errorf("logins = %d, want 2 — the stale rejection must not force an unnecessary third login", logins)
	}
}

var jwtSeq int32

// makeJWT builds an unsigned-but-well-formed JWT carrying an exp claim plus a monotonically
// incrementing jti — the jti exists purely so that two calls made within the same wall-clock second
// (routine in a fast test) still produce distinguishable tokens; exp alone truncates to whole
// seconds and would otherwise make same-second tokens byte-identical, which silently defeats any
// test asserting on token identity (e.g. that invalidation targeted the right one).
func makeJWT(exp time.Time) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"none","typ":"JWT"}`))
	seq := atomic.AddInt32(&jwtSeq, 1)
	payload := base64.RawURLEncoding.EncodeToString([]byte(fmt.Sprintf(`{"exp":%d,"jti":%d}`, exp.Unix(), seq)))
	return header + "." + payload + ".sig"
}

func contains(haystack, needle string) bool {
	return len(haystack) >= len(needle) && (haystack == needle || indexOf(haystack, needle) >= 0)
}

func indexOf(h, n string) int {
	for i := 0; i+len(n) <= len(h); i++ {
		if h[i:i+len(n)] == n {
			return i
		}
	}
	return -1
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}
