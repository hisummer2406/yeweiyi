// Command tbdevicesim runs a simulated liquid-level terminal against ThingsBoard CE, speaking
// 设备协议 V1 (docs/protocol-v1.md). It is the manual-testing front end for internal/devicesim,
// which is the actual reference implementation and is what the automated tests drive.
//
// Nothing here knows about productKey, sys/... topics or a second broker: the legacy Alink stack
// it replaced was removed in full (GitHub issue #10).
//
// Typical session:
//
//	docker compose -f docker-compose.thingsboard.yml up -d
//	go run ./cmd/tbdevicesim provision -name tank-farm-01
//	go run ./cmd/tbdevicesim run -token <printed token> -tanks 2 -interval 10s -alarm-after 15s
//
// Then watch the data land in ThingsBoard's own console at http://localhost:8081
// (tenant@thingsboard.org / tenant), or drive an RPC at it from the console's device page and see
// the ACK and the asynchronous result come back.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

func main() {
	log.SetFlags(log.Ltime)
	if len(os.Args) < 2 {
		usage()
	}

	switch os.Args[1] {
	case "provision":
		provision(os.Args[2:])
	case "run":
		run(os.Args[2:])
	default:
		usage()
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `usage:
  tbdevicesim provision -name <deviceName> [-tb <url>] [-user <email>] [-pass <password>]
      Registers the device in ThingsBoard (idempotent) and prints its MQTT access token.

  tbdevicesim run -token <accessToken> [-broker <url>] [-tanks N] [-interval 30s]
                  [-alarm-after <dur>] [-policy accept|reject|fail]
      Connects as that device: publishes client attributes, then a full tank snapshot every
      -interval, answers RPC requests per -policy, and optionally raises and clears an alarm.`)
	os.Exit(2)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func provision(args []string) {
	fs := flag.NewFlagSet("provision", flag.ExitOnError)
	name := fs.String("name", "", "deviceName to register (required)")
	tbURL := fs.String("tb", envOr("TB_BASE_URL", "http://localhost:8081"), "ThingsBoard base URL")
	user := fs.String("user", envOr("TB_USERNAME", "tenant@thingsboard.org"), "ThingsBoard tenant admin")
	pass := fs.String("pass", envOr("TB_PASSWORD", "tenant"), "ThingsBoard tenant admin password")
	fs.Parse(args)

	if *name == "" {
		fs.Usage()
		os.Exit(2)
	}

	c, err := tbclient.New(tbclient.Config{BaseURL: *tbURL, Username: *user, Password: *pass})
	if err != nil {
		log.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	dev, err := c.EnsureDevice(ctx, *name, tbclient.DefaultDeviceProfile)
	if err != nil {
		log.Fatalf("provision %q: %v", *name, err)
	}
	creds, err := c.GetDeviceCredentials(ctx, dev.ID.ID)
	if err != nil {
		log.Fatalf("read credentials: %v", err)
	}

	fmt.Printf("device   %s\n", dev.Name)
	fmt.Printf("id       %s\n", dev.ID.ID)
	fmt.Printf("token    %s\n", creds.CredentialsID)
	fmt.Printf("\nrun it with:\n  go run ./cmd/tbdevicesim run -token %s\n", creds.CredentialsID)
}

func run(args []string) {
	fs := flag.NewFlagSet("run", flag.ExitOnError)
	token := fs.String("token", envOr("TB_DEVICE_TOKEN", ""), "ThingsBoard device access token (required)")
	broker := fs.String("broker", envOr("TB_MQTT_URL", "tcp://localhost:1884"), "ThingsBoard MQTT transport (tcp:// or ssl://)")
	caFile := fs.String("cacert", envOr("TB_MQTT_CACERT", ""), "PEM CA file verifying the broker certificate; required for ssl://")
	tanks := fs.Int("tanks", 2, "number of tanks to report")
	interval := fs.Duration("interval", protocol.SnapshotIntervalSec*time.Second, "snapshot cadence")
	alarmAfter := fs.Duration("alarm-after", 0, "raise a high-high level alarm this long after start, then clear it 15s later (0 = never)")
	policy := fs.String("policy", "accept", "how to answer control commands: accept, reject, or fail")
	fs.Parse(args)

	if *token == "" {
		fs.Usage()
		os.Exit(2)
	}

	var onCommand devicesim.Policy
	switch *policy {
	case "accept":
		onCommand = devicesim.DefaultPolicy
	case "reject":
		onCommand = devicesim.RejectPolicy("模拟设备联锁拒绝")
	case "fail":
		onCommand = devicesim.FailPolicy(protocol.ErrHardwareFault, "模拟执行失败")
	default:
		log.Fatalf("unknown -policy %q (want accept, reject or fail)", *policy)
	}

	sim := devicesim.New(devicesim.Config{
		BrokerURL:        *broker,
		CACertFile:       *caFile,
		AccessToken:      *token,
		Tanks:            *tanks,
		SnapshotInterval: *interval,
		OnCommand:        onCommand,
		Logf:             log.Printf,
	})

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := sim.Connect(ctx); err != nil {
		log.Fatalf("connect to %s: %v", *broker, err)
	}
	defer sim.Disconnect()
	log.Printf("connected to %s as a %d-tank device, snapshot every %s, command policy %q",
		*broker, *tanks, *interval, *policy)

	if *alarmAfter > 0 {
		go raiseAndClearAlarm(ctx, sim, *alarmAfter)
	}

	if err := sim.RunSnapshots(ctx); err != nil {
		log.Fatalf("snapshot loop: %v", err)
	}
	log.Print("stopped")
}

// raiseAndClearAlarm demonstrates the instant-event path: the alarm goes out the moment it happens,
// not on the next snapshot tick, which is easiest to see with a long -interval.
func raiseAndClearAlarm(ctx context.Context, sim *devicesim.Device, after time.Duration) {
	select {
	case <-ctx.Done():
		return
	case <-time.After(after):
	}

	active, err := sim.RaiseAlarm(1, protocol.AlarmHighHighLevel, protocol.SeverityCritical, "1号罐高高液位报警", 1980.4)
	if err != nil {
		log.Printf("raise alarm: %v", err)
		return
	}

	select {
	case <-ctx.Done():
		return
	case <-time.After(15 * time.Second):
	}

	if _, err := sim.ClearAlarm(active, "1号罐高高液位报警恢复", 1842.5); err != nil {
		log.Printf("clear alarm: %v", err)
	}
}
