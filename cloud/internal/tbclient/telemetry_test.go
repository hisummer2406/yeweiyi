package tbclient_test

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// TestLatestSnapshot_RejectsOutOfRangeTankCount covers the P2 found in review of PR #11:
// tankCount is device-reported and untrusted, and LatestSnapshot used to pass it straight into
// protocol.SnapshotKeys with no bound at all. An anomalous, corrupted or compromised device could
// report a huge tankCount once — it would then sit as ThingsBoard's "latest" value for that key
// indefinitely — and every subsequent read would build an unbounded key list and query string off
// it. The rejection must also happen before the expensive history query is ever issued, not just
// before returning an error.
func TestLatestSnapshot_RejectsOutOfRangeTankCount(t *testing.T) {
	cases := []struct {
		name      string
		tankCount string
	}{
		{"negative", "-1"},
		{"one past the bound", fmt.Sprintf("%d", protocol.MaxTankCount+1)},
		{"far too large", "999999999"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var historyCalls int
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch {
				case r.URL.Path == "/api/auth/login":
					w.Write([]byte(`{"token":"stub-token","refreshToken":"r"}`))
				case r.URL.Query().Get("startTs") != "":
					// The history query LatestSnapshot issues once it has an (accepted) tankCount.
					// An out-of-range value must never reach this point.
					historyCalls++
					w.Write([]byte(`{}`))
				default:
					// The anchor (LatestTimeseries) call for tankCount/snapshotSeq.
					w.Header().Set("Content-Type", "application/json")
					fmt.Fprintf(w, `{%q:[{"ts":1000,"value":%q}],%q:[{"ts":1000,"value":"1"}]}`,
						protocol.KeyTankCount, tc.tankCount, protocol.KeySnapshotSeq)
				}
			}))
			defer srv.Close()

			c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
			if err != nil {
				t.Fatalf("New: %v", err)
			}

			_, _, err = c.LatestSnapshot(context.Background(), "dev-1")
			if err == nil {
				t.Fatalf("tankCount=%s should have been rejected", tc.tankCount)
			}
			if !contains(err.Error(), "outside the allowed range") {
				t.Errorf("error text doesn't explain the rejection: %v", err)
			}
			if historyCalls != 0 {
				t.Errorf("history query was issued %d time(s); an out-of-range tankCount must be rejected before building it (that query is the actual resource-exhaustion risk)", historyCalls)
			}
		})
	}
}

// TestLatestSnapshot_AllowsBoundaryTankCount is the complement of the rejection test above: the
// bound must not be off-by-one in the strict direction either. Zero tanks (a device that has
// reported but has none configured) and exactly protocol.MaxTankCount must both be accepted.
func TestLatestSnapshot_AllowsBoundaryTankCount(t *testing.T) {
	for _, tankCount := range []int{0, protocol.MaxTankCount} {
		t.Run(fmt.Sprintf("tankCount=%d", tankCount), func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch {
				case r.URL.Path == "/api/auth/login":
					w.Write([]byte(`{"token":"stub-token","refreshToken":"r"}`))
				case r.URL.Query().Get("startTs") != "":
					// No data at this exact instant is fine for this test; the point is only that
					// LatestSnapshot must actually reach this call rather than rejecting first.
					w.Write([]byte(`{}`))
				default:
					w.Header().Set("Content-Type", "application/json")
					fmt.Fprintf(w, `{%q:[{"ts":1000,"value":"%d"}],%q:[{"ts":1000,"value":"1"}]}`,
						protocol.KeyTankCount, tankCount, protocol.KeySnapshotSeq)
				}
			}))
			defer srv.Close()

			c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
			if err != nil {
				t.Fatalf("New: %v", err)
			}

			if _, _, err := c.LatestSnapshot(context.Background(), "dev-1"); err != nil {
				t.Fatalf("tankCount=%d is within bounds and must be accepted: %v", tankCount, err)
			}
		})
	}
}
