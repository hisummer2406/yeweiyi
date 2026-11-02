package tbclient_test

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"yeweiyi-cloud/internal/devicesim"
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

	var keys []string
	eventually(t, settleTimeout, "telemetry keys to appear", func() error {
		var err error
		keys, err = c.TimeseriesKeys(ctx, dev.ID.ID)
		if err != nil {
			return err
		}
		if len(keys) == 0 {
			return errors.New("no keys yet")
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
