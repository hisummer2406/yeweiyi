package api

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/gorilla/websocket"
)

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	// This endpoint requires a valid JWT to open the connection at all, so origin checking on top
	// of that adds no real protection — and mini-program/PC clients aren't browser same-origin
	// callers anyway.
	CheckOrigin: func(r *http.Request) bool { return true },
}

const (
	// defaultWSRevalidateInterval bounds how long a revoked user can keep an idle connection open.
	// A revoked user is cut off at the next alarm regardless; this only matters when none arrives.
	defaultWSRevalidateInterval = 30 * time.Second
	// wsAccessCheckTimeout caps one database check. A check that cannot finish counts as a failed
	// check: the connection closes and the client reconnects, rather than the stream staying open
	// on an answer nobody got.
	wsAccessCheckTimeout = 5 * time.Second
	// wsWriteTimeout stops one stalled client from holding the loop that also does revalidation.
	wsWriteTimeout = 10 * time.Second
	// wsReadLimit is generous for what a client may legitimately send here (control frames only).
	wsReadLimit = 512
)

func (d Deps) wsRevalidateInterval() time.Duration {
	if d.WSRevalidateInterval > 0 {
		return d.WSRevalidateInterval
	}
	return defaultWSRevalidateInterval
}

// wsAuthToken mirrors authMiddleware's bearer-header check but also accepts a `token` query param:
// the browser/mini-program WebSocket APIs can't set an Authorization header on the handshake
// request, so the token has to travel some other way for this one endpoint.
func wsAuthToken(c *gin.Context) string {
	if header := c.GetHeader("Authorization"); header != "" {
		if raw, ok := strings.CutPrefix(header, "Bearer "); ok {
			return raw
		}
	}
	return c.Query("token")
}

// wsAlarmsHandler streams the alarm events internal/ingest fans out on the "alarms:live" Redis
// channel to one device's connected clients. Scoped to a single `device` query param for the same
// reason every other endpoint requires one — see listParams in listparams.go.
func wsAlarmsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		raw := wsAuthToken(c)
		if raw == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing bearer token"})
			return
		}
		claims := &apiClaims{}
		token, err := jwt.ParseWithClaims(raw, claims, func(t *jwt.Token) (interface{}, error) {
			return deps.JWTSecret, nil
		}, jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}))
		if err != nil || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid or expired token"})
			return
		}

		device := c.Query("device")
		if device == "" {
			c.AbortWithStatusJSON(http.StatusBadRequest, gin.H{"error": "device query param is required"})
			return
		}

		// This handler authenticates its own JWT outside authMiddleware (a WebSocket handshake can't
		// carry the usual middleware chain), so it calls the shared rule directly rather than
		// requireDeviceOwnership. The rule is applied here at the handshake and again while the
		// connection is open (see the loop below): access is read from the database, never from the
		// token, so a user disabled, unbound or moved to another customer loses the stream too.
		// See userMayAccessDevice in devices.go.
		owns, err := userMayAccessDevice(c.Request.Context(), deps, claims.UserID, device)
		if err != nil {
			c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		if !owns {
			c.AbortWithStatusJSON(http.StatusForbidden, gin.H{"error": "not authorized for this device"})
			return
		}

		conn, err := wsUpgrader.Upgrade(c.Writer, c.Request, nil)
		if err != nil {
			log.Printf("ws: upgrade failed: %v", err)
			return
		}
		defer conn.Close()
		conn.SetReadLimit(wsReadLimit)

		sub := deps.Redis.Subscribe(c.Request.Context(), "alarms:live")
		defer sub.Close()

		// closeWith tells the client why before hanging up, so it can tell "log in again" from a
		// network drop. Best effort: the connection is going away either way.
		closeWith := func(reason string) {
			_ = conn.WriteControl(websocket.CloseMessage,
				websocket.FormatCloseMessage(websocket.ClosePolicyViolation, reason),
				time.Now().Add(time.Second))
		}

		// stillAllowed re-asks the same question as the handshake. Anything but a clear yes —
		// including a database error — answers no: failing open here would hand an alarm to someone
		// who may no longer be entitled to it.
		stillAllowed := func() bool {
			ctx, cancel := context.WithTimeout(context.Background(), wsAccessCheckTimeout)
			defer cancel()
			ok, err := userMayAccessDevice(ctx, deps, claims.UserID, device)
			if err != nil {
				log.Printf("ws: access re-check for user %d on %q failed, closing: %v", claims.UserID, device, err)
				return false
			}
			return ok
		}

		// The token's lifetime bounds the connection: authMiddleware refuses an expired token on
		// every REST call, and a stream that outlived it would be a way around that.
		var expired <-chan time.Time
		if claims.ExpiresAt != nil {
			timer := time.NewTimer(time.Until(claims.ExpiresAt.Time))
			defer timer.Stop()
			expired = timer.C
		}
		revalidate := time.NewTicker(deps.wsRevalidateInterval())
		defer revalidate.Stop()

		// gorilla requires ReadMessage to be pumped so close/ping control frames are handled; this
		// connection is otherwise send-only, so the read loop's only job is to notice disconnect.
		clientGone := make(chan struct{})
		go func() {
			defer close(clientGone)
			for {
				if _, _, err := conn.ReadMessage(); err != nil {
					return
				}
			}
		}()

		ch := sub.Channel()
		for {
			select {
			case <-clientGone:
				return
			case <-expired:
				closeWith("token expired")
				return
			case <-revalidate.C:
				if !stillAllowed() {
					closeWith("access revoked")
					return
				}
			case msg, ok := <-ch:
				if !ok {
					return
				}
				var fanout struct {
					DeviceName string `json:"deviceName"`
				}
				if err := json.Unmarshal([]byte(msg.Payload), &fanout); err != nil {
					continue
				}
				if fanout.DeviceName != device {
					continue
				}
				// Checked per message, not just on the timer: the alarm that arrives right after a
				// revocation must not be the one that gets through.
				if !stillAllowed() {
					closeWith("access revoked")
					return
				}
				conn.SetWriteDeadline(time.Now().Add(wsWriteTimeout))
				if err := conn.WriteMessage(websocket.TextMessage, []byte(msg.Payload)); err != nil {
					return
				}
			}
		}
	}
}
