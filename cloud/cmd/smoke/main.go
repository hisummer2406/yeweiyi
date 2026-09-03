// Command smoke verifies a deployed installation end to end: ThingsBoard is up, a device can
// connect over MQTT and report telemetry, and — when -api is given — the Go backend's own business
// REST API can read that telemetry back, issue a control command, and carry it through to its
// asynchronous result, with the audit trail to prove it.
//
// It exists as a compiled binary rather than as `go test` because the environments that most need
// it — an air-gapped customer network on installation day — have no Go toolchain, no test sources
// and no internet. It ships inside the offline bundle (deploy/) and is the check the install
// procedure ends with.
//
// It provisions its own throwaway device (and, when -api is given, its own throwaway login user)
// and deletes them afterwards, so it is safe to run against a live installation. It never touches
// existing devices, users or business data.
//
// One deliberate exception, found worth calling out explicitly in review of PR #11: a successful
// -api run leaves its own control.issue/control.ack/control.result rows in audit_log permanently.
// This is not an oversight — audit_log has no foreign key to devices or control_commands and never
// cascades a delete (see the table's own COMMENT in db/migrations/000010_control_and_audit.up.sql:
// a denied attempt against a device_name that turns out not to exist is exactly the kind of event
// worth keeping, so nothing here may quietly disappear when the thing it describes does). Deleting
// these rows from here would mean this tool punching a hole in that guarantee for its own
// convenience. They are cheap to filter out of a real audit review: every row a smoke run produces
// has device_name = the throwaway device's own "smoke-<nanos>" name, and actor is either that same
// login username or "device:smoke-<nanos>" for the device-reported result row.
//
//	smoke -tb http://localhost:8081 -mqtt tcp://localhost:1884
//	smoke -api http://localhost:8080      # exercise the Go backend's own REST API, not just ThingsBoard
//
// Why -api matters, found in review of PR #11: an earlier version of this tool only checked
// /healthz on the Go backend and then talked to ThingsBoard directly for telemetry read-back and
// control commands. That meant API auth, device-ownership checks, the ingester, the
// control_commands state machine, and even a broken database migration could all be silently
// broken while smoke still printed all-green — none of the checks after /healthz actually
// exercised the backend at all. When -api is given, every step from telemetry read-back onward now
// goes through the real REST API instead of ThingsBoard directly.
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

func main() {
	// os.Exit is called only here, after run() has returned and its deferred cleanup has executed.
	// Calling it from inside run() would skip every defer — including the ones that delete the
	// throwaway device and login user, which would leave debris in a production installation and
	// break this tool's promise that it is safe to run there. (Found by running it: the first
	// version did exactly that and left two devices behind.)
	os.Exit(run())
}

func run() int {
	tbURL := flag.String("tb", envOr("TB_BASE_URL", "http://localhost:8081"), "ThingsBoard base URL")
	tbUser := flag.String("user", envOr("TB_USERNAME", "tenant@thingsboard.org"), "ThingsBoard tenant admin")
	tbPass := flag.String("pass", envOr("TB_PASSWORD", "tenant"), "ThingsBoard tenant admin password")
	mqttURL := flag.String("mqtt", envOr("TB_MQTT_URL", "tcp://localhost:1884"), "ThingsBoard MQTT transport (tcp:// or ssl://)")
	caFile := flag.String("cacert", envOr("TB_MQTT_CACERT", ""), "PEM CA file verifying the broker certificate; required for ssl://")
	apiURL := flag.String("api", os.Getenv("API_BASE_URL"), "Go backend base URL; when set, read-back and control are exercised through its REST API rather than ThingsBoard directly")
	dbURL := flag.String("db", envOr("DATABASE_URL", "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"), "business PostgreSQL DSN, used only to create/remove this run's own throwaway login user (requires -api)")
	timeout := flag.Duration("timeout", 90*time.Second, "overall time budget")
	verbose := flag.Bool("v", false, "log each step's detail")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	r := &runner{verbose: *verbose}

	// Ordered deliberately: each step is a prerequisite of the next, so the first failure is the
	// most informative one. Stopping at the first failure beats reporting five symptoms of one
	// cause.
	tb := r.step("ThingsBoard 可达并可鉴权", func() (string, error) {
		c, err := tbclient.New(tbclient.Config{BaseURL: *tbURL, Username: *tbUser, Password: *tbPass})
		if err != nil {
			return "", err
		}
		if err := c.Login(ctx); err != nil {
			return "", err
		}
		r.tb = c
		return *tbURL, nil
	})
	if !tb {
		return r.report()
	}

	var api *apiClient
	if *apiURL != "" {
		if !r.step("Go 后台 /healthz", func() (string, error) {
			code, _, err := (&apiClient{base: *apiURL, hc: httpClient()}).do(ctx, http.MethodGet, "/healthz", nil, nil)
			if err != nil {
				return "", err
			}
			if code != http.StatusOK {
				return "", fmt.Errorf("HTTP %d", code)
			}
			return fmt.Sprintf("%s → 200", *apiURL), nil
		}) {
			return r.report()
		}

		var userCleanup func()
		if !r.step("业务后台登录（播种一次性账号，POST /api/login）", func() (string, error) {
			c, username, cleanup, err := loginAsThrowawayUser(ctx, *apiURL, *dbURL)
			if err != nil {
				return "", err
			}
			api, userCleanup = c, cleanup
			return username, nil
		}) {
			return r.report()
		}
		defer func() {
			if userCleanup != nil {
				userCleanup()
			}
		}()
	}

	var (
		devID string
		token string
	)
	name := fmt.Sprintf("smoke-%d", time.Now().UnixNano())

	if !r.step("设备开通（一机一密凭据签发）", func() (string, error) {
		dev, err := r.tb.EnsureDevice(ctx, name, tbclient.DefaultDeviceProfile)
		if err != nil {
			return "", err
		}
		devID = dev.ID.ID
		creds, err := r.tb.GetDeviceCredentials(ctx, devID)
		if err != nil {
			return "", err
		}
		if creds.Type != "ACCESS_TOKEN" || creds.CredentialsID == "" {
			return "", fmt.Errorf("凭据类型 %q，未签发 access token", creds.Type)
		}
		token = creds.CredentialsID
		return name, nil
	}) {
		return r.report()
	}

	// Always clean up, including on failure: a smoke run must not leave devices behind in a
	// production installation. Business-side rows (devices/control_commands, cascaded) are removed
	// the same way, when -api created any.
	defer func() {
		if devID != "" {
			if err := r.tb.DeleteDevice(context.Background(), devID); err != nil {
				fmt.Fprintf(os.Stderr, "  ! 清理测试设备失败（ThingsBoard），请手动删除 %s: %v\n", name, err)
			}
		}
		if api != nil {
			if err := api.deleteBusinessDevice(context.Background(), *dbURL, name); err != nil {
				fmt.Fprintf(os.Stderr, "  ! 清理测试设备失败（业务库），请手动删除 %s: %v\n", name, err)
			}
		}
	}()

	if api != nil {
		if !r.step("设备绑定（POST /api/devices/bind）", func() (string, error) {
			// The pairing secret is minted directly in the business database, the same
			// fixture-setup convention loginAsThrowawayUser above already uses — it stands in for
			// whoever provisions the device physically (platform_admin, via
			// POST /api/devices/:device/pairing-secret in the real flow; that handler and its
			// role gate have their own dedicated tests in internal/api/devices_test.go). Doing it
			// this way, rather than granting the throwaway login user platform_admin just to call
			// that endpoint, keeps every later step exercising the ordinary customer_user
			// ownership-gated path a real mini-program user actually takes.
			pairingSecret, err := mintPairingSecretFixture(ctx, *dbURL, name)
			if err != nil {
				return "", fmt.Errorf("mint pairing secret fixture: %w", err)
			}

			code, _, err := api.do(ctx, http.MethodPost, "/api/devices/bind", map[string]string{"deviceName": name, "pairingToken": pairingSecret}, nil)
			if err != nil {
				return "", err
			}
			if code != http.StatusOK {
				return "", fmt.Errorf("HTTP %d", code)
			}
			return "bound", nil
		}) {
			return r.report()
		}
	}

	var sim *devicesim.Device
	if !r.step("设备 MQTT 接入", func() (string, error) {
		sim = devicesim.New(devicesim.Config{
			BrokerURL:        *mqttURL,
			CACertFile:       *caFile,
			AccessToken:      token,
			Tanks:            2,
			SnapshotInterval: time.Hour, // nothing may ride the snapshot timer during a smoke run
			Logf:             r.logf,
		})
		if err := sim.Connect(ctx); err != nil {
			return "", err
		}
		return *mqttURL, nil
	}) {
		return r.report()
	}
	defer func() {
		if sim != nil {
			sim.Disconnect()
		}
	}()

	var sent protocol.Snapshot
	if !r.step("遥测上行", func() (string, error) {
		s, err := sim.PublishSnapshot()
		if err != nil {
			return "", err
		}
		sent = s
		return fmt.Sprintf("seq=%d tanks=%d", s.SnapshotSeq, len(s.Tanks)), nil
	}) {
		return r.report()
	}

	if api != nil {
		r.step("遥测读回（业务 REST API GET /api/devices/:device/telemetry/latest）", func() (string, error) {
			return "", r.until(ctx, 30*time.Second, func() error {
				var out struct {
					SnapshotSeq int64 `json:"snapshotSeq"`
					Tanks       []struct {
						Index      int     `json:"index"`
						OilLevelMm float64 `json:"oilLevelMm"`
					} `json:"tanks"`
				}
				code, raw, err := api.do(ctx, http.MethodGet, "/api/devices/"+name+"/telemetry/latest", nil, &out)
				if err != nil {
					return err
				}
				if code != http.StatusOK {
					return fmt.Errorf("HTTP %d: %s", code, truncate(raw, 300))
				}
				if out.SnapshotSeq != sent.SnapshotSeq {
					return fmt.Errorf("读回 seq=%d，期望 %d", out.SnapshotSeq, sent.SnapshotSeq)
				}
				if len(out.Tanks) != len(sent.Tanks) || out.Tanks[0].OilLevelMm != sent.Tanks[0].OilLevelMm {
					return errors.New("读回的数值与发出的不一致")
				}
				return nil
			})
		})
	} else {
		r.step("遥测读回（ThingsBoard → 后端）", func() (string, error) {
			return "", r.until(ctx, 30*time.Second, func() error {
				ts, values, err := r.tb.LatestSnapshot(ctx, devID)
				if err != nil {
					return err
				}
				snap, err := protocol.ParseSnapshot(ts, values)
				if err != nil {
					return err
				}
				if snap.SnapshotSeq != sent.SnapshotSeq {
					return fmt.Errorf("读回 seq=%d，期望 %d", snap.SnapshotSeq, sent.SnapshotSeq)
				}
				if len(snap.Tanks) != len(sent.Tanks) || snap.Tanks[0].OilLevelMm != sent.Tanks[0].OilLevelMm {
					return errors.New("读回的数值与发出的不一致")
				}
				return nil
			})
		})
	}

	r.step("即时报警不等快照周期", func() (string, error) {
		start := time.Now()
		ev, err := sim.RaiseAlarm(1, protocol.AlarmHighHighLevel, protocol.SeverityCritical, "冒烟测试报警", 1980.4)
		if err != nil {
			return "", err
		}

		if api != nil {
			err = r.until(ctx, 30*time.Second, func() error {
				// GET /api/alarms answers with a bare JSON array, not {"alarms": [...]}, unlike
				// most of this backend's other list endpoints — found by running this check
				// against the real API instead of assuming the shape.
				var out []struct {
					AlarmID string `json:"alarmId"`
				}
				code, raw, err := api.do(ctx, http.MethodGet, "/api/alarms?device="+name+
					"&beginTime="+start.Add(-time.Minute).UTC().Format(time.RFC3339)+
					"&endTime="+time.Now().Add(time.Minute).UTC().Format(time.RFC3339), nil, &out)
				if err != nil {
					return err
				}
				if code != http.StatusOK {
					return fmt.Errorf("HTTP %d: %s", code, truncate(raw, 300))
				}
				for _, a := range out {
					if a.AlarmID == ev.AlarmID {
						return nil
					}
				}
				return errors.New("报警尚未出现")
			})
		} else {
			err = r.until(ctx, 30*time.Second, func() error {
				series, err := r.tb.Timeseries(ctx, devID, tbclient.TimeseriesQuery{
					Keys: []string{protocol.KeyAlarm}, StartMs: start.Add(-time.Minute).UnixMilli(),
					EndMs: time.Now().Add(time.Minute).UnixMilli(), Limit: 10, Descending: true,
				})
				if err != nil {
					return err
				}
				for _, p := range series[protocol.KeyAlarm] {
					got, perr := protocol.ParseAlarmEvent(p.Value)
					if perr == nil && got.AlarmID == ev.AlarmID {
						return nil
					}
				}
				return errors.New("报警尚未出现")
			})
		}
		if err != nil {
			return "", err
		}
		return fmt.Sprintf("%s 送达", time.Since(start).Round(time.Millisecond)), nil
	})

	var issuedCommandID string
	r.step("控制命令 RPC → 设备 ACK", func() (string, error) {
		// alarm.mute is deliberately the method used: it is non-physical, so a smoke run against a
		// real installation cannot move equipment even if a real device were somehow addressed.
		if api != nil {
			var out struct {
				CommandID string `json:"commandId"`
				Status    string `json:"status"`
				ErrorCode string `json:"errorCode"`
				Reason    string `json:"errorReason"`
			}
			code, raw, err := api.do(ctx, http.MethodPost, "/api/devices/"+name+"/commands", map[string]string{
				"method": protocol.MethodAlarmMute,
				"reason": "部署冒烟测试",
			}, &out)
			if err != nil {
				return "", err
			}
			if code != http.StatusAccepted {
				return "", fmt.Errorf("HTTP %d: %s", code, truncate(raw, 300))
			}
			if out.Status != protocol.CmdStatusReceived {
				return "", fmt.Errorf("ACK 状态 %q（%s: %s）", out.Status, out.ErrorCode, out.Reason)
			}
			issuedCommandID = out.CommandID
			return "received", nil
		}

		now := time.Now().UnixMilli()
		env := protocol.Envelope{
			CommandID:       fmt.Sprintf("smoke-%d", now),
			ProtocolVersion: protocol.Version,
			Args:            json.RawMessage(`{}`),
			IssuedAtMs:      now,
			ExpiresAtMs:     now + 60_000,
			Operator:        "smoke",
			Reason:          "部署冒烟测试",
		}
		ack, err := r.tb.SendCommand(ctx, devID, protocol.MethodAlarmMute, env, 15_000)
		if err != nil {
			return "", err
		}
		if ack.Status != protocol.CmdStatusReceived {
			return "", fmt.Errorf("ACK 状态 %q（%s: %s）", ack.Status, ack.ErrorCode, ack.Reason)
		}
		issuedCommandID = env.CommandID
		return "received", nil
	})

	r.step("控制命令异步最终结果", func() (string, error) {
		var status string
		var err error
		if api != nil {
			err = r.until(ctx, 30*time.Second, func() error {
				var out struct {
					Commands []struct {
						CommandID string `json:"commandId"`
						Status    string `json:"status"`
						Terminal  bool   `json:"terminal"`
					} `json:"commands"`
				}
				code, raw, gerr := api.do(ctx, http.MethodGet, "/api/devices/"+name+"/commands", nil, &out)
				if gerr != nil {
					return gerr
				}
				if code != http.StatusOK {
					return fmt.Errorf("HTTP %d: %s", code, truncate(raw, 300))
				}
				for _, c := range out.Commands {
					if c.CommandID == issuedCommandID && c.Terminal {
						status = c.Status
						return nil
					}
				}
				return errors.New("最终结果尚未上报")
			})
		} else {
			err = r.until(ctx, 30*time.Second, func() error {
				series, terr := r.tb.Timeseries(ctx, devID, tbclient.TimeseriesQuery{
					Keys: []string{protocol.KeyCmdResult}, StartMs: time.Now().Add(-2 * time.Minute).UnixMilli(),
					EndMs: time.Now().Add(time.Minute).UnixMilli(), Limit: 10, Descending: true,
				})
				if terr != nil {
					return terr
				}
				for _, p := range series[protocol.KeyCmdResult] {
					res, perr := protocol.ParseCmdResult(p.Value)
					if perr == nil && protocol.IsTerminal(res.Status) {
						status = res.Status
						return nil
					}
				}
				return errors.New("最终结果尚未上报")
			})
		}
		return status, err
	})

	if api != nil {
		r.step("审计记录落库（GET /api/audit）", func() (string, error) {
			var seen map[string]bool
			err := r.until(ctx, 15*time.Second, func() error {
				var out struct {
					Audit []struct {
						Action    string `json:"action"`
						CommandID string `json:"commandId"`
						Outcome   string `json:"outcome"`
					} `json:"audit"`
				}
				code, raw, gerr := api.do(ctx, http.MethodGet, "/api/audit?device="+name+
					"&beginTime="+time.Now().Add(-time.Hour).UTC().Format(time.RFC3339), nil, &out)
				if gerr != nil {
					return gerr
				}
				if code != http.StatusOK {
					return fmt.Errorf("HTTP %d: %s", code, truncate(raw, 300))
				}
				seen = map[string]bool{}
				for _, e := range out.Audit {
					if e.CommandID == issuedCommandID {
						seen[e.Action] = true
					}
				}
				// The full control lifecycle for this one command must all be on record: the
				// operator's request, ThingsBoard's ACK, and the device's asynchronous result.
				for _, want := range []string{"control.issue", "control.ack", "control.result"} {
					if !seen[want] {
						return fmt.Errorf("审计记录缺少 %s", want)
					}
				}
				return nil
			})
			if err != nil {
				return "", err
			}
			return fmt.Sprintf("%d 类动作留痕", len(seen)), nil
		})
	}

	return r.report()
}

// runner tracks step results and the exit status.
type runner struct {
	tb      *tbclient.Client
	verbose bool
	failed  int
	passed  int
}

func (r *runner) step(name string, fn func() (string, error)) bool {
	start := time.Now()
	detail, err := fn()
	elapsed := time.Since(start).Round(time.Millisecond)

	if err != nil {
		r.failed++
		fmt.Printf("  ✗ %-40s %s\n      %v\n", name, elapsed, err)
		return false
	}
	r.passed++
	if detail != "" {
		fmt.Printf("  ✓ %-40s %-8s %s\n", name, elapsed, detail)
	} else {
		fmt.Printf("  ✓ %-40s %s\n", name, elapsed)
	}
	return true
}

// until retries fn until it succeeds or the budget runs out. ThingsBoard persists telemetry
// asynchronously, and the ingester carries results into the business database on its own schedule,
// so a read immediately after a publish or a command legitimately misses it.
func (r *runner) until(ctx context.Context, budget time.Duration, fn func() error) error {
	deadline := time.Now().Add(budget)
	var last error
	for time.Now().Before(deadline) {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if last = fn(); last == nil {
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return last
}

func (r *runner) logf(format string, args ...any) {
	if r.verbose {
		fmt.Printf("      "+format+"\n", args...)
	}
}

// report prints the summary and returns the process exit code. It must not call os.Exit itself —
// see the comment in main.
func (r *runner) report() int {
	fmt.Println()
	if r.failed > 0 {
		fmt.Printf("冒烟测试失败：%d 项通过，%d 项失败\n", r.passed, r.failed)
		return 1
	}
	fmt.Printf("冒烟测试通过：%d 项全部成功\n", r.passed)
	return 0
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func httpClient() *http.Client { return &http.Client{Timeout: 15 * time.Second} }

// apiClient is a minimal REST client for the Go backend's own API — deliberately hand-rolled rather
// than reusing internal/tbclient's do(), which speaks ThingsBoard's conventions (X-Authorization,
// its own error shape), not this backend's (standard Authorization: Bearer, JSON error bodies).
type apiClient struct {
	base  string
	token string
	hc    *http.Client
}

// do sends one request and, when out is non-nil, decodes the JSON response body into it regardless
// of status code — callers compare the returned status themselves, since a non-2xx here (403 from
// an authorization bug, say) is exactly the kind of thing a smoke run exists to catch, not something
// to convert into a generic transport error that looks the same as ThingsBoard being unreachable.
func (a *apiClient) do(ctx context.Context, method, path string, body, out any) (int, []byte, error) {
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, nil, err
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, a.base+path, reader)
	if err != nil {
		return 0, nil, err
	}
	if reader != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if a.token != "" {
		req.Header.Set("Authorization", "Bearer "+a.token)
	}
	resp, err := a.hc.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, nil, err
	}
	if out != nil && len(raw) > 0 {
		if jerr := json.Unmarshal(raw, out); jerr != nil {
			return resp.StatusCode, raw, fmt.Errorf("decode %s %s: %w (body: %s)", method, path, jerr, truncate(raw, 300))
		}
	}
	return resp.StatusCode, raw, nil
}

// deleteBusinessDevice removes the smoke run's device row from the business database (cascading to
// any control_commands it produced), symmetric with the ThingsBoard-side device.DeleteDevice call
// this tool already makes. Opens its own short-lived connection rather than keeping the seeding
// pool alive for the whole run, since cleanup happens well after login and may run even when login
// itself failed.
func (a *apiClient) deleteBusinessDevice(ctx context.Context, dbURL, deviceName string) error {
	pool, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		return err
	}
	defer pool.Close()
	_, err = pool.Exec(ctx, `DELETE FROM devices WHERE device_name = $1`, deviceName)
	return err
}

// mintPairingSecretFixture stands in for a real installer calling
// POST /api/devices/:device/pairing-secret (platform_admin only) — see the call site's comment for
// why this tool does it as a direct fixture write instead. Returns the plaintext secret;
// deleteBusinessDevice's DELETE FROM devices above cascades into device_pairing_secrets, so no
// separate cleanup is needed here.
func mintPairingSecretFixture(ctx context.Context, dbURL, deviceName string) (string, error) {
	pool, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		return "", err
	}
	defer pool.Close()

	if _, err := pool.Exec(ctx, `INSERT INTO devices (device_name) VALUES ($1) ON CONFLICT DO NOTHING`, deviceName); err != nil {
		return "", err
	}

	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	secret := base64.RawURLEncoding.EncodeToString(raw)

	hash, err := bcrypt.GenerateFromPassword([]byte(secret), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}

	if _, err := pool.Exec(ctx,
		`INSERT INTO device_pairing_secrets (device_name, secret_hash) VALUES ($1, $2)
		 ON CONFLICT (device_name) DO UPDATE SET secret_hash = EXCLUDED.secret_hash, created_at = now()`,
		deviceName, string(hash)); err != nil {
		return "", err
	}

	return secret, nil
}

// loginAsThrowawayUser seeds a random-password user directly in the business database — there is no
// self-registration endpoint, by design (accounts are provisioned out of band) — then exchanges it
// for a real JWT through POST /api/login, the same call every real client makes. The direct-database
// step is fixture setup only; every check this tool makes afterward goes through the API.
func loginAsThrowawayUser(ctx context.Context, apiURL, dbURL string) (*apiClient, string, func(), error) {
	pool, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		return nil, "", nil, fmt.Errorf("connect to business database (-db / DATABASE_URL): %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, "", nil, fmt.Errorf("ping business database (-db / DATABASE_URL): %w", err)
	}

	username := fmt.Sprintf("smoke-%d", time.Now().UnixNano())
	passwordBytes := make([]byte, 18)
	if _, err := rand.Read(passwordBytes); err != nil {
		pool.Close()
		return nil, "", nil, err
	}
	password := base64.RawURLEncoding.EncodeToString(passwordBytes)
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		pool.Close()
		return nil, "", nil, err
	}

	var userID int64
	if err := pool.QueryRow(ctx,
		`INSERT INTO users (username, password_hash, role) VALUES ($1, $2, $3) RETURNING id`,
		username, string(hash), control.RoleCustomerUser,
	).Scan(&userID); err != nil {
		pool.Close()
		return nil, "", nil, fmt.Errorf("seed throwaway login user: %w", err)
	}
	cleanup := func() {
		c := context.Background()
		pool.Exec(c, `DELETE FROM users WHERE id = $1`, userID)
		pool.Close()
	}

	client := &apiClient{base: apiURL, hc: httpClient()}
	var loginOut struct {
		Token string `json:"token"`
	}
	code, raw, err := client.do(ctx, http.MethodPost, "/api/login", map[string]string{
		"username": username, "password": password,
	}, &loginOut)
	if err != nil {
		cleanup()
		return nil, "", nil, err
	}
	if code != http.StatusOK || loginOut.Token == "" {
		cleanup()
		return nil, "", nil, fmt.Errorf("POST /api/login: HTTP %d: %s", code, truncate(raw, 300))
	}
	client.token = loginOut.Token
	return client, username, cleanup, nil
}

func truncate(b []byte, n int) string {
	if len(b) <= n {
		return string(b)
	}
	return string(b[:n]) + "…"
}
