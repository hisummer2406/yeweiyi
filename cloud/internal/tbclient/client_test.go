package tbclient_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

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
