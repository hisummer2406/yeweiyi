package api_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

func bodyCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("body %q: %v", rec.Body.String(), err)
	}
	code, _ := body["code"].(string)
	return code
}

// Device- and command-level refusals carry a code, and it is the same code for every reason the
// caller is refused (another customer's, unbound, nonexistent), so it adds nothing to probe with
// (cloud#79, invariant 9). The bare 403 "not authorized" for account/role problems is unchanged.
func TestErrorCodes_DeviceRefusalsCarryOneCode(t *testing.T) {
	tn := newTenancy(t)
	for name, tc := range map[string]struct{ token, device string }{
		"another customer's device": {tn.userB, tn.devA1},
		"unbound device":            {tn.userA, tn.devA2},
		"nonexistent device":        {tn.adminA, tn.devGhost},
	} {
		if rec := tn.get(tc.token, "/api/devices/"+tc.device+"/commands"); rec.Code != http.StatusForbidden || bodyCode(t, rec) != "device_not_authorized" {
			t.Errorf("%s: list commands = %d %s, want 403 device_not_authorized", name, rec.Code, rec.Body.String())
		}
		if rec := tn.issueMethod(t, tc.token, tc.device, protocol.MethodPumpStart); rec.Code != http.StatusForbidden || bodyCode(t, rec) != "command_not_authorized" {
			t.Errorf("%s: issue = %d %s, want 403 command_not_authorized", name, rec.Code, rec.Body.String())
		}
	}
}

// Once the caller may send a request, what is wrong with it has its own code.
func TestErrorCodes_CommandRequestFailures(t *testing.T) {
	tn := newTenancy(t) // physical commands disabled
	if rec := tn.issueMethod(t, tn.userA, tn.devA1, protocol.MethodPumpStart); rec.Code != http.StatusConflict || bodyCode(t, rec) != "physical_commands_disabled" {
		t.Errorf("pump.start = %d %s, want 409 physical_commands_disabled", rec.Code, rec.Body.String())
	}
	if rec := tn.issueMethod(t, tn.userA, tn.devA1, "no.such.method"); rec.Code != http.StatusBadRequest || bodyCode(t, rec) != "unknown_method" {
		t.Errorf("unknown method = %d %s, want 400 unknown_method", rec.Code, rec.Body.String())
	}
	rec := tn.env.do("POST", "/api/devices/"+tn.devA1+"/commands", []byte(tn.userA), []byte(`{not json`))
	if rec.Code != http.StatusBadRequest || bodyCode(t, rec) != "invalid_request" {
		t.Errorf("malformed body = %d %s, want 400 invalid_request", rec.Code, rec.Body.String())
	}
}

// Platform-admin-only device operations refused for a customer admin say so by code, and keep a
// body other than the bare "not authorized" that clients treat as a revoked account.
func TestErrorCodes_PlatformAdminOnlyDeviceOperations(t *testing.T) {
	tn := newTenancy(t)
	rec := tn.env.do("POST", "/api/devices", []byte(tn.adminA), []byte(`{"deviceName":"x","customerId":1}`))
	if rec.Code != http.StatusForbidden || bodyCode(t, rec) != "platform_admin_required" {
		t.Errorf("register as customer admin = %d %s, want 403 platform_admin_required", rec.Code, rec.Body.String())
	}
}
