// Command loadtest drives a repeatable capacity test against a ThingsBoard installation: it
// provisions N simulated tank terminals, connects them over MQTT, publishes real-shaped 设备协议 V1
// snapshots on the production cadence, and measures what comes out the other side.
//
// GitHub issue #5 asks for capacity to be *demonstrated* rather than estimated from an average
// message rate. The durable deliverable is this harness — it outlives whatever hardware happens to
// be available today, and the numbers it prints are only meaningful together with the machine they
// were produced on, which it therefore prints too.
//
//	loadtest -devices 1000 -duration 5m
//	loadtest -devices 1000 -duration 10m -reconnect-pct 100 -reconnect-at 5m   # 重连风暴
//
// It provisions its own devices under a dedicated name prefix and deletes them afterwards. It is
// safe against an installation holding real devices, but it is emphatically NOT safe to run against
// production while people depend on it: the whole point is to saturate something.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"math"
	"os"
	"os/signal"
	"sort"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"yeweiyi-cloud/internal/devicesim"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/tbclient"
)

func main() { os.Exit(run()) }

type options struct {
	devices       int
	tanks         int
	interval      time.Duration
	duration      time.Duration
	ramp          time.Duration
	latencySample int
	reconnectPct  int
	reconnectAt   time.Duration
	provisionPar  int
	keep          bool
	prefix        string
	mqttURL       string
	caFile        string
}

func run() int {
	var o options
	tbURL := flag.String("tb", envOr("TB_BASE_URL", "http://localhost:8081"), "ThingsBoard base URL")
	user := flag.String("user", envOr("TB_USERNAME", "tenant@thingsboard.org"), "tenant administrator")
	pass := flag.String("pass", envOr("TB_PASSWORD", "tenant"), "tenant administrator password")
	flag.StringVar(&o.mqttURL, "mqtt", envOr("TB_MQTT_URL", "tcp://localhost:1884"), "MQTT transport (tcp:// or ssl://)")
	flag.StringVar(&o.caFile, "cacert", envOr("TB_MQTT_CACERT", ""), "CA file, required for ssl://")
	flag.IntVar(&o.devices, "devices", 100, "number of simulated devices")
	flag.IntVar(&o.tanks, "tanks", 2, "tanks per device (drives datapoints per snapshot)")
	flag.DurationVar(&o.interval, "interval", 30*time.Second, "snapshot cadence per device")
	flag.DurationVar(&o.duration, "duration", 2*time.Minute, "how long to hold the load")
	flag.DurationVar(&o.ramp, "ramp", 30*time.Second, "spread connections over this window")
	flag.IntVar(&o.latencySample, "latency-sample", 10, "devices watched over WebSocket for end-to-end latency")
	flag.IntVar(&o.reconnectPct, "reconnect-pct", 0, "percentage of devices to drop and reconnect at once")
	flag.DurationVar(&o.reconnectAt, "reconnect-at", 0, "when into the run to trigger the reconnect storm")
	flag.IntVar(&o.provisionPar, "provision-parallel", 16, "concurrent provisioning requests")
	flag.BoolVar(&o.keep, "keep", false, "keep the provisioned devices (for inspecting afterwards)")
	flag.StringVar(&o.prefix, "prefix", "loadtest", "device name prefix")
	flag.Parse()

	c, err := tbclient.New(tbclient.Config{BaseURL: *tbURL, Username: *user, Password: *pass})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := c.Login(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "ThingsBoard 登录失败：%v\n", err)
		return 1
	}

	h := &harness{c: c, o: o}
	return h.execute(ctx)
}

type harness struct {
	c *tbclient.Client
	o options

	devices []*deviceEntry

	// Counters are atomic because every simulated device writes to them from its own goroutine.
	published atomic.Int64
	pubErrors atomic.Int64
	connErrs  atomic.Int64

	mu      sync.Mutex
	latency []time.Duration
	dropped int
}

type deviceEntry struct {
	name  string
	tbID  string
	token string

	// sim is replaced wholesale by the reconnect storm while the publish goroutine is still using
	// it, so it must not be touched without the lock. The first version of this had a bare field
	// and crashed with a nil dereference inside PublishSnapshot the moment the storm fired — the
	// storm sets it to nil to reconnect, and the publisher read it in between.
	mu  sync.Mutex
	sim *devicesim.Device
}

func (d *deviceEntry) client() *devicesim.Device {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.sim
}

func (d *deviceEntry) setClient(s *devicesim.Device) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.sim = s
}

// connected reports whether this device currently holds a session.
func (d *deviceEntry) connected() bool { return d.client() != nil }

func (h *harness) execute(ctx context.Context) int {
	fmt.Printf("=== 压测：%d 台设备 × %d 罐，每 %s 一条快照，持续 %s ===\n",
		h.o.devices, h.o.tanks, h.o.interval, h.o.duration)
	dpPerSnapshot := len(protocol.SnapshotKeys(h.o.tanks))
	expectedRate := float64(h.o.devices) * float64(dpPerSnapshot) / h.o.interval.Seconds()
	fmt.Printf("    每条快照 %d 个数据点 → 目标稳态 %.0f 数据点/秒\n\n", dpPerSnapshot, expectedRate)

	if !h.provision(ctx) {
		h.cleanup()
		return 1
	}
	if !h.connect(ctx) {
		h.cleanup()
		return 1
	}

	watchCtx, stopWatch := context.WithCancel(ctx)
	h.watchLatency(watchCtx)

	runCtx, stopRun := context.WithTimeout(ctx, h.o.duration)
	defer stopRun()
	h.publishLoop(runCtx)

	if h.o.reconnectPct > 0 {
		// nothing: the storm is scheduled inside publishLoop so it happens mid-run
	}

	stopWatch()
	h.report()
	h.cleanup()

	if h.pubErrors.Load() > 0 || h.connErrs.Load() > 0 {
		return 1
	}
	return 0
}

func (h *harness) provision(ctx context.Context) bool {
	fmt.Printf("==> 开通 %d 台设备（并发 %d）\n", h.o.devices, h.o.provisionPar)
	start := time.Now()

	h.devices = make([]*deviceEntry, h.o.devices)
	sem := make(chan struct{}, h.o.provisionPar)
	var wg sync.WaitGroup
	var failed atomic.Int64
	stamp := time.Now().UnixNano()

	for i := 0; i < h.o.devices; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			name := fmt.Sprintf("%s-%d-%05d", h.o.prefix, stamp, i)
			dev, err := h.c.EnsureDevice(ctx, name, tbclient.DefaultDeviceProfile)
			if err != nil {
				failed.Add(1)
				return
			}
			creds, err := h.c.GetDeviceCredentials(ctx, dev.ID.ID)
			if err != nil {
				failed.Add(1)
				return
			}
			h.devices[i] = &deviceEntry{name: name, tbID: dev.ID.ID, token: creds.CredentialsID}
		}(i)

		if i%500 == 499 {
			fmt.Printf("    已提交 %d/%d\n", i+1, h.o.devices)
		}
	}
	wg.Wait()

	// Compact out the failures so the rest of the run works with a dense slice.
	live := h.devices[:0]
	for _, d := range h.devices {
		if d != nil {
			live = append(live, d)
		}
	}
	h.devices = live

	fmt.Printf("    完成 %d 台，失败 %d 台，用时 %s（%.0f 台/秒）\n\n",
		len(h.devices), failed.Load(), time.Since(start).Round(time.Millisecond),
		float64(len(h.devices))/time.Since(start).Seconds())

	if len(h.devices) == 0 {
		fmt.Fprintln(os.Stderr, "!!! 没有成功开通任何设备")
		return false
	}
	return true
}

func (h *harness) connect(ctx context.Context) bool {
	fmt.Printf("==> 建立 %d 条 MQTT 连接（%s 内均匀铺开）\n", len(h.devices), h.o.ramp)
	start := time.Now()

	// Ramping matters: connecting a thousand sessions in one instant is a reconnect storm, which is
	// a *separate* scenario measured deliberately later. Conflating the two would make the steady
	// state look worse than it is.
	gap := time.Duration(0)
	if h.o.ramp > 0 && len(h.devices) > 1 {
		gap = h.o.ramp / time.Duration(len(h.devices))
	}

	var wg sync.WaitGroup
	for i, d := range h.devices {
		wg.Add(1)
		go func(i int, d *deviceEntry) {
			defer wg.Done()
			if gap > 0 {
				select {
				case <-ctx.Done():
					return
				case <-time.After(time.Duration(i) * gap):
				}
			}
			if err := h.connectOne(ctx, d); err != nil {
				h.connErrs.Add(1)
			}
		}(i, d)
	}
	wg.Wait()

	ok := 0
	for _, d := range h.devices {
		if d.connected() {
			ok++
		}
	}
	fmt.Printf("    连接成功 %d 台，失败 %d 台，用时 %s\n\n", ok, h.connErrs.Load(), time.Since(start).Round(time.Millisecond))
	return ok > 0
}

func (h *harness) connectOne(ctx context.Context, d *deviceEntry) error {
	sim := devicesim.New(devicesim.Config{
		BrokerURL:   h.o.mqttURL,
		CACertFile:  h.o.caFile,
		AccessToken: d.token,
		Tanks:       h.o.tanks,
		// The harness drives publishing itself so every snapshot's send time is known; the
		// simulator's own timer would hide that.
		SnapshotInterval: time.Hour,
	})
	cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	if err := sim.Connect(cctx); err != nil {
		return err
	}
	d.setClient(sim)
	return nil
}

func (h *harness) publishLoop(ctx context.Context) {
	fmt.Printf("==> 保持负载 %s\n", h.o.duration)
	start := time.Now()

	var stormOnce sync.Once
	var wg sync.WaitGroup

	for i, d := range h.devices {
		if !d.connected() {
			continue
		}
		wg.Add(1)
		go func(i int, d *deviceEntry) {
			defer wg.Done()
			// Stagger within the interval so a thousand devices do not all publish on the same
			// tick — real devices are not synchronised, and pretending they are would invent a
			// thundering herd that does not exist in the field.
			offset := time.Duration(int64(h.o.interval) * int64(i) / int64(max(1, len(h.devices))))
			select {
			case <-ctx.Done():
				return
			case <-time.After(offset):
			}

			t := time.NewTicker(h.o.interval)
			defer t.Stop()
			h.publishOne(d)
			for {
				select {
				case <-ctx.Done():
					return
				case <-t.C:
					h.publishOne(d)
				}
			}
		}(i, d)
	}

	// Progress ticker, and the reconnect storm if one was asked for.
	progress := time.NewTicker(15 * time.Second)
	defer progress.Stop()
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-progress.C:
				el := time.Since(start)
				fmt.Printf("    %5s  已发布 %d，错误 %d，实时速率 %.0f 数据点/秒\n",
					el.Round(time.Second), h.published.Load(), h.pubErrors.Load(),
					float64(h.published.Load()*int64(len(protocol.SnapshotKeys(h.o.tanks))))/el.Seconds())
				if h.o.reconnectPct > 0 && h.o.reconnectAt > 0 && el >= h.o.reconnectAt {
					stormOnce.Do(func() { go h.reconnectStorm(ctx) })
				}
			}
		}
	}()

	wg.Wait()
	fmt.Println()
}

func (h *harness) publishOne(d *deviceEntry) {
	sim := d.client()
	if sim == nil {
		// Mid-reconnect. Not an error: a real device cannot publish while its session is down
		// either, and counting it as a publish failure would make the storm look like data loss
		// when it is simply a gap.
		return
	}
	if _, err := sim.PublishSnapshot(); err != nil {
		h.pubErrors.Add(1)
		return
	}
	h.published.Add(1)
}

// reconnectStorm disconnects a share of the fleet and brings it all back at once — the scenario
// that actually breaks brokers, and the one a steady-state average hides completely.
func (h *harness) reconnectStorm(ctx context.Context) {
	n := len(h.devices) * h.o.reconnectPct / 100
	if n <= 0 {
		return
	}
	fmt.Printf("\n    *** 重连风暴：同时断开并重连 %d 台（%d%%）***\n", n, h.o.reconnectPct)
	start := time.Now()

	var wg sync.WaitGroup
	for _, d := range h.devices[:n] {
		if sim := d.client(); sim != nil {
			sim.Disconnect()
		}
	}
	h.mu.Lock()
	h.dropped = n
	h.mu.Unlock()

	// No ramp here on purpose: this is the worst case, everything coming back in the same instant.
	for _, d := range h.devices[:n] {
		wg.Add(1)
		go func(d *deviceEntry) {
			defer wg.Done()
			d.setClient(nil)
			if err := h.connectOne(ctx, d); err != nil {
				h.connErrs.Add(1)
			}
		}(d)
	}
	wg.Wait()

	back := 0
	for _, d := range h.devices[:n] {
		if d.connected() {
			back++
		}
	}
	fmt.Printf("    *** 恢复 %d/%d 台，用时 %s ***\n\n", back, n, time.Since(start).Round(time.Millisecond))
}

// watchLatency measures true end-to-end latency on a sample: the device's own publish timestamp
// against the moment the backend sees the value over ThingsBoard's WebSocket. Both come from this
// one process's clock, so there is no skew to correct for.
func (h *harness) watchLatency(ctx context.Context) {
	n := min(h.o.latencySample, len(h.devices))
	if n <= 0 {
		return
	}
	fmt.Printf("==> 采样 %d 台设备测量端到端延迟（WebSocket）\n\n", n)

	for _, d := range h.devices[:n] {
		stream, err := h.c.SubscribeTelemetry(ctx, d.tbID, []string{protocol.KeySnapshotSeq})
		if err != nil {
			continue
		}
		go func(stream *tbclient.TelemetryStream) {
			defer stream.Close()
			for {
				select {
				case <-ctx.Done():
					return
				case <-stream.Err:
					return
				case u, ok := <-stream.Updates:
					if !ok {
						return
					}
					if u.TS == 0 {
						continue
					}
					// TS is the device's publish time; now() is when it became visible.
					lat := time.Since(time.UnixMilli(u.TS))
					if lat < 0 || lat > time.Minute {
						continue // clearly a replayed or stale point, not a latency measurement
					}
					h.mu.Lock()
					h.latency = append(h.latency, lat)
					h.mu.Unlock()
				}
			}
		}(stream)
	}
}

func (h *harness) report() {
	dp := int64(len(protocol.SnapshotKeys(h.o.tanks)))
	pub := h.published.Load()

	fmt.Println("=== 结果 ===")
	fmt.Printf("  机器：%s\n", hostSpec())
	fmt.Printf("  设备：%d 台 × %d 罐，间隔 %s，时长 %s\n", len(h.devices), h.o.tanks, h.o.interval, h.o.duration)
	fmt.Printf("  快照：发布 %d 条，失败 %d 条\n", pub, h.pubErrors.Load())
	fmt.Printf("  数据点：约 %d 个，平均 %.0f 点/秒\n", pub*dp, float64(pub*dp)/h.o.duration.Seconds())
	fmt.Printf("  连接错误：%d\n", h.connErrs.Load())

	h.mu.Lock()
	lat := append([]time.Duration(nil), h.latency...)
	dropped := h.dropped
	h.mu.Unlock()

	if dropped > 0 {
		fmt.Printf("  重连风暴：断开并重连 %d 台\n", dropped)
	}
	if len(lat) == 0 {
		fmt.Println("  端到端延迟：无样本（采样设备未收到数据）")
		return
	}
	sort.Slice(lat, func(i, j int) bool { return lat[i] < lat[j] })
	fmt.Printf("  端到端延迟（%d 个样本）：p50 %s  p95 %s  p99 %s  max %s\n",
		len(lat), pct(lat, 50).Round(time.Millisecond), pct(lat, 95).Round(time.Millisecond),
		pct(lat, 99).Round(time.Millisecond), lat[len(lat)-1].Round(time.Millisecond))
}

func (h *harness) cleanup() {
	fmt.Println()
	for _, d := range h.devices {
		if d == nil {
			continue
		}
		if sim := d.client(); sim != nil {
			sim.Disconnect()
		}
	}
	if h.o.keep {
		fmt.Printf("已保留 %d 台测试设备（-keep）。清理：按名字前缀 %s- 删除。\n", len(h.devices), h.o.prefix)
		return
	}

	fmt.Printf("==> 清理 %d 台测试设备\n", len(h.devices))
	sem := make(chan struct{}, h.o.provisionPar)
	var wg sync.WaitGroup
	var failed atomic.Int64
	for _, d := range h.devices {
		if d == nil || d.tbID == "" {
			continue
		}
		wg.Add(1)
		go func(d *deviceEntry) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			// Fresh context: the run's context may already be cancelled, and leaving a thousand
			// devices behind because the operator pressed Ctrl-C would be worse than a slow exit.
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			if err := h.c.DeleteDevice(ctx, d.tbID); err != nil {
				failed.Add(1)
			}
		}(d)
	}
	wg.Wait()
	if failed.Load() > 0 {
		fmt.Printf("    %d 台删除失败，请按前缀 %s- 手动清理\n", failed.Load(), h.o.prefix)
	} else {
		fmt.Println("    完成")
	}
}

func pct(sorted []time.Duration, p int) time.Duration {
	if len(sorted) == 0 {
		return 0
	}
	i := int(math.Ceil(float64(p)/100*float64(len(sorted)))) - 1
	if i < 0 {
		i = 0
	}
	if i >= len(sorted) {
		i = len(sorted) - 1
	}
	return sorted[i]
}

// hostSpec records what the numbers were produced on. A throughput figure without the machine
// behind it is not a result, it is a rumour.
func hostSpec() string {
	type spec struct {
		OS   string `json:"os"`
		CPUs int    `json:"cpus"`
	}
	b, _ := json.Marshal(spec{OS: osName(), CPUs: numCPU()})
	return string(b)
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
