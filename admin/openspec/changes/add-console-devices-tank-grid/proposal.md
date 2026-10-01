## Why

`/console/devices` — the PC-web equivalent of the `yeweiyi-miniapp` device list, and the
landing page customer roles hit after login (`config/routes.ts` redirects `/console` here) —
is currently an empty `<PageContainer title="设备列表" />` placeholder. It renders no data and
calls no API. Customer users (`customer_admin`/`customer_user`) and `platform_admin` have no
way to see their bound devices' live tank readings from a browser today; they must use the
miniapp or the on-device touchscreen. The on-device HMI's visual design
(`docs/design-language/DESIGN_LANGUAGE.md`, extracted from `docs/中成系统.psd`) and the real
backend endpoints (`yeweiyi-cloud` `GET /api/devices` + `GET /api/devices/:device/telemetry/
latest`) are both now known, so this page can move from placeholder to a real, data-backed
overview.

## What Changes

- Add real request functions + TypeScript types for `GET /api/devices` and
  `GET /api/devices/:device/telemetry/latest`, field-for-field aligned with
  `yeweiyi-cloud`'s response shape and with `yeweiyi-miniapp`'s existing
  `types/api.d.ts` (snake_case `Device`, camelCase `Tank`) — not the leftover
  Ant Design Pro template types.
- Replace the `/console/devices` placeholder with a tank-card grid page:
  fetch the caller's bound devices, fetch each device's latest telemetry, and
  render one card per tank (not per device — a device can report multiple
  tanks) using the existing `TankCard` component
  (`src/pages/console/devices/components/TankCard.tsx`) and the design
  tokens in `src/design-tokens.ts`.
- Handle the real edge cases the backend actually returns, instead of
  assuming a happy path:
  - Empty device list (no devices bound to this account).
  - Per-device telemetry `404` (device provisioned but has never reported —
    a normal, expected state, not an error) — show the device as
    "no data yet" rather than failing the whole page.
  - A telemetry fetch failing for one device must not block the rest of the
    grid from rendering (fan-out fetch, isolate failures per device).
  - `is_online` is a known-dead field on the backend (nothing writes it) —
    the UI must not use it to imply connectivity; use per-tank
    `alarmState`/`probeConnected` instead, consistent with the miniapp.
- Clicking a tank card navigates to the existing (still-placeholder)
  `/console/devices/:deviceName` route. That route's own content is out of
  scope for this change.

**Not in scope**: `/console/devices/:deviceName` (device-detail) interaction
design — charts/alarm presentation there are explicitly undecided per the
root `CLAUDE.md` and must not be invented here. Also out of scope:
`/console/issue-command`, `/console/oil-in-out`, and any `/ops/*` page.

## Capabilities

### New Capabilities
- `console/devices-overview`: the `/console/devices` tank-grid page — fetching
  the caller's bound devices and their latest per-tank telemetry, and
  rendering that data as a grid of tank cards with defined behavior for
  empty/missing/partial-failure telemetry states.

### Modified Capabilities
(none — no existing spec covers this page today)

## Impact

- **New files**: a services module for `/api/devices` and
  `/api/devices/:device/telemetry/latest` (types + request functions),
  replacing reliance on `src/services/ant-design-pro/*` template types for
  this page.
- **Modified files**: `src/pages/console/devices/index.tsx` (placeholder →
  real page); `src/pages/console/devices/components/TankCard.tsx` may gain
  minor prop adjustments if the real telemetry shape needs it.
- **Backend dependency**: read-only, calls two existing `yeweiyi-cloud`
  endpoints (`GET /api/devices`, `GET /api/devices/:device/telemetry/
  latest`); no backend changes required.
- **Cross-repo consistency**: field names must track `yeweiyi-miniapp`'s
  `types/api.d.ts` so the two clients don't drift on the same wire shape.
