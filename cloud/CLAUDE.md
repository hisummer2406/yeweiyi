# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: not started yet

This repo is an empty scaffold. Nothing has been built here — no language/framework has been
chosen, no code exists. This file exists to capture the *contract* this project must satisfy
before any implementation decisions get made, so a fresh session (or a fresh subagent) doesn't
have to re-derive it from scratch or from the sibling Android repo.

## What this project is

The cloud backend for **需求B（PC 端 / 小程序远程数据监控）** of 安卓液位仪 (Android Liquid Level
Meter, package `com.sansen.yeweiyi`) — an oil-tank monitoring device that runs on dedicated
hardware terminals. This backend is the missing middle layer between the device's MQTT client and
a WeChat mini-program (and optionally a remote PC client) that need to read/write device data when
they're **not** on the device's local network.

**Do not confuse this with 需求A (PC 局域网监控)** — that need is already solved by the device's
own embedded HTTP server (port 8080, LAN-only) and requires no backend at all. This project exists
*only* because a mini-program's HTTPS requests can't reach a device's LAN IP.

## Sibling repo — read before designing anything

`~/Developer/zhongchengyeweiyi` is the Android device-side repo. It is the **source of truth** for:

- The full MQTT contract (topics, payload shapes, request/response semantics) —
  `docs/小程序与PC端集成设计.md` in that repo, section 四 (§4.2 has the topic-by-topic mapping
  table) and section 五 (write-back/offline-queue design). **Read that file in full before writing
  any code here** — it was written specifically as the design input for this project and already
  answers most "how should this work" questions.
- The device-side data model — same doc, section 二, copied directly from the Android app's
  `db/*.java` xUtils entities. Cloud tables should mirror these field-for-field (see "cloud table
  design" below), not invent a parallel schema.
- Status/history of what's been fixed vs still open on the device side — that repo's
  `README.md` "已知缺陷登记" table and `docs/重构方案.md` §8.5.

Do not duplicate that design doc into this repo. Reference it. If the contract needs to change,
that's a conversation that touches both repos — the Android side's `BackService.java` is the other
half of the same interface.

## The MQTT contract (summary — full detail in the Android repo's doc)

- **Broker**: Aliyun IoT (阿里云 IoT). Topic pattern: `sys/{productKey}/{deviceName}/thing/...`.
  `productKey` is a per-product constant, `deviceName` is per-device (device secret or configured
  address).
- **Not yet provisioned**: as of 2026-08-19, no real Aliyun IoT product/device/credentials exist
  for this to connect to. The device-side code (`BackService.java`) that publishes/subscribes was
  legacy scaffolding — never live, never verified against a real broker. When this gets
  provisioned, issue fresh credentials; do not reuse any string that has ever appeared in the
  Android repo's git history (already-leaked by definition, see that repo's `harden-device-external-access`
  change §1.6).
- **Backend's three jobs** (from the design doc §4.1):
  1. Subscribe to `thing/event/*` (device-initiated: alarms, unload-oil events, online/offline
     status) and persist/cache.
  2. Publish `thing/service/*` requests for historical queries (alarms/unload-oil/shifts), receive
     `thing/event/property/post/*` responses, persist, and serve to clients from the DB — not by
     proxying a live MQTT round-trip per client request (device may be offline; MQTT has latency).
  3. Expose HTTPS REST/WebSocket to the mini-program. The mini-program never speaks MQTT directly.
- **"Realtime" monitoring is request/response, not a stream** — `requestOilcan` gets one snapshot
  reply per request; the backend must poll it on its own cadence (design doc suggests 10–30s,
  confirmed by product) and cache, not assume the device pushes continuously.

## Cloud table design — two things NOT to copy verbatim from the device schema

1. **Primary keys**: device-local `id` columns (SQLite autoincrement, starts at 1 per device) are
   **not globally unique** once more than one device exists. Use `(deviceName, device-local id)` as
   a composite unique key, or mint an independent cloud primary key and demote the device's `id` to
   a plain field. Design this in from day one — retrofitting it after a second device is onboarded
   is expensive.
2. **Extra fields every table needs that the device schema doesn't have**: `deviceName`,
   `syncStatus`, and a cloud-side received-timestamp (distinct from the device's own event
   timestamp — they diverge whenever a device was offline and is backfilling).

## Write-back (cloud/mini-program → device) — offline queue is mandatory, not optional

Product decision already made (see Android repo's issue #4, decision 1): remote clients can
**write**, not just read — tank/probe config, corrections to unload-oil/shift records. Since the
device may be offline when a write is requested, this needs a persistent queue per device
(`id, deviceName, commandType, payload, status(pending/sent/acked/failed), attempts, createdAt`),
FIFO delivery on reconnect, idempotent commands (describe target state, not deltas — safe to
redeliver), and a TTL (design doc suggests aligning with the ~30-day figure used on the device-side
local retry queue, exact number is a product call) so a permanently-offline/decommissioned device
doesn't accumulate an unbounded backlog.

**The device is the final arbiter.** When it acks a write, the device's actual resulting value
overwrites the cloud copy — not the value the client optimistically requested.

This queue is symmetric with (but the opposite direction of) the device-local MQTT-send retry
queue already implemented in the Android repo (`connect-mqtt-reporting-channel` change,
`MqttReportQueue` + `ReportQueueExpiry`) — same shape of problem, same TTL-based answer, worth
looking at that implementation as a reference even though the direction and storage are different.

## Device-side work this project depends on (not yet done, tracked in the other repo)

These are **blockers or near-blockers** for this backend's write path and for the PC-side shift
feature — check their status in the Android repo before assuming they're ready:

- `requestTankTable`/`requestTankSet`/`requestProbeSet` — device subscribes to these three
  `thing/service/*` topics but currently has no handling logic (dead-letter). Needed for remote
  config write-back to actually take effect on-device.
- No "correct an existing record by id" capability on-device yet — only "insert a new record" via
  the probe/shift flows. Needed for the unload-oil/shift correction feature.
- No "operation acknowledged" MQTT message semantics yet — device's existing `thing/service/*`
  responses are all query-result shaped, not confirmation-shaped. The write-back queue's `acked`
  state depends on this existing.
- `CommitWork` handler for PC's LAN HTTP path doesn't exist yet either (unrelated to this repo, but
  relevant if PC-side shift viewing matters for launch scope).

## Open product decisions (do not silently assume an answer)

- How long offline before a device is considered "abnormal" (affects Will-message / online-status
  alerting design) — explicitly unresolved in the design doc.
- Whether `CommitWork.info` (a pre-serialized JSON string on-device) gets stored as-is or parsed
  into structured cloud columns.
- Exact TTL day-count for the write-back queue (see above — a number, not a mechanism, is missing).
- Whether PC-remote and mini-program share one account/auth system or two independent ones.

## What's explicitly NOT this project's problem

- LAN-only PC monitoring (需求A) — already works, no backend involved, don't touch.
- The device's own local retry queue for outbound MQTT sends — that's `zhongchengyeweiyi`'s
  `connect-mqtt-reporting-channel` change, already implemented there.
- Fixing the device's default `admin`/`admin` LAN login or its previously-hardcoded MQTT
  credentials — those are `harden-device-external-access` in the other repo. This project consumes
  whatever real MQTT credentials get issued; it doesn't fix how the device stores them.

## No tech stack chosen yet

Language, framework, database, and hosting are all open. Don't default to a stack without asking —
this file intentionally stops at the contract level. When a stack is chosen, this file should grow
a "Build" and "Architecture" section the same way the Android repo's CLAUDE.md has them.
