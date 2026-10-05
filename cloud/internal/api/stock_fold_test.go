package api

import (
	"testing"

	"yeweiyi-cloud/internal/protocol"
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
