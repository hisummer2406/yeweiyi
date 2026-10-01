## Context

See `proposal.md` - Why. Two backend calls are involved, with no combined
endpoint: `GET /api/devices` (device identity, no tank data) and
`GET /api/devices/:device/telemetry/latest` (per-device tank data, no
device metadata). The page must fan out N telemetry requests after the
device list resolves, and each can independently 404 (never reported) or
error (network/5xx). `src/design-tokens.ts` already encodes the visual
system; `TankCard` (`src/pages/console/devices/components/TankCard.tsx`)
already exists as a presentational component and is reused as-is.

## Goals / Non-Goals

**Goals:**
- Real, correctly-typed request functions for the two endpoints above.
- A devices page that fans out telemetry fetches per device and renders
  independently per device (per spec's isolation requirements).
- Keep wire-shape types in lockstep with `yeweiyi-miniapp`'s
  `types/api.d.ts` naming (snake_case `Device`, camelCase `Tank`).

**Non-Goals:**
- Polling/live-refresh of telemetry (out of scope; page loads once per
  visit for now — revisit if product asks for live updates).
- Any device-detail page content, command issuance, or alarm/oil-in-out
  pages — untouched by this change.
- A shared cross-page "device store"/global state — this page owns its own
  fetch + local state; no need for a data layer yet with only one consumer.

## Decisions

- **New services module, not template reuse**: add
  `src/services/devices/typings.d.ts` + `src/services/devices/api.ts`
  rather than extending `src/services/ant-design-pro/*`. That module is
  still the unmodified Pro template (`RuleListItem`, `FakeCaptcha`, etc.);
  mixing real types into it would blur what's real vs. template, which
  `CLAUDE.md` explicitly flags as the thing to avoid. A dedicated
  `services/devices` module also gives `device-detail`/`issue-command`
  (future changes) an obvious place to add their own request functions
  without re-touching this one.
  - Alternative considered: extend `ant-design-pro/api.ts` in place —
    rejected because it keeps real and template-example code side by side
    in one file, exactly what the repo's current state warns against.

- **Field naming matches the wire, not idiomatic TS conventions**: `Device`
  keeps `device_name`/`is_online`/`last_online_at` snake_case; `Tank` keeps
  `oilType`/`oilLevelMm` camelCase — because that's what
  `yeweiyi-cloud` actually serializes (Go struct tags differ per type) and
  what `yeweiyi-miniapp` already mirrors. Renaming to a consistent style
  would require a translation layer for no behavioral benefit and would
  make this repo the odd one out relative to the miniapp.

- **Fan-out via `Promise.allSettled`, one card-list built client-side**:
  after `GET /api/devices` resolves, issue one
  `GET /api/devices/:device/telemetry/latest` per device concurrently via
  `Promise.allSettled`, then flatten `{ device, tanks }` per device into a
  single tank-card array. `allSettled` (not `Promise.all`) is required
  specifically because one device's rejection must not reject the whole
  batch (spec: per-device isolation). A 404 response is treated as a
  successful "empty" result (no tanks), not a rejection — the fetch
  wrapper distinguishes 404 from other error statuses.
  - Alternative considered: sequential fetching — rejected, no correctness
    benefit and slower for accounts with several devices.
  - Alternative considered: `Promise.all` — rejected, one rejection would
    abort the whole page per spec's isolation requirement.

- **Status derivation lives in a small labels helper, not inline per-card
  logic**: mirror the miniapp's `utils/labels.ts` pattern with a
  `TANK_ALARM_STATE_LABELS` map (or equivalent) in this repo, consumed by
  the devices page (and reusable later by device-detail), instead of
  hardcoding Chinese labels/colors inside the page component.

- **Reuse existing `TankCard`, add only the props telemetry requires**:
  `TankCard` today takes `tankNo`/`medium`/`shape`/`offline`/`metrics`. The
  page will map each `Tank` into those props; `shape` has no source in the
  telemetry payload (backend doesn't report tank geometry), so it defaults
  to `'horizontal'` until a real source exists — this is a visual default,
  not a spec-covered behavior, so it isn't in `specs/`.

## Risks / Trade-offs

- [No live refresh] → data can go stale while the page is open. Mitigated
  by scoping this change to initial load only and calling it out as a
  known limitation rather than silently implying real-time data.
- [N+1 requests for accounts with many devices] → more round trips than a
  hypothetical combined endpoint. Mitigated by concurrency
  (`Promise.allSettled` fires all requests together); if this becomes a
  real performance problem, that's a `yeweiyi-cloud` API change, not
  something this frontend change can fix.
- [Tank shape defaults to horizontal] → visually wrong for accounts whose
  tanks are actually 立罐/长方体. Acceptable because the backend has no
  shape field today; flagged in Decisions so it isn't mistaken for a
  data-driven choice later.

## Open Questions

- Whether `/console/devices` should eventually poll for live updates (and
  at what interval) is a product decision, not something this change
  should preempt.
