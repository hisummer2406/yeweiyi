// Package config loads the server's runtime configuration.
//
// Precedence, lowest to highest: built-in defaults → YAML file → environment variables.
//
// Environment wins so a container or systemd unit can override one value without editing a file
// baked into an image. The YAML file exists because this product ships into fully offline customer
// networks (GitHub issue #1): on-site staff need somewhere to adjust an interval that carries
// comments explaining what it does and what is unsafe, which a list of environment variables in a
// unit file cannot provide.
//
// Every duration is written the way Go writes them — "30s", "5m", "24h" — rather than as a bare
// number whose unit you have to remember. Same reasoning as the units-in-key-names decision in
// 设备协议 V1: a number with an implied unit is a bug waiting to happen.
package config

import (
	"errors"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/goccy/go-yaml"
)

// Duration wraps time.Duration so it can be written as "30s" in YAML and in environment variables.
type Duration time.Duration

func (d Duration) String() string { return time.Duration(d).String() }

// Std returns the standard library duration.
func (d Duration) Std() time.Duration { return time.Duration(d) }

// UnmarshalYAML accepts "30s" / "5m" / "24h". A bare number is rejected rather than guessed at:
// silently treating 30 as nanoseconds (Go's zero-unit default) would turn a 30-second sweep into an
// instant busy loop.
func (d *Duration) UnmarshalYAML(b []byte) error {
	var s string
	if err := yaml.Unmarshal(b, &s); err != nil {
		return fmt.Errorf("duration must be a quoted string like \"30s\": %w", err)
	}
	parsed, err := time.ParseDuration(s)
	if err != nil {
		return fmt.Errorf("invalid duration %q (want a value like \"30s\", \"5m\", \"24h\"): %w", s, err)
	}
	*d = Duration(parsed)
	return nil
}

// Config is the whole runtime configuration.
type Config struct {
	Server      Server      `yaml:"server"`
	Database    Database    `yaml:"database"`
	Redis       Redis       `yaml:"redis"`
	Auth        Auth        `yaml:"auth"`
	ThingsBoard ThingsBoard `yaml:"thingsboard"`
	Control     Control     `yaml:"control"`
	Ingest      Ingest      `yaml:"ingest"`
}

type Server struct {
	ListenAddr string `yaml:"listenAddr"`
	// TLSCertFile and TLSKeyFile enable HTTPS. Both must be set for TLS to activate. Paths are
	// relative to the working directory or can be absolute. Environment variables:
	// LISTEN_ADDR, TLS_CERT_FILE, TLS_KEY_FILE.
	TLSCertFile string `yaml:"tlsCertFile"`
	TLSKeyFile  string `yaml:"tlsKeyFile"`
}

type Database struct {
	URL string `yaml:"url"`
}

type Redis struct {
	Addr string `yaml:"addr"`
}

type Auth struct {
	// JWTSecret signs API tokens. Required. Prefer supplying it through the JWT_SECRET environment
	// variable rather than the file, so it does not end up in a config backup or a git history.
	JWTSecret string   `yaml:"jwtSecret"`
	TokenTTL  Duration `yaml:"tokenTTL"`
}

type ThingsBoard struct {
	// BaseURL empty means "run without ThingsBoard": the server still starts, and the device and
	// control endpoints answer 503 with an explanation. That mode exists so business-endpoint work
	// does not require running a 2 GB container.
	BaseURL  string `yaml:"baseURL"`
	Username string `yaml:"username"`
	Password string `yaml:"password"`
}

type Control struct {
	// CommandTTL is how long a control command stays valid. Both the device and the cloud-side
	// sweep enforce it. Short by design: the window is what stops a command that sat through a
	// network outage from firing a pump long after the operator asked for it and walked away.
	CommandTTL Duration `yaml:"commandTTL"`
	// AckTimeout bounds how long ThingsBoard waits for the device's acknowledgement. It does NOT
	// bound execution — a reboot reports its outcome long after this elapses.
	AckTimeout Duration `yaml:"ackTimeout"`
	// ExpirySweepInterval is how often expired commands are closed out.
	ExpirySweepInterval Duration `yaml:"expirySweepInterval"`
	// AllowPhysicalCommands gates pump/valve/reboot.
	//
	// It defaults to false and MUST stay false until the device-side interlocks are finished and
	// verified on real hardware (issue #1). This is the one setting in this file where the wrong
	// value can move equipment in the field.
	AllowPhysicalCommands bool `yaml:"allowPhysicalCommands"`
}

type Ingest struct {
	// DeviceRefreshInterval is how often the device list is re-read so newly bound devices get a
	// telemetry subscription.
	DeviceRefreshInterval Duration `yaml:"deviceRefreshInterval"`
	// ResubscribeDelay is the wait before reopening a subscription that dropped.
	ResubscribeDelay Duration `yaml:"resubscribeDelay"`
}

// Defaults returns the configuration used when nothing is specified. Every default here is safe to
// run with; the only value that can cause physical action is AllowPhysicalCommands, which is false.
func Defaults() Config {
	return Config{
		Server:   Server{ListenAddr: ":8080"},
		Database: Database{URL: "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"},
		Redis:    Redis{Addr: "localhost:6379"},
		Auth:     Auth{TokenTTL: Duration(24 * time.Hour)},
		Control: Control{
			CommandTTL:            Duration(60 * time.Second),
			AckTimeout:            Duration(10 * time.Second),
			ExpirySweepInterval:   Duration(30 * time.Second),
			AllowPhysicalCommands: false,
		},
		Ingest: Ingest{
			DeviceRefreshInterval: Duration(30 * time.Second),
			ResubscribeDelay:      Duration(5 * time.Second),
		},
	}
}

// Load builds the configuration from defaults, then the YAML file at path if it is non-empty, then
// the environment. A path that is set but unreadable is an error — silently falling back to
// defaults would start the server with settings nobody asked for.
func Load(path string) (Config, error) {
	cfg := Defaults()

	if path != "" {
		raw, err := os.ReadFile(path)
		if err != nil {
			return Config{}, fmt.Errorf("read config %s: %w", path, err)
		}
		// Strict: an unknown or misspelled key is an error, not a silent no-op. A typo'd
		// "allowPhysicalCommand" that quietly did nothing would be the worst possible outcome for
		// that particular field.
		if err := yaml.UnmarshalWithOptions(raw, &cfg, yaml.Strict()); err != nil {
			return Config{}, fmt.Errorf("parse config %s: %w", path, err)
		}
	}

	if err := cfg.applyEnv(); err != nil {
		return Config{}, err
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// applyEnv lets environment variables override whatever the file said.
func (c *Config) applyEnv() error {
	str := func(env string, dst *string) {
		if v := os.Getenv(env); v != "" {
			*dst = v
		}
	}

	str("LISTEN_ADDR", &c.Server.ListenAddr)
	str("TLS_CERT_FILE", &c.Server.TLSCertFile)
	str("TLS_KEY_FILE", &c.Server.TLSKeyFile)
	str("DATABASE_URL", &c.Database.URL)
	str("REDIS_ADDR", &c.Redis.Addr)
	str("JWT_SECRET", &c.Auth.JWTSecret)
	str("TB_BASE_URL", &c.ThingsBoard.BaseURL)
	str("TB_USERNAME", &c.ThingsBoard.Username)
	str("TB_PASSWORD", &c.ThingsBoard.Password)

	for _, d := range []struct {
		env string
		dst *Duration
	}{
		{"TOKEN_TTL", &c.Auth.TokenTTL},
		{"COMMAND_TTL", &c.Control.CommandTTL},
		{"COMMAND_ACK_TIMEOUT", &c.Control.AckTimeout},
		{"COMMAND_EXPIRY_SWEEP_INTERVAL", &c.Control.ExpirySweepInterval},
		{"INGEST_DEVICE_REFRESH_INTERVAL", &c.Ingest.DeviceRefreshInterval},
		{"INGEST_RESUBSCRIBE_DELAY", &c.Ingest.ResubscribeDelay},
	} {
		v := os.Getenv(d.env)
		if v == "" {
			continue
		}
		parsed, err := time.ParseDuration(v)
		if err != nil {
			return fmt.Errorf("%s=%q: want a duration like \"30s\", \"5m\", \"24h\": %w", d.env, v, err)
		}
		*d.dst = Duration(parsed)
	}

	// ALLOW_PHYSICAL_COMMANDS is opt-in and deliberately strict: only "true"/"1" enable it, and
	// anything else is an error rather than a silent false. A typo here ("ture", "yes") must not
	// quietly leave the operator believing pumps are controllable when they are not — nor the
	// reverse. Unset simply leaves whatever the file said.
	if v, ok := os.LookupEnv("ALLOW_PHYSICAL_COMMANDS"); ok && v != "" {
		switch strings.ToLower(v) {
		case "true", "1":
			c.Control.AllowPhysicalCommands = true
		case "false", "0":
			c.Control.AllowPhysicalCommands = false
		default:
			return fmt.Errorf("ALLOW_PHYSICAL_COMMANDS=%q: want true/false (or 1/0)", v)
		}
	}
	return nil
}

// Validate rejects a configuration that would start but misbehave.
func (c *Config) Validate() error {
	if c.Auth.JWTSecret == "" {
		return errors.New("auth.jwtSecret is required (set JWT_SECRET or auth.jwtSecret)")
	}
	if c.Server.ListenAddr == "" {
		return errors.New("server.listenAddr must not be empty")
	}
	if c.Database.URL == "" {
		return errors.New("database.url must not be empty")
	}

	// Positive-duration checks. A zero or negative interval on a ticker panics at runtime; catching
	// it here turns a 3am crash into a startup error with the offending key named.
	for _, d := range []struct {
		name string
		v    Duration
	}{
		{"auth.tokenTTL", c.Auth.TokenTTL},
		{"control.commandTTL", c.Control.CommandTTL},
		{"control.ackTimeout", c.Control.AckTimeout},
		{"control.expirySweepInterval", c.Control.ExpirySweepInterval},
		{"ingest.deviceRefreshInterval", c.Ingest.DeviceRefreshInterval},
		{"ingest.resubscribeDelay", c.Ingest.ResubscribeDelay},
	} {
		if d.v <= 0 {
			return fmt.Errorf("%s must be positive, got %s", d.name, d.v)
		}
	}

	// A command whose acknowledgement window outlives its own validity can only ever be answered
	// after it has already expired.
	if c.Control.AckTimeout >= c.Control.CommandTTL {
		return fmt.Errorf("control.ackTimeout (%s) must be shorter than control.commandTTL (%s), "+
			"otherwise a command expires before the device's acknowledgement can arrive",
			c.Control.AckTimeout, c.Control.CommandTTL)
	}

	// The sweep has to run often enough to actually observe commands inside their window.
	if c.Control.ExpirySweepInterval > c.Control.CommandTTL {
		return fmt.Errorf("control.expirySweepInterval (%s) is longer than control.commandTTL (%s); "+
			"expired commands would sit unclosed for up to a full sweep",
			c.Control.ExpirySweepInterval, c.Control.CommandTTL)
	}

	if c.ThingsBoard.BaseURL != "" && (c.ThingsBoard.Username == "" || c.ThingsBoard.Password == "") {
		return errors.New("thingsboard.username and thingsboard.password are required when thingsboard.baseURL is set")
	}

	// TLS must be fully specified or not at all: if only one is set, it's a misconfiguration.
	if (c.Server.TLSCertFile != "") != (c.Server.TLSKeyFile != "") {
		return errors.New("server.tlsCertFile and server.tlsKeyFile must both be set or both be unset")
	}
	return nil
}

// ThingsBoardEnabled reports whether the single stack is configured.
func (c *Config) ThingsBoardEnabled() bool { return c.ThingsBoard.BaseURL != "" }
