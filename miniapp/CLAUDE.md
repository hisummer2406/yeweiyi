# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: rebuilt against the current yeweiyi-cloud API (2026-09-04)

**Everything below describes the current state — read this, not history.** The previous version of
this file (and of every screen in `pages/`) was written 2026-08-29 against the old Alink/MQTT-backed
cloud API. `yeweiyi-cloud` issue #10 then deleted that whole stack in favor of a ThingsBoard
single-stack architecture (see that repo's CLAUDE.md), which changed or removed most of the routes
this app called. That mismatch went unnoticed until `yeweiyi-cloud` PR #12's review flagged the
mini-program as an existing client nobody had checked against, at which point this repo — same
owner as `yeweiyi-cloud` — was rebuilt in place rather than left broken. **This is the confirmation
`yeweiyi-cloud/docs/protocol-v1-open-items.md` §D asked for**, given by actually updating the client
rather than a sign-off recorded elsewhere.

None of this has been run inside WeChat DevTools or against a live `yeweiyi-cloud` yet — `npm run
typecheck` is clean, that's the extent of verification so far. Treat every screen as a first pass to
replace, not a finished design.

**Update 2026-10-07.** The screens were then restructured to follow the mini-program design draft
(28 artboards, 750 wide; source PSD lives outside the repo and is gitignored — exported PNGs were
compared on 2026-10-07, the 员工管理 artboard was not exported). Requirements and design decisions
for new work now live in `openspec/` (see "Spec workflow" below), not in this file. Two things
about the design draft are easy to get wrong:

- It has **no** device-detail, command-issuing, or login screens — those exist because the cloud
  needs them, and are a design gap to hand back to the designer, not a layout to preserve.
- Several of its screens assume cloud capabilities that do not exist (see "Static design-draft
  pages" below). Do not wire those up by guessing; the open questions are filed as
  `yeweiyi-cloud#43 #44 #45` and `zhongchengyeweiyi#43`.

## What this project is

The WeChat mini-program (微信小程序) frontend for **需求B（PC 端 / 小程序远程数据监控）** of
安卓液位仪 (Android Liquid Level Meter, package `com.sansen.yeweiyi`) — the client the field/office
user opens on their phone to view oil-tank monitoring data and issue remote control commands for
devices that aren't on their local network. This repo is the frontend only. It consumes the
REST/WebSocket API exposed by `~/Developer/yeweiyi-cloud` — it does not talk to ThingsBoard or the
device directly, and it does not implement any backend logic.

This project was split out of `yeweiyi-cloud` on 2026-08-20, matching the existing pattern where
each deployable/runtime unit in this project family gets its own repo (Android device app, cloud
backend, mini-program frontend are three separate repos coordinated by shared design docs, not
shared code).

## Sibling repos — read before designing anything

- `~/Developer/yeweiyi-cloud` — the cloud backend this mini-program talks to. **Its `CLAUDE.md` is
  the authoritative source for the current REST/WS surface — read it, not just this summary, before
  wiring up an API call**, and re-check `types/api.d.ts` against its handler structs
  (`internal/api/*.go`) and protocol structs (`internal/protocol/*.go`) before trusting either if
  enough time has passed that the two could have drifted; there is no shared code or generated
  client between the two repos, so nothing catches a drift automatically except `npm run
  typecheck` failing on a call site once someone notices and fixes the type.
- `~/Developer/zhongchengyeweiyi` — the Android device-side repo. Not needed for wiring up API calls
  (that's `yeweiyi-cloud`'s job to mediate), but relevant background if a screen's behavior depends
  on device-side reality — e.g. why a physical control command is refused, or why 出入库 history can
  come back partial.
- `~/Developer/yeweiyi-admin` — the desktop equivalent of this app: one Ant Design Pro project with
  an `/ops/*` section (platform_admin only) and a `/console/*` section that is meant to cover the
  same capability set this mini-program covers (device list, device detail/telemetry, issue-command,
  oil-in-out) for the same customer roles. If a capability is added here, it should end up on both
  clients — check that repo's `CLAUDE.md` when adding or renaming a screen so the two don't drift.

Every route below except `/api/login` requires `Authorization: Bearer {jwt}` (from `/api/login`'s
response). Every route that takes a `device` is scoped to devices *this user has bound* — an
unbound-or-nonexistent device answers the same 403/401 either way, by design (device-name
enumeration prevention), so don't treat those as necessarily "not yours," and don't build UI that
assumes a `device` string typed/pasted by the user will work without binding first.

- `POST /api/login` — `{username, password}` → `{token}`. The JWT's `sub` claim is the username and
  its `role` claim is `platform_admin`/`customer_admin`/`customer_user` — see `utils/auth.ts`. A
  role change does not take effect until the user's next login.
- **Device pairing.** `POST /api/devices/bind` — `{deviceName, pairingToken}` → `200
  {deviceName, bound:true}` (idempotent). `pairingToken` is a secret minted server-side via
  `POST /api/devices/:device/pairing-secret` (platform_admin only, plaintext returned exactly once)
  — it is **not** the device's ThingsBoard access token, and this app never sees or needs that.
  **The physical QR code's content format is not designed cloud-side** — `utils/pairing.ts` picks a
  JSON-object placeholder (`{"deviceName":"...","pairingToken":"..."}`) and says so; that is the one
  file to change if the real label ends up encoding something else. `POST /api/devices/unbind` —
  `{deviceName}`. `GET /api/devices` → `{rows: [Device...]}` — call this to populate the device list;
  there is no other way to discover which `device_name`s a user may even query. **`is_online` on
  each row is permanently stale** (nothing in the ThingsBoard single-stack backend updates it any
  more) — do not display it; see `types/api.d.ts`'s note on `Device`.
- **Read-only history**, `GET /api/alarms` and `GET /api/oil-in-out` — share one query-param shape:
  `device` (required) + `beginTime`/`endTime` (RFC3339, optional, default last 24h) + `limit`/`offset`
  (paginate, default 50/max 500 — `offset` is a 400, both endpoints page by time window, not row
  offset) + `sort` (`true`=ascending, default `false`≈newest first passed explicitly by this app).
  **The two endpoints do not share a response envelope**: `/api/alarms` answers a **bare array**,
  `/api/oil-in-out` answers `{rows, parsed, unparsable, legacySkipped, error?, code?, sample?,
  legacyCutoff?}` on **both** its 200 and its 502 path. That inconsistency is real, tracked
  cloud-side (`yeweiyi-cloud/docs/protocol-v1-open-items.md` §D item 2), and not something to "fix"
  in this repo by guessing at a shape — see `types/api.d.ts`. The 502 on `/api/oil-in-out` means "some
  records on this device could not be parsed" (pre-V1 firmware still publishing debug strings,
  zhongchengyeweiyi#39) — it still carries whatever *did* parse in `rows`, which is why
  `device-detail.ts`'s `loadOilInOut` reads `ApiError.body` on that path instead of just showing the
  error string.
- `GET /api/devices/:device/telemetry/latest` — the realtime tank snapshot, read straight through to
  ThingsBoard on request (not a cache, not a push stream — poll or re-fetch on tab switch).
  `404` means "authorized, provisioned, never reported" — a real state, not an error. Replaces the
  deleted `/api/oilcan/latest` outright; unrelated shape, do not conflate.
- **Control commands** — replace the old asynchronous write-back queue entirely.
  `POST /api/devices/:device/commands` — `{method, args?, reason}` (`reason` is the mandatory 二次确认
  justification) → **`202`, never `200`** — reports only how far the command got synchronously, and
  carries a server-computed `terminal` flag. **An ACK is not success**: `received` only means the
  device accepted the request; the real outcome (`succeeded`/`failed`) arrives later, asynchronously,
  and an unacknowledged command is `sent`, not `failed` — never render a non-terminal status as done.
  `GET /api/devices/:device/commands` → `{commands: [...]}` — poll here for the outcome. The method
  table (`pump.start`, `alarm.mute`, `config.setTank`, …) is mirrored client-side in
  `utils/labels.ts` from `yeweiyi-cloud/internal/protocol.Methods` — it is part of the protocol
  contract, not per-deployment config, so it should only change in lockstep with that file. Physical
  methods (pump/valve/reboot) are gated off by default on every deployment until device-side
  interlocks are verified (issue #1) and answer **409**, not a bug in this app, until then.
  `config.setTank`/`config.setProbe`'s argument shapes are undefined on the cloud side on purpose
  (device has no handler yet) — `issue-command.ts` takes raw JSON for `args` rather than a typed
  form, the same pattern the retired `oil-can-set` page used for its opaque schema.
- `GET /ws/alarms?device={deviceName}&token={jwt}` — real-time alarm push (JWT via query param, since
  a WebSocket handshake can't set custom headers from 小程序). Pushes JSON text frames shaped
  `{deviceName, alarmId, tank, type, severity, status, msg, value?, tsMs}` for that one device's
  alarm transitions — richer than the old `{deviceName, msg}` shape; see `AlarmPushMessage` in
  `types/api.d.ts`. Same device-ownership check as every other route. Server sends no ping/keepalive
  of its own, so the client reconnects on drop (`utils/ws.ts`).

## Removed from yeweiyi-cloud with no replacement in this app

These existed in the pre-single-stack API and this app used to call them. They are gone cloud-side,
not just unimplemented here:

- `GET /api/shifts`, `POST /api/shifts/correct` — 设备协议 V1 has no 交班 (shift handover) uplink on
  either side (`yeweiyi-cloud` CLAUDE.md: "not scope of the protocol, not a rewrite backlog item").
- `POST /api/oil-in-out/correct` — **record correction has no replacement design yet.** The old
  write-back endpoint went with the queue it was part of; if correcting a stored 出入库 record remains
  a product requirement, it needs designing on top of `yeweiyi-cloud/internal/control` (the new
  control-command state machine), not restoring. Until then this app has no correction UI at all —
  the 出入库 tab is read-only.
- `POST /api/devices/{device}/oil-can-set` — superseded conceptually by
  `config.setTank`/`config.setProbe` control commands, but see the undefined-argument-shape note
  above; there is no working config-write flow for tank/probe settings today, only the general
  control-command form.
- `GET /api/write-back` — superseded by `GET /api/devices/:device/commands`.

## What this mini-program needs to do

- **Auth**: `/api/login` issues a JWT — one shared account/auth system for mini-program and PC-remote
  clients, no per-client-type account silo.
- **Device pairing**: a freshly-logged-in user has zero bound devices — the first real flow after
  login is "scan the QR code on a physical device to bind it" (`wx.scanCode()` →
  `POST /api/devices/bind` with `{deviceName, pairingToken}` parsed from the code — see
  `utils/pairing.ts`'s placeholder-format caveat above).
- **Read-only views**: realtime oil-tank snapshot (poll-on-demand, not a live stream), alarm history,
  出入库 (stock in/out) history — all paginated by time range, and 出入库 specifically must surface
  `unparsable`/`legacySkipped` rather than silently showing an incomplete list as complete.
- **Realtime alarm push**: subscribe to `/ws/alarms` for live alarm notifications rather than polling.
- **Control commands**: issue a command (`issue-command` page), then track its status through
  `GET /api/devices/:device/commands` — three states matter to the UI: not yet terminal (keep
  polling / show as in-flight, using the server's `terminal` flag, never a hard-coded status list),
  `succeeded`, and `failed`/`rejected`/`expired`. **Do not build a UI that assumes ACK means done** —
  see the control-commands section above.

## Tech stack

Decided 2026-08-29: **native WeChat mini-program** (原生小程序 — WXML/WXSS + JS or TS, official
`wx.*` APIs, no cross-compilation framework), for the same reasoning `yeweiyi-cloud`'s CLAUDE.md used
to pick Go/gin/gorilla over alternatives: this codebase is written primarily by Claude, and native
小程序 is first-party, officially documented, and has the broadest training-data coverage.

- **TypeScript**, via WeChat DevTools' built-in TS compiler plugin plus `miniprogram-api-typings` as
  a type-only devDependency — catch a wrong `wx.*` call signature or a field-name mismatch against
  the cloud API at typecheck time instead of on-device.
- **No UI component library.** The app is small and native WXML/WXSS covers it. Revisit if the screen
  count or design polish bar grows enough to justify one.
- **No state management library.** Auth token lives in `wx.storage` (`utils/storage.ts`); each page
  loads its own data on `onLoad`/`onShow`. `app.ts`'s `globalData` + per-page `Page.data` is enough.

## Build

No bundler and no `npm run build` — WeChat DevTools compiles `.ts` → `.js` itself on preview/upload.

1. `npm install` — pulls in `typescript` and `miniprogram-api-typings` (type declarations only).
2. `npm run typecheck` — `tsc --noEmit` over `miniprogram/**/*.ts`. Run this after any `.ts` change;
   it's the only automated check in this repo so far (no test runner yet).
3. Open the repo root in WeChat DevTools (`project.config.json` sets `miniprogramRoot: miniprogram/`
   already). `appid` is the placeholder `"touristappid"` — swap in the real AppID before anything
   beyond local preview is needed.
4. `config/env.ts`'s `BASE_URL`/`WS_BASE_URL` default to `http://127.0.0.1:8080` /
   `ws://127.0.0.1:8080` (`yeweiyi-cloud`'s `LISTEN_ADDR` default). Local non-HTTPS origins need
   DevTools' 详情 → 本地设置 → "不校验合法域名..." checked.

## Architecture

- `app.ts` — `globalData.token` mirrors `utils/storage.ts` on launch; nothing else global.
- `utils/request.ts` — the one `wx.request` wrapper; injects `Authorization: Bearer {token}` on
  every call except `/api/login`. `ApiError` carries the HTTP status *and* the full parsed response
  body, not just the message — needed because `/api/oil-in-out`'s 502 is a structured, still-useful
  response (`{rows, unparsable, legacySkipped, ...}`), not a plain failure. A 401 clears the token
  and bounces to login; so does a 403 whose body is exactly `not authorized` (user deleted/disabled —
  `authMiddleware`), but **not** `not authorized for this device` (see the device-ownership note
  above). A successful authorized response carrying `X-Renewed-Token` (sliding renewal,
  `yeweiyi-cloud#51`) replaces the stored token; the cloud caps it at 30 days from the last password
  login, then the next 401 sends the user back to login. `AlarmSocket` re-reads the token on every
  reconnect, so it picks the new one up without changes.
- `utils/ws.ts` — `AlarmSocket` wraps `wx.connectSocket` for `/ws/alarms` with reconnect-on-close.
- `utils/auth.ts` — `login()`; `usernameFromToken`/`roleFromToken` decode the JWT's `sub`/`role`
  claims client-side purely for display and UI gating (real authorization is always server-side).
- `utils/pairing.ts` — parses the device-pairing QR payload. Placeholder format, see above.
- `utils/labels.ts` — Chinese labels for the closed enums the cloud API returns (alarm type/severity/
  status, tank alarm state, stock direction) and the mirrored control-method table. One place so an
  unmapped value falls back to itself rather than rendering blank.
- `types/api.d.ts` — hand-copied from `yeweiyi-cloud`'s handler/protocol structs. Re-diff before
  trusting it if time has passed.
- `utils/telemetry.ts` — telemetry snapshot → tank view-model and the "never reported" (404) test,
  shared by the home page and `device-detail`. Pure, no `wx`.
- `utils/bind.ts` — `wx.scanCode` → `POST /api/devices/bind`, shared by the home empty state and
  `device-add`.
- `utils/oilInOut.ts` — runtime guard for the `/api/oil-in-out` envelope and the "list is not
  complete" notes (unparsable / legacySkipped / partial / truncated / invalidSource). Pure.
- `utils/devicePicker.ts` — pages `wx.showActionSheet` device choices (WeChat caps it at 6 items).
- `pages/` — three tabBar tabs plus pushed pages:
  - **首页 `devices`** (tabBar) — tank cards grouped by device: `GET /api/devices`, then one
    `telemetry/latest` per device in parallel, each device succeeding/failing independently. No
    station dropdown yet — the cloud has no station entity, so the header says "我的设备" rather than
    faking one. "+" menu: 添加设备 / 油站管理 (placeholder). **No 邀请加入**: accounts are created
    in the back office with a password; there is no registration or invite in the mini-program.
    Tank illustration is always horizontal until the cloud exposes tank shape (`gunType` on the
    device, `yeweiyi-cloud#45`).
  - **我的记录 `records`** (tabBar) — entry list; 报警/出库/入库 pick a device then open
    `device-detail` on that tab; 交班/库存 have no cloud data and say so.
  - **设置 `more`** (tabBar) — entries to the pages below, plus `profile` (account, role, logout).
  - `login`, `device-add` (扫一扫), `device-detail` (in-page tabs: 实时数据/报警记录/出入库记录/
    控制命令, plus the live-alarm banner from `AlarmSocket`; polls the command list every 3s while
    any command is non-terminal), `issue-command` (one control command, raw-JSON args).

## Static design-draft pages (not wired to the cloud — on purpose)

`tank-config`, `tank-shape` (+ `tank-shape-edit`), `calibration`, `backfill`, `record-settings`,
`report-settings` are laid out from the design draft (checked against the exported artboard PNGs by
measuring pixels, see `openspec/changes/home-by-station/tasks.md` 3.8–3.10) and say "暂未开放 /
云端没有对应接口" when saved. Shared pieces: form/button/switch styles in `app.wxss`,
`components/grid-picker` (3-column tank-number sheet) and `components/side-picker` (type dropdown,
12-colour palette). Do not make them send requests until the cloud side exists:

- `config.setTank` / `config.setProbe` / `config.setAlarmThresholds` have method names but no
  argument schema (`yeweiyi-cloud/docs/miniprogram-design-gaps.md` §1).
- Calibration push, tank-table push, record clearing, report export, stock backfill, shift and
  stock records have no cloud method or endpoint at all (same document §2).
- Device configuration is meant to be **station-administrator only**, and the cloud has no such
  role (`yeweiyi-cloud#44`). Do not stand in `customer_admin` for it.
- `calibration` saves to the phone's own storage only; nothing it stores affects any display.

## Spec workflow

Requirements for new work live in `openspec/` (initialised 2026-10-07, schema `spec-driven`, zh-CN).
`openspec/config.yaml` carries the project context and rules that every proposal must follow
(the cloud owns the contract, specs do not copy cloud fields, the do-not-build list). Active change:
`openspec/changes/home-by-station/` (home page by station, with its dependency on the cloud and the
filed issues in `cloud-issues.md`). Use `/opsx:propose`, `/opsx:apply`, `/opsx:archive`.

## What's explicitly NOT this project's problem

- ThingsBoard, MQTT, control-command state machine internals, database schema — all
  `yeweiyi-cloud`'s responsibility. This repo only calls its REST/WebSocket API.
- LAN-only PC monitoring (需求A) and the PC remote client — separate concern, not this repo.
- Anything about the Android device app itself — `zhongchengyeweiyi`.
- 出入库/交班 record correction, and tank/probe config write — no working design exists yet on
  either side (see "Removed from yeweiyi-cloud" above); do not invent one here.
