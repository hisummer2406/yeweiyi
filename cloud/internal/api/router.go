// Package api exposes the HTTPS REST surface yeweiyi-miniapp (and any PC-remote client) consumes,
// per design doc §4.4. It never talks MQTT directly — it only reads/writes through internal/store.
package api

import (
	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
	"github.com/riverqueue/river"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

type Deps struct {
	Queries     *db.Queries
	Redis       *redis.Client
	Pool        *pgxpool.Pool
	JWTSecret   []byte
	RiverClient *river.Client[pgx.Tx]

	// TB and Control belong to the ThingsBoard single stack. Both are optional so the server can
	// still boot without ThingsBoard configured — the routes that need them answer 503 rather than
	// panicking, which is what lets a developer work on the business endpoints alone.
	TB      *tbclient.Client
	Control *control.Service
}

func NewRouter(deps Deps) *gin.Engine {
	r := gin.Default()

	r.GET("/healthz", healthzHandler(deps))
	r.POST("/api/login", loginHandler(deps))
	// Not in the authorized group: gin's JWT middleware only checks the Authorization header, but
	// this endpoint also accepts the token as a query param (see wsAuthToken) since browser/mini-
	// program WebSocket clients can't set that header on the handshake request.
	r.GET("/ws/alarms", wsAlarmsHandler(deps))

	authorized := r.Group("/api")
	authorized.Use(authMiddleware(deps.JWTSecret))
	{
		// /api/alarms reads from ThingsBoard (alarms.go), not from the business database.
		// /api/oilcan/latest was removed with the legacy stack — its replacement is
		// /api/devices/:device/telemetry/latest below.
		authorized.GET("/alarms", listAlarmsHandler(deps))
		authorized.GET("/oil-in-out", listOilInOutHandler(deps))
		authorized.GET("/shifts", listShiftsHandler(deps))
		authorized.GET("/devices", listDevicesHandler(deps))
		authorized.POST("/devices/bind", bindDeviceHandler(deps))
		authorized.POST("/devices/unbind", unbindDeviceHandler(deps))
		authorized.GET("/write-back", listWriteBackCommandsHandler(deps))
		authorized.POST("/oil-in-out/correct", correctOilInOutHandler(deps))
		authorized.POST("/shifts/correct", correctShiftHandler(deps))
		authorized.POST("/devices/:device/oil-can-set", writeOilCanSetHandler(deps))

		// ThingsBoard single stack (issue #10).
		// Telemetry is read through to ThingsBoard on demand, never served from a local copy.
		authorized.GET("/devices/:device/telemetry/latest", latestTelemetryHandler(deps))
		// Remote control. POST answers 202, never 200: the response reports only how far the
		// command got synchronously; the outcome is read back through the GET.
		authorized.POST("/devices/:device/commands", issueCommandHandler(deps))
		authorized.GET("/devices/:device/commands", listDeviceCommandsHandler(deps))
		authorized.GET("/audit", listAuditHandler(deps))
	}

	return r
}
