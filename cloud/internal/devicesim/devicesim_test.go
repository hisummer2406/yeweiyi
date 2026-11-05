package devicesim_test

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"yeweiyi-cloud/internal/devicesim"
)

// TLS configuration is validated before any network I/O, so these need no broker and no containers.

func TestConnect_TLSRequiresCACert(t *testing.T) {
	// Without a CA there is nothing to verify the broker against. Failing here is the point: an
	// offline deployment's server certificate is signed by a private CA that no system trust store
	// knows, so silently falling back to the system roots would either fail confusingly later or,
	// worse, succeed against the wrong server.
	for _, scheme := range []string{"ssl", "tls", "mqtts"} {
		d := devicesim.New(devicesim.Config{BrokerURL: scheme + "://example:8883", AccessToken: "t"})
		err := d.Connect(context.Background())
		if err == nil {
			t.Fatalf("%s:// without CACertFile must fail", scheme)
		}
		if !strings.Contains(err.Error(), "CACertFile") {
			t.Errorf("%s://: error should name the missing setting, got: %v", scheme, err)
		}
	}
}

func TestConnect_TLSRejectsUnusableCAFile(t *testing.T) {
	dir := t.TempDir()

	missing := filepath.Join(dir, "nope.pem")
	if err := connectErr(missing); err == nil || !strings.Contains(err.Error(), "read CA") {
		t.Errorf("a missing CA file should be reported as such, got: %v", err)
	}

	garbage := filepath.Join(dir, "garbage.pem")
	if err := os.WriteFile(garbage, []byte("this is not a certificate"), 0o600); err != nil {
		t.Fatal(err)
	}
	// A file that exists but holds no certificate must not be accepted as an empty trust store —
	// that would verify against nothing at all.
	if err := connectErr(garbage); err == nil || !strings.Contains(err.Error(), "no usable PEM") {
		t.Errorf("a CA file with no certificate should be rejected, got: %v", err)
	}
}

// TestConnect_PlaintextNeedsNoCA pins that the TLS requirements apply only to TLS schemes; a
// tcp:// broker must not start demanding certificates.
func TestConnect_PlaintextNeedsNoCA(t *testing.T) {
	d := devicesim.New(devicesim.Config{BrokerURL: "tcp://127.0.0.1:1", AccessToken: "t"})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err := d.Connect(ctx)
	if err == nil {
		t.Fatal("connecting to a dead port should fail")
	}
	// It must fail on the connection, not on a missing CA.
	if strings.Contains(err.Error(), "CACertFile") {
		t.Errorf("a plaintext broker must not require a CA: %v", err)
	}
}

func connectErr(caFile string) error {
	d := devicesim.New(devicesim.Config{
		BrokerURL:   "ssl://example:8883",
		CACertFile:  caFile,
		AccessToken: "t",
	})
	return d.Connect(context.Background())
}
