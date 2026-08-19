package mqtt_test

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"testing"
	"time"

	paho "github.com/eclipse/paho.mqtt.golang"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	yqmqtt "yeweiyi-cloud/internal/mqtt"
	"yeweiyi-cloud/internal/store/db"
)

func testBrokerURL() string {
	if v := os.Getenv("MQTT_BROKER_URL"); v != "" {
		return v
	}
	return "tcp://localhost:1883"
}

func testDatabaseURL() string {
	if v := os.Getenv("DATABASE_URL"); v != "" {
		return v
	}
	return "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable"
}

func testRedisAddr() string {
	if v := os.Getenv("REDIS_ADDR"); v != "" {
		return v
	}
	return "localhost:6379"
}

// deviceSim is a second, independent paho client standing in for a real device's MqttClient
// (BackService.java) — it publishes exactly the raw JSON bytes a real device would, hand-written
// rather than produced by marshaling our own payload structs, so these tests actually catch a wrong
// json tag rather than just checking our code agrees with itself.
type deviceSim struct {
	client paho.Client
}

func newDeviceSim(t *testing.T) *deviceSim {
	t.Helper()
	opts := paho.NewClientOptions().AddBroker(testBrokerURL()).SetClientID("test-device-sim-" + t.Name())
	c := paho.NewClient(opts)
	if token := c.Connect(); token.Wait() && token.Error() != nil {
		t.Fatalf("device sim connect: %v", token.Error())
	}
	t.Cleanup(func() { c.Disconnect(250) })
	return &deviceSim{client: c}
}

func (d *deviceSim) publish(t *testing.T, topic string, payload string) {
	t.Helper()
	token := d.client.Publish(topic, 1, false, []byte(payload))
	if token.Wait() && token.Error() != nil {
		t.Fatalf("device sim publish %s: %v", topic, token.Error())
	}
}

const testProductKey = "testpk"

func TestClient_AlarmIngestion(t *testing.T) {
	ctx := context.Background()

	pool, err := pgxpool.New(ctx, testDatabaseURL())
	if err != nil {
		t.Fatalf("connect postgres: %v", err)
	}
	t.Cleanup(pool.Close)

	rdb := redis.NewClient(&redis.Options{Addr: testRedisAddr()})
	t.Cleanup(func() { rdb.Close() })

	queries := db.New(pool)
	const deviceName = "mqtt-test-device-alarm"
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), `DELETE FROM alarms WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup alarms: %v", err)
		}
		if _, err := pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup devices: %v", err)
		}
	})

	c := yqmqtt.NewClient(yqmqtt.Config{
		BrokerURL:  testBrokerURL(),
		ProductKey: testProductKey,
		ClientID:   "test-backend-" + t.Name(),
		Queries:    queries,
		Redis:      rdb,
	})
	if err := c.Connect(); err != nil {
		t.Fatalf("client connect: %v", err)
	}
	t.Cleanup(c.Disconnect)

	sim := newDeviceSim(t)

	// Hand-written JSON matching Alarm.java's actual Gson output: guanHao/jieZhi (Java field
	// names), not guanhao/jiezhi (the DB column names) — see payloads.go's AlarmPayload doc.
	topic := fmt.Sprintf("sys/%s/%s/thing/event/property/post/requestAlarm", testProductKey, deviceName)
	payload := `[{"id":7,"time":"2026-08-15 14:35","info":"1号罐高液位报警","guanHao":"1","jieZhi":"柴油"}]`
	sim.publish(t, topic, payload)

	deadline := time.Now().Add(5 * time.Second)
	var found bool
	for time.Now().Before(deadline) {
		rows, qerr := pool.Query(ctx, `SELECT info, guanhao, jiezhi FROM alarms WHERE device_name = $1 AND device_local_id = 7`, deviceName)
		if qerr != nil {
			t.Fatalf("query alarms: %v", qerr)
		}
		if rows.Next() {
			var info, guanhao, jiezhi string
			if err := rows.Scan(&info, &guanhao, &jiezhi); err != nil {
				rows.Close()
				t.Fatalf("scan alarm row: %v", err)
			}
			rows.Close()
			if info != "1号罐高液位报警" || guanhao != "1" || jiezhi != "柴油" {
				t.Fatalf("unexpected alarm row: info=%q guanhao=%q jiezhi=%q", info, guanhao, jiezhi)
			}
			found = true
			break
		}
		rows.Close()
		time.Sleep(100 * time.Millisecond)
	}
	if !found {
		t.Fatalf("alarm row never appeared in postgres")
	}
}

func TestClient_OnlineStatus(t *testing.T) {
	ctx := context.Background()

	pool, err := pgxpool.New(ctx, testDatabaseURL())
	if err != nil {
		t.Fatalf("connect postgres: %v", err)
	}
	t.Cleanup(pool.Close)

	rdb := redis.NewClient(&redis.Options{Addr: testRedisAddr()})
	t.Cleanup(func() { rdb.Close() })

	queries := db.New(pool)
	const deviceName = "mqtt-test-device-online"
	t.Cleanup(func() {
		if _, err := pool.Exec(context.Background(), `DELETE FROM devices WHERE device_name = $1`, deviceName); err != nil {
			t.Errorf("cleanup devices: %v", err)
		}
	})

	c := yqmqtt.NewClient(yqmqtt.Config{
		BrokerURL:  testBrokerURL(),
		ProductKey: testProductKey,
		ClientID:   "test-backend-" + t.Name(),
		Queries:    queries,
		Redis:      rdb,
	})
	if err := c.Connect(); err != nil {
		t.Fatalf("client connect: %v", err)
	}
	t.Cleanup(c.Disconnect)

	sim := newDeviceSim(t)
	topic := fmt.Sprintf("sys/%s/%s/thing/event/isOline/post", testProductKey, deviceName)
	sim.publish(t, topic, `{"isOline":true}`)

	deadline := time.Now().Add(5 * time.Second)
	var isOnline bool
	var seen bool
	for time.Now().Before(deadline) {
		dev, err := queries.GetDevice(ctx, deviceName)
		if err == nil {
			isOnline = dev.IsOnline
			seen = true
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !seen {
		t.Fatalf("device row never appeared")
	}
	if !isOnline {
		t.Fatalf("expected is_online=true, got false")
	}
}

func TestClient_OilcanSnapshotCaching(t *testing.T) {
	ctx := context.Background()

	pool, err := pgxpool.New(ctx, testDatabaseURL())
	if err != nil {
		t.Fatalf("connect postgres: %v", err)
	}
	t.Cleanup(pool.Close)

	rdb := redis.NewClient(&redis.Options{Addr: testRedisAddr()})
	t.Cleanup(func() { rdb.Close() })

	const deviceName = "mqtt-test-device-oilcan"
	t.Cleanup(func() {
		rdb.Del(context.Background(), "oilcan:latest:"+deviceName)
	})

	c := yqmqtt.NewClient(yqmqtt.Config{
		BrokerURL:  testBrokerURL(),
		ProductKey: testProductKey,
		ClientID:   "test-backend-" + t.Name(),
		Queries:    db.New(pool),
		Redis:      rdb,
	})
	if err := c.Connect(); err != nil {
		t.Fatalf("client connect: %v", err)
	}
	t.Cleanup(c.Disconnect)

	sim := newDeviceSim(t)
	topic := fmt.Sprintf("sys/%s/%s/thing/event/property/post/requestOilcan", testProductKey, deviceName)
	payload := `[{"id":1,"oiltype":"0号柴油","oilLevel":123.4,"connect":true,"deviceName":"` + deviceName + `"}]`
	sim.publish(t, topic, payload)

	deadline := time.Now().Add(5 * time.Second)
	var cached string
	var found bool
	for time.Now().Before(deadline) {
		v, err := rdb.Get(ctx, "oilcan:latest:"+deviceName).Result()
		if err == nil {
			cached = v
			found = true
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if !found {
		t.Fatalf("oilcan snapshot never cached")
	}

	var decoded []map[string]any
	if err := json.Unmarshal([]byte(cached), &decoded); err != nil {
		t.Fatalf("cached value isn't valid JSON: %v", err)
	}
	if len(decoded) != 1 || decoded[0]["oiltype"] != "0号柴油" {
		t.Fatalf("unexpected cached payload: %s", cached)
	}
}
