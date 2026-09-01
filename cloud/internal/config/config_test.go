package config_test

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"yeweiyi-cloud/internal/config"
)

// These need no containers: the config layer is pure.

func writeConfig(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	return path
}

func TestLoad_DefaultsAreUsableAndSafe(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")

	cfg, err := config.Load("")
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	if cfg.Server.ListenAddr != ":8080" {
		t.Errorf("listenAddr = %q", cfg.Server.ListenAddr)
	}
	if cfg.Control.CommandTTL.Std() != 60*time.Second {
		t.Errorf("commandTTL = %s", cfg.Control.CommandTTL)
	}
	// The default that matters most: a deployment that configures nothing must not be able to move
	// equipment in the field.
	if cfg.Control.AllowPhysicalCommands {
		t.Error("allowPhysicalCommands must default to false")
	}
	// And nothing should start the legacy stack by accident.
	if cfg.LegacyStackEnabled() {
		t.Error("the legacy Alink stack must not start by default")
	}
	if cfg.ThingsBoardEnabled() {
		t.Error("ThingsBoard should be off until configured")
	}
}

func TestLoad_FileOverridesDefaults(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")

	path := writeConfig(t, `
server:
  listenAddr: ":9999"
control:
  commandTTL: "5m"
  ackTimeout: "20s"
  allowPhysicalCommands: true
thingsboard:
  baseURL: "http://tb:9090"
  username: "u"
  password: "p"
`)

	cfg, err := config.Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Server.ListenAddr != ":9999" {
		t.Errorf("listenAddr = %q", cfg.Server.ListenAddr)
	}
	if cfg.Control.CommandTTL.Std() != 5*time.Minute {
		t.Errorf("commandTTL = %s", cfg.Control.CommandTTL)
	}
	if !cfg.Control.AllowPhysicalCommands {
		t.Error("allowPhysicalCommands should be true when the file says so")
	}
	// Untouched keys keep their defaults rather than becoming zero.
	if cfg.Ingest.ResubscribeDelay.Std() != 5*time.Second {
		t.Errorf("an unset key lost its default: resubscribeDelay = %s", cfg.Ingest.ResubscribeDelay)
	}
}

func TestLoad_EnvOverridesFile(t *testing.T) {
	path := writeConfig(t, `
server:
  listenAddr: ":9999"
control:
  commandTTL: "5m"
`)
	t.Setenv("JWT_SECRET", "s")
	t.Setenv("LISTEN_ADDR", ":7777")
	t.Setenv("COMMAND_TTL", "90s")

	cfg, err := config.Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Server.ListenAddr != ":7777" {
		t.Errorf("env should win over file: listenAddr = %q", cfg.Server.ListenAddr)
	}
	if cfg.Control.CommandTTL.Std() != 90*time.Second {
		t.Errorf("env should win over file: commandTTL = %s", cfg.Control.CommandTTL)
	}
}

// TestLoad_RejectsUnknownKey: a misspelled key must fail loudly. The motivating case is
// "allowPhysicalCommand" (singular) silently doing nothing while an operator believes they enabled
// or disabled pump control.
func TestLoad_RejectsUnknownKey(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "control:\n  allowPhysicalCommand: true\n")

	_, err := config.Load(path)
	if err == nil {
		t.Fatal("a misspelled key must be an error, not silently ignored")
	}
	if !strings.Contains(err.Error(), "allowPhysicalCommand") {
		t.Errorf("the error should name the offending key, got: %v", err)
	}
}

// TestLoad_RejectsBareNumberDuration: 30 in Go's duration parsing is 30 nanoseconds, which would
// turn a 30-second sweep into a busy loop. Guessing is worse than refusing.
func TestLoad_RejectsBareNumberDuration(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "control:\n  commandTTL: 30\n")

	if _, err := config.Load(path); err == nil {
		t.Fatal("a bare number must be rejected; only \"30s\"-style values are valid")
	}
}

func TestLoad_RejectsMissingFile(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")

	// Silently falling back to defaults would start the server with settings nobody asked for.
	if _, err := config.Load("/nonexistent/config.yaml"); err == nil {
		t.Fatal("an unreadable config path must be an error")
	}
}

func TestLoad_RequiresJWTSecret(t *testing.T) {
	t.Setenv("JWT_SECRET", "")

	_, err := config.Load("")
	if err == nil {
		t.Fatal("jwtSecret is required")
	}
	if !strings.Contains(err.Error(), "jwtSecret") {
		t.Errorf("the error should name the missing setting, got: %v", err)
	}
}

// TestValidate_AckTimeoutMustBeShorterThanCommandTTL catches a combination that starts fine and then
// makes every command expire before the device can answer.
func TestValidate_AckTimeoutMustBeShorterThanCommandTTL(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "control:\n  commandTTL: \"10s\"\n  ackTimeout: \"30s\"\n")

	_, err := config.Load(path)
	if err == nil {
		t.Fatal("ackTimeout >= commandTTL must be rejected")
	}
	if !strings.Contains(err.Error(), "ackTimeout") {
		t.Errorf("error should explain which pair conflicts, got: %v", err)
	}
}

func TestValidate_SweepMustNotOutlastCommandTTL(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "control:\n  commandTTL: \"30s\"\n  ackTimeout: \"5s\"\n  expirySweepInterval: \"10m\"\n")

	if _, err := config.Load(path); err == nil {
		t.Fatal("a sweep interval longer than the command TTL must be rejected")
	}
}

// TestValidate_RejectsZeroDurations: a zero ticker interval panics at runtime. Failing at startup
// with the key named beats a 3am crash.
func TestValidate_RejectsZeroDurations(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "ingest:\n  deviceRefreshInterval: \"0s\"\n")

	_, err := config.Load(path)
	if err == nil {
		t.Fatal("a zero interval must be rejected")
	}
	if !strings.Contains(err.Error(), "deviceRefreshInterval") {
		t.Errorf("error should name the key, got: %v", err)
	}
}

func TestValidate_ThingsBoardNeedsCredentials(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "thingsboard:\n  baseURL: \"http://tb:9090\"\n")

	if _, err := config.Load(path); err == nil {
		t.Fatal("a ThingsBoard URL without credentials must be rejected")
	}
}

// TestAllowPhysicalCommands_EnvIsStrict: a typo must not be read as either value. Both directions
// matter — believing pumps are controllable when they are not is as bad as the reverse.
func TestAllowPhysicalCommands_EnvIsStrict(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")

	for _, v := range []string{"ture", "yes", "on", "enabled"} {
		t.Setenv("ALLOW_PHYSICAL_COMMANDS", v)
		if _, err := config.Load(""); err == nil {
			t.Errorf("ALLOW_PHYSICAL_COMMANDS=%q should be rejected, not interpreted", v)
		}
	}

	for _, tc := range []struct {
		v    string
		want bool
	}{{"true", true}, {"1", true}, {"false", false}, {"0", false}} {
		t.Setenv("ALLOW_PHYSICAL_COMMANDS", tc.v)
		cfg, err := config.Load("")
		if err != nil {
			t.Fatalf("ALLOW_PHYSICAL_COMMANDS=%q: %v", tc.v, err)
		}
		if cfg.Control.AllowPhysicalCommands != tc.want {
			t.Errorf("ALLOW_PHYSICAL_COMMANDS=%q gave %v", tc.v, cfg.Control.AllowPhysicalCommands)
		}
	}
}

// TestExampleConfigIsValid keeps config.example.yaml honest: it is the file operators copy, so a
// key renamed in Go without updating it would hand them a config that refuses to start.
func TestExampleConfigIsValid(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")

	cfg, err := config.Load("../../config.example.yaml")
	if err != nil {
		t.Fatalf("config.example.yaml must load cleanly: %v", err)
	}
	// It ships with the safe value; if someone flips it in the example, every copied deployment
	// inherits it.
	if cfg.Control.AllowPhysicalCommands {
		t.Error("config.example.yaml must ship with allowPhysicalCommands: false")
	}
	if cfg.LegacyStackEnabled() {
		t.Error("config.example.yaml must not enable the legacy stack")
	}
}
