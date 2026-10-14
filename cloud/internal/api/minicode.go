package api

import (
	"errors"
	"net/http"
	"regexp"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"

	"yeweiyi-cloud/internal/control"
)

// sceneRe is the intersection of what WeChat accepts for scene (32 visible characters) and what
// POST /api/devices/resolve accepts (shortCodePattern: [0-9A-Za-z_-]). A name WeChat would take but
// the resolver rejects, such as "tank.1", would yield a printed code that always scans to
// device_not_found, so it is refused here instead.
var sceneRe = regexp.MustCompile(`^[0-9A-Za-z_-]{1,32}$`)

// deviceMiniCodeHandler renders the device's mini-program code: scene = device name, page = the
// device detail page. Platform administrators only: it is a printing/installation aid, and the code
// carries no secret and grants nothing (scanning only locates; see POST /api/devices/resolve).
func deviceMiniCodeHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireAdminActor(c, deps)
		if !ok {
			return
		}
		if actor.Role != control.RolePlatformAdmin {
			refuse(c, deps, actor, "device.mini_code", "platform admin only", nil)
			return
		}
		if deps.WeChat == nil {
			apiError(c, http.StatusServiceUnavailable, codeWeChatUnavailable, "WeChat is not enabled on this server")
			return
		}
		device := c.Param("device")
		if !sceneRe.MatchString(device) {
			apiError(c, http.StatusBadRequest, codeInvalidRequest, "device name cannot be used as a mini-program scene")
			return
		}
		if _, err := deps.Queries.GetDevice(c.Request.Context(), device); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				apiError(c, http.StatusNotFound, "device_not_found", "device not found")
				return
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		img, err := deps.WeChat.MiniCode(c.Request.Context(), device, deps.MiniCodePage, deps.MiniCodeEnv)
		if err != nil {
			weChatCallFailed(c, err)
			return
		}
		c.Header("Cache-Control", "no-store")
		c.Data(http.StatusOK, http.DetectContentType(img), img)
	}
}
