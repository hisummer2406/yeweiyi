package api_test

import (
	"encoding/json"
	"net/http"
	"testing"
)

func TestLogin_WrongPassword(t *testing.T) {
	env := newTestEnv(t) // also exercises the successful-login path via newTestEnv's own setup

	body := jsonBody(t, map[string]string{"username": env.username, "password": "not-the-right-password"})
	rec := env.do("POST", "/api/login", nil, body)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestLogin_UnknownUser(t *testing.T) {
	env := newTestEnv(t)

	body := jsonBody(t, map[string]string{"username": "no-such-user", "password": "whatever"})
	rec := env.do("POST", "/api/login", nil, body)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestLogin_ReturnsUsableToken(t *testing.T) {
	env := newTestEnv(t)

	// env.token came from newTestEnv's own login() call — confirm it actually authorizes a request
	// rather than just being a non-empty string.
	rec := env.authed("GET", "/api/alarms?device=nonexistent-device-for-token-check", nil)
	if rec.Code == http.StatusUnauthorized {
		t.Fatalf("token from login should have authorized the request, got 401: %s", rec.Body.String())
	}
}

func TestAuthMiddleware_MissingToken(t *testing.T) {
	env := newTestEnv(t)

	rec := env.do("GET", "/api/alarms?device=x", nil, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestAuthMiddleware_InvalidToken(t *testing.T) {
	env := newTestEnv(t)

	rec := env.do("GET", "/api/alarms?device=x", []byte("not-a-real-jwt"), nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d: %s", rec.Code, rec.Body.String())
	}
	var resp map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode error body: %v", err)
	}
	if resp["error"] == "" {
		t.Fatalf("expected an error message, got: %s", rec.Body.String())
	}
}
