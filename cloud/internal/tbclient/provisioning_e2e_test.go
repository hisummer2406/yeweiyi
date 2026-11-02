package tbclient_test

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	paho "github.com/eclipse/paho.mqtt.golang"

	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/tbclient"
)

// cloud#46 / #62: the device-provisioning flow on a real ThingsBoard — what a freshly installed screen
// gets back for a typed device number — and what revokes an identity. docs/device-provisioning.md records
// the same behaviour, observed by hand on CE 4.2.1.1 on 2026-10-07; these keep it from drifting silently
// when the platform is upgraded.
//
// Needs the device profile applied with provisioning on (go run ./cmd/tbconfig apply); skips otherwise.

type provisionReply struct {
	Status           string `json:"status"`
	CredentialsType  string `json:"credentialsType"`
	CredentialsValue string `json:"credentialsValue"`
	ErrorMsg         string `json:"errorMsg"`
}

// profileProvisioning reads the provisioning key/secret and strategy from the device profile.
func profileProvisioning(t *testing.T, c *tbclient.Client) (key, secret, strategy string) {
	t.Helper()
	var page struct {
		Data []struct {
			Name string `json:"name"`
			// ThingsBoard keeps the key beside the profile and the secret inside its provisioning config.
			Key         string `json:"provisionDeviceKey"`
			ProfileData struct {
				Provision struct {
					Type   string `json:"type"`
					Secret string `json:"provisionDeviceSecret"`
				} `json:"provisionConfiguration"`
			} `json:"profileData"`
		} `json:"data"`
	}
	if err := c.GetJSON(context.Background(), "/api/deviceProfiles?pageSize=200&page=0", &page); err != nil {
		t.Fatalf("list device profiles: %v", err)
	}
	for _, p := range page.Data {
		if p.Name == tbclient.DefaultDeviceProfile {
			return p.Key, p.ProfileData.Provision.Secret, p.ProfileData.Provision.Type
		}
	}
	t.Skipf("device profile %q not found; run: go run ./cmd/tbconfig apply", tbclient.DefaultDeviceProfile)
	return
}

// provisionOnce is what the screen does: connect as `provision` with an empty password and exchange
// the typed number plus the built-in key/secret.
func provisionOnce(t *testing.T, name, key, secret string) provisionReply {
	t.Helper()
	opts := paho.NewClientOptions().AddBroker(tbMQTTURL()).SetUsername("provision").
		SetClientID(fmt.Sprintf("prov-%d", time.Now().UnixNano())).SetCleanSession(true).SetConnectTimeout(10 * time.Second)
	cl := paho.NewClient(opts)
	if tok := cl.Connect(); !tok.WaitTimeout(15*time.Second) || tok.Error() != nil {
		t.Fatalf("connect as provision: %v", tok.Error())
	}
	defer cl.Disconnect(100)

	got := make(chan []byte, 1)
	if tok := cl.Subscribe("/provision/response", 1, func(_ paho.Client, m paho.Message) { got <- m.Payload() }); !tok.WaitTimeout(10*time.Second) || tok.Error() != nil {
		t.Fatalf("subscribe: %v", tok.Error())
	}
	req, _ := json.Marshal(map[string]string{"deviceName": name, "provisionDeviceKey": key, "provisionDeviceSecret": secret})
	if tok := cl.Publish("/provision/request", 1, false, req); !tok.WaitTimeout(10*time.Second) || tok.Error() != nil {
		t.Fatalf("publish: %v", tok.Error())
	}
	select {
	case raw := <-got:
		var r provisionReply
		if err := json.Unmarshal(raw, &r); err != nil {
			t.Fatalf("reply %q: %v", raw, err)
		}
		return r
	case <-time.After(15 * time.Second):
		t.Fatal("no provisioning reply")
	}
	return provisionReply{}
}

// canConnect reports whether a device can open an MQTT session with the token.
func canConnect(token string) bool {
	opts := paho.NewClientOptions().AddBroker(tbMQTTURL()).SetUsername(token).
		SetClientID(fmt.Sprintf("chk-%d", time.Now().UnixNano())).SetCleanSession(true).SetConnectTimeout(8 * time.Second)
	cl := paho.NewClient(opts)
	tok := cl.Connect()
	ok := tok.WaitTimeout(10*time.Second) && tok.Error() == nil
	if ok {
		cl.Disconnect(50)
	}
	return ok
}

func TestProvisioning_ClaimRepeatWrongNumberAndRevocation(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()
	key, secret, strategy := profileProvisioning(t, c)
	if key == "" || strategy != "CHECK_PRE_PROVISIONED_DEVICES" {
		t.Skipf("provisioning is not enabled with the pre-provisioned-only strategy (type=%q); run: go run ./cmd/tbconfig apply", strategy)
	}

	suffix := time.Now().UnixNano()
	register := func(label string) tbclient.Device {
		dev, err := c.EnsureDevice(ctx, fmt.Sprintf("prov-%s-%d", label, suffix), tbclient.DefaultDeviceProfile)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { c.DeleteDevice(context.Background(), dev.ID.ID) })
		return dev
	}
	a, b := register("a"), register("b")

	// A number that was never pre-registered, and one with a wrong key/secret.
	if r := provisionOnce(t, fmt.Sprintf("prov-nobody-%d", suffix), key, secret); r.Status != "FAILURE" {
		t.Errorf("unregistered number: %+v, want FAILURE", r)
	}
	if r := provisionOnce(t, a.Name, key, secret+"x"); r.Status != "NOT_FOUND" {
		t.Errorf("wrong secret: %+v, want NOT_FOUND", r)
	}

	// First claim of a pre-registered number hands out the device's own token and marks it provisioned.
	r := provisionOnce(t, a.Name, key, secret)
	creds, _ := c.GetDeviceCredentials(ctx, a.ID.ID)
	if r.Status != "SUCCESS" || r.CredentialsType != "ACCESS_TOKEN" || r.CredentialsValue == "" || r.CredentialsValue != creds.CredentialsID {
		t.Fatalf("first claim: %+v (platform token %q)", r, creds.CredentialsID)
	}
	tokenA := r.CredentialsValue
	attrs, err := c.Attributes(ctx, a.ID.ID, tbclient.ScopeServer, []string{"provisionState"})
	if err != nil || attrs["provisionState"] != "provisioned" {
		t.Errorf("provisionState = %v (%v), want provisioned", attrs["provisionState"], err)
	}

	// A second screen typing the same number cannot take the identity over silently.
	if r2 := provisionOnce(t, a.Name, key, secret); r2.Status != "FAILURE" || r2.CredentialsValue != "" {
		t.Errorf("second claim of an activated number: %+v, want FAILURE and no token", r2)
	}

	// Typing B's valid, not-yet-activated number by mistake takes B's identity: validity is not a match.
	mistaken := provisionOnce(t, b.Name, key, secret)
	if mistaken.Status != "SUCCESS" {
		t.Fatalf("mistyped-but-valid number: %+v", mistaken)
	}
	tokenB := mistaken.CredentialsValue
	if !canConnect(tokenB) {
		t.Fatal("the token handed out for B does not connect")
	}

	// Revocation: deleting provisionState only re-opens the name — and returns the SAME token.
	if err := c.DeleteServerAttributes(ctx, b.ID.ID, []string{"provisionState"}); err != nil {
		t.Fatal(err)
	}
	if again := provisionOnce(t, b.Name, key, secret); again.Status != "SUCCESS" || again.CredentialsValue != tokenB {
		t.Errorf("re-provision after deleting provisionState: %+v, want SUCCESS with the unchanged token", again)
	}
	if !canConnect(tokenB) {
		t.Error("the old token must still work after only deleting provisionState — that is why it is not a revocation")
	}

	// Rotating the token is what revokes: the old one stops connecting at once, and a re-provision
	// then returns the new one.
	newToken := fmt.Sprintf("rot%d", suffix)
	if err := c.SetAccessToken(ctx, b.ID.ID, newToken); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(15 * time.Second)
	for canConnect(tokenB) && time.Now().Before(deadline) {
		time.Sleep(500 * time.Millisecond)
	}
	if canConnect(tokenB) {
		t.Error("the old token still connects after SetAccessToken")
	}
	if !canConnect(newToken) {
		t.Error("the new token does not connect")
	}
	if err := c.DeleteServerAttributes(ctx, b.ID.ID, []string{"provisionState"}); err != nil {
		t.Fatal(err)
	}
	if r3 := provisionOnce(t, b.Name, key, secret); r3.Status != "SUCCESS" || r3.CredentialsValue != newToken {
		t.Errorf("re-provision after rotation: %+v, want the rotated token %q", r3, newToken)
	}

	// A's device, holding the token it was given, still works and reports as that device.
	sim := devicesim.New(devicesim.Config{BrokerURL: tbMQTTURL(), AccessToken: tokenA, Tanks: 1, Logf: t.Logf})
	cctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	if err := sim.Connect(cctx); err != nil {
		t.Fatalf("device A with its provisioned token: %v", err)
	}
	defer sim.Disconnect()
}
