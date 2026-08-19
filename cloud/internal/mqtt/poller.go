package mqtt

import (
	"context"
	"log"
	"time"

	"yeweiyi-cloud/internal/store/db"
)

// DefaultPollInterval sits inside the 10-30s range design doc §4.3 suggests (product-confirmed per
// CLAUDE.md's "MQTT contract") for polling requestOilcan — the device only replies once per
// request, so "realtime" monitoring means the backend re-requests on its own cadence and caches.
const DefaultPollInterval = 20 * time.Second

// Poller periodically re-requests each known online device's oilcan snapshot. It only reads from
// devices — the actual persistence happens in handleOilcanSnapshot when the response arrives on
// the property/post/requestOilcan subscription.
type Poller struct {
	Client   *Client
	Queries  *db.Queries
	Interval time.Duration
}

func (p *Poller) Run(ctx context.Context) {
	interval := p.Interval
	if interval <= 0 {
		interval = DefaultPollInterval
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.pollOnce(ctx)
		}
	}
}

func (p *Poller) pollOnce(ctx context.Context) {
	deviceNames, err := p.Queries.ListOnlineDeviceNames(ctx)
	if err != nil {
		log.Printf("mqtt: poll device list: %v", err)
		return
	}

	for _, deviceName := range deviceNames {
		if err := p.Client.RequestOilcan(deviceName); err != nil {
			log.Printf("mqtt: request oilcan for %s: %v", deviceName, err)
		}
	}
}
