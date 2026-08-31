# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status: tech stack decided, no code written yet

This repo is still an empty scaffold — no code exists yet. The tech stack (Go, see "Tech stack"
below) was decided on 2026-08-20; nothing has been scaffolded against it yet. This file exists to
capture the *contract* this project must satisfy before implementation starts, so a fresh session
(or a fresh subagent) doesn't have to re-derive it from scratch or from the sibling repos.

## What this project is

The cloud backend (only) for **需求B（PC 端 / 小程序远程数据监控）** of 安卓液位仪 (Android Liquid
Level Meter, package `com.sansen.yeweiyi`) — an oil-tank monitoring device that runs on dedicated
hardware terminals. This backend is the missing middle layer between the device's MQTT client and
a WeChat mini-program (and optionally a remote PC client) that need to read/write device data when
they're **not** on the device's local network.

**The mini-program frontend itself lives in a separate repo, `~/Developer/yeweiyi-miniapp`** (split
out 2026-08-20, also not started yet). This repo exposes REST/WebSocket for it to consume; it does
not contain any mini-program UI code. If the API contract changes, update that repo's CLAUDE.md
too — it currently documents the same draft API shape as §4.4 below.

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
  address). **This was the design doc's original assumption, from the device side — see "Device
  connectivity platform" below for why the cloud side moved off Aliyun IoT Platform entirely
  (2026-08-29) and what that means for this topic pattern once the device side's own protocol gets
  revisited.**
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

**Corrections found 2026-08-20 by reading `BackService.java`/`MqttUtil.java`/the relevant bean
classes directly, not just the design doc's summary tables** (same lesson as the `OilCanSet`
cross-check above — go to the source, the doc's summaries have real gaps):

- **Topic direction, precisely**: `MqttUtil.getService` (`.../thing/service/{id}`) is what the
  *device* subscribes to — the backend publishes requests there. `MqttUtil.getPropert`
  (`.../thing/event/property/post/{id}`) is what the *device* publishes responses to — the backend
  subscribes there. `MqttUtil.getEvent` (`.../thing/event/{id}/post`) is used for `isOline` (Will +
  online-status, retained) and `msg` (real-time alarm push) — backend subscribes to both.
  `internal/mqtt` subscribes with `+` wildcards for `deviceName` (`sys/{productKey}/+/thing/...`)
  rather than one subscription per device — originally justified by an assumed ~10,000-device scale
  target; the real target is much smaller (see "Real device scale" below), but the wildcard design
  has no real downside at the smaller size either, so it was kept as-is. This is a Go-backend
  architectural choice with no equivalent on the device side (each device only ever handles its own
  single-device topics), and — see the 2026-08-29 broker correction below — it depends on being
  connected to a broker product whose ACL model actually grants that permission, which Aliyun IoT
  Platform (originally assumed) turned out not to.
- **The realtime snapshot bean is `NetOilCan`, not "`BaseOilCan`"** — no `BaseOilCan` class exists
  anywhere in the Android repo. `OilcanDataManager.getNetOilcans()` returns `List<NetOilCan>` and is
  used for *both* the LAN `/baseOilcan` HTTP endpoint and MQTT's `requestOilcan` response, so the
  design doc's "shared structure" claim is right, but its field list (`id/oilType/level/volume/
  weight/temple/oilRadio/waterRadio/connet/stateInfo`) doesn't match `NetOilCan.java` at all — see
  `NetOilCanPayload` in `internal/mqtt/payloads.go` for the real ~18-field shape.
- **JSON field names on the wire are the Java field names, not the SQLite `@Column` DB names** —
  Gson (`GsonUtil`, plain `new Gson()`, no `@SerializedName` anywhere in `Alarm`/`OilInOut`/
  `CommitWork`) serializes by field name. Concretely: `Alarm`'s MQTT JSON has `guanHao`/`jieZhi`
  (DB columns are `guanhao`/`jiezhi`); `OilInOut`'s has `beginHeigth`/`endHeigth`/`beginVolum`/
  `endVolum` (device's own field-name typos, not transcription errors — DB columns are
  `beginheigth`/`begivolum`, spelled differently again); `CommitWork`'s JSON key for the shift-info
  blob is `info` (DB column is `oilinfogson`). `internal/mqtt/payloads.go`'s payload structs use the
  verified wire names; `internal/store/db`'s generated models use the DB column names — the two
  intentionally disagree, don't "fix" one to match the other.
- **Device timestamps are `"yyyy-MM-dd HH:mm"`, no seconds, no timezone** —
  `DateUtils.getYYYMMddHHmmss()` (used for `Alarm.time`, `OilInOut.time`, `CommitWork.time`/
  `endTime` on every live call site checked) has the seconds-including format commented out in
  source despite the method name. No timezone is embedded; `internal/mqtt` assumes Asia/Shanghai
  device-local time (`deviceLocation` in `internal/mqtt/handlers.go`) — a reasonable guess for this
  product, not verified against a real device.
- **Two more query flows exist on-device with no cloud counterpart yet**: `requestOilLeak` (backed
  by the `Sideleakage` table — 测漏/侧漏, side-leak detection) and `requestOilStorage` (backed by
  `OilSaveInfo`, a *different* table from `OilInOut`/`requestUnloadOil` despite the similar name).
  Neither is in the design doc's §4.2 table. `internal/mqtt` subscribes to both response topics
  (they're covered by the property/post wildcard) but currently just logs "no handler" — no cloud
  table, no ingestion, by design until product decides these are in scope.
- **`CommitWork.info`'s raw-vs-parsed question (see "Open product decisions") has a data-quality
  wrinkle**: a dead method (`DBDataManager.saveCommitWorkRecord`, defined but never called from
  anywhere in the app) constructs a `CommitWork` with its constructor args in the wrong order,
  putting a user-id string into the `endTime` field. Confirmed unreachable — the real shift-close
  path (`DBDataManager.CommitWork(int, String, SparseArray)`) sets `endTime` correctly via a setter
  — so this doesn't affect real data, but don't resurrect that dead method without fixing the bug.
- **`requestOilStorage` is one string constant with two unrelated MQTT meanings — verified
  2026-08-29 while checking the Android repo's `connect-mqtt-reporting-channel` follow-up
  (commit `7d1be58`)**: the identifier this project's `internal/mqtt` subscribes to under
  `thing/event/property/post/requestOilStorage` (the `OilSaveInfo` paginated query response, per
  "Two more query flows" above) is a *different channel* from a second, AIDL-triggered
  `sendUnloadOils(String)` method that commit added to `BackService`'s outer class (distinct from
  the `PushCallback`-internal `sendUnloadOils(int,int,...)` that answers the query) — it publishes
  to `thing/event/requestOilStorage/post` (an `eventTopic`, real-time push, no `property/post`
  segment), using `MqttUtil.getEvent(productKey, deviceName, requestOilStorage)`. Same literal
  topic-identifier string, two different topic shapes and semantics (query-response vs. live
  push). Neither is ingested yet (see "Two more query flows" above, still by design), but if
  `requestOilStorage` ever gets a cloud table, its handler must dispatch on the *topic shape*, not
  just the identifier string, or it will parse a live push as if it were a paginated query result.

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
- No **MQTT-reachable** "correct an existing record by id" capability — the device does have a
  general by-primary-key update path on-device (`DBDataManager.UpdateObject(Object)`, used by local
  UI flows like `InOilFragment`'s oil-in-out edit and the `CommitWork` edit dialog, confirmed
  2026-08-29), so this isn't a missing on-device capability so much as a missing `thing/service/*`
  entry point to it — no topic exists yet that lets a remote write reach `UpdateObject` for
  unload-oil/shift records. Needed for the unload-oil/shift correction feature.
- No "operation acknowledged" MQTT message semantics yet — device's existing `thing/service/*`
  responses are all query-result shaped, not confirmation-shaped. The write-back queue's `acked`
  state depends on this existing.
- `CommitWork` handler for PC's LAN HTTP path doesn't exist yet either (unrelated to this repo, but
  relevant if PC-side shift viewing matters for launch scope).

## Known cloud-side gaps not yet built (found 2026-08-29, no Android-side dependency)

Unlike the section above, nothing here is blocked on the other repo — these just haven't been
written yet.

~~No user↔device authorization model at all.~~ — **fixed 2026-08-29.** Pairing flow decided by
product the same day: **a mini-program user binds a device by scanning a QR code printed/displayed
on the physical device**, which encodes `deviceName` — possession of the physical device is the
authorization, the same trust model most consumer IoT pairing flows use. See `internal/api/devices.go`
and the 2026-08-29 authorization pass below (in "Build") for the implementation; left the
strikethrough here instead of deleting the bullet for the same reason as the partition one below.

~~No automatic partition creation for `alarms`/`oil_in_out`~~ — **fixed 2026-08-29**, see
`internal/partitions` in "Module layout"/"Architecture" below. Left the strikethrough here instead
of deleting the bullet so this section's "found 2026-08-29" items stay traceable to what they
originally were.

## Real device scale (corrected 2026-08-29)

Every "~10,000 devices" figure elsewhere in this file (the original "Why Go" justification in "Tech
stack", the wildcard-subscription rationale in "MQTT contract" and "Architecture") was a planning
assumption, never a product-confirmed target. **Actual target, confirmed 2026-08-29: ~100 devices
now, under 1,000 within 3–5 years.** This is a two-orders-of-magnitude correction. It doesn't
invalidate anything already built (Go, the wildcard-subscribe design, monthly partitioning on
`alarms`/`oil_in_out` all still work fine, and cost nothing extra, at the smaller real scale) — the
individual "~10,000" mentions elsewhere are left in place with pointers back to this section rather
than rewritten one by one, so this stays the single place a future session needs to check for the
real number.

## Device connectivity platform (corrected 2026-08-29, three times — current decision at the top)

**Current decision, 2026-08-29: self-hosted ThingsBoard (open source), not any Aliyun MQTT product,
not a bare broker.** This supersedes everything below it in this section — kept for the trail of
*why*, since this flipped twice in one day before landing here. Read "What's not yet decided" at the
end before assuming this is fully designed; it isn't yet, only the platform choice is settled.

**Why not Aliyun IoT Platform at all**: it stopped accepting new purchases/new instance creation
**2025-02-01** (confirmed via multiple independent sources — landian.news, v2ex.com/t/1100655 — and
retroactively explains the "文档停止维护" tag on most `help.aliyun.com/zh/iot/` pages cited elsewhere
in this file, which should have been a signal caught earlier in this file's own research and wasn't).
No new feature development either. Existing customers' instances keep running with no announced
shutdown date, but **this project can't provision a new one at all** — that's a harder blocker than
the ACL/ownership questions researched below, and makes all of that research moot for *this* project
regardless of whether it was otherwise correct.

**Why not 云消息队列 MQTT 版 (bare MQTT middleware, the previous decision earlier the same day)**:
that decision covered "a broker to move data," but the actual requirement turned out to be broader —
confirmed 2026-08-29 that a **console UI**, **OTA firmware delivery**, and **per-device credential
issuance/rotation (one-device-one-secret)** are real requirements, not nice-to-haves. A bare MQTT
product provides none of those; building all three from scratch on top of `internal/api` would be a
lot of net-new surface this project doesn't currently have any of.

**Why ThingsBoard specifically**: open source, self-hostable, and covers the three requirements above
out of the box — a device management console, an OTA-updates feature (versioned firmware/software
packages, grouped rollout, progress tracking), and per-device credentials (access token / X.509 /
MQTT username-password) with a provisioning flow for onboarding, which maps onto "一机一密." It also
has its own built-in MQTT transport, so it replaces the broker-choice question entirely rather than
sitting on top of one — 云消息队列 MQTT 版 is no longer needed either.

**Resource cost of this choice, confirmed 2026-08-29**: ThingsBoard's own docs recommend **2 vCPU /
8 GB RAM minimum for a single-node deployment** — one tier above the current 2c4g ECS, which is
already running Postgres + Redis + the Go backend. Adopting ThingsBoard means upgrading that ECS or
adding a second machine for it; user confirmed 2026-08-29 this cost is acceptable given the three
requirements above matter enough to pay for. **Decided 2026-09-01: upgrade the existing ECS**, not
add a second machine — no reason given beyond the choice itself, so don't backfill one. Worth noting
when sizing the upgrade: a 2026-09-01 web-research pass (done while designing the ThingsBoard
integration options below) found ThingsBoard's own docs give a lower minimum, **4 GB RAM, for a
single-node deployment backed by PostgreSQL only** (the 8 GB figure appears to assume Cassandra,
which this project has no reason to add) — unverified against this project's actual planned
device/message volume, and the existing ECS is already running Postgres + Redis + the Go backend
concurrently, so treat 4 GB as a lower bound to sanity-check against, not a target to upgrade to.

**What OTA does *not* solve on its own**: ThingsBoard's OTA feature is the cloud-side distribution
half only (store a firmware package, version it, target a device group, track rollout progress). The
device itself still needs client-side logic to receive, verify, and flash an update — that code does
not exist anywhere in `~/Developer/zhongchengyeweiyi` today, and picking ThingsBoard doesn't create
it. This is unchanged, still-real device-side work, tracked the same way as the rest of "Device-side
work this project depends on" below.

**What's not yet decided** (don't assume any of this is settled just because the platform is):

- ~~**How `internal/mqtt`/`internal/api` relate to ThingsBoard.**~~ — **decided 2026-09-01: ThingsBoard
  is scoped to device identity (provisioning/one-device-one-secret) and OTA only; it does not carry
  business MQTT traffic.** `internal/mqtt`/`internal/api` are unaffected — they keep talking to a
  separate, independent MQTT broker over the existing Alink-style `sys/{productKey}/{deviceName}/
  thing/...` topics, same as today, just pointed at that broker instead of the originally-assumed
  Aliyun IoT Platform. **The independent broker is self-hosted EMQX**, chosen 2026-09-01 over the two
  other candidates considered (再次评估 云消息队列 MQTT 版 now that it only needs to carry business
  messages, not console/OTA/credentials; and hardening the existing dev Mosquitto for production) for
  one deciding reason: EMQX is self-hosted, so the ACL question that blocked Aliyun IoT Platform and
  left 云消息队列 MQTT 版 "unverified, conflicting signals" (see the historical trail below) doesn't
  exist here — whether one backend client gets wildcard-subscribe permission is this project's own
  config choice, not a vendor's undocumented ACL model. Marginal ops cost of self-hosting a second
  thing (EMQX) alongside ThingsBoard was judged acceptable given self-hosting ThingsBoard itself was
  already accepted. `internal/mqtt`'s existing paho-based design, wildcard subscriptions, and
  `handlers.go`'s dispatch/persistence logic carry over unchanged under this choice — only the
  connection target and per-device credential format change — avoiding a repeat of the
  `internal/aliyuniot` AMQP/OpenAPI rewrite-then-delete detour from the Aliyun IoT Platform correction
  below.

  **ACL model empirically verified 2026-09-01** against a real (throwaway, Docker) EMQX 5.8.0
  instance — the exact test CLAUDE.md's own methodology called for, run before writing any real
  connection code: enabled EMQX's built-in-database authentication + authorization (`no_match: deny`
  so the test isn't accidentally passing on a permissive default; had to also disable the default
  `file` authorization source, whose stock `{allow, all}.` catch-all rule was silently masking the
  first attempt at a negative test), registered two simulated devices and one simulated backend
  client, and confirmed via `mosquitto_pub`/`mosquitto_sub` all five cases `internal/mqtt`'s real
  design needs: (1) backend, granted `subscribe` on `sys/{productKey}/+/thing/event/property/post/+`,
  receives a message a device publishes on its own concrete topic; (2) a second device, with no grant
  for another device's topic, **cannot** publish there (forged/impersonated publish silently
  dropped); (3) a device itself, with no subscribe grant, **cannot** subscribe to the backend's
  wildcard; (4) backend, granted `publish` on `sys/{productKey}/+/thing/service/+`, successfully
  publishes to one concrete device's service topic and that device (granted `subscribe` on only its
  own `.../thing/service/+`) receives it — the downstream direction, not just the upstream one; (5) a
  second device **cannot** subscribe to another device's service topic. All three "who() + action() +
  topic_match()" ACL primitives this project needs (self-scoped per-device publish/subscribe, plus
  one backend client's cross-device wildcard grant in both directions) work exactly as EMQX's own
  ACL rule spec describes, with no surprises — closing out the empirical-risk flag from the design
  discussion above. Throwaway container and all test users/rules were torn down afterward; nothing
  from this test persists in the repo or the dev stack. **Still not done**: EMQX hasn't been
  provisioned anywhere durable (dev or prod), `internal/mqtt` doesn't yet point at one, and this test
  used a manually-configured ACL over HTTP — it says nothing yet about *how* per-device credentials
  and their matching ACL rule get provisioned automatically at onboarding time (today's manual
  `curl`-the-dashboard-API approach obviously doesn't scale to "a new device gets paired via QR code
  and needs working MQTT credentials within seconds" — that provisioning-automation design is new,
  separate work, not yet started).

  Device-side impact of this whole split: small — `MqttUtil.java`'s
  existing topic shape and broker connection are largely unaffected (same reasoning as
  `internal/mqtt` above); the *only* new device-side surface is whatever client-side logic talks to
  ThingsBoard for provisioning/OTA, which was already unavoidable, ThingsBoard-role-independent work
  (see "What OTA does *not* solve on its own" above) — still needs deciding with whoever owns
  `~/Developer/zhongchengyeweiyi`, not unilaterally here, same caveat as always for that repo. Two
  separate device identity registries now exist (ThingsBoard's own device table, and this project's
  `devices`/`user_devices`) that need to agree on the same `deviceName` string per device — nothing
  in code enforces that agreement yet, worth keeping in mind once either side's provisioning flow is
  actually implemented.
- ~~**ECS sizing**: upgrade existing vs. add a second machine~~ — **decided 2026-09-01: upgrade the
  existing ECS**, see the resource-cost point above. The exact target spec (how much RAM/CPU to
  upgrade to) is still open.
- Nothing has been provisioned or installed for ThingsBoard yet — this is a decision, not an
  implementation. As with both prior plans in this section, nothing existed to migrate away from
  either, so — still true a third time — now remains the cheapest point to make this call.

---

*Historical trail below, kept for why the decision moved twice before landing above — the technical
content is still accurate for its own product, just no longer the chosen path.*

**First correction**: `internal/mqtt`'s "one paho client, wildcard-subscribe/publish across the
whole product" design does not work against **阿里云物联网平台 (Aliyun IoT Platform)** — found while
starting to configure real credentials for that product, before any had actually been provisioned.
Aliyun IoT Platform's device ACL model authenticates one MQTT connection as one specific device
(one-device-one-secret, `productKey`+`deviceName`+`deviceSecret`), scoped to that device's own
`/${productKey}/${deviceName}/...` topics only — there is no "authenticate as the product, subscribe
with `+`" mode. The `sys/{productKey}/+/thing/...` wildcard subscriptions `internal/mqtt/client.go`'s
`onConnect` sets up only work against the local Mosquitto container because that container runs with
anonymous auth and no ACL at all (see `dev/mosquitto/mosquitto.conf`), which silently hid this gap in
local dev and in `internal/mqtt`'s integration tests. Confirmed against `help.aliyun.com/zh/iot/`:
Aliyun IoT Platform's actual supported mechanisms for a backend to reach "all devices under a
product" from one connection are two different protocols split by direction — **AMQP server-side
subscription** (uplink, AccessKey/Secret-authenticated consumer group) and the **`Pub` OpenAPI
action** (downlink, an AccessKey/Secret-signed HTTPS RPC call, not an MQTT publish) — neither of
which is what `internal/mqtt` does.

A package `internal/aliyuniot` was written to implement that AMQP-server-side-subscription +
Pub-OpenAPI split (`amqp.go`/`publisher.go`, `github.com/Azure/go-amqp` +
`github.com/alibabacloud-go`'s generated `iot20180120` client), reusing `internal/mqtt`'s topic
parsing/persistence logic via a new transport-agnostic `RouteMessage`/`NewDispatcher` entry point
(`internal/mqtt/router.go`) rather than duplicating it. **This has since been deleted** — see the
second correction below — but the underlying research about Aliyun IoT Platform's ACL model above is
still accurate and worth keeping if that product is ever reconsidered; only the "so we built
`internal/aliyuniot`" conclusion changed.

**Second correction, same day**: re-evaluated the broker choice entirely once the real device scale
(see "Real device scale" above) made "阿里云 IoT 有点贵" less clear-cut, and considered self-hosting
(EMQX on the existing single ECS — no clustering needed at this scale) as an alternative to any
managed Aliyun product. That led to checking Aliyun's actual public/enterprise-instance pricing for
IoT Platform (新版公共实例 is free but capped at 50 concurrent online devices / 500 max devices / 5
message TPS — already below the current ~100-device count, so not viable even today; 企业版实例 is a
CU-priced subscription, exact cost for this scale not confirmed from docs alone), and separately
surfaced a **different Aliyun product that had been mentioned but not yet evaluated: 云消息队列 MQTT
版 (Message Queue for MQTT)** — general-purpose MQTT middleware, not a device-management platform.

**Decision: use 云消息队列 MQTT 版 (Serverless, pay-as-you-go), not Aliyun IoT Platform, and not a
self-hosted broker.** This decision stands on pricing/ops-burden grounds alone (see the pricing point
below), which don't depend on the ACL claim next to it — but that ACL claim was checked again
2026-08-29 and downgraded from "confirmed" to "unverified, conflicting signals", so read it as such:

- **UNVERIFIED, re-checked and downgraded 2026-08-29**: the claim that this product's ACL model lets
  a specific Username/ClientID be granted **wildcard-subscribe permission across a multi-level
  topic** — which is what would let `internal/mqtt`'s original design (one
  `eclipse/paho.mqtt.golang` client, wildcard subscribe) work unchanged, avoiding an
  AMQP/OpenAPI-style split the way Aliyun IoT Platform needed. This claim first came from a WebSearch
  summary ("一个消费者可以订阅多个 Topic（包括使用通配符订阅多级 Topic），从而接收来自多个设备发布的消
  息"), repeated near-verbatim across 3 independent search queries — consistent enough to not be a
  one-off fabrication — but re-verification via direct `WebFetch` of the pages that should contain
  this (service-introduction-terms, authentication-overview) either didn't surface it or reported it
  isn't there, likely because the real page renders that section client-side in a way the fetch
  didn't capture, but that's a guess, not a confirmed explanation. A separate, lower-confidence
  signal from an Aliyun developer-forum Q&A adds a specific complication: **"一机一密暂不支持 pub、sub
  细粒度鉴权"** (the one-device-one-secret auth mode reportedly doesn't support fine-grained pub/sub
  permissions) — if true, getting the "devices publish only to their own topic, backend
  wildcard-subscribes everything" split working might require the separate "自定义鉴权" (custom
  authentication) mode instead of 一机一密, which is a real design difference, not a detail. **Don't
  start implementing against this product assuming the wildcard grant works** — this is cheap to
  settle empirically instead of by more searching, since Serverless instances are pay-as-you-go and
  essentially free to spin up for a test: create one, register two test clients, and directly try
  granting one of them wildcard-subscribe over the other's topic before writing any real code or
  finalizing the topic scheme below.
- Serverless pricing is usage-based and cheap at this scale, confirmed from
  `help.aliyun.com/zh/apsaramq-for-mqtt/`'s own billing examples: 0.003 元/connection/day, 0.0002
  元/subscription/day, 0.04 元/万条 messages, with a reserved allowance (500 concurrent connections,
  5000 subscriptions per day) covered by a flat ~2.5 元/day base fee. At ~100–1,000 devices with
  10–30s polling this lands somewhere in the single-to-low-double-digit RMB/day range — not
  precisely computed (message-count assumptions weren't pinned down), but clearly cheap relative to
  what "阿里云 MQTT 有点贵" originally assumed, and self-hosting's ops burden (TLS, device auth
  design, security patching) isn't worth taking on to chase savings that small. This point doesn't
  depend on the ACL question above being resolved favorably.

**Not yet done**: no code targets 云消息队列 MQTT 版 specifically yet, and per the ACL point above,
don't assume `internal/mqtt` works against it unchanged until that's been tested against a real
instance — if the wildcard grant turns out not to work the way hoped, the AMQP/OpenAPI-style split
`internal/aliyuniot` did for Aliyun IoT Platform (deleted, see above — was real, working, tested code
before deletion) is the fallback shape to reach for again, adapted to whatever this product's actual
per-device auth constraint turns out to be. Also still open, deliberately not decided here: the
actual **topic naming scheme**. Aliyun IoT Platform's
`sys/{productKey}/{deviceName}/thing/service/{id}`-style topics are that product's own
Alink/Thing-Model convention; 云消息队列 MQTT 版 doesn't impose one, so this project gets to define
its own topic shape — could keep the same shape for continuity with the Android repo's existing
`MqttUtil.java` constants, or take the opportunity to simplify, but either way **this touches the
device side** (`~/Developer/zhongchengyeweiyi`, `BackService.java`/`MqttUtil.java`) and needs to be
decided with whoever owns that repo, not unilaterally here — and should probably wait until the ACL
question above is settled, since the answer affects what topic shape is even viable. As with the
original Aliyun IoT Platform plan, nothing has been provisioned for this product yet either — see
"MQTT contract" above — so, same as before, now is the cheapest time to make this call, before either
side has shipped against a specific contract.

## Open product decisions (do not silently assume an answer)

- How long offline before a device is considered "abnormal" (affects Will-message / online-status
  alerting design) — explicitly unresolved in the design doc.
- Whether `CommitWork.info` (a pre-serialized JSON string on-device) gets stored as-is or parsed
  into structured cloud columns.
- Exact TTL day-count for the write-back queue (see above — a number, not a mechanism, is missing).
  The mechanism now exists (`internal/queue`'s `TTLSweepWorker`, default 30 days via
  `queue.DefaultTTL`, overridable with `WRITE_BACK_TTL_DAYS` — see "Build"/"Architecture" below) —
  don't read the 30-day default as the product decision, it's a placeholder mirroring the design
  doc's example number.
- ~~Whether PC-remote and mini-program share one account/auth system or two independent ones.~~
  **Decided 2026-08-20: one shared account/auth system.** A single user table and JWT issuance path
  serves both PC-remote and mini-program clients — no per-client-type account silo. See "Tech stack"
  below for the auth library choice this unblocked.
- ~~How does a user's account get associated with a physical device?~~ **Decided 2026-08-29: QR
  code.** The mini-program binds a device by scanning a QR code printed/displayed on the physical
  device (encoding `deviceName`) — see `internal/api/devices.go` and the 2026-08-29 authorization
  pass in "Build". Still open: what exactly the QR code's content format is beyond "encodes
  deviceName somehow" (a bare string? a URL/deeplink wrapping it? does it need a signature/checksum
  to prevent a forged/guessed QR code from binding a device the scanner never actually touched? —
  the current cloud endpoint trusts whatever `deviceName` string the client sends, which is fine
  under "possession of the physical device is the authorization" as long as the QR's *content* can
  only be obtained by seeing the physical device, but that property depends on what actually gets
  printed on it — a decision for whoever designs the physical label/screen, not made here) and
  whether PC-remote clients get a pairing flow at all or are mini-program-only for now.

## What's explicitly NOT this project's problem

- LAN-only PC monitoring (需求A) — already works, no backend involved, don't touch.
- The device's own local retry queue for outbound MQTT sends — that's `zhongchengyeweiyi`'s
  `connect-mqtt-reporting-channel` change, already implemented there.
- Fixing the device's default `admin`/`admin` LAN login or its previously-hardcoded MQTT
  credentials — those are `harden-device-external-access` in the other repo. This project consumes
  whatever real MQTT credentials get issued; it doesn't fix how the device stores them.

## Tech stack

Decided 2026-08-20: **Go**.

- **Web/API**: `gin` for REST; `gorilla/websocket` for `/ws/alarms`. Decided 2026-08-20 over
  `echo`/`chi` (gin has the broadest Go community adoption, meaning the most training-data coverage
  for AI-authored code) and over `fiber` (its `fasthttp` base pools/reuses the request context —
  a request/context reference kept past the handler's lifetime, e.g. passed into a goroutine for
  async processing, silently reads stale data; this class of bug isn't caught by the compiler,
  unlike the type/error-handling mistakes Go's compiler does catch, so it's worth avoiding
  deliberately). `gorilla/websocket` over `nhooyr.io/websocket`/`coder/websocket` for the same
  training-data-coverage reason, despite the latter having a more modern API.
- **Device connectivity platform**: originally planned as 阿里云物联网平台 (IoT Platform) using
  `eclipse/paho.mqtt.golang` directly. **Corrected 2026-08-29, three times — see "Device
  connectivity platform" below for the full story, current decision at its top.** Short version:
  IoT Platform turned out to have stopped accepting new purchases since 2025-02-01 (can't be used
  for this project at all, regardless of its ACL model, which was also a problem); a same-day
  detour through 云消息队列 MQTT 版 (bare MQTT middleware) got dropped once console/OTA/per-device
  credential requirements turned out to be real needs, not nice-to-haves. **Current decision:
  self-hosted ThingsBoard** (open source device-management platform — console, OTA delivery, device
  credentialing, and its own built-in MQTT transport, so no separate broker product is needed).
  `internal/mqtt`'s existing paho-based design is not confirmed to carry over unchanged — ThingsBoard
  has its own topic/API conventions, see "Device connectivity platform" below for what's still open.
- **Database**: PostgreSQL. `sqlc` for the query layer — decided 2026-08-20 over `gorm`. sqlc's
  errors surface at `sqlc generate`/build time (SQL referencing a dropped column, type mismatches),
  the same compile-time-safety argument that decided Go over Python; GORM's reflection-based query
  building only surfaces the same class of error at runtime. sqlc also has no abstraction to fight
  when writing the Postgres-specific SQL this schema needs anyway — partitioned-table DDL (below)
  and composite-unique-key `ON CONFLICT ... DO UPDATE` upserts (needed for §5.2's "device is final
  arbiter" overwrite) are natural to write directly and awkward through GORM's fluent API. Paired
  with `golang-migrate` (decided 2026-08-20 over `goose` — the more common pairing with sqlc, most
  training-data coverage) for schema/partition migrations — sqlc doesn't manage schema itself; the
  migration files are also sqlc's schema input. Migrations start from the very first `CREATE TABLE`,
  not retrofitted later — cheapest to adopt from commit one on a new project, and periodic partition
  creation for `Alarm`/`OilInOut` is itself an ongoing migration, not a one-time setup step.
  `Alarm`/`OilInOut` should be time-partitioned given the expected device count (see below) — they're
  the high-write-volume event tables; `OilCanSet` and the write-back queue table stay small and don't
  need partitioning.
- **Write-back queue** (design doc §5.2): `riverqueue/river` — decided 2026-08-20 over `asynq`.
  river is Postgres-backed, so a pending command can be enqueued in the *same DB transaction* as any
  other business-state write, eliminating the dual-write inconsistency window `asynq` (Redis-backed)
  would have between "business row committed" and "task enqueued." This also keeps Redis strictly a
  cache (safe to flush, no durable state) rather than a second source of truth. Cost: river is newer
  (2023-ish) with less community/training-data coverage than `asynq`, and no equivalent of
  `asynqmon`'s dashboard.

  **Important scoping note**: neither library models the design doc's full `pending → sent → acked`
  flow — that's specific to this project, not a generic job-queue shape. river only owns the
  `pending → sent` hop (the "job" is "publish this MQTT message"; success = published). The
  `sent → acked` transition happens later, driven by an *unrelated* inbound MQTT message (the
  device's confirmation, carrying the same command id) — that has to be handled by a bespoke MQTT
  event handler that updates the command row directly, not through river. Same for the 30-day TTL
  sweep (§5.3) — a periodic job (river supports these) that scans the state table for expired
  pending/sent rows, written by hand. Don't assume river gives this state machine for free; it only
  gives reliable at-least-once delivery for one hop of it.
- **Realtime snapshot cache**: Redis (`go-redis/redis`) for the polled `requestOilcan` snapshot
  (design doc §4.3) and as pub/sub backing for `/ws/alarms` if fan-out across multiple API instances
  is needed.
- **Hosting**: Aliyun (阿里云) ECS — decided 2026-08-20. Plain VM over 容器服务 ACK/函数计算 — no
  containerization/serverless complexity needed for a single Go binary at this stage. The original
  "same cloud as the MQTT broker, so the ingestion path stays intra-cloud" reasoning assumed Aliyun
  IoT Platform; per "Device connectivity platform" above, the device connectivity platform is now
  self-hosted ThingsBoard, on this same ECS — decided 2026-09-01 to upgrade the existing box rather
  than add a second machine (see that section) — so the intra-cloud/low-latency reasoning still
  applies, just because it's the same physical infrastructure rather than the same managed product.
- **Auth**: JWT via `golang-jwt/jwt` for token issuance/validation, `golang.org/x/crypto/bcrypt` for
  password hashing at `/api/login`. One shared user table/token scheme serves both PC-remote and
  mini-program clients (see "Open product decisions" above — that question is now resolved).
  Explicitly not the device's own LAN `/login` pattern (plaintext table lookup, no token, no
  session) — design doc §4.4 flags that as something to not replicate.

**Why Go, over the Python/Node alternatives that were also considered**: originally justified here by
an assumed ~10,000-device target, reasoning that at the product-confirmed 10–30s polling cadence
(see "MQTT contract" above) the polling/MQTT-ingestion core would see roughly 330–1,000
service/response round-trips per second — a concurrency profile Go's goroutine-per-device model
absorbs natively, without the multi-process device-sharding a Python asyncio service would need to
engineer in from day one at that scale. **Corrected 2026-08-29: real target is ~100 devices now,
under 1,000 within 3–5 years** (see "Real device scale" below) — at that size the round-trip rate is
low single digits to double digits per second, and a single-process Python/Node service would handle
it without any sharding. The concurrency argument above doesn't actually apply at this project's real
scale; it's kept here (struck through in spirit, not deleted) so a future session doesn't re-derive a
now-inaccurate justification from scratch. **This is not a reason to reconsider Go** — a substantial,
tested Go codebase already exists (migrations, API, MQTT ingestion, write-back queue), and the other
half of the original reasoning is scale-independent and still holds: this codebase is written
primarily by Claude, and Go's compiler catches a class of AI-generated-code mistakes (type
mismatches, unhandled error paths) at `go build` time rather than at runtime — a tighter, more
deterministic feedback loop than Python offers when a human isn't hand-authoring every line. The
team being equally comfortable in Go and Python was never the deciding factor either way.

## Build

Module `yeweiyi-cloud` (Go 1.26, see `go.mod`). Verified 2026-08-20 against the local dev stack
(`docker compose up -d`, see `docker-compose.yml`):

```
go build ./...                  # compiles cleanly
go vet ./...                    # clean
sqlc generate                   # regenerates internal/store/db from db/migrations + db/queries
migrate -database "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable" \
        -path db/migrations up  # applies all migrations; down-then-up round-trip verified for each
go test ./...                   # internal/queue, internal/mqtt, internal/partitions, and (since
                                 # 2026-08-29) internal/api have real integration tests against
                                 # local postgres/redis; cmd/server and internal/store/db have none
JWT_SECRET=... MQTT_BROKER_URL=tcp://localhost:1883 MQTT_PRODUCT_KEY=... go run ./cmd/server
                                 # MQTT_BROKER_URL is optional — omit it to run without MQTT. This
                                 # still points at local Mosquitto for dev only — per "Device
                                 # connectivity platform" above, production is now decided to be
                                 # self-hosted ThingsBoard, not a raw broker this env var would point
                                 # at unchanged; no code or env-var shape exists for that yet.
```

River's own schema (`river_job`/`river_leader`/`river_queue`/`river_notification`) is folded into
`db/migrations` as `000002`–`000007`, one file per river migration version — river's library uses
`ALTER TYPE ... ADD VALUE` in one version and reads that new enum value in a later one, which
Postgres refuses inside a single transaction, so each version must stay its own golang-migrate file
(confirmed by hitting exactly that error when they were first combined into one file, then splitting
them). Dumped via `river migrate-get --version N --up/--down` (the `river` CLI, `go install
github.com/riverqueue/river/cmd/river@latest`) with `--exclude-version 1` (that version's
`river_migration` tracking table isn't needed — golang-migrate's own `schema_migrations` is the only
migration-tracking table this project uses).

Manually exercised end-to-end against the local containers (seeded a throwaway user/device/alarm
row, deleted afterward): `GET /healthz` → 200; `POST /api/login` → 401 on wrong password, 200 +
JWT on correct; `GET /api/alarms` without a bearer token → 401; with a valid token and
`device`/`beginTime`/`endTime` → 200 with the seeded row; `GET /api/alarms` with no `device` query
param → 400; `GET /api/oilcan/latest` → 404 (nothing writes to its Redis cache key yet, see below).
Also ran the built server binary directly (not `go run`, to get an exact PID) to confirm it still
boots with the river client started alongside the API.

`internal/queue` has two real `go test` integration tests against the local dev Postgres:
`TestEnqueueWriteBack_PublishesAndMarksSent` (enqueue → river picks up the job → fake `Publisher` is
called → row transitions pending→sent) and `TestTTLSweepWorker_MarksExpiredCommandsFailed` (a
backdated pending row gets marked failed by the sweep). Both seed and clean up their own rows.

`internal/mqtt` has real `go test` integration tests too, against a local Mosquitto broker
(`docker-compose.yml`'s `mosquitto` service — a stand-in for Aliyun IoT, anonymous auth, no TLS,
**not representative of the real broker's auth model**). `client_test.go` runs a second, independent
paho client (`deviceSim`) that publishes **hand-written JSON literals**, not payloads marshaled from
our own structs — this is deliberate, so the tests actually catch a wrong `json` tag instead of just
checking the code agrees with itself. Confirmed passing: an alarm published with the real device
field names (`guanHao`/`jieZhi`) lands correctly in `alarms`; an `isOline` event updates `devices`;
a `requestOilcan` response gets cached verbatim under `oilcan:latest:{device}` in Redis.

Beyond the package-level tests, the full binary was run end-to-end 2026-08-20 with
`MQTT_BROKER_URL` pointed at the local Mosquitto container: `mosquitto_pub` (run inside the
container) simulated a device publishing an alarm on `sys/e2etest/e2e-device-01/thing/event/
property/post/requestAlarm` → confirmed the row in Postgres → logged in via `POST /api/login` →
`GET /api/alarms?device=e2e-device-01&...` returned that exact row with the timezone conversion
correct (`"2026-08-15 09:20"` device-local → `"2026-08-15T09:20:00+08:00"` in the response). Same
end-to-end check repeated for `isOline` (→ `devices.is_online`) and `requestOilcan` (→
`GET /api/oilcan/latest` returning the cached snapshot instead of 404). All throwaway rows/keys
deleted afterward.

**What this proves**: the module wiring (gin, pgx/v5 pool, go-redis client, JWT/bcrypt auth,
river/riverpgxv5, paho.mqtt.golang), the sqlc-generated query layer, the migration schema (including
river's), the four read endpoints, and the write-back queue's pending→sent hop all fit together and
work against the local Postgres/Redis/Mosquitto containers — and, as of this pass, so does the full
MQTT ingestion path (alarm/online-status/oilcan-snapshot) end to end, and, as of the 2026-08-29 pass
below, so does `requestUnloadOil`/`requestShift`/the poller. **What it does not prove**:
`requestOilLeak`/`requestOilStorage` responses are logged, not ingested (no cloud table — see "The
MQTT contract" above); the queue's `Publisher` is still a placeholder that always errors in the real
binary (`unbuiltMQTTPublisher` in `main.go`) since the write-back publish topic/ack contract doesn't
exist on the device side yet — but as of 2026-08-29, `internal/api/writeback.go`'s four endpoints do
call `EnqueueWriteBack` (see the 2026-08-29 write-back pass below), so a command now reliably reaches
`pending` even though nothing yet carries it past `sent`; the sent→acked transition and the TTL day-count are
still open per "Open product decisions" below; the assumed Asia/Shanghai device timezone (see "The
MQTT contract" above) is unverified; and **none
of this has been exercised against the real Aliyun IoT broker** — there isn't one provisioned yet,
and Mosquitto's anonymous/no-TLS auth model isn't representative of what that connection will need.

**2026-08-29 follow-up pass**, closing the `requestUnloadOil`/`requestShift`/poller manual-coverage
gap noted above: rebuilt the binary, ran it against the same local containers (`MQTT_BROKER_URL`
pointed at Mosquitto), and used a throwaway hand-written-JSON device simulator (same rationale as
`client_test.go`'s `deviceSim` — real Java field names, not our own struct's marshaled output) to
publish a `requestUnloadOil` response (`beginHeigth`/`endHeigth`/`beginVolum`/`endVolum` field
names) and a `requestShift` response (`info`/`endTime` field names) on
`sys/e2etest/e2e-device-01/thing/event/property/post/*`. Confirmed both landed correctly in
`oil_in_out`/`commit_works` (device-local `"2026-08-15 09:20"`/`"...16:00"` converted to the correct
UTC rows) and round-tripped correctly through `GET /api/oil-in-out`/`GET /api/shifts` (back to
`+08:00` in the JSON response). Separately verified the poller end to end: published `isOline:true`
for a device, confirmed `devices.is_online` flipped, then confirmed the poller itself published a
`requestOilcan` request to `.../thing/service/requestOilcan` within one `DefaultPollInterval` tick
(no cloud-side trigger needed — this is `poller.go` autonomously polling `ListOnlineDeviceNames`) —
then replied to that request as the simulated device and confirmed the snapshot landed in Redis and
`GET /api/oilcan/latest` served it. All throwaway rows/keys/users deleted afterward; the device
simulator was a temporary `cmd/devicesim-e2e` never committed. Docker Desktop needed a full
container recreate (`docker rm` + `docker compose up -d`, not just `docker start`/`restart`) to get
past a stuck state where `docker start` connections were dropping with EOF on this machine — noted
here in case it recurs, it's an environment issue, not anything in this repo.

**2026-08-29 write-back REST pass** (`internal/api/writeback.go`): implements the REST entry points
design doc §5.2 always specified but that nothing called — `queue.EnqueueWriteBack` itself has been
tested since 2026-08-20 (see `internal/queue`'s tests above), the gap was purely that no HTTP handler
ever invoked it. Four new endpoints, all behind `authMiddleware`: `POST /api/oil-in-out/correct` and
`POST /api/shifts/correct` take a typed body mirroring `internal/mqtt`'s wire-format payload structs
(`beginHeigth`/`endHeigth`/... , `deviceLocalId`/`info`/...) — deliberately the device's own field
names, not this table's DB column names, since the stored payload is meant to reach the device
unchanged once a `Publisher` exists — and 404 via the new `GetLatestOilInOutByDeviceLocalID`/
`GetShiftByDeviceLocalID` queries if no record with that `(device, deviceLocalId)` exists yet.
`POST /api/devices/:device/oil-can-set` takes an **opaque** JSON body instead of a typed struct on
purpose: `requestTankTable`/`requestTankSet`/`requestProbeSet` have no on-device handling logic at
all yet (see "Device-side work this project depends on"), so there is no real wire contract to type
against, only a guess — typing it now would risk baking in a wrong shape. `GET /api/write-back`
lists a device's commands most-recent-first (new `ListWriteBackCommandsForDevice` query) for status
polling, re-shaping `payload` as `json.RawMessage` instead of the raw `[]byte` sqlc generates (which
`encoding/json` would otherwise base64-encode). Manually verified end to end against the local
containers: seeded a throwaway device/oil-in-out row/shift row/user, logged in, hit all four
endpoints — confirmed 202 + `status: pending` for a real correction, 404 for a `deviceLocalId` that
doesn't exist and for an unknown device, and `GET /api/write-back` returning all three commands with
readable (non-base64) payload JSON, most-recent-first. Every command stayed `pending` after
enqueueing, as expected — `unbuiltMQTTPublisher` still errors on every attempt, so river retries the
publish job on its own backoff without ever reaching `sent`; that's the existing, already-documented
limitation above, not something this pass changes. All throwaway rows/users deleted afterward.

**2026-08-29 `internal/api` test-coverage pass**: closes the gap flagged throughout this file —
`internal/api` had zero automated tests while every other package with real logic (`internal/mqtt`,
`internal/queue`, `internal/partitions`) had integration tests. Added `api_test.go` (shared
`testEnv` helper: real `pgxpool`/`redis.Client`/`river.Client` wired into a real `gin.Engine` via
`api.NewRouter`, driven through `httptest` — no mocks, same philosophy as the other packages' tests;
a seeded user gets a real JWT through the real `/api/login` handler rather than one minted by hand)
plus `health_test.go`, `auth_test.go`, `records_test.go`, `oilcan_test.go`, `writeback_test.go` — 22
tests total, covering every route: login success/failure, `authMiddleware` missing/invalid token,
all three list endpoints' required-`device`/seeded-row/unauthenticated paths, the oilcan cache's
404-when-empty and verbatim-passthrough-when-cached cases, and all four write-back endpoints'
202/404 paths plus the list endpoint's most-recent-first ordering and readable (non-base64) payload.
All pass against the local containers; each test seeds and cleans up its own rows/users via
`t.Cleanup`.

**2026-08-29 user↔device authorization pass**: closes the gap "Known cloud-side gaps" flagged —
adds a `user_devices` many-to-many binding table (migration `000008`; a device can have more than
one bound user, e.g. multiple staff on the same tank, and a user more than one device), three new
routes in `internal/api/devices.go` (`POST /api/devices/bind`, `POST /api/devices/unbind`,
`GET /api/devices` — the last one so a client can discover which `device_name`s it's even allowed to
query, since every other endpoint requires knowing the exact string already), and a
`requireDeviceOwnership(c, deps, deviceName)` check now called first thing in every device-scoped
handler: `records.go`'s three list handlers, `oilcan.go`, `ws.go` (which parses its own JWT
independently of `authMiddleware`, so it needed the same claims-shape change applied there too), and
all four of `writeback.go`'s handlers. Two implementation choices worth knowing:
- **Ownership is checked before any "does this record/device exist" check**, deliberately — so a
  caller who isn't authorized for a device gets the same 403 whether that device_name is someone
  else's real device or doesn't exist at all, and can't use response codes to enumerate real device
  names. This *changed* two response codes from their 2026-08-29 write-back pass values:
  `writeOilCanSetHandler`'s unknown-device case is now 403 (not 404 — the separate `GetDevice`
  existence check was removed as redundant, since an owned device is guaranteed to exist via
  `user_devices`'s foreign key to `devices`), and any correction against a real record on a
  not-yours device is 403 before it ever gets to the "does this deviceLocalId exist" 404 check.
  `internal/api/writeback_test.go`'s `TestCorrectOilInOut_ForbiddenWhenDeviceNotOwned` exists
  specifically to pin this ordering down.
- **The JWT gained a `uid` claim** (`apiClaims` in `auth.go`, wrapping `jwt.RegisteredClaims`) so
  `requireDeviceOwnership` can check `user_devices` directly from the token's claims instead of a
  `GetUserByUsername` round trip per request. `loginHandler` and `authMiddleware` both updated;
  `ws.go`'s independent claims-parsing updated to match.
- `bindDeviceHandler` calls `EnsureDevice` before inserting the binding — a device can legitimately
  get scanned/bound before it has ever sent an MQTT message (no `devices` row yet), same reasoning
  `internal/mqtt`'s handlers already use `EnsureDevice` for.

23 new/updated tests across `devices_test.go` (new) and the existing `*_test.go` files (which needed
a `bindDevice`/`ownDevice` test helper added to `api_test.go` and switched from bare `seedDevice` to
`ownDevice` everywhere a request now needs to actually succeed, not just 403). Manually verified
against the running binary too: unbound device → 403 on `/api/oilcan/latest`; `POST
/api/devices/bind` (simulating the QR-scan step) → 200; `GET /api/devices` lists it;
`/api/oilcan/latest` now 404 (owned, nothing cached — not 403); `POST /api/devices/unbind` → 200;
`/api/oilcan/latest` back to 403. Throwaway user/device rows deleted afterward. **What this doesn't
cover**: the actual QR code generation/display on the device side, and the mini-program's camera-scan
UI — both are `yeweiyi-miniapp`/Android-repo concerns, this pass is only the cloud API the pairing
step calls once a QR has been scanned and decoded into a `deviceName` string client-side.

**2026-09-01 table-comments pass** (`db/migrations/000009_table_comments`): documentation-only
migration, no DDL changes — attaches `COMMENT ON TABLE`/`COMMENT ON COLUMN` (in Chinese) to every
app-owned table (`users`, `devices`, `alarms`, `oil_in_out`, `commit_works`, `oil_can_sets`,
`write_back_commands`, `user_devices`) and their columns, so the schema is self-describing via
`psql \d+`/pgAdmin without needing this file open — river's own tables (`river_job`/etc.) are left
uncommented since they're not app schema. Verified against the local Postgres container: applied
cleanly to `version 9`, then a down-then-up round trip (`migrate ... down 1` → confirmed back at
`version 8` → `migrate ... up 1` → back to `version 9`, no dirty state either direction) per this
project's usual migration-verification convention, and spot-checked with `obj_description`/
`col_description` (e.g. `users` and `oil_in_out.begin_height`) to confirm the Chinese text actually
lands, not just that the migration runs.

`/ws/alarms` (2026-08-20, `internal/api/ws.go`) is implemented and manually verified end-to-end
against the local containers: seeded a throwaway user, ran the binary, connected with
`gorilla/websocket`'s client passing the JWT as a `token` query param (not the `Authorization`
header — see below), published two `alarms:live` messages via `redis-cli PUBLISH` (one for a
different device, one for the connected device's `deviceName`), and confirmed only the matching
device's message was delivered. Throwaway user/connection cleaned up afterward. Auth deliberately
diverges from `authMiddleware`: it accepts the bearer token as either the `Authorization` header
or a `token` query param, since browser/mini-program `WebSocket` clients can't set custom headers
on the handshake request — so this route is registered directly on `r`, not inside the `/api`
authorized group. Scoped to one `device` query param like every other endpoint (see
`internal/api/records.go`'s `listParams` comment for why "all devices" isn't a supported shape);
messages for other devices arriving on the shared `alarms:live` channel are filtered out
per-connection, not per-subscription — every open connection subscribes to the same Redis channel
and discards what isn't theirs, which is fine at today's scale but would need per-device Redis
channels or a smarter fan-out if connection count gets large.

Module layout (current, not just planned):

```
cmd/server/main.go   # entrypoint: wires gin + pgxpool + redis client + river queue client +
                     # (optional, if MQTT_BROKER_URL set) mqtt client + poller
cmd/devicesim/main.go # dev-only tool (2026-08-29): simulates a device against the local Mosquitto,
                     # publishing hand-written JSON (real device field names, not this repo's own
                     # marshaled structs — same rationale as internal/mqtt's deviceSim test helper)
                     # for alarm/unloadoil/shift/oilcan/online/offline/msg, plus a `watch` mode that
                     # subscribes to thing/service/* to observe the poller's (or, once
                     # queue.Publisher is implemented, the write-back queue's) outbound requests.
                     # `go run ./cmd/devicesim -h`-equivalent: run any subcommand with no flags to
                     # see the usage line. Lets local dev exercise the real internal/mqtt handler
                     # code path without hand-seeding Postgres/Redis or standing up a real device.
internal/mqtt/       # topics.go (topic build/parse, service vs property vs event), payloads.go
                     # (wire-format structs — see "The MQTT contract" corrections above), client.go
                     # (paho wiring, wildcard subscribe), handlers.go (dispatch + persistence),
                     # poller.go (§4.3 requestOilcan cadence), *_test.go (integration tests, see
                     # above). Local-dev-only for now (still points at Mosquitto) — was briefly
                     # replaced by an AMQP+OpenAPI split (internal/aliyuniot) to work around Aliyun
                     # IoT Platform's ACL model, reverted once that plan was dropped. Per the
                     # 2026-09-01 addition to "Device connectivity platform" below, production target
                     # is now self-hosted EMQX (not ThingsBoard — ThingsBoard is scoped to device
                     # identity/OTA only), and this package's design (paho, wildcard subscribe,
                     # handlers.go dispatch) is expected to carry over unchanged, just pointed at a
                     # different host/credentials — not yet done. The wildcard-subscribe ACL grant
                     # this design needs was empirically verified 2026-09-01 against a real (throwaway)
                     # EMQX instance — see "Device connectivity platform" below — but no durable EMQX
                     # instance exists yet and this package still points at Mosquitto.
internal/api/        # router.go, health.go, auth.go (JWT login + middleware, apiClaims), records.go
                     # (alarms/oil-in-out/shifts list handlers), oilcan.go (Redis-cached snapshot
                     # read), ws.go (/ws/alarms — see above), writeback.go (2026-08-29: write-back
                     # REST entry points — see below), devices.go (2026-08-29: bind/unbind/list +
                     # requireDeviceOwnership — see the authorization pass below); api_test.go/
                     # health_test.go/auth_test.go/records_test.go/oilcan_test.go/writeback_test.go/
                     # devices_test.go (2026-08-29: integration tests, see above)
internal/queue/      # client.go (river wiring), enqueue.go (transactional insert+enqueue),
                     # publish.go (pending->sent worker + Publisher interface), ttl_sweep.go
                     # (§5.3 periodic sweep), queue_test.go (integration tests, see above)
internal/partitions/ # partitions.go (2026-08-29): keeps alarms/oil_in_out's monthly range
                     # partitions pre-created a few months ahead — plain ticker goroutine, not a
                     # river job (river is scoped to the write-back queue per "Tech stack" below;
                     # CREATE TABLE IF NOT EXISTS is already idempotent/multi-instance-safe on its
                     # own). partitions_test.go is a real integration test against local postgres.
internal/store/db/   # sqlc-generated: db.go, models.go, querier.go, + one .sql.go per query file
db/migrations/       # golang-migrate schema; 000001 is the app schema, 000002-000007 are river's
                     # (see above), 000008 adds user_devices (2026-08-29 authorization pass),
                     # 000009 attaches COMMENT ON TABLE/COLUMN to every app table (2026-09-01
                     # table-comments pass, documentation-only, no DDL change)
db/queries/          # sqlc query input — devices.sql, users.sql, user_devices.sql (2026-08-29:
                     # BindUserDevice/UnbindUserDevice/ListDevicesForUser/UserOwnsDevice),
                     # alarms.sql, oil_in_out.sql,
                     # commit_works.sql (each of the last three now has an Upsert*, used by
                     # internal/mqtt, alongside the List* used by internal/api; oil_in_out.sql and
                     # commit_works.sql also each have a GetLatest*ByDeviceLocalID/Get*ByDeviceLocalID,
                     # added 2026-08-29 for writeback.go's correction endpoints to check a record
                     # exists before enqueueing),
                     # write_back_commands.sql (now also has ListWriteBackCommandsForDevice, added
                     # 2026-08-29 for GET /api/write-back) — sqlc.yaml at repo root wires
                     # schema=db/migrations, queries=db/queries, out=internal/store/db
dev/mosquitto/       # mosquitto.conf for the local dev broker (anonymous auth, no TLS — dev only)
```

## Architecture

- `cmd/server/main.go` builds a `pgxpool.Pool` and a `redis.Client` from `DATABASE_URL`/
  `REDIS_ADDR` (env, with localhost defaults matching `docker-compose.yml`), a JWT signing key from
  the required `JWT_SECRET` env var, and a `queue.Client` (TTL overridable via
  `WRITE_BACK_TTL_DAYS`, see "Open product decisions"), then hands the API deps to
  `internal/api.NewRouter` and starts both. If `MQTT_BROKER_URL` is set, it also builds an
  `internal/mqtt.Client` (product key/credentials from `MQTT_PRODUCT_KEY`/`MQTT_USERNAME`/
  `MQTT_PASSWORD`) and starts an `internal/mqtt.Poller` goroutine; omitting the env var skips MQTT
  entirely (no broker exists in most dev environments, and none exists for the real Aliyun IoT
  broker yet either). Listens on `LISTEN_ADDR` (default `:8080`).
- `internal/api` is the REST surface from design doc §4.4, deliberately deviating from that doc's
  draft routes in one way: every list endpoint requires a `device` query param, since CLAUDE.md's
  multi-device cloud table design means there's no well-defined "all devices" listing. `POST
  /api/login` issues an HS256 JWT (24h TTL, see `tokenTTL` in `internal/api/auth.go`) after a bcrypt
  password check against `users`; `GET /api/alarms`, `/api/oil-in-out`, `/api/shifts` all share the
  same `device`+`beginTime`+`endTime`+`limit`+`offset`+`sort` query-param shape and are behind the
  JWT `authMiddleware`; `GET /api/oilcan/latest` reads a `oilcan:latest:{device}` Redis key that
  `internal/mqtt`'s `handleOilcanSnapshot` writes on every `requestOilcan` response (see below) — it
  still 404s for a device nothing has polled yet, by design, not as a bug. `GET /ws/alarms` (`ws.go`)
  is the one route not behind `authMiddleware` — see its own writeup above for why and how it
  authenticates instead. `writeback.go` (2026-08-29) adds four more routes behind `authMiddleware` —
  `POST /api/oil-in-out/correct`, `POST /api/shifts/correct`, `POST /api/devices/:device/oil-can-set`,
  `GET /api/write-back` — see the 2026-08-29 write-back pass above for what each does. `devices.go`
  (2026-08-29) adds `POST /api/devices/bind`, `POST /api/devices/unbind`, `GET /api/devices`, and
  `requireDeviceOwnership` — the authorization check every device-scoped handler above (all three
  list handlers, `oilcan.go`, `ws.go`, and all four of `writeback.go`'s) now calls first — see the
  2026-08-29 authorization pass above for the full design (why ownership is checked before
  existence, the JWT's new `uid` claim, etc.).
- `internal/queue` implements design doc §5.2's offline queue, scoped exactly the way CLAUDE.md's
  "Tech stack" section says river should be scoped — it only owns pending→sent:
  `EnqueueWriteBack(ctx, pool, riverClient, deviceName, commandType, payload)` inserts the
  `write_back_commands` row and calls `riverClient.InsertTx` in the *same* pgx transaction (the
  reason river was chosen over a Redis-backed queue). `PublishCommandWorker` is the river worker
  that does the pending→sent hop: it calls the `Publisher` interface (which `internal/mqtt` will
  implement once it exists) and marks the row `sent` on success; it's a no-op (not an error) if the
  row already moved past `pending`, so a redelivered/retried job is safe. The sent→acked transition
  is explicitly *not* here — CLAUDE.md is clear that has to be a bespoke handler in `internal/mqtt`
  reacting to the device's inbound confirmation message, calling
  `Queries.MarkWriteBackCommandAcked` directly, bypassing river entirely, once that message
  semantics exists on the device side (currently doesn't — see "Device-side work this project
  depends on"). `TTLSweepWorker` is a river periodic job (default hourly) implementing §5.3: rows
  stuck `pending`/`sent` past `TTL` (default `queue.DefaultTTL` = 30 days, explicitly flagged in code
  as a placeholder mirroring the design doc's example number, not a confirmed product decision) get
  marked `failed`.
- `internal/partitions` (2026-08-29) closes the gap flagged in "Known cloud-side gaps" above:
  `EnsureFuture(ctx, pool, lookahead)` runs `CREATE TABLE IF NOT EXISTS {table}_{yyyy}_{mm}
  PARTITION OF {table} FOR VALUES FROM (...) TO (...)` for `alarms`/`oil_in_out`, covering the
  current month through `lookahead` months ahead (`DefaultLookahead` = 2). `cmd/server/main.go`
  starts `partitions.Run` as a background goroutine right after the pool is built — it calls
  `EnsureFuture` once immediately, then every `DefaultInterval` (24h) for the life of the process.
  Deliberately a plain ticker, not a river periodic job like `TTLSweepWorker` above: river's
  scoping in "Tech stack" is specifically the write-back queue, and `CREATE TABLE IF NOT EXISTS` is
  already idempotent and safe to call from more than one server instance without river's
  coordination, so adding river here would be an unneeded abstraction.
- `internal/store/db` is the sqlc output over the schema in `db/migrations/000001_init_schema.up.sql`
  — that migration already creates `users`, `devices`, `alarms`/`oil_in_out` (time-partitioned,
  partitions pre-created through 2026-09), `commit_works`, `oil_can_sets`, and
  `write_back_commands`, matching the device data model in the design doc §二 and the write-back
  queue shape in §5.2. `000008_user_devices.up.sql` (2026-08-29) adds the `user_devices` binding
  table — see the 2026-08-29 authorization pass above. `alarms`/`oil_in_out`/`commit_works` were checked field-for-field directly
  against the Android repo's `db/Alarm.java`/`OilInOut.java`/`CommitWork.java` (2026-08-20, not just
  the design doc's summary table) and match exactly. `oil_can_sets` did not — the design doc's §二
  table only listed 13 of `OilCanSet.java`'s ~26 real columns; the missing ones (`pk_d2dId`,
  `pk_tanZhenId`, `oilHeight`, `heightWaterAlarm`, `oilCorrection`/`waterCorrection`/
  `tempCorrection`, `density`/`densVar`, `alarmByte`, `gmt_create`/`gmt_modified`,
  `chuanGanQiHight`) have been added to the migration. `gmt_create`/`gmt_modified` are kept as raw
  nullable `TEXT`, not parsed to a timestamp, since their on-device string format isn't documented
  anywhere. Confirmed via `grep` that `OilCanSet` (not the unrelated `OilCanTable`/`OilTable`
  entities in the same package) is the live, actively-referenced entity.
- `internal/mqtt` implements the two subscribe/poll jobs from design doc §4.1 (job 3, the REST
  surface, is `internal/api` above). `Client` (client.go) wraps `paho.mqtt.golang`: on connect it
  subscribes to wildcard filters — one `sys/{productKey}/+/thing/event/property/post/+` covering
  every device's query responses, plus one each for `isOline` and `msg` — rather than a per-device
  subscription, avoiding per-device Subscribe calls as the fleet grows (see "MQTT contract"
  corrections above; this wildcard pattern has no equivalent in `BackService.java`, which only ever
  handles its own single device). Requires the broker to actually grant wildcard-subscribe
  permission to one client, which ruled out Aliyun IoT Platform; this package is local-dev/Mosquitto
  only for now — per the 2026-09-01 addition to "Device connectivity platform" below, the production
  target is self-hosted EMQX (chosen specifically because, being self-hosted, the wildcard-subscribe
  grant is this project's own config choice rather than an unverified vendor ACL model — confirmed
  2026-09-01 against a real throwaway EMQX instance, see that section), not ThingsBoard (ThingsBoard
  is scoped to device identity/OTA only, per that same section). `handlers.go`
  dispatches an arrived property/post message by its
  parsed identifier: `requestOilcan` caches the raw response bytes verbatim under
  `oilcan:latest:{device}` in Redis (the same key `internal/api/oilcan.go` already reads — this is
  what turns that endpoint's permanent 404 into real data); `requestAlarm`/`requestUnloadOil`/
  `requestShift` decode into `AlarmPayload`/`OilInOutPayload`/`CommitWorkPayload` (see "MQTT
  contract" for why these wire-format structs use different field names than the DB columns) and
  upsert via the `Upsert*` queries added to `db/queries/`; `requestOilLeak`/`requestOilStorage` are
  logged as unhandled (no cloud table, see "MQTT contract"). `isOline` events call
  `UpsertDeviceOnlineStatus`; `msg` events get published to the `alarms:live` Redis channel, which
  `internal/api/ws.go`'s `/ws/alarms` subscribes to and fans out (see above). `poller.go` implements
  §4.3: every
  `DefaultPollInterval` (20s, inside the doc's 10–30s range) it calls `RequestOilcan` for every
  device `ListOnlineDeviceNames` returns — the actual persistence happens later when that response
  arrives on the property/post subscription, not synchronously in the poll loop. `internal/mqtt`
  does *not* implement `queue.Publisher` — the write-back queue's pending→sent hop still needs a
  real device-side "operation acknowledged" topic/semantics that doesn't exist yet (see "Device-side
  work this project depends on"), so `cmd/server/main.go` still wires `unbuiltMQTTPublisher` there.
