package api

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

func healthzHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if err := deps.Pool.Ping(c.Request.Context()); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"postgres": err.Error()})
			return
		}
		if err := deps.Redis.Ping(c.Request.Context()).Err(); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"redis": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	}
}
