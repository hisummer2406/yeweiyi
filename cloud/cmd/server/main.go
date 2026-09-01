package main

import (
	"context"
	"errors"
	"log"
	"os"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/ingest"
	yqmqtt "yeweiyi-cloud/internal/mqtt"
	"yeweiyi-cloud/internal/partitions"
	"yeweiyi-cloud/internal/queue"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

// unbuiltMQTTPublisher belongs to the legacy write-back queue, which the ThingsBoard single stack
// replaces with internal/control. It stays only until internal/mqtt and internal/queue are removed
// (GitHub issue #10); nothing in the current architecture reaches it.
type unbuiltMQTTPublisher struct{}

func (unbuiltMQTTPublisher) PublishCommand(context.Context, string, int64, string, []byte) error {
	return errors.New("the legacy write-back publisher was never built; use internal/control instead")
}

func main() {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pgURL := getenv("DATABASE_URL", "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable")
	pool, err := pgxpool.New(ctx, pgURL)
	if err != nil {
		log.Fatalf("postgres: %v", err)
	}
	defer pool.Close()

	go partitions.Run(ctx, pool, partitions.DefaultInterval, partitions.DefaultLookahead)

	rdb := redis.NewClient(&redis.Options{
		Addr: getenv("REDIS_ADDR", "localhost:6379"),
	})
	defer rdb.Close()

	jwtSecret := os.Getenv("JWT_SECRET")
	if jwtSecret == "" {
		log.Fatal("JWT_SECRET is required")
	}

	queries := db.New(pool)

	// --- ThingsBoard CE single stack -------------------------------------------------------
	//
	// Optional at boot, on purpose. Issue #10 requires the system to start without any Aliyun
	// productKey, Alink topic or EMQX; it must also start without ThingsBoard, so that work on the
	// business endpoints does not require running a 2 GB container. When it is absent the
	// device-facing routes answer 503 with an explanatory message rather than the process refusing
	// to boot or panicking on first use.
	var (
		tbClient   *tbclient.Client
		controlSvc *control.Service
	)
	if tbURL := os.Getenv("TB_BASE_URL"); tbURL != "" {
		tbClient, err = tbclient.New(tbclient.Config{
			BaseURL:  tbURL,
			Username: os.Getenv("TB_USERNAME"),
			Password: os.Getenv("TB_PASSWORD"),
		})
		if err != nil {
			log.Fatalf("thingsboard client: %v", err)
		}
		// Fail fast on bad credentials rather than surfacing them as a 503 on the first control
		// action hours later.
		if err := tbClient.Login(ctx); err != nil {
			log.Fatalf("thingsboard login: %v", err)
		}

		controlSvc = control.New(queries, tbClient, control.Config{
			CommandTTL:            commandTTL(),
			AllowPhysicalCommands: allowPhysicalCommands(),
		})

		// Drives cmdResult back into the command state machine and fans alarms out to /ws/alarms.
		ingester := ingest.New(ingest.Config{
			TB:      tbClient,
			Control: controlSvc,
			Redis:   rdb,
			Queries: queries,
			Logf:    log.Printf,
		})
		go ingester.Run(ctx)

		// Closes out commands that passed their validity window without reaching a terminal state.
		go controlSvc.RunExpirySweep(ctx, 30*time.Second, log.Printf)

		log.Printf("thingsboard: connected to %s; telemetry ingestion and remote control are active", tbURL)
		if !allowPhysicalCommands() {
			log.Print("thingsboard: physical control actions (pump/valve/reboot) are DISABLED — " +
				"set ALLOW_PHYSICAL_COMMANDS=true only once device-side interlocks are verified on real hardware")
		}
	} else {
		log.Print("TB_BASE_URL not set — running without ThingsBoard: no telemetry ingestion, " +
			"and device/control endpoints will answer 503")
	}

	// --- legacy Alink/Paho stack, removed by issue #10 --------------------------------------
	queueTTL := queue.DefaultTTL
	if v := os.Getenv("WRITE_BACK_TTL_DAYS"); v != "" {
		days, err := strconv.Atoi(v)
		if err != nil || days <= 0 {
			log.Fatalf("WRITE_BACK_TTL_DAYS must be a positive integer, got %q", v)
		}
		queueTTL = time.Duration(days) * 24 * time.Hour
	}

	riverClient, err := queue.NewClient(queue.Config{
		Pool:      pool,
		Queries:   queries,
		Publisher: unbuiltMQTTPublisher{},
		TTL:       queueTTL,
	})
	if err != nil {
		log.Fatalf("queue client: %v", err)
	}
	if err := riverClient.Start(ctx); err != nil {
		log.Fatalf("queue start: %v", err)
	}
	defer riverClient.Stop(ctx)

	if brokerURL := os.Getenv("MQTT_BROKER_URL"); brokerURL != "" {
		log.Print("WARNING: MQTT_BROKER_URL is set, starting the LEGACY Alink/Paho stack. " +
			"This is not the current architecture (see AGENTS.md) and is scheduled for removal.")
		mqttClient := yqmqtt.NewClient(yqmqtt.Config{
			BrokerURL:  brokerURL,
			ProductKey: os.Getenv("MQTT_PRODUCT_KEY"),
			ClientID:   getenv("MQTT_CLIENT_ID", "yeweiyi-cloud"),
			Username:   os.Getenv("MQTT_USERNAME"),
			Password:   os.Getenv("MQTT_PASSWORD"),
			Queries:    queries,
			Redis:      rdb,
		})
		if err := mqttClient.Connect(); err != nil {
			log.Fatalf("mqtt connect: %v", err)
		}
		defer mqttClient.Disconnect()

		poller := &yqmqtt.Poller{Client: mqttClient, Queries: queries}
		go poller.Run(ctx)
	}

	router := api.NewRouter(api.Deps{
		Queries:     queries,
		Redis:       rdb,
		Pool:        pool,
		JWTSecret:   []byte(jwtSecret),
		RiverClient: riverClient,
		TB:          tbClient,
		Control:     controlSvc,
	})

	addr := getenv("LISTEN_ADDR", ":8080")
	log.Printf("listening on %s", addr)
	if err := router.Run(addr); err != nil {
		log.Fatalf("server: %v", err)
	}
}

// allowPhysicalCommands reads the interlock gate.
//
// It is opt-IN, and reads as false for every value except an explicit "true"/"1". Issue #1 forbids
// exposing pump/valve/reboot actions before the device-side interlocks are finished and verified on
// real hardware, so the state you get by configuring nothing — or by typo'ing the value — has to be
// the safe one.
func allowPhysicalCommands() bool {
	switch os.Getenv("ALLOW_PHYSICAL_COMMANDS") {
	case "true", "TRUE", "1":
		return true
	default:
		return false
	}
}

// commandTTL reads COMMAND_TTL_SECONDS, the control command validity window. The default is a
// placeholder, not a product decision (docs/protocol-v1.md §12).
func commandTTL() time.Duration {
	v := os.Getenv("COMMAND_TTL_SECONDS")
	if v == "" {
		return control.DefaultCommandTTL
	}
	secs, err := strconv.Atoi(v)
	if err != nil || secs <= 0 {
		log.Fatalf("COMMAND_TTL_SECONDS must be a positive integer, got %q", v)
	}
	return time.Duration(secs) * time.Second
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
