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

// DefaultDeviceProfile is the ThingsBoard device profile new devices are filed under. "default" is
// the profile every ThingsBoard installation ships with, so provisioning works on a fresh instance
// with no manual setup — which matters for the offline private deployment, where nobody is going to
// click through a console before the first device connects.
const DefaultDeviceProfile = "default"

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
