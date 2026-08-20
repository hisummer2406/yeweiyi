# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: not started yet

This repo is an empty scaffold. Nothing has been built here — no framework has been chosen, no
code exists. This file exists to capture the *contract* this project must satisfy before any
implementation decisions get made, so a fresh session (or a fresh subagent) doesn't have to
re-derive it from scratch or from the sibling repos.

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
  the same contract summary from the backend's side. **As of 2026-08-20, that backend is now
  substantially built** (Go/gin, see that repo's CLAUDE.md "Build"/"Architecture" sections) — §4.4's
  draft routes (`/api/oilcan/latest`, `/api/alarms`, `/ws/alarms`, `/api/oil-in-out`, `/api/shifts`,
  `/api/login`) are real endpoints now, with one deviation from the draft: every list endpoint
  requires a `device` query param (no "all devices" listing — multi-device cloud design). This
  mini-program's API client work is no longer blocked on the backend existing, though write-back
  endpoints (config/corrections) aren't wired up yet — see that repo's CLAUDE.md "Open product
  decisions"/"Device-side work this project depends on" for what's still open. If the API contract
  needs to change, that's a conversation across both repos.
  - `/ws/alarms` specifically (implemented 2026-08-20): `GET /ws/alarms?device={deviceName}`,
    auth via either the `Authorization: Bearer {jwt}` header or a `token` query param (added
    because browser/mini-program `WebSocket` clients can't set custom headers on the handshake —
    use the query param from 小程序). Server pushes JSON text frames shaped
    `{"deviceName": "...", "msg": "..."}` for that one device's real-time alarm-text pushes only
    (device-side `thing/event/msg/post`, §4.2) — it does not also carry structured `Alarm` rows;
    history/backfill still goes through `GET /api/alarms`. Server sends no ping/keepalive frames of
    its own yet, so build reconnect-on-drop into the client.

## What this mini-program needs to do (summary — full detail in the Android repo's design doc)

- **Read-only views**: realtime oil-tank snapshot (polled/cached by backend, not a live MQTT
  stream — don't build UI that assumes continuous push), alarm history, oil in/out (卸油) history,
  shift (交班) history — all paginated by time range.
- **Realtime alarm push**: subscribe to the backend's WebSocket (`/ws/alarms`, now implemented —
  see "Sibling repos" above for the concrete request/message shape) for live alarm notifications
  rather than polling.
- **Write-back (remote config / corrections)**: tank & probe config changes, corrections to
  unload-oil/shift records. Per the design doc §5.4, **the backend's write is asynchronous even
  when the device is online** — the UI must show a three-state status per submitted change:
  已提交(pending) → 已同步到设备(acked) → 同步失败(failed，附原因). Do not build a UI that assumes
  "submit succeeded" means "device now has the new value" — the device's acked value is the
  source of truth and may differ from what the user submitted.
- **Auth**: design doc recommends a real token scheme (JWT or similar) for `/api/login`, explicitly
  *not* the device's own zero-auth/no-token LAN login pattern. Whether mini-program auth and PC-client
  auth share one account system or are independent is an open product decision (see
  `yeweiyi-cloud`'s CLAUDE.md "Open product decisions") — don't assume either answer.

## No tech stack chosen yet

Framework (native 小程序 / uni-app / Taro / other), state management, and UI component library are
all open. Don't default to a choice without asking — this file intentionally stops at the contract
level. When a stack is chosen, this file should grow a "Build" and "Architecture" section the same
way the Android repo's CLAUDE.md has them.

## What's explicitly NOT this project's problem

- MQTT, device polling cadence, write-back queue mechanics, database schema — all `yeweiyi-cloud`'s
  responsibility. This repo only calls its REST/WebSocket API.
- LAN-only PC monitoring (需求A) and the PC remote client — separate concern, not this repo.
- Anything about the Android device app itself — `zhongchengyeweiyi`.
