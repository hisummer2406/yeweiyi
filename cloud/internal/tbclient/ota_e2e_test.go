package tbclient_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"yeweiyi-cloud/internal/tbclient"
)

// Set OTA_LARGE_TEST=1 only after configuring all upload limits for a 65 MiB package.
func TestOTARealSoftwareDistribution(t *testing.T) {
	c := newTestClient(t)
	device, token := provisionDevice(t, c)
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	var raw struct {
		Profile tbclient.EntityID `json:"deviceProfileId"`
	}
	if err := c.GetJSON(ctx, "/api/device/"+device.ID.ID, &raw); err != nil {
		t.Fatal(err)
	}
	version := int(time.Now().Unix())
	info, err := c.CreateSoftwarePackage(ctx, raw.Profile.ID, version)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanup, done := context.WithTimeout(context.Background(), 30*time.Second)
		defer done()
		if err := c.SetSoftwarePackage(cleanup, device.ID.ID, ""); err != nil {
			t.Errorf("cleanup unassign: %v", err)
			return
		}
		if err := c.DeleteSoftwarePackage(cleanup, info.ID.ID); err != nil {
			t.Errorf("cleanup package: %v", err)
		}
	})
	size := 2*1024*1024 + 17
	if os.Getenv("OTA_LARGE_TEST") == "1" {
		size = 65*1024*1024 + 17
	}
	file := filepath.Join(t.TempDir(), "app.apk")
	f, err := os.Create(file)
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256.New()
	block := bytes.Repeat([]byte{0x5a}, 64*1024)
	for remaining := size; remaining > 0; {
		n := min(remaining, len(block))
		if _, err := io.MultiWriter(f, hash).Write(block[:n]); err != nil {
			f.Close()
			t.Fatal(err)
		}
		remaining -= n
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	expectedHash := hex.EncodeToString(hash.Sum(nil))
	if _, err := c.UploadSoftwarePackage(ctx, info.ID.ID, file, int64(size), expectedHash); err != nil {
		t.Fatal(err)
	}
	readBack, err := c.SoftwarePackageInfo(ctx, info.ID.ID)
	if err != nil || readBack.Checksum != expectedHash || readBack.DataSize != int64(size) {
		t.Fatalf("read-back metadata mismatch: %v", err)
	}
	if err := c.SetSoftwarePackage(ctx, device.ID.ID, info.ID.ID); err != nil {
		t.Fatal(err)
	}
	versionString := strconv.Itoa(version)
	deadline := time.Now().Add(settleTimeout)
	for {
		attrs, err := c.Attributes(ctx, device.ID.ID, tbclient.ScopeShared, []string{"sw_version", "sw_checksum"})
		if err != nil {
			t.Fatal(err)
		}
		if attrs["sw_version"] == versionString && attrs["sw_checksum"] == expectedHash {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("SOFTWARE shared attributes did not converge")
		}
		time.Sleep(settlePollPeriod)
	}
	httpClient := &http.Client{Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	deviceGet := func(deviceToken, endpoint string, query url.Values, limit int) (int, []byte) {
		t.Helper()
		target := tbBaseURL() + "/api/v1/" + url.PathEscape(deviceToken) + "/" + endpoint + "?" + query.Encode()
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
		if err != nil {
			t.Fatal("cannot build device request")
		}
		resp, err := httpClient.Do(req)
		if err != nil {
			t.Fatalf("device transport failed (%T)", err)
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(io.LimitReader(resp.Body, int64(limit+1)))
		if err != nil || len(body) > limit {
			t.Fatal("invalid device response length")
		}
		return resp.StatusCode, body
	}
	status, body := deviceGet(token, "attributes", url.Values{"sharedKeys": {"sw_title,sw_version,sw_checksum"}}, 4096)
	var attrs struct {
		Shared map[string]any `json:"shared"`
	}
	if status != 200 || json.Unmarshal(body, &attrs) != nil || attrs.Shared["sw_version"] != versionString {
		t.Fatal("Device API attributes do not match assignment")
	}
	chunkSize := 1024 * 1024
	downloadHash := sha256.New()
	for offset := 0; offset < size; offset += chunkSize {
		q := url.Values{"title": {"com.sansen.yeweiyi"}, "version": {versionString}, "size": {strconv.Itoa(chunkSize)}, "chunk": {strconv.Itoa(offset / chunkSize)}}
		status, body := deviceGet(token, "software", q, chunkSize)
		if status != 200 || len(body) != min(chunkSize, size-offset) {
			t.Fatalf("chunk %d status=%d bytes=%d", offset/chunkSize, status, len(body))
		}
		downloadHash.Write(body)
	}
	if hex.EncodeToString(downloadHash.Sum(nil)) != expectedHash {
		t.Fatal("downloaded SOFTWARE checksum mismatch")
	}
	q := url.Values{"title": {"com.sansen.yeweiyi"}, "version": {versionString}, "size": {"1"}, "chunk": {"0"}}
	if status, _ := deviceGet("intentionally-invalid-token", "software", q, 4096); status != 401 {
		t.Fatalf("invalid token status=%d", status)
	}
	q.Set("version", "wrong-version")
	if status, _ := deviceGet(token, "software", q, 4096); status != 400 {
		t.Fatalf("wrong version status=%d", status)
	}
	q.Set("version", versionString)
	if err := c.SetSoftwarePackage(ctx, device.ID.ID, ""); err != nil {
		t.Fatal(err)
	}
	deadline = time.Now().Add(settleTimeout)
	for {
		status, _ := deviceGet(token, "software", q, 4096)
		if status == 404 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("SOFTWARE remained downloadable after unassignment; check profile default")
		}
		time.Sleep(settlePollPeriod)
	}
	t.Logf("SOFTWARE round-trip: %s, %d bytes, full/chunk digest matched, revoked download denied", fmt.Sprint(version), size)
}
