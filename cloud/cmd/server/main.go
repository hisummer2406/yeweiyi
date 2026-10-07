package main

import (
	"context"
	"flag"
	"log"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/api"
	"yeweiyi-cloud/internal/config"
	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/ingest"
	"yeweiyi-cloud/internal/rollup"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

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

	rdb := redis.NewClient(&redis.Options{Addr: cfg.Redis.Addr, Password: cfg.Redis.Password})
	defer rdb.Close()

	queries := db.New(pool)

	// ThingsBoard is optional at boot, on purpose: the server must start without it so that work on
	// the business endpoints does not require running a 2 GB container. When it is absent the
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

		// Drives cmdResult back into the command state machine, stores 出入库/报警 records in the business
		// database (issue #29) and fans alarms out to /ws/alarms.
		ingester := ingest.New(ingest.Config{
			TB:                    tbClient,
			Control:               controlSvc,
			Redis:                 rdb,
			Queries:               queries,
			DeviceRefreshInterval: cfg.Ingest.DeviceRefreshInterval.Std(),
			ResubscribeDelay:      cfg.Ingest.ResubscribeDelay.Std(),

			RecordReconcileInterval: cfg.Ingest.RecordReconcileInterval.Std(),
			RecordReconcileLookback: cfg.Ingest.RecordReconcileLookback.Std(),
			OnlineStatusInterval:    cfg.Ingest.OnlineStatusInterval.Std(),
			Logf:                    log.Printf,
		})
		go ingester.Run(ctx)

		// Keeps the hourly telemetry summaries the business database holds for good (issue #30).
		go rollup.New(rollup.Config{
			TB:        tbClient,
			Queries:   queries,
			Interval:  cfg.Telemetry.RollupInterval.Std(),
			Recompute: cfg.Telemetry.RollupRecompute.Std(),
			Logf:      log.Printf,
		}).Run(ctx)

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

	router := api.NewRouter(api.Deps{
		Queries:       queries,
		Redis:         rdb,
		Pool:          pool,
		JWTSecret:     []byte(cfg.Auth.JWTSecret),
		TokenTTL:      cfg.Auth.TokenTTL.Std(),
		MaxSessionAge: cfg.Auth.MaxSessionAge.Std(),
		TB:            tbClient,
		Control:       controlSvc,

		StockLegacyFormatBefore: cfg.Stock.LegacyFormatBefore.Time,
		TelemetryRetention:      cfg.Telemetry.Retention.Std(),
	})

	if cfg.Server.TLSCertFile != "" && cfg.Server.TLSKeyFile != "" {
		log.Printf("listening on %s (HTTPS)", cfg.Server.ListenAddr)
		if err := router.RunTLS(cfg.Server.ListenAddr, cfg.Server.TLSCertFile, cfg.Server.TLSKeyFile); err != nil {
			log.Fatalf("server: %v", err)
		}
	} else {
		log.Printf("listening on %s", cfg.Server.ListenAddr)
		if err := router.Run(cfg.Server.ListenAddr); err != nil {
			log.Fatalf("server: %v", err)
		}
	}
}
