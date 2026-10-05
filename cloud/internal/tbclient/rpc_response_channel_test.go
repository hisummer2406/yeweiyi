package tbclient_test

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	paho "github.com/eclipse/paho.mqtt.golang"

	"yeweiyi-cloud/internal/protocol"
)

// TestTwoWayRPC_SecondDeviceResponseNeverReachesTheBackend settles a question that
// docs/protocol-v1-open-items.md §A-1 had to record as *inferred*: what happens when a device
// answers one two-way RPC twice on the same response topic — an ACK first, then its terminal result
// — instead of publishing the result as a `cmdResult` telemetry event.
//
// It matters because that is exactly what the Android device does today
// (zhongchengyeweiyi RpcDispatcher.dispatch(): publishAck at :88, then again at :104 with the
// handler's outcome), and the whole cloud-side state machine assumes the outcome arrives on the
// telemetry stream instead. If the second response did reach the caller, A-1 would be a naming
// disagreement. If it does not, A-1 means no command can ever reach a terminal state on real
// hardware — an entirely different severity.
//
// Reviewers rightly refused to accept the inference. This test answers it by measurement, and it is
// kept rather than deleted so that a future ThingsBoard upgrade changing the behaviour is noticed
// here rather than in the field.
//
// The device is a raw paho client rather than internal/devicesim on purpose: devicesim is this
// project's *reference* implementation and already does the correct thing. Teaching it to
// misbehave would put the wrong behaviour behind a flag that someone could later mistake for a
// supported mode.
func TestTwoWayRPC_SecondDeviceResponseNeverReachesTheBackend(t *testing.T) {
	c := newTestClient(t)
	ctx := context.Background()
	dev, token := provisionDevice(t, c)

	// Watch telemetry too, so the conclusion is "nothing delivered the terminal status by ANY
	// channel the backend reads", not merely "the REST call returned the first answer".
	stream, err := c.SubscribeTelemetry(ctx, dev.ID.ID, nil)
	if err != nil {
		t.Fatalf("SubscribeTelemetry: %v", err)
	}
	defer stream.Close()
	drainInitial(stream)

	commandID := newCommandID()

	opts := paho.NewClientOptions().
		AddBroker(tbMQTTURL()).
		SetClientID(fmt.Sprintf("rpc-double-%d", time.Now().UnixNano())).
		SetUsername(token).
		SetConnectTimeout(20 * time.Second)

	var (
		mu        sync.Mutex
		published []string
	)
	secondSent := make(chan struct{})

	opts.SetOnConnectHandler(func(cl paho.Client) {
		cl.Subscribe(protocol.RPCRequestTopicFilter, 1, func(_ paho.Client, msg paho.Message) {
			requestID := msg.Topic()[strings.LastIndexByte(msg.Topic(), '/')+1:]
			topic := protocol.RPCResponseTopicPfx + requestID

			ack, _ := json.Marshal(protocol.Ack{
				CommandID: commandID,
				Status:    protocol.CmdStatusReceived,
				TSMs:      time.Now().UnixMilli(),
			})
			cl.Publish(topic, 1, false, ack).WaitTimeout(10 * time.Second)
			mu.Lock()
			published = append(published, protocol.CmdStatusReceived)
			mu.Unlock()

			// The device now "runs the pump" and answers again on the same response topic — the
			// Android shape. A real handler takes a while; 1.5s is enough to be unambiguously
			// after the ACK without stretching the test.
			go func() {
				time.Sleep(1500 * time.Millisecond)
				done, _ := json.Marshal(protocol.Ack{
					CommandID: commandID,
					Status:    protocol.CmdStatusSucceeded,
					TSMs:      time.Now().UnixMilli(),
				})
				cl.Publish(topic, 1, false, done).WaitTimeout(10 * time.Second)
				mu.Lock()
				published = append(published, protocol.CmdStatusSucceeded)
				mu.Unlock()
				close(secondSent)
			}()
		}).WaitTimeout(10 * time.Second)
	})

	mqtt := paho.NewClient(opts)
	if tok := mqtt.Connect(); !tok.WaitTimeout(30*time.Second) || tok.Error() != nil {
		t.Fatalf("device MQTT connect: %v", tok.Error())
	}
	defer mqtt.Disconnect(250)

	env := newEnvelope(t, `{"tank":1}`)
	env.CommandID = commandID

	ack, err := c.SendCommand(ctx, dev.ID.ID, protocol.MethodAlarmMute, env, 10_000)
	if err != nil {
		t.Fatalf("SendCommand: %v", err)
	}

	// 1. The backend gets the FIRST response — the ack — and only that one.
	if ack.Status != protocol.CmdStatusReceived {
		t.Fatalf("SendCommand returned status %q, want %q", ack.Status, protocol.CmdStatusReceived)
	}

	select {
	case <-secondSent:
	case <-time.After(15 * time.Second):
		t.Fatal("the device never published its second response; the experiment did not run")
	}

	mu.Lock()
	sent := append([]string(nil), published...)
	mu.Unlock()
	if len(sent) != 2 {
		t.Fatalf("device published %v, want both an ack and a terminal response", sent)
	}

	// 2. Nothing carried the terminal status to the backend afterwards. The telemetry stream is
	//    the only other channel the backend reads, and a device answering on the RPC topic does
	//    not publish to it — which is precisely the gap.
	deadline := time.After(6 * time.Second)
	for {
		select {
		case u, ok := <-stream.Updates:
			if !ok {
				goto done
			}
			if raw, present := u.Values[protocol.KeyCmdResult]; present {
				res, perr := protocol.ParseCmdResult(raw)
				if perr == nil && res.CommandID == commandID {
					t.Fatalf("a cmdResult for %s arrived after all — ThingsBoard's behaviour "+
						"differs from what open-items §A-1 records; update that document", commandID)
				}
			}
		case err := <-stream.Err:
			t.Fatalf("telemetry stream failed: %v", err)
		case <-deadline:
			goto done
		}
	}
done:

	t.Logf("measured: device published %v on the RPC response topic; the backend received only %q "+
		"and no cmdResult telemetry. A device that reports its outcome as a second RPC response "+
		"leaves every command stuck at 'received' until the expiry sweep closes it.",
		sent, ack.Status)
}
