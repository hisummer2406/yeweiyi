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

// Timestamp is an RFC3339 instant in the configuration file. Same reasoning as Duration: it is a
// quoted string and nothing else, because the alternatives people reach for — a bare date, an epoch
// number — each have a wrong-by-default interpretation (which timezone? seconds or millis?) and the
// one setting that uses this decides whether historical data is reported as missing or as broken.
type Timestamp struct{ time.Time }

// UnmarshalYAML accepts an RFC3339 instant such as "2026-10-01T00:00:00+08:00". An offset is
// required: a depot's "we upgraded on the 1st" is a local statement, and defaulting it to UTC would
// silently move the boundary by up to a day in a value whose whole job is to be a boundary.
func (t *Timestamp) UnmarshalYAML(b []byte) error {
	var s string
	if err := yaml.Unmarshal(b, &s); err != nil {
		return fmt.Errorf("timestamp must be a quoted RFC3339 string like \"2026-10-01T00:00:00+08:00\": %w", err)
	}
	return t.parse(s)
}

func (t *Timestamp) parse(s string) error {
	if strings.TrimSpace(s) == "" {
		*t = Timestamp{}
		return nil
	}
	parsed, err := time.Parse(time.RFC3339, s)
	if err != nil {
		return fmt.Errorf("invalid timestamp %q (want RFC3339 with an offset, e.g. \"2026-10-01T00:00:00+08:00\"): %w", s, err)
	}
	*t = Timestamp{parsed}
	return nil
}

// Set reports whether a value was configured at all.
func (t Timestamp) Set() bool { return !t.IsZero() }

// Config is the whole runtime configuration.
type Config struct {
	Server      Server      `yaml:"server"`
	Database    Database    `yaml:"database"`
	Redis       Redis       `yaml:"redis"`
	Auth        Auth        `yaml:"auth"`
	ThingsBoard ThingsBoard `yaml:"thingsboard"`
	Control     Control     `yaml:"control"`
	Ingest      Ingest      `yaml:"ingest"`
	Stock       Stock       `yaml:"stock"`
}

type Server struct {
	ListenAddr string `yaml:"listenAddr"`
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

// Stock tunes the 出入库 (stock in/out) read-through endpoint.
type Stock struct {
	// LegacyFormatBefore is the instant at or after which every device on this deployment is
	// expected to publish `stockEvent` as 设备协议 V1 JSON rather than the pre-V1
	// DetailInoutRecord.toString() debug string (zhongchengyeweiyi#39).
	//
	// It exists because upgrading the firmware does NOT convert what is already in ThingsBoard.
	// Records written before the upgrade stay unreadable forever, so without a boundary any query
	// window reaching back far enough would answer 502 for the rest of the deployment's life —
	// making the endpoint permanently broken rather than temporarily honest. Found in review of
	// PR #12.
	//
	// With it set, /api/oil-in-out treats the two cases as what they actually are:
	//
	//	before the cutoff  a known, closed data hole — counted and reported as legacySkipped,
	//	                   still HTTP 200, because nothing is wrong *now* and no action follows;
	//	at or after it     a device that should have been upgraded and was not — HTTP 502, because
	//	                   somebody needs to go and look at it.
	//
	// Unset (the default) means "the upgrade has not happened yet", and every unparseable record
	// is treated as current breakage. That is the correct default: a deployment that has not been
	// told the fleet was upgraded must not start quietly writing off unreadable records.
	LegacyFormatBefore Timestamp `yaml:"legacyFormatBefore"`
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
	// STOCK_LEGACY_FORMAT_BEFORE mirrors stock.legacyFormatBefore. A malformed value is an error
	// rather than a silent zero: a typo'd cutoff that parses as "unset" would turn every historical
	// query back into a 502, which looks like a regression in the endpoint rather than in the
	// configuration.
	if v, ok := os.LookupEnv("STOCK_LEGACY_FORMAT_BEFORE"); ok && v != "" {
		if err := c.Stock.LegacyFormatBefore.parse(v); err != nil {
			return fmt.Errorf("STOCK_LEGACY_FORMAT_BEFORE: %w", err)
		}
	}

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
	return nil
}

// ThingsBoardEnabled reports whether the single stack is configured.
func (c *Config) ThingsBoardEnabled() bool { return c.ThingsBoard.BaseURL != "" }
