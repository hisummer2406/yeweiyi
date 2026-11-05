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
	if cfg.Control.AllowConfigCommands {
		t.Error("allowConfigCommands must default to false")
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
}

// TestStockLegacyFormatBefore covers the boundary added in review of PR #12. It is the setting that
// decides whether an unreadable 出入库 record is reported as a closed historical hole or as a device
// that is broken right now, so a value that is silently mis-parsed is worse than one that is
// rejected: the mistake would surface as the endpoint apparently regressing, far from its cause.
func TestStockLegacyFormatBefore(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")

	t.Run("unset by default", func(t *testing.T) {
		cfg, err := config.Load("")
		if err != nil {
			t.Fatalf("Load: %v", err)
		}
		if cfg.Stock.LegacyFormatBefore.Set() {
			t.Error("default must be unset: a deployment nobody told about a fleet upgrade must " +
				"treat every unreadable record as current breakage")
		}
	})

	t.Run("file value", func(t *testing.T) {
		path := writeConfig(t, "stock:\n  legacyFormatBefore: \"2026-10-01T00:00:00+08:00\"\n")
		cfg, err := config.Load(path)
		if err != nil {
			t.Fatalf("Load: %v", err)
		}
		want := time.Date(2026, 10, 1, 0, 0, 0, 0, time.FixedZone("", 8*3600))
		if !cfg.Stock.LegacyFormatBefore.Equal(want) {
			t.Errorf("got %s, want %s", cfg.Stock.LegacyFormatBefore, want)
		}
	})

	t.Run("env overrides file", func(t *testing.T) {
		path := writeConfig(t, "stock:\n  legacyFormatBefore: \"2026-10-01T00:00:00+08:00\"\n")
		t.Setenv("STOCK_LEGACY_FORMAT_BEFORE", "2026-11-05T12:00:00Z")
		cfg, err := config.Load(path)
		if err != nil {
			t.Fatalf("Load: %v", err)
		}
		if !cfg.Stock.LegacyFormatBefore.Equal(time.Date(2026, 11, 5, 12, 0, 0, 0, time.UTC)) {
			t.Errorf("env did not override the file: got %s", cfg.Stock.LegacyFormatBefore)
		}
	})

	t.Run("rejects a value without an offset", func(t *testing.T) {
		// "2026-10-01T00:00:00" would have to be guessed at as UTC, which can move the boundary by
		// up to a day — in the one value whose entire job is to be a boundary.
		path := writeConfig(t, "stock:\n  legacyFormatBefore: \"2026-10-01T00:00:00\"\n")
		if _, err := config.Load(path); err == nil {
			t.Error("a timestamp without a timezone offset must be rejected, not assumed to be UTC")
		}
	})

	t.Run("rejects a bare date", func(t *testing.T) {
		path := writeConfig(t, "stock:\n  legacyFormatBefore: \"2026-10-01\"\n")
		if _, err := config.Load(path); err == nil {
			t.Error("a bare date must be rejected")
		}
	})

	t.Run("rejects a malformed env value", func(t *testing.T) {
		t.Setenv("STOCK_LEGACY_FORMAT_BEFORE", "yesterday")
		if _, err := config.Load(""); err == nil {
			t.Error("a malformed STOCK_LEGACY_FORMAT_BEFORE must be an error, not a silent zero — " +
				"a typo'd cutoff that parses as unset turns every historical query back into a 502")
		}
	})
}

// TestLoad_RejectsRetentionShorterThanOneRollupCycle: the rollup only writes completed hours, so
// retention must outlast the interval plus an hour or raw data expires before it is summarised.
func TestLoad_RejectsRetentionShorterThanOneRollupCycle(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "telemetry:\n  retention: 2h\n  rollupInterval: 2h\n")

	_, err := config.Load(path)
	if err == nil || !strings.Contains(err.Error(), "telemetry.retention") {
		t.Fatalf("retention == interval must be rejected naming telemetry.retention, got %v", err)
	}
}

// A production Redis runs with requirepass (docker-compose.prod.yml makes it mandatory), so the
// password has to reach the client from both the file and the environment, and a development setup
// that configures nothing must still connect without one.
func TestLoad_RedisPassword(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")

	cfg, err := config.Load("")
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Redis.Password != "" {
		t.Errorf("default redis password = %q, want empty (development Redis has none)", cfg.Redis.Password)
	}

	path := writeConfig(t, "redis:\n  password: from-file\n")
	cfg, err = config.Load(path)
	if err != nil {
		t.Fatalf("Load file: %v", err)
	}
	if cfg.Redis.Password != "from-file" {
		t.Errorf("file redis password = %q", cfg.Redis.Password)
	}

	t.Setenv("REDIS_PASSWORD", "from-env")
	cfg, err = config.Load(path)
	if err != nil {
		t.Fatalf("Load env: %v", err)
	}
	if cfg.Redis.Password != "from-env" {
		t.Errorf("env should override the file: got %q", cfg.Redis.Password)
	}
}

func TestLoad_MaxSessionAge(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")

	cfg, err := config.Load("")
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Auth.MaxSessionAge.Std() != 30*24*time.Hour {
		t.Errorf("default maxSessionAge = %s, want 720h", cfg.Auth.MaxSessionAge)
	}

	t.Setenv("TOKEN_MAX_SESSION_AGE", "168h")
	cfg, err = config.Load("")
	if err != nil {
		t.Fatalf("Load with env: %v", err)
	}
	if cfg.Auth.MaxSessionAge.Std() != 168*time.Hour {
		t.Errorf("env should set maxSessionAge, got %s", cfg.Auth.MaxSessionAge)
	}
}

// A session cap shorter than one token's lifetime would make every token expire early and renewal
// pointless; reject it rather than silently running a configuration nobody meant.
func TestValidate_MaxSessionAgeMustCoverTokenTTL(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "auth:\n  tokenTTL: \"24h\"\n  maxSessionAge: \"12h\"\n")

	_, err := config.Load(path)
	if err == nil {
		t.Fatal("maxSessionAge < tokenTTL must be rejected")
	}
	if !strings.Contains(err.Error(), "maxSessionAge") {
		t.Errorf("error should name the key, got: %v", err)
	}
}

func TestLoad_WeChatIsOffByDefault(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	cfg, err := config.Load("")
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.WeChat.Enabled {
		t.Error("WeChat login must be off unless configured: offline deployments depend on it")
	}
}

func TestLoad_WeChatEnabledNeedsCredentials(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	t.Setenv("WECHAT_ENABLED", "true")
	if _, err := config.Load(""); err == nil {
		t.Fatal("enabling WeChat without an AppID and secret must fail at startup, not on the first login")
	}
	t.Setenv("WECHAT_APP_ID", "wx123")
	t.Setenv("WECHAT_APP_SECRET", "shh")
	cfg, err := config.Load("")
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if !cfg.WeChat.Enabled || cfg.WeChat.AppID != "wx123" || cfg.WeChat.AppSecret != "shh" {
		t.Errorf("wechat = %+v", cfg.WeChat)
	}
}

func TestLoad_WeChatSecretNotAllowedInFile(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	path := writeConfig(t, "wechat:\n  enabled: true\n  appId: wx123\n  appSecret: from-the-file\n")
	_, err := config.Load(path)
	if err == nil || !strings.Contains(err.Error(), "WECHAT_APP_SECRET") {
		t.Fatalf("a file-supplied appSecret must be refused with a pointer to the env var, got %v", err)
	}
}

func TestLoad_WeChatBadBoolIsAnError(t *testing.T) {
	t.Setenv("JWT_SECRET", "s")
	t.Setenv("WECHAT_ENABLED", "yes")
	if _, err := config.Load(""); err == nil {
		t.Fatal("WECHAT_ENABLED=yes must be rejected rather than read as false")
	}
}
