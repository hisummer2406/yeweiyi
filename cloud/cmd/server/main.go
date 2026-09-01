package main

import (
	"context"
	"errors"
	"flag"
	"log"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/config"
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
	// -config is optional: with no file the built-in defaults plus environment variables are used,
	// which is what the local dev loop and the container images rely on.
	configPath := flag.String("config", "", "path to config.yaml (see config.example.yaml)")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := pgxpool.New(ctx, cfg.Database.URL)
	if err != nil {
		log.Fatalf("postgres: %v", err)
	}
	defer pool.Close()

	go partitions.Run(ctx, pool, cfg.Database.PartitionInterval.Std(), cfg.Database.PartitionLookahead)

	rdb := redis.NewClient(&redis.Options{Addr: cfg.Redis.Addr})
	defer rdb.Close()

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
	if cfg.ThingsBoardEnabled() {
		tbClient, err = tbclient.New(tbclient.Config{
			BaseURL:  cfg.ThingsBoard.BaseURL,
			Username: cfg.ThingsBoard.Username,
			Password: cfg.ThingsBoard.Password,
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
			CommandTTL:            cfg.Control.CommandTTL.Std(),
			AckTimeout:            cfg.Control.AckTimeout.Std(),
			AllowPhysicalCommands: cfg.Control.AllowPhysicalCommands,
		})

		// Drives cmdResult back into the command state machine and fans alarms out to /ws/alarms.
		ingester := ingest.New(ingest.Config{
			TB:                    tbClient,
			Control:               controlSvc,
			Redis:                 rdb,
			Queries:               queries,
			DeviceRefreshInterval: cfg.Ingest.DeviceRefreshInterval.Std(),
			ResubscribeDelay:      cfg.Ingest.ResubscribeDelay.Std(),
			Logf:                  log.Printf,
		})
		go ingester.Run(ctx)

		// Closes out commands that passed their validity window without reaching a terminal state.
		go controlSvc.RunExpirySweep(ctx, cfg.Control.ExpirySweepInterval.Std(), log.Printf)

		log.Printf("thingsboard: connected to %s; telemetry ingestion and remote control are active (commandTTL=%s ackTimeout=%s)",
			cfg.ThingsBoard.BaseURL, cfg.Control.CommandTTL, cfg.Control.AckTimeout)
		if !cfg.Control.AllowPhysicalCommands {
			log.Print("thingsboard: physical control actions (pump/valve/reboot) are DISABLED — " +
				"set ALLOW_PHYSICAL_COMMANDS=true only once device-side interlocks are verified on real hardware")
		}
	} else {
		log.Print("thingsboard.baseURL not set — running without ThingsBoard: no telemetry ingestion, " +
			"and device/control endpoints will answer 503")
	}

	// --- legacy Alink/Paho stack, removed by issue #10 --------------------------------------
	riverClient, err := queue.NewClient(queue.Config{
		Pool:      pool,
		Queries:   queries,
		Publisher: unbuiltMQTTPublisher{},
		TTL:       cfg.Legacy.WriteBackTTL.Std(),
	})
	if err != nil {
		log.Fatalf("queue client: %v", err)
	}
	if err := riverClient.Start(ctx); err != nil {
		log.Fatalf("queue start: %v", err)
	}
	defer riverClient.Stop(ctx)

	if cfg.LegacyStackEnabled() {
		log.Print("WARNING: legacy.mqttBrokerURL is set, starting the LEGACY Alink/Paho stack. " +
			"This is not the current architecture (see AGENTS.md) and is scheduled for removal.")
		mqttClient := yqmqtt.NewClient(yqmqtt.Config{
			BrokerURL:  cfg.Legacy.MQTTBrokerURL,
			ProductKey: cfg.Legacy.MQTTProductKey,
			ClientID:   cfg.Legacy.MQTTClientID,
			Username:   cfg.Legacy.MQTTUsername,
			Password:   cfg.Legacy.MQTTPassword,
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
		JWTSecret:   []byte(cfg.Auth.JWTSecret),
		TokenTTL:    cfg.Auth.TokenTTL.Std(),
		RiverClient: riverClient,
		TB:          tbClient,
		Control:     controlSvc,
	})

	log.Printf("listening on %s", cfg.Server.ListenAddr)
	if err := router.Run(cfg.Server.ListenAddr); err != nil {
		log.Fatalf("server: %v", err)
	}
}
