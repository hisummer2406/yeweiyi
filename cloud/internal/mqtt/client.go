package mqtt

import (
	"encoding/json"
	"log"

	paho "github.com/eclipse/paho.mqtt.golang"
	"github.com/redis/go-redis/v9"

	"yeweiyi-cloud/internal/store/db"
)

// Config holds everything needed to connect and start handling messages for one product. Unlike
// BackService.java (one MqttClient instance per physical device, impersonating that device), this
// backend runs a single connection per product and reaches every device through wildcard
// subscriptions (see topics.go) — so there's no per-device deviceName or Will here.
type Config struct {
	BrokerURL  string // e.g. "tcp://localhost:1883" for local dev, the real Aliyun IoT endpoint in prod
	ProductKey string
	ClientID   string
	Username   string
	Password   string
	Queries    *db.Queries
	Redis      *redis.Client
}

type Client struct {
	cfg    Config
	client paho.Client
}

func NewClient(cfg Config) *Client {
	c := &Client{cfg: cfg}

	opts := paho.NewClientOptions().
		AddBroker(cfg.BrokerURL).
		SetClientID(cfg.ClientID).
		SetUsername(cfg.Username).
		SetPassword(cfg.Password).
		SetAutoReconnect(true).
		SetOnConnectHandler(c.onConnect).
		SetConnectionLostHandler(func(_ paho.Client, err error) {
			log.Printf("mqtt: connection lost: %v", err)
		})

	c.client = paho.NewClient(opts)
	return c
}

// Connect blocks until the initial connection attempt completes (success or failure). Subsequent
// reconnects happen automatically (SetAutoReconnect) and re-subscribe via onConnect, since MQTT
// brokers don't remember subscriptions across a clean-session reconnect.
func (c *Client) Connect() error {
	token := c.client.Connect()
	token.Wait()
	return token.Error()
}

func (c *Client) Disconnect() {
	c.client.Disconnect(250)
}

func (c *Client) onConnect(client paho.Client) {
	log.Printf("mqtt: connected, subscribing for product %s", c.cfg.ProductKey)

	if token := client.Subscribe(propertyTopicFilter(c.cfg.ProductKey), 1, c.handlePropertyMessage); token.Wait() && token.Error() != nil {
		log.Printf("mqtt: subscribe property filter failed: %v", token.Error())
	}
	if token := client.Subscribe(eventTopicFilter(c.cfg.ProductKey, IdentifierIsOnline), 1, c.handleOnlineMessage); token.Wait() && token.Error() != nil {
		log.Printf("mqtt: subscribe isOline filter failed: %v", token.Error())
	}
	if token := client.Subscribe(eventTopicFilter(c.cfg.ProductKey, IdentifierMsg), 1, c.handleMsgMessage); token.Wait() && token.Error() != nil {
		log.Printf("mqtt: subscribe msg filter failed: %v", token.Error())
	}
}

func (c *Client) publish(topic string, qos byte, payload any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	token := c.client.Publish(topic, qos, false, body)
	token.Wait()
	return token.Error()
}

// RequestOilcan publishes an (ignored, per BackService.messageArrived) request body — the device
// replies with its next 1-second-cadence snapshot regardless of payload content.
func (c *Client) RequestOilcan(deviceName string) error {
	return c.publish(serviceTopic(c.cfg.ProductKey, deviceName, IdentifierRequestOilcan), 1, struct{}{})
}

func (c *Client) RequestAlarms(deviceName string, r RequestRecord) error {
	return c.publish(serviceTopic(c.cfg.ProductKey, deviceName, IdentifierRequestAlarm), 1, r)
}

func (c *Client) RequestUnloadOil(deviceName string, r RequestRecord) error {
	return c.publish(serviceTopic(c.cfg.ProductKey, deviceName, IdentifierRequestUnloadOil), 1, r)
}

func (c *Client) RequestShift(deviceName string, r RequestRecord) error {
	return c.publish(serviceTopic(c.cfg.ProductKey, deviceName, IdentifierRequestShift), 1, r)
}
