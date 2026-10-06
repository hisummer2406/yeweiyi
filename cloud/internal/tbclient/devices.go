package tbclient

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
)

// EntityID is ThingsBoard's polymorphic identifier shape: {"id": "<uuid>", "entityType": "DEVICE"}.
type EntityID struct {
	ID         string `json:"id"`
	EntityType string `json:"entityType"`
}

// Device is the subset of ThingsBoard's device object this backend cares about. ThingsBoard returns
// considerably more (tenantId, customerId, deviceProfileId, additionalInfo, ...); the fields left
// out are deliberately not modelled, so that a ThingsBoard release adding a field does not require
// a change here.
type Device struct {
	ID    EntityID `json:"id"`
	Name  string   `json:"name"`
	Type  string   `json:"type"`
	Label string   `json:"label,omitempty"`
}

// DeviceCredentials is a device's ThingsBoard-issued identity. For V1 the credentials type is
// ACCESS_TOKEN and CredentialsID is the token the device puts in the MQTT username field — the
// "one-device-one-secret" property the platform choice was made for.
type DeviceCredentials struct {
	ID            EntityID `json:"id"`
	DeviceID      EntityID `json:"deviceId"`
	CredentialsID string   `json:"credentialsId"`
	Type          string   `json:"credentialsType"`
}

// GetDeviceByName looks a device up by the name this project uses everywhere else as deviceName.
// Returns ErrNotFound when ThingsBoard has no such device.
//
// Note on the two device registries: ThingsBoard has its own device table and this project has its
// own `devices`/`user_devices` tables. They are joined by nothing but this name string agreeing on
// both sides — nothing in either system enforces it. Provisioning must therefore always go through
// EnsureDevice below rather than creating a ThingsBoard device by hand.
func (c *Client) GetDeviceByName(ctx context.Context, name string) (Device, error) {
	var d Device
	q := url.Values{"deviceName": {name}}
	// ThingsBoard answers 404 here when the name is unknown, which roundTrip maps to ErrNotFound.
	if err := c.do(ctx, http.MethodGet, "/api/tenant/devices"+query(q), nil, &d); err != nil {
		return Device{}, err
	}
	return d, nil
}

// CreateDevice registers a device with a caller-supplied access token. ThingsBoard mints a random
// token when accessToken is empty; pass one only where the token is already known (a device with a
// pre-flashed credential), otherwise let ThingsBoard generate it and read it back with
// GetDeviceCredentials — a generated token is stronger than anything derived from a device name.
func (c *Client) CreateDevice(ctx context.Context, name, deviceType, accessToken string) (Device, error) {
	if name == "" {
		return Device{}, errors.New("tbclient: device name is required")
	}
	if deviceType == "" {
		deviceType = DefaultDeviceProfile
	}

	body := map[string]any{"name": name, "type": deviceType}
	var q url.Values
	if accessToken != "" {
		q = url.Values{"accessToken": {accessToken}}
	}

	var d Device
	if err := c.do(ctx, http.MethodPost, "/api/device"+query(q), body, &d); err != nil {
		return Device{}, fmt.Errorf("create device %q: %w", name, err)
	}
	return d, nil
}

// DefaultDeviceProfile is the ThingsBoard device profile new devices are filed under.
//
// A project-specific profile rather than ThingsBoard's stock "default": that one is shared with the
// demo devices every installation ships with, so anything configured on it — alarm rules, a default
// rule chain, an OTA queue — would apply to those too, and vice versa.
//
// Provisioning still works on a completely fresh installation even if nobody has run
// `tbconfig apply` first: ThingsBoard **auto-creates** a device profile when a device is created
// with an unknown type (verified against a real instance). The profile is then ThingsBoard's own
// defaults rather than the one defined in deploy/tb-config/, so apply is a fill-in-the-settings
// step, not a start-up prerequisite. That distinction matters for the offline deployment, where
// nobody is going to click through a console before the first device connects.
const DefaultDeviceProfile = "yeweiyi-tank-terminal"

// EnsureDevice returns the ThingsBoard device with this name, creating it if it does not exist.
// This is the provisioning entry point the QR-code pairing flow calls: a device may legitimately be
// bound by a user before it has ever connected, so the ThingsBoard record has to be creatable
// on demand rather than only appearing when the device first talks.
//
// It is safe to call repeatedly. The create is not transactional against a concurrent caller — if
// two requests race, one gets a duplicate-name error from ThingsBoard and re-reads.
func (c *Client) EnsureDevice(ctx context.Context, name, deviceType string) (Device, error) {
	d, err := c.GetDeviceByName(ctx, name)
	if err == nil {
		return d, nil
	}
	if !errors.Is(err, ErrNotFound) {
		return Device{}, err
	}

	d, err = c.CreateDevice(ctx, name, deviceType, "")
	if err == nil {
		return d, nil
	}
	// Lost a create race with another caller: the device exists now, so read it back.
	if existing, getErr := c.GetDeviceByName(ctx, name); getErr == nil {
		return existing, nil
	}
	return Device{}, err
}

// GetDeviceCredentials reads a device's credentials, i.e. the MQTT access token it authenticates
// with. Handle the result as a secret: it is the entire authentication for that device.
func (c *Client) GetDeviceCredentials(ctx context.Context, deviceID string) (DeviceCredentials, error) {
	var creds DeviceCredentials
	if err := c.do(ctx, http.MethodGet, "/api/device/"+url.PathEscape(deviceID)+"/credentials", nil, &creds); err != nil {
		return DeviceCredentials{}, fmt.Errorf("get credentials for device %s: %w", deviceID, err)
	}
	return creds, nil
}

// DeleteDevice removes a device and its credentials. Used by the e2e checks to clean up after
// themselves; device decommissioning in production is a business decision that goes through the
// business database first.
func (c *Client) DeleteDevice(ctx context.Context, deviceID string) error {
	return c.do(ctx, http.MethodDelete, "/api/device/"+url.PathEscape(deviceID), nil, nil)
}

// deviceInfoPageSize is how many devices one deviceInfos page asks for. ThingsBoard caps page size
// itself; this stays well under that while keeping a 10,000-device fleet to a handful of requests.
const deviceInfoPageSize = 1000

// DeviceActivity returns every tenant device's ThingsBoard `active` flag, keyed by device name.
//
// This is what the platform means by "online", and it is cheaper than asking per device: one
// deviceInfos page answers a thousand devices. `active` is true while the device has shown activity
// (a connect, telemetry, an attribute update) within the device profile's inactivity timeout, and
// it only turns false once that timeout passes — so it lags a real disconnect by up to the timeout
// (ThingsBoard's default is 10 minutes). Measured on 4.2.1.1: a device killed mid-stream was still
// reported active a minute later.
//
// The result is all-or-nothing: an error on any page returns no map, so a caller never mistakes a
// partial listing for the whole fleet and marks the missing devices offline.
func (c *Client) DeviceActivity(ctx context.Context) (map[string]bool, error) {
	out := map[string]bool{}
	for page := 0; ; page++ {
		q := url.Values{
			"pageSize": {fmt.Sprint(deviceInfoPageSize)},
			"page":     {fmt.Sprint(page)},
		}
		var resp struct {
			Data []struct {
				Name   string `json:"name"`
				Active bool   `json:"active"`
			} `json:"data"`
			HasNext bool `json:"hasNext"`
		}
		if err := c.do(ctx, http.MethodGet, "/api/tenant/deviceInfos"+query(q), nil, &resp); err != nil {
			return nil, fmt.Errorf("list device infos (page %d): %w", page, err)
		}
		for _, d := range resp.Data {
			out[d.Name] = d.Active
		}
		if !resp.HasNext {
			return out, nil
		}
	}
}
