package api

import (
	"testing"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

func TestFoldByEventID(t *testing.T) {
	// row builds a report of eventId id received under envelope ts env. The payload's own tsMs is
	// the same for every report of one movement (the record-close time) and must not decide anything.
	row := func(id string, env int64, during float64) stockRow {
		return stockRow{ev: protocol.StockEvent{EventID: id, TSMs: 1, DuringDeliveryVolumeL: during}, envTS: env}
	}
	ids := func(rs []stockRow) string {
		s := ""
		for _, r := range rs {
			s += r.ev.EventID
		}
		return s
	}

	t.Run("greatest envelope ts wins even when tsMs is identical", func(t *testing.T) {
		got, folded := foldByEventID([]stockRow{row("a", 100, 0), row("a", 200, 420)}, true)
		if folded != 1 || len(got) != 1 || got[0].ev.DuringDeliveryVolumeL != 420 {
			t.Errorf("got %+v folded=%d, want the 200-envelope correction", got, folded)
		}
		got, _ = foldByEventID([]stockRow{row("a", 200, 420), row("a", 100, 0)}, false)
		if len(got) != 1 || got[0].ev.DuringDeliveryVolumeL != 420 {
			t.Errorf("descending input: got %+v, want the correction", got)
		}
	})

	t.Run("survivors are ordered by the movement's own time, not by when the correction was sent", func(t *testing.T) {
		at := func(id string, tsMs, env int64) stockRow {
			return stockRow{ev: protocol.StockEvent{EventID: id, TSMs: tsMs}, envTS: env}
		}
		// a closed at 100 and was corrected at env 900; b closed at 150. Ascending must be a, b even
		// though a's winning report is the newest thing in the list.
		got, _ := foldByEventID([]stockRow{at("a", 100, 100), at("b", 150, 150), at("a", 100, 900)}, true)
		if ids(got) != "ab" || got[0].envTS != 900 {
			t.Errorf("ascending: %+v", got)
		}
		got, _ = foldByEventID([]stockRow{at("a", 100, 900), at("b", 150, 150), at("a", 100, 100)}, false)
		if ids(got) != "ba" {
			t.Errorf("descending: %+v", got)
		}
	})

	t.Run("tie keeps the first listed", func(t *testing.T) {
		got, folded := foldByEventID([]stockRow{row("a", 100, 1), row("a", 100, 2)}, true)
		if folded != 1 || len(got) != 1 || got[0].ev.DuringDeliveryVolumeL != 1 {
			t.Errorf("got %+v folded=%d", got, folded)
		}
	})

	t.Run("empty eventId is never folded", func(t *testing.T) {
		got, folded := foldByEventID([]stockRow{row("", 1, 0), row("", 2, 0)}, true)
		if folded != 0 || len(got) != 2 {
			t.Errorf("got %+v folded=%d", got, folded)
		}
	})
}

func TestFoldByEventID_CountsCopies(t *testing.T) {
	r := func(id string, env int64) stockRow {
		return stockRow{ev: protocol.StockEvent{EventID: id}, envTS: env}
	}
	got, folded := foldByEventID([]stockRow{r("a", 1), r("a", 3), r("a", 2), r("b", 4)}, true)
	if folded != 2 || len(got) != 2 {
		t.Fatalf("folded=%d rows=%d, want 2 and 2", folded, len(got))
	}
	for _, g := range got {
		want := map[string]int{"a": 3, "b": 1}[g.ev.EventID]
		if g.copies != want {
			t.Errorf("%s copies = %d, want %d", g.ev.EventID, g.copies, want)
		}
	}
}

func TestSelectWindow(t *testing.T) {
	row := func(id string, tsMs int64, copies int) stockRow {
		return stockRow{ev: protocol.StockEvent{EventID: id, TSMs: tsMs}, copies: copies}
	}
	rows := []stockRow{row("early", 50, 2), row("a", 100, 1), row("b", 200, 3), row("late", 900, 2)}

	listed, folded, truncated := selectWindow(rows, 100, 900, 10)
	if len(listed) != 2 || folded != 2 || truncated {
		t.Errorf("in window: listed=%d folded=%d truncated=%v, want 2/2/false (out-of-window folds not counted; end is exclusive)", len(listed), folded, truncated)
	}

	// The cut keeps "a" (unmodified), hiding "b" whose two folded duplicates must not be reported.
	listed, folded, truncated = selectWindow(rows, 100, 900, 1)
	if len(listed) != 1 || listed[0].ev.EventID != "a" || folded != 0 || !truncated {
		t.Errorf("limit 1: listed=%v folded=%d truncated=%v, want [a]/0/true", listed, folded, truncated)
	}
}

func TestScanOutcome(t *testing.T) {
	at := func(ts ...int64) []tbclient.TSValue {
		var out []tbclient.TSValue
		for _, v := range ts {
			out = append(out, tbclient.TSValue{TS: v})
		}
		return out
	}
	const end = 1000
	cases := []struct {
		name              string
		points            []tbclient.TSValue
		complete          bool
		wantPartial, tooL bool
	}{
		{"complete read", at(10, 20), true, false, false},
		{"cap hit inside the window: window itself incomplete", at(10, 999), false, false, true},
		{"cap hit exactly at the end: window fully read, later corrections unseen", at(10, 1000), false, true, false},
		{"cap hit beyond the end: old window stays readable", at(10, 5000), false, true, false},
		{"cap hit with nothing read", nil, false, false, true},
	}
	for _, c := range cases {
		partial, tooLarge := scanOutcome(c.points, c.complete, end)
		if partial != c.wantPartial || tooLarge != c.tooL {
			t.Errorf("%s: partial=%v tooLarge=%v, want %v/%v", c.name, partial, tooLarge, c.wantPartial, c.tooL)
		}
	}
}
