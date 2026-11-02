package tbclient_test

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

// TestPurge_DeletesEveryKeyThenTheDevice is the ThingsBoard half of the device purge (cloud#83): every
// key the device ever reported is listed and deleted through the public API, nothing is left to read
// back, and once the device entity is gone its name is unknown.
func TestPurge_DeletesEveryKeyThenTheDevice(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()
	dev, token := provisionDevice(t, c)
	sim := connectSim(t, devicesim.Config{AccessToken: token, Tanks: 2})

	for i := 0; i < 3; i++ {
		if _, err := sim.PublishSnapshot(); err != nil {
			t.Fatalf("PublishSnapshot: %v", err)
		}
	}

	// Wait until the last snapshot is stored, every key of it. ThingsBoard persists asynchronously:
	// deleting as soon as the first key shows up let a snapshot still in flight write its points back
	// afterwards, and the "every key gone" check below then timed out on keys the delete never saw.
	// Counting points per key does not work either: snapshots sent within the same millisecond share
	// a timestamp and ThingsBoard keeps one point per (key, ts).
	var keys []string
	eventually(t, settleTimeout, "the last snapshot to be stored", func() error {
		var err error
		keys, err = c.TimeseriesKeys(ctx, dev.ID.ID)
		if err != nil {
			return err
		}
		if len(keys) == 0 {
			return errors.New("no keys yet")
		}
		points, err := c.Timeseries(ctx, dev.ID.ID, tbclient.TimeseriesQuery{
			Keys: keys, StartMs: 0, EndMs: time.Now().Add(time.Hour).UnixMilli(), Limit: 10,
		})
		if err != nil {
			return err
		}
		lastTS := int64(-1)
		for _, p := range points[protocol.KeySnapshotSeq] {
			if p.Value == "3" {
				lastTS = p.TS
			}
		}
		if lastTS < 0 {
			return errors.New("snapshot 3 not stored yet")
		}
		for _, k := range keys {
			found := false
			for _, p := range points[k] {
				found = found || p.TS == lastTS
			}
			if !found {
				return fmt.Errorf("key %s has no point of the last snapshot yet", k)
			}
		}
		return nil
	})

	if err := c.DeleteAllTimeseries(ctx, dev.ID.ID, keys); err != nil {
		t.Fatalf("DeleteAllTimeseries: %v", err)
	}

	eventually(t, settleTimeout, "every key and point to be gone", func() error {
		left, err := c.TimeseriesKeys(ctx, dev.ID.ID)
		if err != nil {
			return err
		}
		if len(left) != 0 {
			return fmt.Errorf("keys still listed: %v", left)
		}
		points, err := c.Timeseries(ctx, dev.ID.ID, tbclient.TimeseriesQuery{
			Keys: keys, StartMs: 0, EndMs: time.Now().Add(time.Hour).UnixMilli(), Limit: 10,
		})
		if err != nil {
			return err
		}
		for k, p := range points {
			if len(p) > 0 {
				return fmt.Errorf("key %s still has %d point(s)", k, len(p))
			}
		}
		return nil
	})

	if err := c.DeleteDevice(ctx, dev.ID.ID); err != nil {
		t.Fatalf("DeleteDevice: %v", err)
	}
	if _, err := c.GetDeviceByName(ctx, dev.Name); !errors.Is(err, tbclient.ErrNotFound) {
		t.Fatalf("GetDeviceByName after delete: %v, want ErrNotFound", err)
	}
}
