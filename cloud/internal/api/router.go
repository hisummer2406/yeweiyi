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
	"yeweiyi-cloud/internal/wechat"
)

type Deps struct {
	Queries   *db.Queries
	Redis     *redis.Client
	Pool      *pgxpool.Pool
	JWTSecret []byte

	// TokenTTL is how long an issued JWT stays valid. Zero means defaultTokenTTL.
	TokenTTL time.Duration

	// MaxSessionAge caps how long sliding renewal can keep a login alive, counted from the password
	// login (the token's authAt). Zero means defaultMaxSessionAge.
	MaxSessionAge time.Duration

	// WeChat verifies mini-program login codes. Nil means the feature is off (config wechat.enabled);
	// the WeChat routes then answer 503 wechat_unavailable and nothing else is affected.
	WeChat wechat.Client
	// MiniCodePage / MiniCodeEnv configure the device mini-program code (GET /devices/:device/mini-code).
	MiniCodePage, MiniCodeEnv string

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

	// TelemetryRetention is how long ThingsBoard keeps raw telemetry (config telemetry.retention).
	// telemetry/history reads ThingsBoard for what is newer and the hourly summaries for what is
	// older. Zero means the 7-day default.
	TelemetryRetention time.Duration

	// WSRevalidateInterval is how often an open /ws/alarms connection re-checks that its user may
	// still see the device. Zero means defaultWSRevalidateInterval. Tests shorten it.
	WSRevalidateInterval time.Duration

	// DeviceCodePrefixes are the model prefixes tried, in order, when a QR scan carries only the
	// short code (`000001` for device `ZCT000001`). Empty means short-code binding matches the
	// scanned text exactly and nothing else. See bindDeviceHandler.
	DeviceCodePrefixes []string
}

func NewRouter(deps Deps) *gin.Engine {
	r := gin.Default()

	r.GET("/healthz", healthzHandler(deps))
	r.POST("/api/login", loginHandler(deps))
	r.POST("/api/auth/wechat/login", wechatLoginHandler(deps))
	r.POST("/api/auth/wechat/phone-login", wechatPhoneLoginHandler(deps))
	// Not in the authorized group: gin's JWT middleware only checks the Authorization header, but
	// this endpoint also accepts the token as a query param (see wsAuthToken) since browser/mini-
	// program WebSocket clients can't set that header on the handshake request.
	r.GET("/ws/alarms", wsAlarmsHandler(deps))

	authorized := r.Group("/api")
	authorized.Use(authMiddleware(deps))
	{
		// /api/alarms (alarms.go) and /api/oil-in-out (stock.go) read the business database
		// (alarm_events / stock_events, written by internal/ingest). Two routes were removed
		// rather than migrated:
		// /api/oilcan/latest (replaced by /api/devices/:device/telemetry/latest below) and
		// /api/shifts (设备协议 V1 has no 交班 uplink on either side, so it could only return
		// an empty list — see docs/protocol-v1-reconciliation.md).
		authorized.GET("/me", meHandler(deps))
		authorized.GET("/alarms", listAlarmsHandler(deps))
		authorized.GET("/oil-in-out", listOilInOutHandler(deps))
		authorized.GET("/devices", listDevicesHandler(deps))
		authorized.GET("/stations", listStationsHandler(deps))
		authorized.POST("/devices", registerDeviceHandler(deps))
		authorized.POST("/devices/bind", bindDeviceHandler(deps)) // retired (410), cloud#63
		authorized.POST("/devices/resolve", resolveDeviceHandler(deps))
		authorized.GET("/devices/:device/tanks", listDeviceTanksHandler(deps))
		authorized.GET("/users/:id/tank-grants", listUserTankGrantsHandler(deps))
		authorized.PUT("/users/:id/tank-grants/:device", putUserTankGrantsHandler(deps))
		authorized.POST("/devices/unbind", unbindDeviceHandler(deps))
		// Provisioning-time only (platform_admin), not part of the mini-program's own pairing
		// flow — see mintPairingSecretHandler's doc comment.
		authorized.POST("/devices/:device/pairing-secret", mintPairingSecretHandler(deps))

		// ThingsBoard single stack (issue #10).
		// Telemetry is read through to ThingsBoard on demand, never served from a local copy.
		authorized.GET("/devices/:device/telemetry/latest", latestTelemetryHandler(deps))
		authorized.GET("/devices/:device/telemetry/history", telemetryHistoryHandler(deps))
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
		authorized.POST("/stations", createStationHandler(deps))
		authorized.PUT("/stations/:id", updateStationHandler(deps))
		authorized.DELETE("/stations/:id", deleteStationHandler(deps))
		authorized.GET("/devices/:device/mini-code", deviceMiniCodeHandler(deps))
		authorized.PUT("/devices/:device/station", assignDeviceStationHandler(deps))
		authorized.POST("/stations/:id/replace-device", replaceStationDeviceHandler(deps))
	}

	return r
}

// maxSessionAge resolves the sliding-renewal cap, falling back to the default.
func (d Deps) maxSessionAge() time.Duration {
	if d.MaxSessionAge > 0 {
		return d.MaxSessionAge
	}
	return defaultMaxSessionAge
}

// tokenTTL resolves the configured token lifetime, falling back to the default.
func (d Deps) tokenTTL() time.Duration {
	if d.TokenTTL > 0 {
		return d.TokenTTL
	}
	return defaultTokenTTL
}
