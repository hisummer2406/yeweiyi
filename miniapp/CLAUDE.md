# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: tech stack decided, no code written yet

This repo is still an empty scaffold — no code exists yet. The tech stack (native WeChat
mini-program, see "Tech stack" below) was decided on 2026-08-29; nothing has been scaffolded
against it yet. This file exists to capture the *contract* this project must satisfy before
implementation starts, so a fresh session (or a fresh subagent) doesn't have to re-derive it from
scratch or from the sibling repos.

## What this project is

The WeChat mini-program (微信小程序) frontend for **需求B（PC 端 / 小程序远程数据监控）** of
安卓液位仪 (Android Liquid Level Meter, package `com.sansen.yeweiyi`) — the client the field/office
user opens on their phone to view and (eventually) correct oil-tank monitoring data for devices
that aren't on their local network. This repo is the frontend only. It consumes the REST/WebSocket
API exposed by `~/Developer/yeweiyi-cloud` — it does not talk to MQTT or the device directly, and
it does not implement any backend logic.

This project was split out of `yeweiyi-cloud` on 2026-08-20 once that repo's scope was confirmed to
be backend-only, matching the existing pattern where each deployable/runtime unit in this project
family gets its own repo (Android device app, cloud backend, mini-program frontend are three
separate repos coordinated by shared design docs, not shared code).

## Sibling repos — read before designing anything

- `~/Developer/zhongchengyeweiyi` — Android device-side repo, source of truth for the underlying
  data model and MQTT contract. `docs/小程序与PC端集成设计.md` section 二 has the field-level data
  model (Alarm/OilInOut/CommitWork/OilCanSet), section 四 has the MQTT↔REST mapping this
  mini-program's data ultimately traces back to, and section 五 has the write-back UX requirement
  (see below). **Read that file in full before designing screens or API calls** — it's the shared
  design input for both `yeweiyi-cloud` and this repo.
- `~/Developer/yeweiyi-cloud` — the cloud backend this mini-program talks to. Its `CLAUDE.md` has
  the same contract summary from the backend's side, and is the authoritative source for exact
  request/response field names — **read it, not just this summary, before wiring up an API call**;
  this section is a map of what exists, not a full spec. **As of 2026-08-29, the full REST/WS
  surface this mini-program needs is built and manually + automatically tested** (Go/gin, see that
  repo's CLAUDE.md "Build"/"Architecture"). Every route below except `/api/login` requires
  `Authorization: Bearer {jwt}` (from `/api/login`'s response). Every route that takes a `device`
  (query param, path param, or JSON body field depending on the route) is scoped to devices *this
  user has bound* — see the pairing flow below; an unbound-or-nonexistent device both 403 the same
  way, by design, so don't treat a 403 as necessarily "not yours," and don't build UI that assumes
  a `device` string typed/pasted by the user will work without binding first.
  - `POST /api/login` — `{username, password}` → `{token}`.
  - **Device pairing** (product decision, 2026-08-29): a device is bound to a user's account by
    scanning a QR code printed/displayed on the physical device, which encodes its `deviceName`.
    `wx.scanCode()` is the native API for this. `POST /api/devices/bind` — `{deviceName}` → `200`
    (idempotent — safe to call again, e.g. if the user rescans). `POST /api/devices/unbind` — same
    body shape. `GET /api/devices` → `{rows: [...]}` of bound devices (each row has `device_name`,
    `is_online`, `last_online_at`, `last_offline_at`) — call this to populate a device picker/list
    screen; there is no other way to discover which `device_name`s a user is even allowed to query.
  - `GET /api/alarms`, `/api/oil-in-out`, `/api/shifts` — share one query-param shape:
    `device` (required) + `beginTime`/`endTime` (RFC3339, optional, default to "everything") +
    `limit`/`offset` (paginate, default 50/max 500) + `sort` (`true`=ascending, default). Response
    is `{rows: [...]}`, each row shaped like that table's Postgres columns (`device_local_id`,
    `time`, plus the record's own fields — see `yeweiyi-cloud/internal/store/db/models.go`'s
    `Alarm`/`OilInOut`/`CommitWork` structs for the exact field list/names).
  - `GET /api/oilcan/latest?device=...` — the polled realtime snapshot (§4.3 — the backend
    re-polls the device every ~20s and caches, this is *not* a live stream; don't build UI that
    assumes continuous push). Returns the device's raw cached JSON array verbatim (device's
    `NetOilCan` shape — see `yeweiyi-cloud/internal/mqtt/payloads.go`'s `NetOilCanPayload` for
    fields) or `404` if nothing has been polled for that device yet.
  - **Write-back** — `POST /api/oil-in-out/correct` (`{device, deviceLocalId, oilId, jieZhi, time,
    beginHeigth, endHeigth, beginVolum, endVolum, beginWeight, endWeight}`) and
    `POST /api/shifts/correct` (`{device, deviceLocalId, workId, time, endTime, info}`) submit a
    corrected *full target state* for an existing record (identified by `deviceLocalId`, the id
    shown in that record's list-endpoint row — not the cloud `id`); `404` if no record with that
    `deviceLocalId` exists on that device. `POST /api/devices/{device}/oil-can-set` submits a tank/
    probe config change — body shape is deliberately unspecified/opaque cloud-side (the device
    doesn't handle this yet — see that repo's CLAUDE.md), so don't invent a strict schema for it
    beyond whatever fields the design doc's config screen actually needs to send. All three return
    `202 {id, status:"pending"}` on success. `GET /api/write-back?device=...` lists a device's
    submitted commands most-recent-first (`{rows: [{id, command_type, payload, status, attempts,
    created_at, updated_at}]}`, `status` one of `pending`/`sent`/`acked`/`failed`) — poll this (there
    is no push for status changes yet) to drive the three-state UI described below. **As of
    2026-08-29 a command can reliably reach `pending` but nothing yet carries it past that** — the
    device side has no ack semantics yet, so don't expect to see `sent`/`acked` in real usage until
    that repo's CLAUDE.md says otherwise; build the UI states, but expect only `pending` in practice
    for now, and don't treat "status never advances" as a bug on this end.
  - `GET /ws/alarms?device={deviceName}&token={jwt}` — real-time alarm push (JWT via query param,
    not the `Authorization` header, since the WebSocket handshake can't set custom headers from
    小程序). Server pushes JSON text frames shaped `{"deviceName": "...", "msg": "..."}` for that
    one device's real-time alarm-text pushes only (device-side `thing/event/msg/post`) — it does
    not also carry structured `Alarm` rows; history/backfill still goes through `GET /api/alarms`.
    Same device-ownership check as every other route (403 on connect if unbound). Server sends no
    ping/keepalive frames of its own yet, so build reconnect-on-drop into the client.

## What this mini-program needs to do (summary — full detail in the Android repo's design doc)

- **Auth**: `/api/login` issues a JWT (see "Sibling repos" above) — explicitly *not* the device's
  own zero-auth/no-token LAN login pattern. **Decided 2026-08-20: one shared account/auth system**
  for mini-program and PC-remote clients (no per-client-type account silo) — no longer an open
  question.
- **Device pairing**: a freshly-logged-in user has zero bound devices — the first real flow after
  login has to be "scan the QR code on a physical device to bind it" (`wx.scanCode()` →
  `POST /api/devices/bind`), not a device picker assuming devices already exist. `GET /api/devices`
  drives whatever screen lists a user's bound devices; every other screen needs the user to have
  gone through this at least once.
- **Read-only views**: realtime oil-tank snapshot (polled/cached by backend, not a live MQTT
  stream — don't build UI that assumes continuous push), alarm history, oil in/out (卸油) history,
  shift (交班) history — all paginated by time range.
- **Realtime alarm push**: subscribe to the backend's WebSocket (`/ws/alarms`, see "Sibling repos"
  above for the concrete request/message shape) for live alarm notifications rather than polling.
- **Write-back (remote config / corrections)**: tank & probe config changes, corrections to
  unload-oil/shift records — see "Sibling repos" above for the concrete endpoints. Per the design
  doc §5.4, **the backend's write is asynchronous even when the device is online** — the UI must
  show a three-state status per submitted change: 已提交(pending) → 已同步到设备(acked) → 同步失败
  (failed，附原因), polled from `GET /api/write-back` (no push for status changes yet). Do not build
  a UI that assumes "submit succeeded" means "device now has the new value" — the device's acked
  value is the source of truth and may differ from what the user submitted. In practice today,
  expect every submission to sit at `pending` indefinitely (see "Sibling repos" above) — that's a
  known backend/device-side limitation, not something to debug in this repo.

## Tech stack

Decided 2026-08-29: **native WeChat mini-program** (原生小程序 — WXML/WXSS + JS or TS, official
`wx.*` APIs, no cross-compilation framework). Decided over uni-app/Taro for the same reasoning
`yeweiyi-cloud`'s CLAUDE.md used to pick Go/gin/gorilla over alternatives: this codebase is written
primarily by Claude, and native 小程序 is first-party, officially documented, and has the broadest
training-data coverage — no cross-compilation layer between what gets written and what actually
runs on-device to introduce a class of AI-authored-code bugs that's hard to catch (a Taro/uni-app
API that compiles but behaves subtly differently on WeChat's runtime than its abstraction promised).
uni-app/Taro's main selling point — one codebase targeting multiple mini-program platforms/H5/native
— doesn't apply here: this repo is WeChat-only by scope (`yeweiyi-cloud`'s CLAUDE.md explicitly
carves PC-remote out as a separate concern this repo doesn't serve), so there's no second target
platform to amortize a cross-compilation layer's cost against.

State management and UI component library are still open — decide when scaffolding starts;
TypeScript vs. plain JS is also open (native 小程序 supports both). When code exists, this file
should grow a "Build" and "Architecture" section the same way the Android/cloud repos' CLAUDE.md
files have them.

## What's explicitly NOT this project's problem

- MQTT, device polling cadence, write-back queue mechanics, database schema — all `yeweiyi-cloud`'s
  responsibility. This repo only calls its REST/WebSocket API.
- LAN-only PC monitoring (需求A) and the PC remote client — separate concern, not this repo.
- Anything about the Android device app itself — `zhongchengyeweiyi`.
