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
	"yeweiyi-cloud/internal/queue"
	"yeweiyi-cloud/internal/store/db"
)

// unbuiltMQTTPublisher is a placeholder until internal/mqtt exists (blocked on Aliyun IoT
// credentials, see CLAUDE.md "MQTT contract"). Nothing enqueues a write-back command yet either —
// the config/correction write endpoints aren't built — so this never actually runs in practice; it
// exists so the queue's worker pool can start now rather than being wired in later as a bigger change.
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

	router := api.NewRouter(api.Deps{
		Queries:   queries,
		Redis:     rdb,
		Pool:      pool,
		JWTSecret: []byte(jwtSecret),
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
