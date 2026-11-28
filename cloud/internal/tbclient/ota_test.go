package tbclient_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"yeweiyi-cloud/internal/tbclient"
)

const otaID = "12345678-1234-4234-8234-123456789abc"

func otaClient(t *testing.T, handler http.HandlerFunc) *tbclient.Client {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/login" {
			fmt.Fprint(w, `{"token":"test-token"}`)
			return
		}
		handler(w, r)
	}))
	t.Cleanup(srv.Close)
	c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "tenant", Password: "test"})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestOTASoftwareAssignmentPreservesDeviceAndRetriesConflict(t *testing.T) {
	var reads, writes int
	var original map[string]any
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "GET" {
			reads++
			payload := fmt.Sprintf(`{"id":{"id":%q,"entityType":"DEVICE"},"version":%d,"name":"terminal","deviceProfileId":{"id":"profile"},"firmwareId":{"id":"firmware"},"additionalInfo":{"nested":[1,true]},"unknown":{"keep":"me"}}`, otaID, reads)
			json.Unmarshal([]byte(payload), &original)
			fmt.Fprint(w, payload)
			return
		}
		writes++
		var got map[string]json.RawMessage
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			t.Error(err)
		}
		for _, key := range []string{"firmwareId", "deviceProfileId", "additionalInfo", "unknown"} {
			var actual any
			json.Unmarshal(got[key], &actual)
			if !reflect.DeepEqual(actual, original[key]) {
				t.Errorf("changed %s: got %v want %v", key, actual, original[key])
			}
		}
		if string(got["version"]) != fmt.Sprint(reads) {
			t.Error("stale version")
		}
		var assigned tbclient.EntityID
		if json.Unmarshal(got["softwareId"], &assigned) != nil || assigned.ID != otaID || assigned.EntityType != "OTA_PACKAGE" {
			t.Errorf("softwareId = %s", got["softwareId"])
		}
		if writes == 1 {
			w.WriteHeader(409)
			return
		}
		json.NewEncoder(w).Encode(got)
	})
	if err := c.SetSoftwarePackage(context.Background(), otaID, otaID); err != nil {
		t.Fatal(err)
	}
	if reads != 2 || writes != 2 {
		t.Fatalf("reads=%d writes=%d", reads, writes)
	}
}

func TestOTAAssignmentRequiresOptimisticVersion(t *testing.T) {
	for _, version := range []string{"", "null", `"1"`, "1.5", "-1"} {
		t.Run("version_"+version, func(t *testing.T) {
			writes := 0
			c := otaClient(t, func(w http.ResponseWriter, r *http.Request) {
				if r.Method == "GET" {
					field := ""
					if version != "" {
						field = `,"version":` + version
					}
					fmt.Fprintf(w, `{"id":{"id":%q},"name":"terminal"%s}`, otaID, field)
					return
				}
				writes++
				fmt.Fprint(w, `{}`)
			})
			if err := c.SetSoftwarePackage(context.Background(), otaID, otaID); err == nil {
				t.Error("accepted device without valid concurrency version")
			}
			if writes != 0 {
				t.Errorf("unsafe writes=%d", writes)
			}
		})
	}
}

func TestOTAUnassignUsesNullAndBoundsConflictRetry(t *testing.T) {
	writes := 0
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "GET" {
			fmt.Fprintf(w, `{"id":{"id":%q},"version":1,"softwareId":{"id":"old"}}`, otaID)
			return
		}
		writes++
		var got map[string]json.RawMessage
		json.NewDecoder(r.Body).Decode(&got)
		if string(got["softwareId"]) != "null" {
			t.Errorf("unassign = %s", got["softwareId"])
		}
		w.WriteHeader(409)
	})
	if err := c.SetSoftwarePackage(context.Background(), otaID, ""); err == nil {
		t.Fatal("expected conflict")
	}
	if writes != 3 {
		t.Fatalf("writes=%d", writes)
	}
}

func TestOTAUploadReplaysIdenticalMultipartAfter401(t *testing.T) {
	data := strings.Repeat("apk-content", 40000)
	sum := sha256.Sum256([]byte(data))
	hash := hex.EncodeToString(sum[:])
	file := filepath.Join(t.TempDir(), "release.apk")
	if err := os.WriteFile(file, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	uploads := 0
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) {
		uploads++
		if r.URL.Query().Get("checksumAlgorithm") != "SHA256" || r.URL.Query().Get("checksum") != hash {
			t.Errorf("query = %s", r.URL.RawQuery)
		}
		if r.ContentLength <= int64(len(data)) {
			t.Error("multipart content length missing")
		}
		reader, err := r.MultipartReader()
		if err != nil {
			t.Error(err)
			return
		}
		part, err := reader.NextPart()
		if err != nil {
			t.Error(err)
			return
		}
		if part.FormName() != "file" || part.FileName() != "app.apk" {
			t.Errorf("part = %s %s", part.FormName(), part.FileName())
		}
		body, _ := io.ReadAll(part)
		if string(body) != data {
			t.Errorf("upload %d content changed", uploads)
		}
		if _, err := reader.NextPart(); err != io.EOF {
			t.Errorf("multipart closing: %v", err)
		}
		if uploads == 1 {
			w.WriteHeader(401)
			return
		}
		fmt.Fprintf(w, `{"id":{"id":%q},"type":"SOFTWARE","title":"com.sansen.yeweiyi","version":"6","hasData":true,"dataSize":%d,"checksumAlgorithm":"SHA256","checksum":%q}`, otaID, len(data), hash)
	})
	got, err := c.UploadSoftwarePackage(context.Background(), otaID, file, int64(len(data)), hash)
	if err != nil {
		t.Fatal(err)
	}
	if uploads != 2 || got.Checksum != hash {
		t.Fatalf("uploads=%d info=%+v", uploads, got)
	}
}

func TestOTAUploadRejectsChangedSourceBeforeNetwork(t *testing.T) {
	file := filepath.Join(t.TempDir(), "release.apk")
	os.WriteFile(file, []byte("abc"), 0600)
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) { t.Error("upload should not reach network") })
	if _, err := c.UploadSoftwarePackage(context.Background(), otaID, file, 3, strings.Repeat("a", 64)); err == nil {
		t.Fatal("expected hash rejection")
	}
}

func TestOTAUploadRejectsUnexpectedMetadata(t *testing.T) {
	sum := sha256.Sum256([]byte("abc"))
	hash := hex.EncodeToString(sum[:])
	file := filepath.Join(t.TempDir(), "release.apk")
	os.WriteFile(file, []byte("abc"), 0600)
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		fmt.Fprint(w, `{"hasData":true,"dataSize":3,"checksum":"wrong","type":"SOFTWARE"}`)
	})
	if _, err := c.UploadSoftwarePackage(context.Background(), otaID, file, 3, hash); err == nil {
		t.Fatal("expected metadata rejection")
	}
}

func TestOTAInfoUsesMetadataEndpoint(t *testing.T) {
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/otaPackage/info/"+otaID {
			t.Errorf("unsafe binary endpoint: %s", r.URL.Path)
		}
		fmt.Fprintf(w, `{"id":{"id":%q},"hasData":true,"type":"SOFTWARE"}`, otaID)
	})
	if _, err := c.SoftwarePackageInfo(context.Background(), otaID); err != nil {
		t.Fatal(err)
	}
}

func TestOTACreateDoesNotAssignOrRetrySideEffect(t *testing.T) {
	calls := 0
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != "/api/otaPackage" {
			t.Errorf("path = %s", r.URL.Path)
		}
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		if body["type"] != "SOFTWARE" || body["title"] != "com.sansen.yeweiyi" || body["version"] != "6" {
			t.Errorf("metadata = %+v", body)
		}
		w.WriteHeader(500)
	})
	if _, err := c.CreateSoftwarePackage(context.Background(), otaID, 6); err == nil {
		t.Fatal("expected error")
	}
	if calls != 1 {
		t.Fatalf("create retried %d times", calls)
	}
}

func TestOTAUploadRespectsCancellation(t *testing.T) {
	file := filepath.Join(t.TempDir(), "release.apk")
	os.WriteFile(file, []byte("abc"), 0600)
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) { t.Error("cancelled request reached server") })
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	started := time.Now()
	if _, err := c.UploadSoftwarePackage(ctx, otaID, file, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"); err == nil {
		t.Fatal("expected cancellation")
	}
	if time.Since(started) > time.Second {
		t.Error("cancellation blocked")
	}
}

func TestOTAMetadataResponseIsBounded(t *testing.T) {
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, strings.Repeat(" ", 1024*1024+1)) })
	if _, err := c.SoftwarePackageInfo(context.Background(), otaID); err == nil {
		t.Fatal("accepted oversized metadata")
	}
}

func TestOTAUploadNeverFollowsRedirect(t *testing.T) {
	var reached atomic.Bool
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { reached.Store(true) }))
	defer other.Close()
	c := otaClient(t, func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		w.Header().Set("Location", other.URL)
		w.WriteHeader(307)
	})
	file := filepath.Join(t.TempDir(), "app.apk")
	os.WriteFile(file, []byte("abc"), 0600)
	if _, err := c.UploadSoftwarePackage(context.Background(), otaID, file, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"); err == nil {
		t.Fatal("redirect accepted")
	}
	if reached.Load() {
		t.Fatal("tenant credentials followed redirect")
	}
}

func TestOTAUploadHasIndependentTimeout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/login" {
			fmt.Fprint(w, `{"token":"test"}`)
			return
		}
		io.Copy(io.Discard, r.Body)
		time.Sleep(80 * time.Millisecond)
		fmt.Fprintf(w, `{"id":{"id":%q},"type":"SOFTWARE","title":"com.sansen.yeweiyi","version":"6","hasData":true,"dataSize":3,"checksumAlgorithm":"SHA256","checksum":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}`, otaID)
	}))
	defer srv.Close()
	c, _ := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "tenant", Password: "test", HTTPClient: &http.Client{Timeout: 40 * time.Millisecond}, OTAUploadTimeout: time.Second})
	file := filepath.Join(t.TempDir(), "app.apk")
	os.WriteFile(file, []byte("abc"), 0600)
	if _, err := c.UploadSoftwarePackage(context.Background(), otaID, file, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"); err != nil {
		t.Fatal(err)
	}
}
