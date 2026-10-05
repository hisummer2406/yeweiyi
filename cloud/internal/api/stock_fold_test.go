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

	t.Run("survivors stay sorted when a correction replaces an earlier slot", func(t *testing.T) {
		// Ascending: a@100, b@150, a@200 must not come out as a@200, b@150.
		got, _ := foldByEventID([]stockRow{row("a", 100, 0), row("b", 150, 0), row("a", 200, 420)}, true)
		if ids(got) != "ba" || got[0].envTS != 150 || got[1].envTS != 200 {
			t.Errorf("ascending order broken: %+v", got)
		}
		// Descending: a@200, b@150, a@100.
		got, _ = foldByEventID([]stockRow{row("a", 200, 420), row("b", 150, 0), row("a", 100, 0)}, false)
		if ids(got) != "ab" || got[0].envTS != 200 || got[1].envTS != 150 {
			t.Errorf("descending order broken: %+v", got)
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

func TestDropEarlierOrigins(t *testing.T) {
	row := func(id string, env int64) stockRow {
		return stockRow{ev: protocol.StockEvent{EventID: id}, envTS: env}
	}
	rows := []stockRow{row("a", 300), row("b", 200), row("c", 100), row("", 50)}
	back := []stockRow{row("a", 10), row("a", 20)}

	kept, dropped := dropEarlierOrigins(rows, back)
	if dropped != 1 {
		t.Errorf("dropped = %d, want 1 movement (two earlier copies of one id count once)", dropped)
	}
	if len(kept) != 3 || kept[0].ev.EventID != "b" || kept[2].ev.EventID != "" {
		t.Errorf("kept = %+v, want b, c and the id-less row", kept)
	}
	if kept, dropped = dropEarlierOrigins(rows, nil); len(kept) != 4 || dropped != 0 {
		t.Errorf("no earlier copies must keep everything: %d kept, %d dropped", len(kept), dropped)
	}
}
