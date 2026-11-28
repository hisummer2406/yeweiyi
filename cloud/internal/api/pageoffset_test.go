package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

// An offset past int32 must not wrap into a small or negative page offset.
func TestPagingOffsetBeyondInt32(t *testing.T) {
	gin.SetMode(gin.TestMode)
	const huge = "4294967301" // wraps to 5 as int32

	w := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(w)
	c.Request = httptest.NewRequest(http.MethodGet, "/?offset="+huge, nil)
	if _, ok := parseListParams(c); ok || w.Code != http.StatusBadRequest {
		t.Fatalf("parseListParams(offset=%s): ok=%v code=%d, want 400", huge, ok, w.Code)
	}

	c, _ = gin.CreateTestContext(httptest.NewRecorder())
	c.Request = httptest.NewRequest(http.MethodGet, "/?offset="+huge, nil)
	if _, offset := pageParams(c, 20); offset != 0 {
		t.Fatalf("pageParams(offset=%s) offset = %d, want 0 (ignored)", huge, offset)
	}
}
