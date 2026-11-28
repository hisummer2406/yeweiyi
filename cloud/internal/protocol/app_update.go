package protocol

import (
	"bytes"
	"encoding/json"
	"io"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	AppUpdateTitle              = "com.sansen.yeweiyi"
	MaxAppUpdateBytes     int64 = 128 * 1024 * 1024
	MaxAppUpdateInfoBytes       = 64 * 1024
	AppUpdateSharedKeys         = "sw_title,sw_version,sw_size,sw_checksum_algorithm,sw_checksum,appUpdateManifest"
)

type AppUpdateStatus string

const (
	AppUpdateAvailable   AppUpdateStatus = "AVAILABLE"
	AppUpdateCurrent     AppUpdateStatus = "CURRENT"
	AppUpdateOlder       AppUpdateStatus = "OLDER"
	AppUpdateNotAssigned AppUpdateStatus = "NOT_ASSIGNED"
	AppUpdateDisabled    AppUpdateStatus = "DISABLED"
	AppUpdatePending     AppUpdateStatus = "INFO_PENDING"
	AppUpdateUnsupported AppUpdateStatus = "UNSUPPORTED_SCHEMA"
	AppUpdateInvalid     AppUpdateStatus = "INVALID_INFO"
)

type AppUpdateManifest struct {
	SchemaVersion      int               `json:"schemaVersion"`
	Enabled            bool              `json:"enabled"`
	ReleaseID          string            `json:"releaseId"`
	TBPackageID        string            `json:"tbPackageId"`
	AssignmentRevision int64             `json:"assignmentRevision"`
	Title              string            `json:"title"`
	VersionCode        int               `json:"versionCode"`
	VersionName        string            `json:"versionName"`
	PackageName        string            `json:"packageName"`
	SizeBytes          int64             `json:"sizeBytes"`
	SHA256             string            `json:"sha256"`
	MinSDK             int               `json:"minSdk"`
	SupportedABIs      []string          `json:"supportedAbis"`
	ReleaseNotes       map[string]string `json:"releaseNotes"`
	ReleasedAt         string            `json:"releasedAt"`
}

type AppUpdateCheck struct {
	Status    AppUpdateStatus
	Candidate *AppUpdateManifest
}

var (
	updateID        = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
	updateUUID      = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	updateHash      = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)
	updateCode      = regexp.MustCompile(`^[1-9][0-9]{0,9}$`)
	updateLocale    = regexp.MustCompile(`^[a-z]{2}(-[A-Z]{2})?$`)
	updateTimestamp = regexp.MustCompile(`^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})$`)
)

func (m AppUpdateManifest) Valid() bool {
	if m.SchemaVersion != 1 || !updateID.MatchString(m.ReleaseID) || !updateUUID.MatchString(m.TBPackageID) ||
		m.AssignmentRevision <= 0 || m.AssignmentRevision > 9007199254740991 || m.Title != AppUpdateTitle || m.PackageName != AppUpdateTitle ||
		m.VersionCode <= 0 || int64(m.VersionCode) > 2147483647 || len(m.VersionName) == 0 || len(m.VersionName) > 128 ||
		strings.TrimSpace(m.VersionName) != m.VersionName || m.SizeBytes <= 0 || m.SizeBytes > MaxAppUpdateBytes ||
		!updateHash.MatchString(m.SHA256) || m.MinSDK < 1 || m.MinSDK > 1000 || len(m.SupportedABIs) == 0 || len(m.SupportedABIs) > 8 {
		return false
	}
	seen := map[string]bool{}
	for _, abi := range m.SupportedABIs {
		switch abi {
		case "armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64":
		default:
			return false
		}
		if seen[abi] {
			return false
		}
		seen[abi] = true
	}
	if m.ReleaseNotes == nil || len(m.ReleaseNotes) > 16 {
		return false
	}
	noteBytes := 0
	for locale, notes := range m.ReleaseNotes {
		if !updateLocale.MatchString(locale) {
			return false
		}
		noteBytes += len(notes)
	}
	if noteBytes > 16384 {
		return false
	}
	_, err := time.Parse(time.RFC3339, m.ReleasedAt)
	return updateTimestamp.MatchString(m.ReleasedAt) && err == nil
}

func (m AppUpdateManifest) Notes(locale string) string {
	for _, key := range []string{locale, strings.Split(locale, "-")[0], "en", "zh"} {
		if note := m.ReleaseNotes[key]; note != "" {
			return note
		}
	}
	return ""
}

// ParseAppUpdate accepts a fresh TB Device API response; partial MQTT pushes are not candidates.
func ParseAppUpdate(raw []byte, currentVersionCode int) AppUpdateCheck {
	invalid := AppUpdateCheck{Status: AppUpdateInvalid}
	if len(raw) > MaxAppUpdateInfoBytes || currentVersionCode < 0 || !boundedAppUpdateJSON(raw) {
		return invalid
	}
	var root map[string]json.RawMessage
	if json.Unmarshal(raw, &root) != nil {
		return invalid
	}
	var shared map[string]json.RawMessage
	if json.Unmarshal(root["shared"], &shared) != nil || shared == nil {
		return invalid
	}
	hasUpdate := false
	for _, key := range strings.Split(AppUpdateSharedKeys, ",") {
		if _, ok := shared[key]; ok {
			hasUpdate = true
		}
	}
	if !hasUpdate {
		return AppUpdateCheck{Status: AppUpdateNotAssigned}
	}
	manifestRaw, ok := shared["appUpdateManifest"]
	if !ok || string(manifestRaw) == "null" {
		return AppUpdateCheck{Status: AppUpdatePending}
	}
	var shape map[string]json.RawMessage
	if json.Unmarshal(manifestRaw, &shape) != nil || shape == nil {
		return invalid
	}
	var schema int
	if json.Unmarshal(shape["schemaVersion"], &schema) != nil || schema < 1 {
		return invalid
	}
	if schema != 1 {
		return AppUpdateCheck{Status: AppUpdateUnsupported}
	}
	var enabled *bool
	if json.Unmarshal(shape["enabled"], &enabled) != nil || enabled == nil {
		return invalid
	}
	if !*enabled {
		return AppUpdateCheck{Status: AppUpdateDisabled}
	}
	var m AppUpdateManifest
	// Struct decoding matches keys case-insensitively; only canonical wire keys may populate fields.
	canonical := make(map[string]json.RawMessage)
	for _, key := range []string{"schemaVersion", "enabled", "releaseId", "tbPackageId", "assignmentRevision", "title", "versionCode", "versionName", "packageName", "sizeBytes", "sha256", "minSdk", "supportedAbis", "releaseNotes", "releasedAt"} {
		if value, exists := shape[key]; exists {
			canonical[key] = value
		}
	}
	canonicalRaw, err := json.Marshal(canonical)
	if err != nil || json.Unmarshal(canonicalRaw, &m) != nil || !m.Valid() {
		return invalid
	}
	var notes map[string]json.RawMessage
	if json.Unmarshal(shape["releaseNotes"], &notes) != nil || notes == nil {
		return invalid
	}
	for _, value := range notes {
		var note *string
		if json.Unmarshal(value, &note) != nil || note == nil {
			return invalid
		}
	}
	for _, key := range []string{"sw_title", "sw_version", "sw_size", "sw_checksum_algorithm", "sw_checksum"} {
		if value, exists := shared[key]; !exists || string(value) == "null" {
			return AppUpdateCheck{Status: AppUpdatePending}
		}
	}
	var title, version, algorithm, checksum string
	var size int64
	if json.Unmarshal(shared["sw_title"], &title) != nil || json.Unmarshal(shared["sw_version"], &version) != nil ||
		json.Unmarshal(shared["sw_checksum_algorithm"], &algorithm) != nil || json.Unmarshal(shared["sw_checksum"], &checksum) != nil ||
		json.Unmarshal(shared["sw_size"], &size) != nil || !updateCode.MatchString(version) || !updateHash.MatchString(checksum) ||
		(algorithm != "SHA256" && algorithm != "SHA-256") || size <= 0 || size > MaxAppUpdateBytes {
		return invalid
	}
	code, err := strconv.ParseInt(version, 10, 32)
	if err != nil {
		return invalid
	}
	if title != m.Title || int(code) != m.VersionCode || size != m.SizeBytes || !strings.EqualFold(checksum, m.SHA256) {
		return AppUpdateCheck{Status: AppUpdatePending}
	}
	m.SHA256 = strings.ToLower(m.SHA256)
	status := AppUpdateAvailable
	if m.VersionCode == currentVersionCode {
		status = AppUpdateCurrent
	} else if m.VersionCode < currentVersionCode {
		status = AppUpdateOlder
	}
	return AppUpdateCheck{Status: status, Candidate: &m}
}

func boundedAppUpdateJSON(raw []byte) bool {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	depth := 0
	for {
		token, err := decoder.Token()
		if err == io.EOF {
			return depth == 0
		}
		if err != nil {
			return false
		}
		if delimiter, ok := token.(json.Delim); ok {
			switch delimiter {
			case '{', '[':
				depth++
				if depth > 32 {
					return false
				}
			case '}', ']':
				depth--
			}
		}
	}
}
