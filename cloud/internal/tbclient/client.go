// Package tbclient is this backend's only door into ThingsBoard CE.
//
// Two rules shape everything here, both from GitHub issue #10:
//
//  1. Never touch ThingsBoard's own database directly. Everything goes through its public REST and
//     WebSocket APIs, so a ThingsBoard upgrade cannot silently break us through a schema change.
//  2. Only ThingsBoard CE (Apache 2.0) features. Nothing here may depend on a PE-only endpoint,
//     on ThingsBoard Cloud, or on any online license check — the same binary has to run on a
//     fully air-gapped network.
//
// Division of labour with the rest of the repo: this package speaks HTTP/WS and returns raw
// ThingsBoard shapes; internal/protocol owns the wire contract and does the interpreting. Keeping
// those apart is what lets the protocol be tested from fixtures with no ThingsBoard running.
package tbclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// ErrNotFound is returned when ThingsBoard answers 404 — most usefully for "no such device", which
// callers need to distinguish from a transport failure.
var ErrNotFound = errors.New("thingsboard: not found")

// Error is a non-2xx ThingsBoard response, kept whole so callers can log the server's own message
// rather than a generic "request failed".
type Error struct {
	StatusCode int
	Method     string
	Path       string
	Body       string
}

func (e *Error) Error() string {
	return fmt.Sprintf("thingsboard: %s %s: %d: %s", e.Method, e.Path, e.StatusCode, e.Body)
}

// Unavailable reports whether this error means ThingsBoard itself is down or broken, as opposed to
// the request being wrong. Issue #10 requires the API to degrade explicitly when ThingsBoard is
// unavailable, and this is the predicate that decision hangs off: 5xx is theirs, 4xx is ours.
func (e *Error) Unavailable() bool { return e.StatusCode >= 500 }

// Unavailable reports whether err indicates ThingsBoard is unreachable or failing, covering both
// transport errors (connection refused, timeout) and 5xx responses.
//
// The two answers lead to different actions, which is why they must not be conflated: "ThingsBoard
// is unavailable" is a platform outage the API surfaces as 503 and an operator has to fix, whereas
// a missing device or an unreachable *device* is a normal, expected state that the caller retries
// or reports to the user. Both of the latter travel as sentinel errors and are excluded here.
func Unavailable(err error) bool {
	if err == nil {
		return false
	}
	// Known, well-formed outcomes that say nothing about ThingsBoard's own health. Checked before
	// the fallback below, which would otherwise treat them as outages — they are not *Error values,
	// so they would fall through to the catch-all.
	if errors.Is(err, ErrNotFound) || errors.Is(err, ErrDeviceOffline) {
		return false
	}
	var tbErr *Error
	if errors.As(err, &tbErr) {
		return tbErr.Unavailable()
	}
	// Anything that is not a well-formed ThingsBoard response — dial failure, TLS failure, context
	// deadline — means we could not reach it.
	return true
}

// Config describes how to reach one ThingsBoard installation.
type Config struct {
	// BaseURL is the ThingsBoard HTTP endpoint, e.g. http://localhost:8080. No trailing slash.
	BaseURL string
	// Username/Password authenticate a ThingsBoard *tenant administrator*. The backend acts as
	// that tenant admin for device provisioning and RPC; it never uses a device's own credentials.
	Username string
	Password string
	// HTTPClient is optional; a 30s-timeout client is used when nil.
	HTTPClient *http.Client
}

// Client is a ThingsBoard REST client. It is safe for concurrent use — the JWT it holds is guarded
// by a mutex and refreshed transparently.
type Client struct {
	cfg  Config
	http *http.Client

	mu           sync.Mutex
	token        string
	refreshToken string
	tokenSetAt   time.Time
}

// New builds a Client. It performs no I/O; the first request logs in lazily.
func New(cfg Config) (*Client, error) {
	if cfg.BaseURL == "" {
		return nil, errors.New("tbclient: BaseURL is required")
	}
	if cfg.Username == "" || cfg.Password == "" {
		return nil, errors.New("tbclient: Username and Password are required")
	}
	hc := cfg.HTTPClient
	if hc == nil {
		hc = &http.Client{Timeout: 30 * time.Second}
	}
	cfg.BaseURL = strings.TrimRight(cfg.BaseURL, "/")
	return &Client{cfg: cfg, http: hc}, nil
}

type loginResponse struct {
	Token        string `json:"token"`
	RefreshToken string `json:"refreshToken"`
}

// Login authenticates and caches a JWT. Callers do not normally need to call this — every request
// does it on demand — but the e2e checks use it to fail fast with a clear message.
func (c *Client) Login(ctx context.Context) error {
	body, _ := json.Marshal(map[string]string{
		"username": c.cfg.Username,
		"password": c.cfg.Password,
	})

	// Deliberately not via c.do: that would recurse, since do() logs in when it has no token.
	raw, err := c.roundTrip(ctx, http.MethodPost, "/api/auth/login", bytes.NewReader(body), "")
	if err != nil {
		return fmt.Errorf("login as %s: %w", c.cfg.Username, err)
	}
	var lr loginResponse
	if err := json.Unmarshal(raw, &lr); err != nil {
		return fmt.Errorf("login: decode response: %w", err)
	}
	if lr.Token == "" {
		return errors.New("tbclient: login returned an empty token")
	}

	c.mu.Lock()
	c.token, c.refreshToken, c.tokenSetAt = lr.Token, lr.RefreshToken, time.Now()
	c.mu.Unlock()
	return nil
}

// Token returns the cached JWT, logging in first if there is none. The WebSocket subscriber needs
// the raw token because ThingsBoard's telemetry socket authenticates by query parameter, not by
// header.
func (c *Client) Token(ctx context.Context) (string, error) {
	c.mu.Lock()
	tok := c.token
	c.mu.Unlock()
	if tok != "" {
		return tok, nil
	}
	if err := c.Login(ctx); err != nil {
		return "", err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.token, nil
}

// do performs an authenticated request, transparently re-logging-in once on a 401. ThingsBoard JWTs
// are short-lived (2.5h by default) and this process is expected to outlive many of them, so a
// retry-once-on-401 is the difference between a long-running poller working and it dying overnight.
func (c *Client) do(ctx context.Context, method, path string, body any, out any) error {
	var payload []byte
	if body != nil {
		var err error
		payload, err = json.Marshal(body)
		if err != nil {
			return fmt.Errorf("encode request body: %w", err)
		}
	}

	token, err := c.Token(ctx)
	if err != nil {
		return err
	}

	reader := func() io.Reader {
		if payload == nil {
			return nil
		}
		return bytes.NewReader(payload)
	}

	raw, err := c.roundTrip(ctx, method, path, reader(), token)
	var tbErr *Error
	if errors.As(err, &tbErr) && tbErr.StatusCode == http.StatusUnauthorized {
		if lerr := c.Login(ctx); lerr != nil {
			return lerr
		}
		token, terr := c.Token(ctx)
		if terr != nil {
			return terr
		}
		raw, err = c.roundTrip(ctx, method, path, reader(), token)
	}
	if err != nil {
		return err
	}

	if out == nil || len(raw) == 0 {
		return nil
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return fmt.Errorf("decode %s %s: %w (body: %s)", method, path, err, truncate(raw, 512))
	}
	return nil
}

func (c *Client) roundTrip(ctx context.Context, method, path string, body io.Reader, token string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.cfg.BaseURL+path, body)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	if token != "" {
		// ThingsBoard's own header name — not the standard Authorization header.
		req.Header.Set("X-Authorization", "Bearer "+token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("thingsboard: %s %s: %w", method, path, err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("thingsboard: %s %s: read body: %w", method, path, err)
	}

	if resp.StatusCode == http.StatusNotFound {
		return nil, fmt.Errorf("%s %s: %w", method, path, ErrNotFound)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, &Error{StatusCode: resp.StatusCode, Method: method, Path: path, Body: truncate(raw, 512)}
	}
	return raw, nil
}

func truncate(b []byte, n int) string {
	if len(b) <= n {
		return string(b)
	}
	return string(b[:n]) + "…"
}

func query(values url.Values) string {
	if len(values) == 0 {
		return ""
	}
	return "?" + values.Encode()
}
