// Package api exposes the HTTPS REST surface yeweiyi-miniapp (and any PC-remote client) consumes.
//
// It never speaks MQTT. Device data is read through internal/tbclient from ThingsBoard on demand,
// and business records come from internal/store — there is no local copy of telemetry.
package api

import (
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
	"yeweiyi-cloud/internal/tbclient"
)

type Deps struct {
	Queries   *db.Queries
	Redis     *redis.Client
	Pool      *pgxpool.Pool
	JWTSecret []byte

	// TokenTTL is how long an issued JWT stays valid. Zero means defaultTokenTTL.
	TokenTTL time.Duration

	// TB and Control belong to the ThingsBoard single stack. Both are optional so the server can
	// still boot without ThingsBoard configured — the routes that need them answer 503 rather than
	// panicking, which is what lets a developer work on the business endpoints alone.
	TB      *tbclient.Client
	Control *control.Service

	// StockLegacyFormatBefore is the boundary between "historical records nobody can read any more"
	// and "a device that is still publishing the pre-V1 format today". See config.Stock and
	// stock.go; zero means the fleet upgrade has not happened, so every unreadable record is
	// current breakage.
	StockLegacyFormatBefore time.Time
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
	authorized.Use(authMiddleware(deps))
	{
		// /api/alarms (alarms.go) and /api/oil-in-out (stock.go) read from ThingsBoard, not from
		// the business database. Two routes were removed rather than migrated:
		// /api/oilcan/latest (replaced by /api/devices/:device/telemetry/latest below) and
		// /api/shifts (设备协议 V1 has no 交班 uplink on either side, so it could only return
		// an empty list — see docs/protocol-v1-reconciliation.md).
		authorized.GET("/alarms", listAlarmsHandler(deps))
		authorized.GET("/oil-in-out", listOilInOutHandler(deps))
		authorized.GET("/devices", listDevicesHandler(deps))
		authorized.POST("/devices/bind", bindDeviceHandler(deps))
		authorized.POST("/devices/unbind", unbindDeviceHandler(deps))
		// Provisioning-time only (platform_admin), not part of the mini-program's own pairing
		// flow — see mintPairingSecretHandler's doc comment.
		authorized.POST("/devices/:device/pairing-secret", mintPairingSecretHandler(deps))

		// ThingsBoard single stack (issue #10).
		// Telemetry is read through to ThingsBoard on demand, never served from a local copy.
		authorized.GET("/devices/:device/telemetry/latest", latestTelemetryHandler(deps))
		// Remote control. POST answers 202, never 200: the response reports only how far the
		// command got synchronously; the outcome is read back through the GET.
		authorized.POST("/devices/:device/commands", issueCommandHandler(deps))
		authorized.GET("/devices/:device/commands", listDeviceCommandsHandler(deps))
		authorized.GET("/audit", listAuditHandler(deps))

		// Tenant administration (admin.go). Who may do what is decided inside each handler from the
		// caller's current database row, not from the token.
		authorized.POST("/customers", createCustomerHandler(deps))
		authorized.GET("/customers", listCustomersHandler(deps))
		authorized.PUT("/customers/:id", renameCustomerHandler(deps))
		authorized.DELETE("/customers/:id", deleteCustomerHandler(deps))
		authorized.POST("/users", createUserHandler(deps))
		authorized.GET("/users", listUsersHandler(deps))
		authorized.PUT("/users/:id", updateUserHandler(deps))
		authorized.PUT("/users/:id/customer", moveUserHandler(deps))
		authorized.PUT("/devices/:device/customer", assignDeviceHandler(deps))
	}

	return r
}

// tokenTTL resolves the configured token lifetime, falling back to the default.
func (d Deps) tokenTTL() time.Duration {
	if d.TokenTTL > 0 {
		return d.TokenTTL
	}
	return defaultTokenTTL
}
