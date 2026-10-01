## Purpose

Gives customer-facing users (`customer_admin`, `customer_user`, and `platform_admin`) a
browser view of every tank on every device bound to their account, with the same live
readings the miniapp and on-device HMI show, as a grid of per-tank cards.

## ADDED Requirements

### Requirement: Bound device list retrieval
The `/console/devices` page SHALL fetch the caller's bound devices from
`GET /api/devices` on load and SHALL treat the response's `rows` array as the
complete set of devices to render (no client-side pagination is assumed
unless the backend response indicates more pages).

#### Scenario: Devices bound to the account
- **WHEN** the page loads and `GET /api/devices` returns one or more rows
- **THEN** the page proceeds to fetch telemetry for each returned device

#### Scenario: No devices bound to the account
- **WHEN** the page loads and `GET /api/devices` returns an empty `rows` array
- **THEN** the page SHALL show an explicit empty state (not a blank grid, not
  a loading spinner left hanging) explaining that no devices are bound to
  this account

### Requirement: Per-device telemetry retrieval
For each device returned by `GET /api/devices`, the page SHALL request
`GET /api/devices/:device/telemetry/latest` and render every entry in that
response's `tanks` array as one tank card.

#### Scenario: Device reports multiple tanks
- **WHEN** a device's telemetry response contains more than one entry in `tanks`
- **THEN** the page SHALL render one card per tank entry, not one card per device

### Requirement: Missing telemetry is a normal empty state, not an error
A device that has never reported telemetry SHALL be presented as
"no data yet", not as a failed request, and SHALL NOT prevent other devices'
cards from rendering.

#### Scenario: Telemetry endpoint returns 404 for a provisioned device
- **WHEN** `GET /api/devices/:device/telemetry/latest` returns 404 for a
  given device
- **THEN** the page SHALL render that device as a "no data yet" placeholder
  card instead of an error card, and SHALL continue rendering the remaining
  devices normally

### Requirement: Per-device telemetry failures are isolated
A telemetry request failure for one device SHALL NOT block or blank out the
cards for other devices whose telemetry requests succeeded.

#### Scenario: One device's telemetry request errors, others succeed
- **WHEN** `GET /api/devices/:device/telemetry/latest` fails (network error
  or non-404 error status) for one device while other devices' requests
  succeed
- **THEN** the page SHALL render the successful devices' tank cards normally
  and SHALL show a distinct per-card error/retry state only for the device
  whose request failed

### Requirement: Device-level `is_online` is never shown as connectivity status
The page SHALL NOT use the `Device.is_online` field (from `GET /api/devices`)
to indicate whether a device or tank is online, because that field is not
maintained by the backend and does not reflect real connectivity.

#### Scenario: Rendering a device's connectivity/status indicator
- **WHEN** the page renders a tank card's status indicator
- **THEN** it SHALL derive that indicator from the tank's own `alarmState`
  and `probeConnected` fields (from the telemetry response), never from
  `Device.is_online`

### Requirement: Tank card data fields
Each tank card SHALL display, at minimum, the tank's index/number, medium
(`oilType`), liquid level, volume, water height, and temperature, sourced
from the corresponding `Tank` entry in the telemetry response.

#### Scenario: Tank telemetry present
- **WHEN** a tank entry is available for a device
- **THEN** the card SHALL display its `index`, `oilType`, `oilLevelMm`,
  `oilVolumeL`, `waterLevelMm`, and `tempC` values

### Requirement: Navigation to device detail
Clicking a tank card SHALL navigate to `/console/devices/:deviceName`, where
`:deviceName` is that tank's owning device's `device_name`.

#### Scenario: User clicks a tank card
- **WHEN** the user clicks a rendered tank card
- **THEN** the page SHALL navigate to `/console/devices/:deviceName` for the
  device that tank belongs to
