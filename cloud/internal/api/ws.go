package api

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/gorilla/websocket"

	"yeweiyi-cloud/internal/store/db"
)

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	// This endpoint requires a valid JWT to open the connection at all, so origin checking on top
	// of that adds no real protection — and mini-program/PC clients aren't browser same-origin
	// callers anyway.
	CheckOrigin: func(r *http.Request) bool { return true },
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

		owns, err := deps.Queries.UserOwnsDevice(c.Request.Context(), db.UserOwnsDeviceParams{UserID: claims.UserID, DeviceName: device})
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

		sub := deps.Redis.Subscribe(c.Request.Context(), "alarms:live")
		defer sub.Close()

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
				if err := conn.WriteMessage(websocket.TextMessage, []byte(msg.Payload)); err != nil {
					return
				}
			}
		}
	}
}
