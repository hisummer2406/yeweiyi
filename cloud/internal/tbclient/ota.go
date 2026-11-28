package tbclient

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"yeweiyi-cloud/internal/protocol"
)

const otaResponseLimit = 1024 * 1024

var otaUUID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

type SoftwarePackageInfo struct {
	ID                EntityID `json:"id"`
	DeviceProfileID   EntityID `json:"deviceProfileId"`
	Type              string   `json:"type"`
	Title             string   `json:"title"`
	Version           string   `json:"version"`
	HasData           bool     `json:"hasData"`
	DataSize          int64    `json:"dataSize"`
	ChecksumAlgorithm string   `json:"checksumAlgorithm"`
	Checksum          string   `json:"checksum"`
}

// SoftwarePackageInfo reads metadata, never the binary-bearing /otaPackage/{id} endpoint.
func (c *Client) SoftwarePackageInfo(ctx context.Context, id string) (SoftwarePackageInfo, error) {
	var info SoftwarePackageInfo
	if !otaUUID.MatchString(id) {
		return info, errors.New("invalid OTA package ID")
	}
	err := c.otaJSON(ctx, http.MethodGet, "/api/otaPackage/info/"+id, nil, &info)
	if err == nil && (info.ID.ID != id || info.Type != "SOFTWARE") {
		err = errors.New("unexpected SOFTWARE package metadata")
	}
	return info, err
}

func (c *Client) CreateSoftwarePackage(ctx context.Context, profileID string, versionCode int) (SoftwarePackageInfo, error) {
	var info SoftwarePackageInfo
	if !otaUUID.MatchString(profileID) || versionCode <= 0 || int64(versionCode) > 2147483647 {
		return info, errors.New("invalid SOFTWARE package definition")
	}
	body := map[string]any{"type": "SOFTWARE", "title": protocol.AppUpdateTitle, "version": strconv.Itoa(versionCode), "deviceProfileId": EntityID{ID: profileID, EntityType: "DEVICE_PROFILE"}}
	if err := c.otaJSON(ctx, http.MethodPost, "/api/otaPackage", body, &info); err != nil {
		return info, err
	}
	if !otaUUID.MatchString(info.ID.ID) || info.Type != "SOFTWARE" || info.Title != protocol.AppUpdateTitle || info.Version != strconv.Itoa(versionCode) {
		return info, errors.New("unexpected created SOFTWARE package metadata")
	}
	return info, nil
}

// SetSoftwarePackage preserves every unmodelled TB field and re-reads on optimistic-lock conflict.
func (c *Client) SetSoftwarePackage(ctx context.Context, deviceID, packageID string) error {
	if !otaUUID.MatchString(deviceID) || (packageID != "" && !otaUUID.MatchString(packageID)) {
		return errors.New("invalid software assignment ID")
	}
	for attempt := 0; attempt < 3; attempt++ {
		var device map[string]json.RawMessage
		if err := c.otaJSON(ctx, http.MethodGet, "/api/device/"+deviceID, nil, &device); err != nil {
			return err
		}
		var id EntityID
		if json.Unmarshal(device["id"], &id) != nil || id.ID != deviceID {
			return errors.New("unexpected device identity")
		}
		var version *int64
		if json.Unmarshal(device["version"], &version) != nil || version == nil || *version < 0 {
			return errors.New("device concurrency version is missing or invalid")
		}
		value := json.RawMessage("null")
		if packageID != "" {
			value, _ = json.Marshal(EntityID{ID: packageID, EntityType: "OTA_PACKAGE"})
		}
		device["softwareId"] = value
		err := c.otaJSON(ctx, http.MethodPost, "/api/device", device, nil)
		var tbErr *Error
		if !errors.As(err, &tbErr) || tbErr.StatusCode != http.StatusConflict {
			return err
		}
		if attempt == 2 {
			return err
		}
	}
	return errors.New("software assignment conflict")
}

func (c *Client) DeleteSoftwarePackage(ctx context.Context, id string) error {
	if !otaUUID.MatchString(id) {
		return errors.New("invalid OTA package ID")
	}
	return c.otaJSON(ctx, http.MethodDelete, "/api/otaPackage/"+id, nil, nil)
}

func (c *Client) otaJSON(ctx context.Context, method, path string, body, out any) error {
	var payload []byte
	var err error
	if body != nil {
		payload, err = json.Marshal(body)
		if err != nil {
			return err
		}
		if len(payload) > otaResponseLimit {
			return errors.New("OTA metadata request too large")
		}
	}
	for attempt := 0; attempt < 2; attempt++ {
		token, err := c.Token(ctx)
		if err != nil {
			return err
		}
		req, err := http.NewRequestWithContext(ctx, method, c.cfg.BaseURL+path, bytes.NewReader(payload))
		if err != nil {
			return err
		}
		req.Header.Set("Accept", "application/json")
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		req.Header.Set("X-Authorization", "Bearer "+token)
		hc := *c.http
		hc.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
		resp, err := hc.Do(req)
		if err != nil {
			return err
		}
		raw, err := io.ReadAll(io.LimitReader(resp.Body, otaResponseLimit+1))
		resp.Body.Close()
		if err != nil {
			return err
		}
		if len(raw) > otaResponseLimit {
			return errors.New("OTA metadata response too large")
		}
		if resp.StatusCode == http.StatusUnauthorized && attempt == 0 {
			if err := c.Login(ctx); err != nil {
				return err
			}
			continue
		}
		if resp.StatusCode == http.StatusNotFound {
			return ErrNotFound
		}
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return &Error{StatusCode: resp.StatusCode, Method: method, Path: path, Body: truncate(raw, 512)}
		}
		if out == nil {
			return nil
		}
		if err := json.Unmarshal(raw, out); err != nil {
			return errors.New("invalid OTA metadata response")
		}
		return nil
	}
	return errors.New("OTA authentication failed")
}

// UploadSoftwarePackage reopens a validated private staging file for each authentication attempt.
func (c *Client) UploadSoftwarePackage(ctx context.Context, id, path string, size int64, checksum string) (SoftwarePackageInfo, error) {
	var info SoftwarePackageInfo
	decoded, err := hex.DecodeString(checksum)
	if !otaUUID.MatchString(id) || size <= 0 || size > protocol.MaxAppUpdateBytes || err != nil || len(decoded) != 32 {
		return info, errors.New("invalid OTA upload description")
	}
	checksum = strings.ToLower(checksum)
	for attempt := 0; attempt < 2; attempt++ {
		if err := ctx.Err(); err != nil {
			return info, err
		}
		file, err := openSoftwareSource(ctx, path, size, checksum)
		if err != nil {
			return info, err
		}
		raw, err := c.uploadSoftwareAttempt(ctx, id, file, size, checksum)
		file.Close()
		var tbErr *Error
		if errors.As(err, &tbErr) && tbErr.StatusCode == http.StatusUnauthorized && attempt == 0 {
			if err := c.Login(ctx); err != nil {
				return info, err
			}
			continue
		}
		if err != nil {
			return info, err
		}
		if err = json.Unmarshal(raw, &info); err != nil {
			return info, errors.New("invalid OTA upload response")
		}
		if info.ID.ID != id || !info.HasData || info.Type != "SOFTWARE" || info.Title != protocol.AppUpdateTitle || info.DataSize != size || info.ChecksumAlgorithm != "SHA256" || !strings.EqualFold(info.Checksum, checksum) {
			return info, errors.New("uploaded SOFTWARE metadata does not match staging file")
		}
		return info, nil
	}
	return info, errors.New("SOFTWARE upload authentication failed")
}

type contextFileReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r contextFileReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.reader.Read(p)
}

func openSoftwareSource(ctx context.Context, path string, size int64, checksum string) (*os.File, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, errors.New("cannot open OTA staging file")
	}
	ok := false
	defer func() {
		if !ok {
			file.Close()
		}
	}()
	stat, err := file.Stat()
	if err != nil || !stat.Mode().IsRegular() || stat.Size() != size {
		return nil, errors.New("OTA staging file size changed")
	}
	hash := sha256.New()
	if _, err = io.Copy(hash, contextFileReader{ctx, io.LimitReader(file, size+1)}); err != nil {
		return nil, err
	}
	if hex.EncodeToString(hash.Sum(nil)) != checksum {
		return nil, errors.New("OTA staging file checksum changed")
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	ok = true
	return file, nil
}

func (c *Client) uploadSoftwareAttempt(ctx context.Context, id string, file *os.File, size int64, checksum string) ([]byte, error) {
	var envelope bytes.Buffer
	mw := multipart.NewWriter(&envelope)
	head := make(textproto.MIMEHeader)
	head.Set("Content-Disposition", `form-data; name="file"; filename="app.apk"`)
	head.Set("Content-Type", "application/vnd.android.package-archive")
	if _, err := mw.CreatePart(head); err != nil {
		return nil, err
	}
	prefix := append([]byte(nil), envelope.Bytes()...)
	if err := mw.Close(); err != nil {
		return nil, err
	}
	suffix := envelope.Bytes()[len(prefix):]
	body := io.MultiReader(bytes.NewReader(prefix), io.NewSectionReader(file, 0, size), bytes.NewReader(suffix))
	path := "/api/otaPackage/" + id + "?" + url.Values{"checksumAlgorithm": {"SHA256"}, "checksum": {checksum}}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.BaseURL+path, body)
	if err != nil {
		return nil, err
	}
	req.ContentLength = int64(len(prefix)) + size + int64(len(suffix))
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	token, err := c.Token(ctx)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Authorization", "Bearer "+token)
	hc := *c.http
	hc.Timeout = c.cfg.OTAUploadTimeout
	if hc.Timeout <= 0 {
		hc.Timeout = 3 * time.Minute
	}
	hc.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	resp, err := hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("OTA upload transport: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, otaResponseLimit+1))
	if err != nil {
		return nil, err
	}
	if len(raw) > otaResponseLimit {
		return nil, errors.New("OTA upload response too large")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, &Error{StatusCode: resp.StatusCode, Method: http.MethodPost, Path: "/api/otaPackage/" + id, Body: truncate(raw, 512)}
	}
	return raw, nil
}
