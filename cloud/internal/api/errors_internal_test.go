package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func recordError(t *testing.T, write func(c *gin.Context)) (int, map[string]string) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	rec := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(rec)
	c.Request = httptest.NewRequest(http.MethodGet, "/api/x", nil)
	write(c)
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("body %q: %v", rec.Body.String(), err)
	}
	return rec.Code, body
}

// A 500 never carries the underlying error text: SQL, constraint and host names stay in the log
// (cloud#79).
func TestInternalError_HidesUnderlyingError(t *testing.T) {
	secret := `ERROR: duplicate key value violates unique constraint "users_phone_key" (SQLSTATE 23505)`
	status, body := recordError(t, func(c *gin.Context) { internalError(c, errors.New(secret)) })
	if status != http.StatusInternalServerError {
		t.Fatalf("status = %d", status)
	}
	if body["code"] != codeInternalError || body["error"] != internalErrorMessage {
		t.Fatalf("body = %v", body)
	}
	for _, v := range body {
		if strings.Contains(v, "users_phone_key") || strings.Contains(v, "SQLSTATE") {
			t.Fatalf("500 body leaks the underlying error: %v", body)
		}
	}
}

// The tank list reads ThingsBoard; its failures must use the codes the telemetry reads use.
func TestWriteTankIndexError_UsesTelemetryCodes(t *testing.T) {
	for status, want := range map[int]string{
		http.StatusNotFound:            codeDeviceNotProvisioned,
		http.StatusServiceUnavailable:  codeDevicePlatformUnavailable,
		http.StatusInternalServerError: codeInternalError,
	} {
		got, body := recordError(t, func(c *gin.Context) { writeTankIndexError(c, status, errors.New("tb said no")) })
		if got != status || body["code"] != want {
			t.Errorf("status %d: got %d %v, want code %s", status, got, body, want)
		}
		if strings.Contains(body["error"], "tb said no") {
			t.Errorf("status %d leaks the upstream error: %v", status, body)
		}
	}
}
