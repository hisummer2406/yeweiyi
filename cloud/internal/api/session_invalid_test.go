package api_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// Every token-caused 401 — missing, expired, tampered — carries the same code and text, on a plain
// protected route and on the /ws/alarms handshake alike (cloud#78).
func TestSessionInvalid_SameCodeOnHTTPAndWebSocket(t *testing.T) {
	env := renewEnv(t)
	now := time.Now()
	expired := env.mintToken(now.Add(-2*time.Hour), now.Add(-time.Hour), nil)
	valid := env.mintToken(now, now.Add(time.Hour), nil)
	// Change a character in the middle of the signature. (The last characters of a base64 signature
	// carry unused bits, so editing them can leave the decoded signature unchanged.)
	dot := strings.LastIndex(valid, ".") + 1
	swap := byte('A')
	if valid[dot] == swap {
		swap = 'B'
	}
	tampered := valid[:dot] + string(swap) + valid[dot+1:]

	cases := []struct {
		name  string
		token string // "" = no token at all
	}{{"missing", ""}, {"expired", expired}, {"tampered", tampered}}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var tok []byte
			if tc.token != "" {
				tok = []byte(tc.token)
			}
			check := func(where string, rec *httptest.ResponseRecorder) {
				t.Helper()
				if rec.Code != http.StatusUnauthorized {
					t.Fatalf("%s: status = %d, want 401", where, rec.Code)
				}
				var body map[string]string
				if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
					t.Fatalf("%s: body %q: %v", where, rec.Body.String(), err)
				}
				if body["code"] != "session_invalid" || body["error"] != "登录失效，请重新登录" {
					t.Fatalf("%s: body = %v", where, body)
				}
			}
			check("http", env.do("GET", renewProbePath, tok, nil))

			path := "/ws/alarms?device=any"
			if tc.token != "" {
				path += "&token=" + tc.token
			}
			check("ws", env.do("GET", path, nil, nil))
		})
	}
}

// Login failures stay what they were: a wrong password is not "session invalid".
func TestSessionInvalid_LoginErrorsUnchanged(t *testing.T) {
	env := renewEnv(t)
	rec := env.do("POST", "/api/login", nil, []byte(`{"username":"`+env.username+`","password":"wrong"}`))
	if rec.Code != http.StatusUnauthorized || !strings.Contains(rec.Body.String(), `"invalid_credentials"`) {
		t.Fatalf("login = %d %s", rec.Code, rec.Body.String())
	}
}

// Validation failures name the field to fix by code (cloud#79); status and text are unchanged.
func TestValidationErrors_CarryFieldCodes(t *testing.T) {
	env := newTestEnv(t)
	device := "code-validation-device"
	env.ownDevice(device)
	for query, want := range map[string]string{
		"limit=0":        "invalid_paging",
		"beginTime=nope": "invalid_time_format",
		"endTime=nope":   "invalid_time_format",
	} {
		rec := env.authed("GET", "/api/alarms?device="+device+"&"+query, nil)
		if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), `"code":"`+want+`"`) {
			t.Errorf("%s: %d %s, want 400 %s", query, rec.Code, rec.Body.String(), want)
		}
	}
}
