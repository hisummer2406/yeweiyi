package protocol_test

import (
	"encoding/json"
	"strings"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

func TestAppUpdateSharedContract(t *testing.T) {
	var fixture struct {
		Base  json.RawMessage `json:"base"`
		Cases []struct {
			Name     string         `json:"name"`
			Reset    bool           `json:"reset"`
			Patch    map[string]any `json:"patch"`
			Current  int            `json:"currentVersionCode"`
			Expected string         `json:"expected"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(readFixture(t, "app_update_cases.json"), &fixture); err != nil {
		t.Fatal(err)
	}
	for _, tc := range fixture.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			var root map[string]any
			if err := json.Unmarshal(fixture.Base, &root); err != nil {
				t.Fatal(err)
			}
			if tc.Reset {
				root["shared"] = map[string]any{}
			}
			mergeUpdateFixture(root["shared"].(map[string]any), tc.Patch)
			raw, _ := json.Marshal(root)
			result := protocol.ParseAppUpdate(raw, tc.Current)
			if string(result.Status) != tc.Expected {
				t.Fatalf("got %s, want %s", result.Status, tc.Expected)
			}
			if result.Candidate != nil && tc.Expected == "AVAILABLE" {
				if got := result.Candidate.Notes("uz"); got != "Fix level display" {
					t.Fatalf("fallback notes = %q", got)
				}
				if result.Candidate.VersionCode != 6 {
					t.Fatalf("code = %d", result.Candidate.VersionCode)
				}
			}
		})
	}
}

func mergeUpdateFixture(dst, patch map[string]any) {
	for k, value := range patch {
		if value == nil {
			delete(dst, k)
			continue
		}
		if child, ok := value.(map[string]any); ok {
			if target, ok := dst[k].(map[string]any); ok {
				mergeUpdateFixture(target, child)
				continue
			}
		}
		dst[k] = value
	}
}

func TestAppUpdateRejectsMalformedAndUnboundedInfo(t *testing.T) {
	for _, raw := range []string{"null", "[]", "{}", `{"shared":null}`, `{"shared":[]}`, `{"shared":`, strings.Repeat(" ", protocol.MaxAppUpdateInfoBytes+1)} {
		if got := protocol.ParseAppUpdate([]byte(raw), 5); got.Status != protocol.AppUpdateInvalid {
			t.Errorf("invalid input accepted as %s", got.Status)
		}
	}
}

func TestAppUpdateRejectsExcessiveJSONDepth(t *testing.T) {
	var fixture struct {
		Base json.RawMessage `json:"base"`
	}
	if err := json.Unmarshal(readFixture(t, "app_update_cases.json"), &fixture); err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	json.Unmarshal(fixture.Base, &root)
	var nested any = true
	for i := 0; i < 32; i++ {
		nested = map[string]any{"next": nested}
	}
	root["shared"].(map[string]any)["futureKey"] = nested
	raw, _ := json.Marshal(root)
	if got := protocol.ParseAppUpdate(raw, 5); got.Status != protocol.AppUpdateInvalid {
		t.Fatalf("deep JSON accepted: %s", got.Status)
	}
}

func TestAppUpdateIgnoresCaseVariantAliasesInWireOrder(t *testing.T) {
	var fixture struct {
		Base json.RawMessage `json:"base"`
	}
	if err := json.Unmarshal(readFixture(t, "app_update_cases.json"), &fixture); err != nil {
		t.Fatal(err)
	}
	raw := strings.Replace(string(fixture.Base), `"versionCode": 6`, `"versionCode": 6, "VersionCode": 99`, 1)
	if raw == string(fixture.Base) {
		t.Fatal("wire-order fixture patch did not apply")
	}
	if got := protocol.ParseAppUpdate([]byte(raw), 5); got.Status != protocol.AppUpdateAvailable {
		t.Fatalf("unknown alias changed canonical field: %s", got.Status)
	}
}
