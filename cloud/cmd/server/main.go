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
	yqmqtt "yeweiyi-cloud/internal/mqtt"
	"yeweiyi-cloud/internal/partitions"
	"yeweiyi-cloud/internal/queue"
	"yeweiyi-cloud/internal/store/db"
)

// unbuiltMQTTPublisher is still a placeholder even though internal/mqtt now exists: the write-back
// queue needs a "publish this config change" topic and an "operation acknowledged" response
// semantics that don't exist on the device side yet (requestTankTable/requestTankSet/
// requestProbeSet are subscribed-but-unhandled dead code — see CLAUDE.md "Device-side work this
// project depends on"). Nothing enqueues a write-back command yet either (no config/correction
// write endpoints exist), so this never actually runs in practice.
type unbuiltMQTTPublisher struct{}

func (unbuiltMQTTPublisher) PublishCommand(context.Context, string, int64, string, []byte) error {
	return errors.New("internal/mqtt is not implemented yet")
}

func main() {
	ctx := context.Background()

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
	} else {
		log.Print("MQTT_BROKER_URL not set, skipping MQTT — no live device data will be ingested")
	}

	router := api.NewRouter(api.Deps{
		Queries:     queries,
		Redis:       rdb,
		Pool:        pool,
		JWTSecret:   []byte(jwtSecret),
		RiverClient: riverClient,
	})

	addr := getenv("LISTEN_ADDR", ":8080")
	log.Printf("listening on %s", addr)
	if err := router.Run(addr); err != nil {
		log.Fatalf("server: %v", err)
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
