package api_test

import (
	"net/http"
	"testing"
)

func TestHealthz_OK(t *testing.T) {
	env := newTestEnv(t)

	rec := env.do("GET", "/healthz", nil, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
}
