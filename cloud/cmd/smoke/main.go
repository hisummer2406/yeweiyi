// Command smoke verifies a deployed installation end to end: ThingsBoard is up, a device can
// connect over MQTT and report telemetry, the backend can read it back, and a control command
// completes the full RPC → ACK → asynchronous-result loop.
//
// It exists as a compiled binary rather than as `go test` because the environments that most need
// it — an air-gapped customer network on installation day — have no Go toolchain, no test sources
// and no internet. It ships inside the offline bundle (deploy/) and is the check the install
// procedure ends with.
//
// It provisions its own throwaway device and deletes it afterwards, so it is safe to run against a
// live installation. It never touches existing devices or business data.
//
//	smoke -tb http://localhost:8081 -mqtt tcp://localhost:1884
//	smoke -api http://localhost:8080      # also checks the Go backend's /healthz
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

func main() {
	// os.Exit is called only here, after run() has returned and its deferred cleanup has executed.
	// Calling it from inside run() would skip every defer — including the one that deletes the
	// throwaway device, which would leave debris in a production installation and break this
	// tool's promise that it is safe to run there. (Found by running it: the first version did
	// exactly that and left two devices behind.)
	os.Exit(run())
}

func run() int {
	tbURL := flag.String("tb", envOr("TB_BASE_URL", "http://localhost:8081"), "ThingsBoard base URL")
	tbUser := flag.String("user", envOr("TB_USERNAME", "tenant@thingsboard.org"), "ThingsBoard tenant admin")
	tbPass := flag.String("pass", envOr("TB_PASSWORD", "tenant"), "ThingsBoard tenant admin password")
	mqttURL := flag.String("mqtt", envOr("TB_MQTT_URL", "tcp://localhost:1884"), "ThingsBoard MQTT transport")
	apiURL := flag.String("api", os.Getenv("API_BASE_URL"), "Go backend base URL (optional; checks /healthz)")
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

	if *apiURL != "" {
		r.step("Go 后台 /healthz", func() (string, error) {
			req, _ := http.NewRequestWithContext(ctx, http.MethodGet, *apiURL+"/healthz", nil)
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				return "", err
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				return "", fmt.Errorf("HTTP %d", resp.StatusCode)
			}
			return fmt.Sprintf("%s → 200", *apiURL), nil
		})
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
	// production installation.
	defer func() {
		if devID != "" {
			if err := r.tb.DeleteDevice(context.Background(), devID); err != nil {
				fmt.Fprintf(os.Stderr, "  ! 清理测试设备失败，请手动删除 %s: %v\n", name, err)
			}
		}
	}()

	var sim *devicesim.Device
	if !r.step("设备 MQTT 接入", func() (string, error) {
		sim = devicesim.New(devicesim.Config{
			BrokerURL:        *mqttURL,
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

	r.step("遥测读回（ThingsBoard → 后端）", func() (string, error) {
		return "", r.until(ctx, 30*time.Second, func() error {
			ts, values, err := r.tb.LatestValues(ctx, devID, protocol.SnapshotKeys(2))
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

	r.step("即时报警不等快照周期", func() (string, error) {
		start := time.Now()
		ev, err := sim.RaiseAlarm(1, protocol.AlarmHighHighLevel, protocol.SeverityCritical, "冒烟测试报警", 1980.4)
		if err != nil {
			return "", err
		}
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
		if err != nil {
			return "", err
		}
		return fmt.Sprintf("%s 送达", time.Since(start).Round(time.Millisecond)), nil
	})

	r.step("控制命令 RPC → 设备 ACK", func() (string, error) {
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
		// alarm.mute is deliberately the method used: it is non-physical, so a smoke run against a
		// real installation cannot move equipment even if a real device were somehow addressed.
		ack, err := r.tb.SendCommand(ctx, devID, protocol.MethodAlarmMute, env, 15_000)
		if err != nil {
			return "", err
		}
		if ack.Status != protocol.CmdStatusReceived {
			return "", fmt.Errorf("ACK 状态 %q（%s: %s）", ack.Status, ack.ErrorCode, ack.Reason)
		}
		return "received", nil
	})

	r.step("控制命令异步最终结果", func() (string, error) {
		var status string
		err := r.until(ctx, 30*time.Second, func() error {
			series, err := r.tb.Timeseries(ctx, devID, tbclient.TimeseriesQuery{
				Keys: []string{protocol.KeyCmdResult}, StartMs: time.Now().Add(-2 * time.Minute).UnixMilli(),
				EndMs: time.Now().Add(time.Minute).UnixMilli(), Limit: 10, Descending: true,
			})
			if err != nil {
				return err
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
		return status, err
	})

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
		fmt.Printf("  ✗ %-28s %s\n      %v\n", name, elapsed, err)
		return false
	}
	r.passed++
	if detail != "" {
		fmt.Printf("  ✓ %-28s %-8s %s\n", name, elapsed, detail)
	} else {
		fmt.Printf("  ✓ %-28s %s\n", name, elapsed)
	}
	return true
}

// until retries fn until it succeeds or the budget runs out. ThingsBoard persists telemetry
// asynchronously, so a read immediately after a publish legitimately misses it.
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
