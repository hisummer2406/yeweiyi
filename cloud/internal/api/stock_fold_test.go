package api

import (
	"testing"

	"yeweiyi-cloud/internal/protocol"
)

func TestFoldByEventID(t *testing.T) {
	ev := func(id string, ts int64, during float64) protocol.StockEvent {
		return protocol.StockEvent{EventID: id, TSMs: ts, DuringDeliveryVolumeL: during}
	}

	t.Run("latest tsMs wins regardless of list order", func(t *testing.T) {
		// Descending query: the correction (ts 200) is listed before the original (ts 100).
		got, folded := foldByEventID([]protocol.StockEvent{ev("a", 200, 420), ev("b", 150, 0), ev("a", 100, 0)})
		if folded != 1 || len(got) != 2 {
			t.Fatalf("folded=%d len=%d, want 1 and 2", folded, len(got))
		}
		if got[0].EventID != "a" || got[0].DuringDeliveryVolumeL != 420 || got[1].EventID != "b" {
			t.Errorf("wrong survivor or order: %+v", got)
		}
		// Ascending query: original first, correction later; the later one must replace in place.
		got, _ = foldByEventID([]protocol.StockEvent{ev("a", 100, 0), ev("b", 150, 0), ev("a", 200, 420)})
		if got[0].EventID != "a" || got[0].DuringDeliveryVolumeL != 420 || got[1].EventID != "b" {
			t.Errorf("wrong survivor or order: %+v", got)
		}
	})

	t.Run("tie keeps the first listed", func(t *testing.T) {
		got, folded := foldByEventID([]protocol.StockEvent{ev("a", 100, 1), ev("a", 100, 2)})
		if folded != 1 || len(got) != 1 || got[0].DuringDeliveryVolumeL != 1 {
			t.Errorf("got %+v folded=%d", got, folded)
		}
	})

	t.Run("empty eventId is never folded", func(t *testing.T) {
		got, folded := foldByEventID([]protocol.StockEvent{ev("", 1, 0), ev("", 2, 0)})
		if folded != 0 || len(got) != 2 {
			t.Errorf("got %+v folded=%d", got, folded)
		}
	})
}
