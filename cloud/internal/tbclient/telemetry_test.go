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

// A device that has never reported: this ThingsBoard answers the latest-values query with one entry
// per requested key whose value is null (observed on the dev compose stack, device created through
// the public API and never connected), not with an empty object. That has to read as "no data", not
// as an unparsable tankCount — which used to surface as a 503 "platform unavailable" and so made a
// freshly installed replacement device look like an outage (cloud#61, B has no data yet).
func TestLatestSnapshot_NullValuesMeanNeverReported(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/auth/login":
			w.Write([]byte(`{"token":"stub-token","refreshToken":"r"}`))
		case r.URL.Query().Get("startTs") != "":
			t.Errorf("history must not be queried when there is no anchor")
			w.Write([]byte(`{}`))
		default:
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprintf(w, `{%q:[{"ts":1791908831160,"value":null}],%q:[{"ts":1791908831160,"value":null}]}`,
				protocol.KeyTankCount, protocol.KeySnapshotSeq)
		}
	}))
	defer srv.Close()

	c, err := tbclient.New(tbclient.Config{BaseURL: srv.URL, Username: "u@example.com", Password: "p"})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	latest, err := c.LatestTimeseries(context.Background(), "dev-1", []string{protocol.KeyTankCount})
	if err != nil || len(latest) != 0 {
		t.Fatalf("LatestTimeseries = %v, %v; want an empty map (a null value is no data)", latest, err)
	}
	ts, values, err := c.LatestSnapshot(context.Background(), "dev-1")
	if err != nil {
		t.Fatalf("LatestSnapshot: %v (a device that never reported is a normal state, and must not look like an outage)", err)
	}
	if ts != 0 || len(values) != 0 {
		t.Errorf("LatestSnapshot = %d, %v; want nothing", ts, values)
	}
}
