// Command devicesim stands in for a real device's MqttClient (BackService.java) against the local
// dev Mosquitto broker (docker-compose.yml). It publishes hand-written JSON literals using the
// device's real wire-format field names (guanHao/jieZhi, beginHeigth/endHeigth, ...) rather than
// marshaling this repo's own payload structs — the same reasoning as internal/mqtt/client_test.go's
// deviceSim: this exercises internal/mqtt's actual json tags instead of just agreeing with itself.
//
// Usage:
//
//	go run ./cmd/devicesim alarm -device=dev1 -id=1
//	go run ./cmd/devicesim unloadoil -device=dev1 -id=1
//	go run ./cmd/devicesim shift -device=dev1 -id=1
//	go run ./cmd/devicesim oilcan -device=dev1
//	go run ./cmd/devicesim online -device=dev1
//	go run ./cmd/devicesim offline -device=dev1
//	go run ./cmd/devicesim msg -device=dev1 -text="1号罐高液位报警"
//	go run ./cmd/devicesim watch -device=dev1   # prints thing/service/* requests published TO this
//	                                             # device (the poller's requestOilcan ticks, and any
//	                                             # write-back command once internal/mqtt implements
//	                                             # queue.Publisher) instead of publishing anything
//
// -broker defaults to tcp://localhost:1883 (docker-compose.yml's mosquitto service). -productKey
// defaults to $MQTT_PRODUCT_KEY, falling back to "devtest" — pass the same value the server was
// started with (see cmd/server's MQTT_PRODUCT_KEY) or messages won't land on a topic it subscribes
// to.
package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"time"

	paho "github.com/eclipse/paho.mqtt.golang"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	mode := os.Args[1]

	fs := flag.NewFlagSet(mode, flag.ExitOnError)
	broker := fs.String("broker", envOr("MQTT_BROKER_URL", "tcp://localhost:1883"), "MQTT broker URL")
	productKey := fs.String("productKey", envOr("MQTT_PRODUCT_KEY", "devtest"), "product key (must match the server's MQTT_PRODUCT_KEY)")
	device := fs.String("device", "dev1", "deviceName")
	id := fs.Int("id", 1, "device-local record id")
	text := fs.String("text", "1号罐高液位报警", "msg mode: the alarm text")
	fs.Parse(os.Args[2:])

	c := connect(*broker)
	defer c.Disconnect(250)

	switch mode {
	case "alarm":
		topic := propertyTopic(*productKey, *device, "requestAlarm")
		now := time.Now().Format("2006-01-02 15:04")
		payload := fmt.Sprintf(`[{"id":%d,"time":%q,"info":%q,"guanHao":"1","jieZhi":"0号柴油"}]`,
			*id, now, *text)
		publish(c, topic, payload)

	case "unloadoil":
		topic := propertyTopic(*productKey, *device, "requestUnloadOil")
		now := time.Now().Format("2006-01-02 15:04")
		payload := fmt.Sprintf(`[{"id":%d,"oilId":1,"jieZhi":"0号柴油","time":%q,"beginHeigth":120.5,"endHeigth":30.2,"beginVolum":5000.0,"endVolum":1200.0,"beginWeight":4300.0,"endWeight":1030.0}]`,
			*id, now)
		publish(c, topic, payload)

	case "shift":
		topic := propertyTopic(*productKey, *device, "requestShift")
		begin := time.Now().Add(-8 * time.Hour).Format("2006-01-02 15:04")
		end := time.Now().Format("2006-01-02 15:04")
		payload := fmt.Sprintf(`[{"id":%d,"workId":101,"time":%q,"endTime":%q,"info":"{\"summary\":\"班次记录\"}"}]`,
			*id, begin, end)
		publish(c, topic, payload)

	case "oilcan":
		topic := propertyTopic(*productKey, *device, "requestOilcan")
		payload := fmt.Sprintf(`[{"id":1,"oiltype":"0号柴油","oilLevel":88.8,"connect":true,"deviceName":%q}]`, *device)
		publish(c, topic, payload)

	case "online":
		publish(c, eventTopic(*productKey, *device, "isOline"), `{"isOline":true}`)

	case "offline":
		publish(c, eventTopic(*productKey, *device, "isOline"), `{"isOline":false}`)

	case "msg":
		payload := fmt.Sprintf(`{"msg":%q}`, *text)
		publish(c, eventTopic(*productKey, *device, "msg"), payload)

	case "watch":
		topic := fmt.Sprintf("sys/%s/%s/thing/service/+", *productKey, *device)
		done := make(chan struct{})
		if token := c.Subscribe(topic, 1, func(_ paho.Client, msg paho.Message) {
			fmt.Printf("%s  topic=%s  payload=%s\n", time.Now().Format(time.RFC3339), msg.Topic(), string(msg.Payload()))
		}); token.Wait() && token.Error() != nil {
			log.Fatalf("subscribe: %v", token.Error())
		}
		fmt.Printf("watching %s (ctrl-C to stop)...\n", topic)
		<-done

	default:
		usage()
	}
}

func propertyTopic(productKey, deviceName, identifier string) string {
	return fmt.Sprintf("sys/%s/%s/thing/event/property/post/%s", productKey, deviceName, identifier)
}

func eventTopic(productKey, deviceName, identifier string) string {
	return fmt.Sprintf("sys/%s/%s/thing/event/%s/post", productKey, deviceName, identifier)
}

func connect(broker string) paho.Client {
	opts := paho.NewClientOptions().AddBroker(broker).SetClientID(fmt.Sprintf("devicesim-%d", time.Now().UnixNano()))
	c := paho.NewClient(opts)
	if token := c.Connect(); token.Wait() && token.Error() != nil {
		log.Fatalf("connect %s: %v", broker, token.Error())
	}
	return c
}

func publish(c paho.Client, topic, payload string) {
	token := c.Publish(topic, 1, false, []byte(payload))
	token.Wait()
	if token.Error() != nil {
		log.Fatalf("publish %s: %v", topic, token.Error())
	}
	fmt.Printf("published to %s\n%s\n", topic, payload)
	time.Sleep(300 * time.Millisecond) // let the broker forward it before the process exits
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: devicesim <alarm|unloadoil|shift|oilcan|online|offline|msg|watch> [-broker=...] [-productKey=...] [-device=...] [-id=N] [-text=...]")
	os.Exit(2)
}
