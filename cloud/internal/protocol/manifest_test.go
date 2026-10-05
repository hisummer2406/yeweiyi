package protocol_test

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

// The fixtures in fixtures/protocol/v1 are supposed to be a *shared* artifact: the same bytes
// consumed by this repo's tests and by the Android repo's (issue #7). "Shared" only means anything
// if both sides can prove they are reading the same bytes, and the first attempt at this shared
// artifact failed for exactly the reason a checksum would have caught — both repositories wrote
// their own fixtures, each with a comment saying the other's did not exist yet, and the two turned
// out to be mutually unparseable (docs/protocol-v1-reconciliation.md).
//
// MANIFEST.json is what makes the sharing checkable. These tests enforce it on the cloud side;
// verify-manifest.sh in the same directory is the dependency-free equivalent for the Android build,
// which has no Go toolchain. Both refuse to auto-repair: editing a fixture must force a matching
// edit to the manifest, so a rename or a unit change can turn *both* repositories' builds red at
// the same time instead of being discovered on a real device.
//
// "Can", not "does". **As of this commit only the cloud side runs any of this**, so what exists
// here is the cloud half of a gate, not a gate: the Android repository has not imported these
// files, has no build-time check, and its tests still read fixtures of its own. Nothing in this
// package can observe that — which is exactly why a green run here must not be read as "the
// cross-repo contract is enforced". The manifest says so in `enforcement`, and
// TestManifestDoesNotOverstateEnforcement stops that claim from being flipped without evidence.
// Wiring steps for the Android side: docs/protocol-v1-open-items.md §C.

type fixtureManifest struct {
	ProtocolVersion int    `json:"protocolVersion"`
	Frozen          bool   `json:"frozen"`
	OpenItems       string `json:"openItems"`
	Enforcement     struct {
		CrossRepoGate     bool   `json:"crossRepoGate"`
		Cloud             string `json:"cloud"`
		Android           string `json:"android"`
		AndroidVerifiedAt string `json:"androidVerifiedAt"`
	} `json:"enforcement"`
	Fixtures []struct {
		File      string `json:"file"`
		SHA256    string `json:"sha256"`
		Direction string `json:"direction"`
		Pins      string `json:"pins"`
	} `json:"fixtures"`
}

func loadManifest(t *testing.T) fixtureManifest {
	t.Helper()
	var m fixtureManifest
	if err := json.Unmarshal(readFixture(t, "MANIFEST.json"), &m); err != nil {
		t.Fatalf("MANIFEST.json does not parse: %v", err)
	}
	if len(m.Fixtures) == 0 {
		t.Fatal("MANIFEST.json lists no fixtures")
	}
	return m
}

// TestFixtureManifestMatchesFiles is the cloud half of the cross-repo checksum gate.
func TestFixtureManifestMatchesFiles(t *testing.T) {
	m := loadManifest(t)

	onDisk := map[string]bool{}
	entries, err := os.ReadDir(fixtureDir)
	if err != nil {
		t.Fatalf("read %s: %v", fixtureDir, err)
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") || e.Name() == "MANIFEST.json" {
			continue
		}
		onDisk[e.Name()] = true
	}

	for _, f := range m.Fixtures {
		if !onDisk[f.File] {
			t.Errorf("MANIFEST.json lists %s, which is not in %s", f.File, fixtureDir)
			continue
		}
		delete(onDisk, f.File)

		raw, err := os.ReadFile(filepath.Join(fixtureDir, f.File))
		if err != nil {
			t.Errorf("read %s: %v", f.File, err)
			continue
		}
		sum := sha256.Sum256(raw)
		if got := hex.EncodeToString(sum[:]); got != f.SHA256 {
			t.Errorf("%s drifted from the manifest\n  manifest: %s\n  on disk:  %s\n"+
				"If this change is intentional, update MANIFEST.json AND make the matching change in "+
				"zhongchengyeweiyi — that simultaneity is the whole point of the shared fixtures.",
				f.File, f.SHA256, got)
		}
		var any any
		if err := json.Unmarshal(raw, &any); err != nil {
			t.Errorf("%s is listed in the manifest but is not valid JSON: %v", f.File, err)
		}
		if f.Direction != "uplink" && f.Direction != "downlink" {
			t.Errorf("%s: direction = %q, want uplink or downlink", f.File, f.Direction)
		}
		if strings.TrimSpace(f.Pins) == "" {
			// A fixture nobody can say what it pins is a fixture nobody will maintain correctly.
			t.Errorf("%s: manifest entry has no `pins` description", f.File)
		}
	}

	for name := range onDisk {
		t.Errorf("%s is in %s but not listed in MANIFEST.json — an unlisted fixture is one the "+
			"Android side has no way to know it should be consuming", name, fixtureDir)
	}
}

// TestFixtureManifestAgreesWithProtocolVersion catches the manifest being carried forward unchanged
// through a version bump, which would leave the Android side pinning V1 bytes against a V2 reader.
func TestFixtureManifestAgreesWithProtocolVersion(t *testing.T) {
	m := loadManifest(t)
	if m.ProtocolVersion != protocol.Version {
		t.Errorf("MANIFEST.json protocolVersion = %d, protocol.Version = %d",
			m.ProtocolVersion, protocol.Version)
	}
	if m.OpenItems == "" {
		t.Error("MANIFEST.json must point at the open-items list for as long as the protocol is not frozen")
	} else if _, err := os.Stat(filepath.Join("..", "..", m.OpenItems)); err != nil {
		t.Errorf("MANIFEST.json openItems points at %s, which does not exist: %v", m.OpenItems, err)
	}
	// `frozen` is informational, not enforced — freezing is a decision for the two repo owners, not
	// something a test can make. What is enforced is that a frozen manifest cannot also be pointing
	// at a list of unresolved questions.
	if m.Frozen {
		t.Log("MANIFEST.json says the protocol is frozen; confirm docs/protocol-v1-open-items.md is empty")
	}
}

// TestEveryFixtureIsActuallyConsumed guards against the other failure mode of a shared artifact: a
// fixture that exists, is checksummed, is dutifully copied into both repos, and is read by nobody.
// It would keep passing every checksum while pinning nothing at all.
func TestEveryFixtureIsActuallyConsumed(t *testing.T) {
	m := loadManifest(t)

	var sources []byte
	root := filepath.Join("..", "..")
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // an unreadable corner of the tree is not this test's business
		}
		if info.IsDir() {
			// .git and the agent worktrees hold copies of this repo; walking them would let a
			// fixture "prove" it is used by finding its own name in a stale checkout.
			switch info.Name() {
			case ".git", ".claude", "node_modules":
				return filepath.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(path, "_test.go") {
			return nil
		}
		b, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		sources = append(sources, b...)
		return nil
	})
	if err != nil {
		t.Fatalf("walk %s: %v", root, err)
	}

	haystack := string(sources)
	for _, f := range m.Fixtures {
		if !strings.Contains(haystack, f.File) {
			t.Errorf("%s is checksummed and shipped but no test in this repo reads it — it pins "+
				"nothing and will rot", f.File)
		}
	}
}

// TestManifestDoesNotOverstateEnforcement guards the claim itself rather than the fixtures.
//
// The failure this exists to prevent is a documentation one with real consequences: someone reads
// `go test ./internal/protocol/` passing, concludes the cross-repo contract is enforced, and stops
// treating a one-sided rename as dangerous. This repository has no way to observe the Android
// build, so `crossRepoGate: true` is a claim it cannot substantiate on its own — it may only be set
// alongside a recorded verification (the zhongchengyeweiyi commit at which the import and the
// build-time check were confirmed present).
func TestManifestDoesNotOverstateEnforcement(t *testing.T) {
	m := loadManifest(t)
	e := m.Enforcement

	if e.Cloud == "" || e.Android == "" {
		t.Error("MANIFEST.json enforcement must state, for each side, whether it actually runs this check")
	}
	if e.CrossRepoGate && strings.TrimSpace(e.AndroidVerifiedAt) == "" {
		t.Error("MANIFEST.json claims crossRepoGate, but androidVerifiedAt is empty. This repository " +
			"cannot see the Android build; the claim that both sides enforce the manifest needs a " +
			"recorded verification (the zhongchengyeweiyi commit where the import and the build-time " +
			"check were confirmed) rather than an assertion made from here.")
	}
}
