package ingest_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/purge"
	"yeweiyi-cloud/internal/tbclient"
)

// TestPurge_RemovesALiveDeviceEndToEnd runs the device purge (cloud#83) against the real stack: a device
// with a live subscription and a stored alarm is purged through real ThingsBoard, its subscription
// stops, its rows and its ThingsBoard entity are gone, and an alarm it raises afterwards lands nowhere.
func TestPurge_RemovesALiveDeviceEndToEnd(t *testing.T) {
	s := newStack(t, control.Config{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	t.Cleanup(func() {
		s.pool.Exec(context.Background(), `DELETE FROM device_purges WHERE device_name = $1`, s.device)
	})
	t.Cleanup(func() { s.pool.Exec(context.Background(), `DELETE FROM alarm_events WHERE device_name = $1`, s.device) })

	s.ing.StartDevice(ctx, s.device)
	waitForSubscription(t, s, ctx)
	if _, err := s.sim.PublishSnapshot(); err != nil {
		t.Fatal(err)
	}
	if _, err := s.sim.RaiseAlarm(1, protocol.AlarmHighHighLevel, protocol.SeverityCritical, "before purge", 1980); err != nil {
		t.Fatal(err)
	}
	alarms := func() int {
		var n int
		s.pool.QueryRow(context.Background(), `SELECT COUNT(*) FROM alarm_events WHERE device_name = $1`, s.device).Scan(&n)
		return n
	}
	deadline := time.Now().Add(20 * time.Second)
	for alarms() == 0 {
		if time.Now().After(deadline) {
			t.Fatal("the alarm never reached the business database")
		}
		time.Sleep(200 * time.Millisecond)
	}

	var id int64
	if err := s.pool.QueryRow(ctx, `INSERT INTO device_purges (device_name, reason, requested_username) VALUES ($1, 'e2e', 'tester') RETURNING id`,
		s.device).Scan(&id); err != nil {
		t.Fatal(err)
	}
	runner := purge.New(purge.Config{Pool: s.pool, Queries: s.q, TB: s.tb, StopDevice: s.ing.StopDevice, Logf: t.Logf})
	if err := runner.Run(ctx, id); err != nil {
		t.Fatalf("purge: %v", err)
	}
	task, err := s.q.GetDevicePurge(ctx, id)
	if err != nil || task.Status != purge.StatusCompleted {
		t.Fatalf("task: %+v %v", task, err)
	}
	if n := alarms(); n != 0 {
		t.Errorf("%d alarm row(s) left", n)
	}
	if _, err := s.tb.GetDeviceByName(ctx, s.device); !errors.Is(err, tbclient.ErrNotFound) {
		t.Errorf("ThingsBoard lookup after purge: %v, want ErrNotFound", err)
	}
	deadline = time.Now().Add(5 * time.Second)
	for {
		active := false
		for _, n := range s.ing.ActiveDevices() {
			active = active || n == s.device
		}
		if !active {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the subscription is still running after the purge")
		}
		time.Sleep(100 * time.Millisecond)
	}

	// The device keeps trying: its credentials are gone, and nothing it sends is stored.
	_, _ = s.sim.RaiseAlarm(1, protocol.AlarmHighHighLevel, protocol.SeverityCritical, "after purge", 1990)
	time.Sleep(3 * time.Second)
	if n := alarms(); n != 0 {
		t.Errorf("an alarm raised after the purge was stored (%d row(s))", n)
	}
}
