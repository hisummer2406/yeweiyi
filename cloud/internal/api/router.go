// Package api exposes the HTTPS REST surface yeweiyi-miniapp (and any PC-remote client) consumes,
// per design doc §4.4. It never talks MQTT directly — it only reads/writes through internal/store.
package api

import (
	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/store/db"
)

type Deps struct {
	Queries   *db.Queries
	Redis     *redis.Client
	Pool      *pgxpool.Pool
	JWTSecret []byte
}

func NewRouter(deps Deps) *gin.Engine {
	r := gin.Default()

	r.GET("/healthz", healthzHandler(deps))
	r.POST("/api/login", loginHandler(deps))

	authorized := r.Group("/api")
	authorized.Use(authMiddleware(deps.JWTSecret))
	{
		authorized.GET("/alarms", listAlarmsHandler(deps))
		authorized.GET("/oil-in-out", listOilInOutHandler(deps))
		authorized.GET("/shifts", listShiftsHandler(deps))
		authorized.GET("/oilcan/latest", oilcanLatestHandler(deps))
	}

	return r
}
