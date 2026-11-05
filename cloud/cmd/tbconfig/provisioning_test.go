package main

import "testing"

func profile(ptype, key, secret string) map[string]any {
	pc := map[string]any{"type": ptype}
	if secret != "" {
		pc["provisionDeviceSecret"] = secret
	}
	p := map[string]any{"provisionType": ptype, "profileData": map[string]any{"provisionConfiguration": pc}}
	if key != "" {
		p["provisionDeviceKey"] = key
	}
	return p
}

func TestCarryProvisioningKeepsInstalledPair(t *testing.T) {
	spec := profile("CHECK_PRE_PROVISIONED_DEVICES", "", "")
	carryProvisioning(spec, profile("CHECK_PRE_PROVISIONED_DEVICES", "k1", "s1"))
	if ensureProvisioningCredentials(spec) {
		t.Fatal("an installed pair must not be regenerated")
	}
	if spec["provisionDeviceKey"] != "k1" || provisionConfig(spec)["provisionDeviceSecret"] != "s1" {
		t.Fatalf("pair not carried: %v", spec)
	}
}

func TestEnsureGeneratesOnlyWhenEnabledAndMissing(t *testing.T) {
	off := profile("DISABLED", "", "")
	if ensureProvisioningCredentials(off) || off["provisionDeviceKey"] != nil {
		t.Fatal("DISABLED must not get credentials")
	}
	on := profile("CHECK_PRE_PROVISIONED_DEVICES", "", "")
	if !ensureProvisioningCredentials(on) {
		t.Fatal("enabled profile without a pair must get one")
	}
	if k, _ := on["provisionDeviceKey"].(string); len(k) < 20 {
		t.Fatalf("key too short: %q", k)
	}
	if s, _ := provisionConfig(on)["provisionDeviceSecret"].(string); len(s) < 20 {
		t.Fatalf("secret too short: %q", s)
	}
}

func TestRedactProvisioningStripsKeyAndSecret(t *testing.T) {
	p := map[string]any{
		"name":               "x",
		"provisionType":      "CHECK_PRE_PROVISIONED_DEVICES",
		"provisionDeviceKey": "k",
		"profileData": map[string]any{
			"provisionConfiguration": map[string]any{
				"type":                  "CHECK_PRE_PROVISIONED_DEVICES",
				"provisionDeviceSecret": "s",
			},
		},
	}
	redactProvisioning(p)
	if _, ok := p["provisionDeviceKey"]; ok {
		t.Fatal("provisionDeviceKey survived")
	}
	pc := p["profileData"].(map[string]any)["provisionConfiguration"].(map[string]any)
	if _, ok := pc["provisionDeviceSecret"]; ok {
		t.Fatal("provisionDeviceSecret survived")
	}
	if p["provisionType"] != "CHECK_PRE_PROVISIONED_DEVICES" || pc["type"] == nil {
		t.Fatal("non-secret fields must be kept")
	}
	redactProvisioning(map[string]any{}) // no profileData: must not panic
}
