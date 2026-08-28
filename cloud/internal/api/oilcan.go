package api

import (
	"errors"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
)

// oilcanSnapshotKeyPrefix matches the cache key the future MQTT polling loop (design doc §4.3) will
// write to after each requestOilcan round-trip. Nothing populates it yet — internal/mqtt is not
// built — so this handler will 404 until that ingestion job exists.
const oilcanSnapshotKeyPrefix = "oilcan:latest:"

func oilcanLatestHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		device := c.Query("device")
		if device == "" {
			c.JSON(http.StatusBadRequest, gin.H{"error": "device query param is required"})
			return
		}
		if !requireDeviceOwnership(c, deps, device) {
			return
		}

		raw, err := deps.Redis.Get(c.Request.Context(), oilcanSnapshotKeyPrefix+device).Result()
		if errors.Is(err, redis.Nil) {
			c.JSON(http.StatusNotFound, gin.H{"error": "no cached snapshot for this device yet"})
			return
		}
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		c.Data(http.StatusOK, "application/json", []byte(raw))
	}
}
