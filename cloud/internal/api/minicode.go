package api

import (
	"errors"
	"net/http"
	"regexp"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"

	"yeweiyi-cloud/internal/control"
)

// WeChat limits scene to 32 visible characters from this set; device names are well inside it, but
// a name outside it must be refused rather than printed as a code that scans to something else.
var sceneRe = regexp.MustCompile(`^[0-9A-Za-z!#$&'()*+,/:;=?@._~-]{1,32}$`)

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
