package api

import "testing"

func TestBucketAlignment(t *testing.T) {
	const step = 60_000
	cases := []struct{ in, floor, ceil int64 }{
		{120_000, 120_000, 120_000},
		{120_001, 120_000, 180_000},
		{179_999, 120_000, 180_000},
		{0, 0, 0},
		{-1, -60_000, 0}, // pre-epoch must floor downward, not toward zero
	}
	for _, c := range cases {
		if got := floorTo(c.in, step); got != c.floor {
			t.Errorf("floorTo(%d) = %d, want %d", c.in, got, c.floor)
		}
		if got := ceilTo(c.in, step); got != c.ceil {
			t.Errorf("ceilTo(%d) = %d, want %d", c.in, got, c.ceil)
		}
	}
}

func TestChartableValue(t *testing.T) {
	for _, in := range []string{"NaN", "nan", "Inf", "+Inf", "-Inf", "Infinity", "", "abc"} {
		if v, ok := chartableValue(in); ok {
			t.Errorf("chartableValue(%q) = %v, true; want rejected", in, v)
		}
	}
	for in, want := range map[string]float64{"0": 0, "-1.5": -1.5, "1e3": 1000} {
		if v, ok := chartableValue(in); !ok || v != want {
			t.Errorf("chartableValue(%q) = %v, %v; want %v, true", in, v, ok, want)
		}
	}
}

// The one-bucket allowance after a reassignment (telemetryHistoryHandler) is only sound while the
// reassignment transaction cannot outlast the smallest bucket.
func TestAssignTxTimeoutIsBelowSmallestBucket(t *testing.T) {
	for name, d := range historyIntervals {
		if assignTxTimeout >= d {
			t.Errorf("assignTxTimeout %v >= interval %s (%v): the post-reassignment allowance is no longer sound", assignTxTimeout, name, d)
		}
	}
}
