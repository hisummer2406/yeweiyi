## 1. Types and services

- [x] 1.1 Create `src/services/devices/typings.d.ts` with `Device` (`device_name`, `is_online`, `last_online_at`, `last_offline_at`, `updated_at`) and `Tank`/`LatestTelemetryResponse` types matching `yeweiyi-cloud`'s `internal/store/db/models.go` `Device` struct and `internal/protocol/telemetry.go` `Tank` struct field-for-field, and verify the field names match `yeweiyi-miniapp/miniprogram/types/api.d.ts`'s `Device`/`Tank`/`LatestTelemetryResponse`.
- [x] 1.2 Create `src/services/devices/api.ts` with `listDevices()` (`GET /api/devices`, returns `{ rows: Device[] }`) and `getLatestTelemetry(deviceName)` (`GET /api/devices/:device/telemetry/latest`), following the `request<T>()` pattern already used in `src/services/ant-design-pro/api.ts`; verify `npm run tsc` passes with no errors from this module.
- [x] 1.3 Add a small `TANK_ALARM_STATE_LABELS` (and any other tank-status label map needed) helper mirroring `yeweiyi-miniapp/miniprogram/utils/labels.ts`, placed under `src/services/devices/` or `src/utils/`, and verify it covers all `alarmState` values (`'NORMAL' | 'ALARM' | 'FAULT' | ''`).

## 2. Devices page

- [x] 2.1 Implement the fan-out fetch in `src/pages/console/devices/index.tsx`: call `listDevices()`, then `getLatestTelemetry` for every returned device via `Promise.allSettled`, distinguishing a 404 response (treat as "no tanks yet") from other failures (treat as a per-device error state); verify by running `npm run dev` and confirming the network tab shows one `/api/devices` call plus one `/telemetry/latest` call per device.
- [x] 2.2 Flatten successful telemetry results into a tank-card list (one entry per `Tank`, carrying its owning `device_name`) and render each via the existing `TankCard` component, mapping `index`→`tankNo`, `oilType`→`medium`, and `oilLevelMm`/`oilVolumeL`/`waterLevelMm`/`tempC`/`oilWeightKg` into `metrics`; verify visually against `docs/design-exports/00_首页-卧罐.png` layout (grid of cards, label/value/unit rows).
- [x] 2.3 Derive each card's `offline`/status indicator from that tank's `alarmState`/`probeConnected`, never from `Device.is_online`; verify by grepping the page file for `is_online` and confirming it is not read for display purposes (only passed through/ignored).
- [x] 2.4 Implement the empty-device-list state (no rows from `GET /api/devices`) and the per-device "no data yet" state (404 on that device's telemetry) as distinct, explicit UI states — not blank space or a stuck spinner; verify by temporarily forcing each response case and confirming the corresponding state renders.
- [x] 2.5 Implement the per-device error state for non-404 telemetry failures, and verify that one failing device does not prevent other devices' cards from rendering (simulate by rejecting one of the `Promise.allSettled` entries).
- [x] 2.6 Wire tank card clicks to `history.push`/`Link` navigation to `/console/devices/:deviceName` using that tank's owning device's `device_name`; verify by clicking a card in the running app and confirming the URL and route match.

## 3. Verification

- [x] 3.1 Run `npm run tsc`, `npm run lint`, and `npm run test` and confirm all pass with the new services module and page in place.
- [ ] 3.2 Manually exercise the page against a running `yeweiyi-cloud` backend (per root `CLAUDE.md` quick-start) with at least: an account with zero bound devices, an account with a device that has never reported telemetry, and an account with a normally-reporting device — confirm all three states render as specified in `specs/console/devices-overview/spec.md`.
