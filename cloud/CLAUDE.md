# CLAUDE.md / AGENTS.md

> **`AGENTS.md` is a symlink to this file.** Codex, Claude Code, Cursor and anything else that reads
> one of those two names gets exactly this content, so there is a single file to maintain and no way
> for the two to drift apart. Edit `CLAUDE.md`; never replace `AGENTS.md` with a real file.
> (Git stores it as a symlink, mode `120000`. A Windows checkout without `core.symlinks=true` would
> materialise it as a 9-byte text file containing the string `CLAUDE.md` — harmless here, since dev
> is macOS and deployment is Linux, but that is what you are looking at if it ever appears empty.)
>
> **How this file is organised, and how to read it:**
>
> - **Part 1 — Operating guide (below).** Current state: architecture, commands, layout, invariants,
>   conventions. Short. **Start here, and trust it over anything further down.**
> - **Part 2 — Engineering journal (from "What this project is" onward).** ~1,000 lines recording
>   *why* decisions were made, including several that have since been superseded and are kept marked
>   rather than deleted. Go there when you need the reasoning or the history behind something — but
>   read it knowing that older sections describe the architecture **as it was when they were
>   written**, in the present tense. Where the two parts disagree about present-day fact, Part 1 wins.

---

# Part 1 — Operating guide

## Project status: implementation underway (single-stack migration in progress)

**Superseded note, kept for the record:** this section used to read "this repo is still an empty
scaffold — no code exists yet", which was true when written on 2026-08-20 and stopped being true
almost immediately. Corrected 2026-09-01.

There is now a substantial Go codebase, containing **two device-connectivity stacks at once** — see
the warning immediately below, which is the single most important thing to know before writing code
here.

## Architecture in one line

**Devices connect only to ThingsBoard CE.** There is no EMQX, no second MQTT stack, no Alink/Paho
compatibility layer, and no local copy of telemetry. The legacy Alink stack that used to live
alongside this one was **deleted in full** on 2026-09-02 (GitHub issue #10) — `internal/mqtt`,
`cmd/devicesim`, `internal/queue`, `internal/partitions`, the mosquitto service and the five
legacy tables are all gone.

If you find yourself writing a `productKey`, a `sys/{productKey}/...` topic, an EMQX config, a
second MQTT client, or a table that stores telemetry locally, stop — none of those belong here, and
the git history that shows them is history.

## What this project is

The cloud backend for 安卓液位仪 (`com.sansen.yeweiyi`) — an oil-tank level monitoring terminal.
It provides remote monitoring and remote control for a WeChat mini-program and a fixed web console,
in both a public-cloud deployment and a **fully offline (air-gapped) private deployment** that must
run from the same code and the same deployment templates.

```text
Android device
    ⇄ ThingsBoard native MQTT
ThingsBoard CE          device access, credentials, telemetry, attributes, alarms, RPC, OTA
    ⇄ REST / WebSocket
Go backend (this repo)  business logic, tenancy, control state machine, audit
    ⇄
PostgreSQL / Redis + web console
```

Sibling repositories (do not edit from here; protocol changes require agreement with their owners):

- `~/Developer/zhongchengyeweiyi` — the Android device firmware/app.
- `~/Developer/yeweiyi-miniapp` — the mini-program frontend.

---

## Quickstart

```bash
# business stack (Postgres, Redis)
docker compose up -d

# ThingsBoard CE — heavy; first boot installs its schema and takes 2–4 minutes
docker compose -f docker-compose.thingsboard.yml up -d
#   console  http://localhost:8081   tenant@thingsboard.org / tenant
#   device MQTT  tcp://localhost:1884

go build ./... && go vet ./...

migrate -database "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable" \
        -path db/migrations up

# a simulated device speaking 设备协议 V1
go run ./cmd/tbdevicesim provision -name tank-farm-01
go run ./cmd/tbdevicesim run -token <token> -tanks 2 -interval 10s -alarm-after 15s

# the backend, on the single stack. Note what is NOT here: no MQTT_BROKER_URL, no
# MQTT_PRODUCT_KEY, no broker of any kind — the legacy stack those env vars used to start
# (internal/mqtt, cmd/devicesim) was deleted outright on 2026-09-02 (issue #10), so cmd/server no
# longer reads either variable at all; setting them now does nothing.
JWT_SECRET=dev-secret \
TB_BASE_URL=http://localhost:8081 TB_USERNAME=tenant@thingsboard.org TB_PASSWORD=tenant \
go run ./cmd/server
```

Configuration is **defaults → `config.yaml` → environment**, loaded by `internal/config`; every key
and its environment equivalent is documented in `config.example.yaml`. Pass `-config path.yaml`, or
omit it and rely on defaults plus env (what the dev loop does).

| setting | effect |
|---|---|
| `thingsboard.baseURL` / `TB_BASE_URL` (+ username/password) | **Unset ⇒ no ingestion, and device/control routes answer 503** — the server still boots, deliberately, so business-endpoint work needs no 2 GB container. |
| `control.allowPhysicalCommands` / `ALLOW_PHYSICAL_COMMANDS` | opt-in gate for pump/valve/reboot. **Leave false.** A typo'd env value is an error, not a silent false. |
| `control.commandTTL` / `COMMAND_TTL` | command validity window (default `60s`, a placeholder not a product decision) |

Durations are `"30s"`-style strings everywhere; a bare number is rejected rather than read as
nanoseconds. Unknown/misspelled YAML keys fail startup rather than being ignored — the motivating
case is `allowPhysicalCommand` (singular) silently doing nothing.

### Tests

```bash
go test ./internal/protocol/   # contract tests; needs NO containers
go test ./internal/tbclient/   # needs ThingsBoard
go test ./internal/control/    # needs Postgres
go test ./internal/ingest/     # needs ThingsBoard + Postgres + Redis
```

Tests **skip with an explanatory message** when their infrastructure is absent rather than failing.
If you see a skip, start the container it names — do not assume the test is broken.

**Use `go test ./... -p 1`.** Three packages (`internal/api`, `internal/tbclient`,
`internal/ingest`) each drive the same ThingsBoard instance, and on a development machine the load
generator and the server share the same CPU. Run in parallel they occasionally miss a 25-second
timeout — most often `TestFullLoop_CommandReachesTerminalStateViaIngest`. Serialised, the suite is
green; each package alone is green; ThingsBoard itself stays healthy throughout (its queues report
`totalFailed [0]`), so this is contention, not a defect in the system under test.

An earlier entry below claimed parallel runs were reliable after the legacy packages were deleted.
That was measured on a lightly-loaded instance and did not hold up once ThingsBoard had accumulated
data and a fourth ThingsBoard-dependent package existed — the claim was made from three green runs,
which was too small a sample to conclude "stable" from. The older river/mosquitto flakiness it
described really is gone; this is a different, later contention.

---

## Layout

```
internal/protocol/    设备协议 V1 — the single source of truth for the wire contract
internal/tbclient/    the ONLY door into ThingsBoard (public REST/WS; never its database)
internal/devicesim/   device-side reference implementation, driven in-process by tests
internal/control/     control command state machine, authorization, audit
internal/ingest/      per-device ThingsBoard telemetry subscription → business layer
internal/api/         REST/WebSocket surface (still serving the legacy read model)
internal/store/db/    sqlc OUTPUT — generated, never hand-edit
db/migrations/        golang-migrate schema; also sqlc's schema input
db/queries/           sqlc query input
fixtures/protocol/v1/ cross-repo protocol fixtures, shared with the Android repo
cmd/tbdevicesim/      simulated device CLI
docs/                 protocol spec + architecture baseline

internal/mqtt/, cmd/devicesim/, dev/mosquitto/   ← legacy, see the warning above
```

---

## Invariants you must not break

These encode safety properties, not style preferences. Each is pinned by a test; if a test in this
list fails, **the change is wrong**, not the test.

1. **Physical commands are disabled by default.** `control.Config.AllowPhysicalCommands` defaults to
   `false`, gating pump/valve/reboot. The device-side interlocks are unfinished and unverified on
   real hardware, so "configure nothing" must mean "cannot start a pump". The gate applies to
   platform admins too — it constrains unverified *hardware*, not trust levels.
2. **An unacknowledged command is `sent`, never `failed`.** "The device did not answer" is not "the
   device did not act" — it may have received the command, started the pump, and lost its connection
   before replying. It stays non-terminal until a result arrives or it expires.
3. **A device ACK is not success.** The ACK means received-and-accepted. Whether the action happened
   arrives later as a separate `cmdResult`. Never collapse the two.
4. **Command status transitions are guarded in SQL**, in `db/queries/control_commands.sql`
   (`WHERE status IN (...)`), so a late or duplicated message cannot rewrite a terminal row. Do not
   move these checks into Go.
5. **Telemetry is never copied into the business database.** It lives in ThingsBoard and is read
   back on demand. `TestIngest_DoesNotPersistTelemetry` fails if this is violated. Only commands,
   audit and business records cross the boundary.
6. **Authorization is checked before existence**, everywhere. An unauthorized caller must get the
   same answer whether the device belongs to someone else or does not exist, or error codes become a
   device-name oracle. See `internal/api/devices.go` and `control.Service.authorize`.
7. **Never touch ThingsBoard's own database.** Public REST/WebSocket APIs only, so their upgrades
   cannot break us through a schema change.
8. **CE-only, offline-capable.** No ThingsBoard PE feature, no ThingsBoard Cloud, no online license
   check, no reliance on outbound internet — the private deployment is fully air-gapped.

---

## Conventions

**Go.** Standard `gofmt`. `gin` for REST, `gorilla/websocket`, `pgx/v5` + `sqlc`, `golang-migrate`,
`riverqueue/river`, `golang-jwt/jwt` + bcrypt. Prefer the stdlib over a new dependency for something
small (the UUID generator in `internal/control` is hand-rolled for exactly this reason).

**Comments explain *why*, not *what*.** The existing code comments are dense and deliberately so:
they record the reasoning and the traps (e.g. why ThingsBoard stringifies telemetry but not
attributes). Match that density. Do not add comments that restate the code.

**Schema changes** — always this order:

```bash
# 1. write db/migrations/0000NN_name.{up,down}.sql   (both directions, always)
# 2. apply, then verify the down→up round trip (repo convention, do not skip)
migrate -database "$DSN" -path db/migrations up
migrate -database "$DSN" -path db/migrations down 1
migrate -database "$DSN" -path db/migrations up
# 3. write/adjust db/queries/*.sql
sqlc generate      # regenerates internal/store/db — never hand-edit that directory
```

New tables and columns get Chinese `COMMENT ON` (see migration `000009`).

**Tests are integration tests against real infrastructure**, not mocks. The one place a fake is used
(`control.Sender` in `internal/control`) is for branches a healthy ThingsBoard will not produce on
demand — an outage, an offline device. Where a wire format is involved, tests use **hand-written
JSON literals or shared fixtures**, never the repo's own marshalled structs, so a wrong field name
is actually caught instead of the code agreeing with itself.

**Verify against the real thing before writing conclusions.** This project has been burned by
research-derived assumptions twice: Aliyun IoT Platform's ACL model, and its purchase availability.
Two ThingsBoard behaviours documented here (telemetry stringification; attributes *not* being
stringified) were found by running it, and contradicted expectations. If you write "X works", have
run it.

**Commit messages are in Chinese**, subject line ≤ ~50 chars, body explaining the *why* and calling
out anything non-obvious or deliberately not done. Look at `git log` for the register. End with:

```
Claude-Session: <session url>     # or your agent's equivalent; omit if not applicable
```

Commit only when asked. Work on a feature branch; the default branch is `main`.

**Documentation.** Record substantial work under "Build" further down this file (it is the project's
long-term memory — additive, keeps superseded decisions marked rather than deleted), and update
`docs/` when architecture or protocol changes. Keep the guide above current when the layout or the
invariants change.

---

## Do not assume these are decided

- **设备协议 V1 is a draft, not frozen — and the Android side independently implemented an
  INCOMPATIBLE version of it on the same day.** Both repos built "ThingsBoard single-stack protocol
  V1" in parallel, each noting the other's fixtures did not exist yet. As things stand a device's
  telemetry does not parse on the cloud side at all: flat `tank1.oilLevelMm` keys here versus a
  nested `tanks` array there, `alarm` versus `alarmEvent`, and a dozen renamed fields.
  **Read `docs/protocol-v1-reconciliation.md` before touching anything protocol-shaped**, and do not
  add new fields to either side until the structure question is settled — renaming is cheap now and
  gets expensive fast. The topics, the RPC envelope and the safety model did converge independently.
- `docs/protocol-v1.md` §12 lists ten further open items requiring
  the Android owner or product. Most consequential: the **units** (mm/L/kg/°C) are
  industry-convention guesses never checked against the device's real register scaling, and
  `config.setTank`/`config.setProbe` argument shapes are undefined on purpose because the device has
  no handler for them yet. **Do not freeze the protocol or start device-side migration** without
  that sign-off.
- **Multi-tenancy is not built.** There are no customer entities and no tenant-scoped query
  isolation. `users.role` (`platform_admin`/`customer_admin`/`customer_user`) plus the existing
  `user_devices` binding is the entire authorization model today.
- **All read endpoints are migrated.** `/api/alarms`, `/api/oil-in-out` and
  `/api/devices/:device/telemetry/latest` read through to ThingsBoard; `/api/oilcan/latest` and
  `/api/shifts` were deleted rather than migrated. **`/api/oil-in-out` depends on an unmerged
  device-side change** (zhongchengyeweiyi#39): the device already publishes `stockEvent` on every
  movement but as `DetailInoutRecord.toString()`, a Java debug string rather than JSON, so real
  devices currently produce records the endpoint skips. It degrades to an empty list, not a 500.
- **交班 (shift handover) is out of scope** unless product says otherwise: V1 has no uplink for it on
  either side, so `/api/shifts` could only ever have returned an empty list.
- **Record correction has no replacement yet.** The old write-back endpoints
  (`POST /api/oil-in-out/correct` etc.) went with the river queue. If correcting a stored record
  remains a product requirement it needs redesigning on top of `internal/control`, not restoring.
- **Not verified:** MQTT over TLS (dev uses plaintext 1884), OTA delivery, ThingsBoard-side tenant
  isolation, and any load/capacity testing. The device-side OTA client does not exist at all.
- **river's own tables remain** (`river_job`/`river_leader`/`river_queue`/`river_notification`,
  migrations 000002–000007) even though river itself is gone from `go.mod`. Dropping them from a
  later migration while those six files still claim to create them makes the history inconsistent
  both ways; retiring the six files together is a schema-history change worth doing on its own.

---

---

# Part 2 — Engineering journal

*Everything below is history and reasoning. Older sections describe superseded architectures in the
present tense; Part 1 above is authoritative for present-day fact.*

## What this project is (original 2026-08-20 framing)

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

## Device connectivity platform (corrected repeatedly — current decision at the top)

**CURRENT DECISION, 2026-09-01 (final, and it overrides every other statement in this file):
ThingsBoard CE single stack. Devices connect ONLY to ThingsBoard CE. There is no EMQX, no second
MQTT stack, and no Alink/Paho compatibility channel.** This came from GitHub issues #1/#9/#7, which
scoped the project as a greenfield build with no legacy cloud protocol to stay compatible with —
so the "keep `internal/mqtt`'s Alink topics and just point them at a different broker" reasoning
below no longer applies. The Android side's MQTT protocol is explicitly allowed to change, and 设备协议 V1
(`docs/protocol-v1.md`) is that change.

This **supersedes the 2026-09-01 EMQX decision** recorded further down in this section. The EMQX
wildcard-ACL experiment described there was real and its findings are accurate, but the split it was
designed to serve — "ThingsBoard for identity/OTA, a separate broker for business MQTT" — is no
longer the architecture. Nothing was ever provisioned or written against EMQX, so there is nothing
to migrate away from; the cost of that reversal was one throwaway container.

What this pass actually built and verified (2026-09-01, branch `feat/thingsboard-single-stack`) is
recorded in the "2026-09-01 ThingsBoard CE single-stack pass" entry under "Build" below, with the
architecture baseline in `docs/thingsboard-ce-baseline.md` and the wire contract in
`docs/protocol-v1.md`. **Read those two documents rather than re-deriving anything from the
historical trail below**, which is kept only to explain why the decision moved four times.

---

*Historical trail from here down.*

**Third correction, 2026-08-29: self-hosted ThingsBoard (open source), not any Aliyun MQTT product,
not a bare broker.** This superseded everything below it in this section — kept for the trail of
*why*, since this flipped twice in one day before landing here.

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

- ~~**How `internal/mqtt`/`internal/api` relate to ThingsBoard.**~~ — decided 2026-09-01: ThingsBoard
  is scoped to device identity (provisioning/one-device-one-secret) and OTA only; it does not carry
  business MQTT traffic. **REVERSED later the same day — see the CURRENT DECISION at the top of this
  section. ThingsBoard now carries everything and EMQX is not used at all. The paragraph below is
  historical; its EMQX ACL findings are accurate but no longer relevant to this project.** `internal/mqtt`/`internal/api` are unaffected — they keep talking to a
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
  device — see `internal/api/devices.go` and the 2026-08-29 authorization pass in "Build".
  **Corrected 2026-09-04**: the cloud endpoint used to trust whatever `deviceName` string the client
  sent with no verification at all — not "fine under possession-is-authorization", an actual P1
  found in PR #11 review, since a bare device name is guessable/enumerable and nothing checked the
  caller had ever seen the physical device. `POST /api/devices/bind` now also requires
  `pairingToken`, verified server-side against a bcrypt-hashed secret this backend itself mints
  (`POST /api/devices/:device/pairing-secret`, platform_admin only) and stores in
  `device_pairing_secrets` — **not** ThingsBoard's device access token, which the fifth round tried
  first and the sixth round found leaked the device's MQTT identity (see the sixth round in "Build"
  below) — so the QR must encode at least `deviceName` *and* that minted secret for binding to
  succeed at all. Still open, unchanged by either fix: the QR's exact content *format* beyond
  "carries these two values somehow" (bare strings? a URL/deeplink wrapping them? one combined value
  or two?) is a decision for whoever designs the physical label/screen, not made here — as is who
  actually calls the minting endpoint at install time (a technician's own authenticated session? a
  separate provisioning tool?) — and whether PC-remote clients get a pairing flow at all or are
  mini-program-only for now.

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
- **Device connectivity platform**: **self-hosted ThingsBoard CE 4.2.1.1 (Apache 2.0), single stack,
  decided 2026-09-01 — devices connect to nothing else.** Pinned in
  `docker-compose.thingsboard.yml`; baseline and licence record in
  `docs/thingsboard-ce-baseline.md`. Go reaches it only through its public REST/WebSocket APIs
  (`internal/tbclient`), never its database. The wire contract is 设备协议 V1
  (`docs/protocol-v1.md`, implemented in `internal/protocol`), which uses ThingsBoard's native
  `v1/devices/me/...` topics — **not** the legacy Alink `sys/{productKey}/...` shape.
  See "Device connectivity platform" below for why this moved four times (Aliyun IoT Platform →
  云消息队列 MQTT 版 → ThingsBoard+EMQX split → ThingsBoard only); everything in that section below
  its CURRENT DECISION header is historical.
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

**2026-09-01 ThingsBoard CE single-stack pass** (branch `feat/thingsboard-single-stack`, GitHub
issues #9 and #7) — the first code written against the architecture at the top of "Device
connectivity platform" above. Four new packages (`internal/protocol`, `internal/tbclient`,
`internal/devicesim`, `cmd/tbdevicesim`), two docs, and a pinned
`docker-compose.thingsboard.yml` (`thingsboard/tb-postgres:4.2.1.1`, Apache 2.0). **Nothing in the
old stack was touched** — `internal/mqtt`, `cmd/devicesim`, `cmd/server` and the mosquitto service
are all untouched and still work; #9's deliverable for them is a *removal list*
(`docs/thingsboard-ce-baseline.md` §6), and the deletion itself belongs to #10.

Verified against a real ThingsBoard CE container, not from documentation — `go test
./internal/tbclient/`, 11 tests, all passing. The two loops #9 is accepted on both close:
telemetry → ThingsBoard → Go read-back, and Go RPC → device ACK → asynchronous final result.
Alarm raise propagated device→ThingsBoard→backend in **29 ms** and recovery in **66 ms**, with the
simulated device's snapshot interval deliberately set to 10 minutes so an alarm riding the snapshot
timer could not possibly have passed. Also covered: client attributes up, shared attributes down,
interlock rejection, accepted-then-failed-in-the-field, duplicate-commandId replay with no second
physical action, expired-command rejection, and the offline-device vs platform-down distinction.

**Air-gap verification was done properly and the obvious shortcut is wrong**: disconnecting the
container from its bridge network also kills Docker's published ports, so a host-side `curl` then
measures "unreachable", not "works offline". What was actually done: put ThingsBoard on a
`--internal` Docker network, prove with a sidecar container on that same network that it has no
egress, cross-compile the e2e tests to a static Linux binary, and run them from *inside* that
network — so both ThingsBoard and the test client are offline. All 11 passed. The throwaway network
and every test device were torn down afterwards; only ThingsBoard's own stock demo devices remain.

**Two ThingsBoard API behaviours found by testing, both of which would have caused real bugs:**
1. **Telemetry values are stringified on read-back.** A value published as the number `1842.5`
   comes back from both the REST timeseries API and the WebSocket as the *string* `"1842.5"`;
   `true` comes back as `"true"`; a JSON object comes back as a JSON string. A parser written only
   against the device-side shape parses off MQTT and fails off REST.
   `internal/protocol/convert.go` accepts both, and `fixtures/protocol/v1/telemetry_snapshot_rest.json`
   pins it as a fixture of its own.
2. **Attribute values are NOT stringified** — they keep their JSON types. The same platform's two
   APIs disagree, which is exactly the sort of thing that is cheap to pin now and expensive to
   discover later.

One real bug the tests caught in code written this same pass: `tbclient.Unavailable` classified
`ErrDeviceOffline` as a ThingsBoard outage, because that sentinel is not a `*tbclient.Error` and
fell through to the catch-all. Reporting "the device is offline" as "the platform is down" would
drive false operational alarms; fixed, and pinned by
`TestControl_OfflineDeviceIsReportedDistinctly`.

**Protocol V1 is a draft, deliberately NOT frozen.** #7's acceptance requires the Android owner to
co-sign it, and `docs/protocol-v1.md` §12 lists the ten specific things still needing a decision
rather than leaving TBDs scattered through the spec. The most consequential: the units
(mm/L/kg/°C/percent) are industry-convention guesses that have **not** been checked against the
device's actual register scaling, and `config.setTank`/`config.setProbe` argument shapes are
undefined on purpose because the device side still has no handler for them — typing them now would
bake in a guess. Neither #9 nor #7 was closed.

Design decisions worth knowing before changing any of this: telemetry uses flat per-tank scalar keys
(`tank1.oilLevelMm`) rather than one nested JSON blob, so ThingsBoard's own aggregation/TTL/trend
widgets work on it directly; **units are in the key names** so "a number with an undefined unit" is
structurally impossible (#7 acceptance); instant events are single JSON-object-valued keys (`alarm`,
`cmdResult`) rather than flat keys, because a discrete record must not be readable half-written;
the device's `ts` is epoch millis UTC, dropping the legacy `"yyyy-MM-dd HH:mm"` string that had no
seconds and no timezone and forced the Asia/Shanghai guess documented above; and `alarmId` is minted
by the device and reused across ACTIVE/CLEARED so overlapping occurrences of the same alarm type on
the same tank can still be paired.

**2026-09-01 control state machine + ingestion pass** (issue #10, first slice, same branch) —
`db/migrations/000010_control_and_audit`, `internal/control`, `internal/ingest`. Adds `users.role`
(platform_admin/customer_admin/customer_user), `control_commands` (the 8-state machine issue #1
specifies) and `audit_log`. `internal/control` issues commands through `internal/tbclient` and
applies ACKs; `internal/ingest` holds one ThingsBoard telemetry WebSocket subscription per device,
routes `cmdResult` into the state machine and fans alarms out to the existing `alarms:live` Redis
channel that `/ws/alarms` already serves.

**All status transitions are guarded in SQL** (`WHERE status IN (...)` in
`db/queries/control_commands.sql`), not in application code — so a late ACK, a redelivered
`cmdResult`, or a result arriving after the expiry sweep cannot rewrite a terminal row. Application
-level checks would have to be right at every call site; the guard is right once.

**Three judgement calls that are easy to get backwards, each pinned by a test:**
- **An unacknowledged command is recorded `sent`, never `failed`.** "The device did not answer" is
  not "the device did not act" — it may have received the command, started the pump, and lost its
  connection before replying. Marking it failed would tell an operator the pump is off while it is
  running. It stays non-terminal until a result arrives or it expires.
- **ThingsBoard being down leaves the command `pending`**, because it was never handed over.
- **A device refusing is not a Go error** — it returns a row with status `rejected` and the device's
  reason, because a refusal is information the operator needs, not a transport failure.

**`Config.AllowPhysicalCommands` defaults to false, and that default is the safety mechanism.**
Issue #1 forbids exposing actions whose device-side interlocks are unfinished and unverified on real
hardware; since that work is not done, the safe state has to be the one you get by configuring
nothing. A flag defaulting to "allowed" would mean a fresh deployment ships able to start pumps. The
gate applies to platform admins too — it is a statement about unverified hardware, not about trust.

**The data boundary is enforced by a test, not just documented**:
`TestIngest_DoesNotPersistTelemetry` fails if the ingester writes any telemetry row into the
business database. Telemetry stays in ThingsBoard; only commands, audit and business records cross
over. This is what stops the `alarms`/`oil_in_out` partition tables quietly becoming a second
time-series store again.

Verified end to end against real ThingsBoard + real simulated device + real Postgres/Redis
(`internal/ingest`, 4 tests): a command walks `pending → sent → received → succeeded` with the last
hop driven entirely by the device's asynchronous `cmdResult` through the ingester; an alarm reaches
the operator fan-out channel in **30 ms**; cross-tenant control is refused and the denied attempt is
audited. `internal/control` adds 14 more tests against real Postgres with a fake Sender, so the
branches a healthy ThingsBoard will not produce on demand (outage, offline device) are still
deterministic.

**Still not done in #10** (do not read this pass as closing it): no REST endpoints expose any of
this yet, `cmd/server` is still wired to the old stack and does not start `internal/ingest` or
`internal/control`, the full multi-tenant model (customer entities, tenant-scoped queries
everywhere) does not exist — `users.role` plus the existing `user_devices` binding is the whole
authorization model right now — and none of the old Alink stack has been deleted.

**2026-09-01 REST + single-stack server pass** (issue #10, same branch) — `internal/api/control.go`
(`POST`/`GET /api/devices/:device/commands`, `GET /api/audit`), `internal/api/telemetry.go`
(`GET /api/devices/:device/telemetry/latest`), a `role` claim in the JWT, and `cmd/server` rewired
onto the single stack.

**The system now boots with no Aliyun productKey, no Alink topic and no broker of any kind** — one
of issue #10's acceptance criteria. ThingsBoard is configured through `TB_BASE_URL`/`TB_USERNAME`/
`TB_PASSWORD` and is itself *optional*: without it the server still starts and the device-facing
routes answer 503 with an explanatory message, so business-endpoint work does not require running a
2 GB container. `MQTT_BROKER_URL` still starts the legacy stack, now behind a loud warning.

Verified by running the real binary (not `go run`) with no MQTT env vars at all and driving it over
HTTP: provision → bind (the QR-scan step) → simulated device connects → `GET .../telemetry/latest`
returns the live snapshot read through from ThingsBoard → `pump.start` refused **409** by the
interlock gate → `alarm.mute` accepted **202 received** → the ingester carries the device's async
`cmdResult` home and the same command reads back **succeeded/terminal** → `GET /api/audit` shows the
whole trail (`control.issue` denied, `control.issue` allowed, `control.ack` allowed,
`control.result` succeeded with `actor=device:...`). Business tables stayed at
`alarms=0 oil_in_out=0 commit_works=0` throughout, `control_commands=1` — the data boundary holding
in a real run, not just in a unit test. All throwaway rows, the ThingsBoard device and the seeded
operator were deleted afterwards.

Two HTTP-status choices worth not "fixing" later:
- **Issuing a command answers 202, never 200.** The response reports only how far the command got
  synchronously (`received`, or `rejected`); the outcome arrives asynchronously. 200 would invite
  clients to read it as "it worked" — the exact conflation the state machine exists to prevent. The
  response also carries a server-computed `terminal` flag so a client never hard-codes the status
  list to know whether to keep polling.
- **The interlock gate answers 409, not 403.** The caller may well be entitled to the method; it is
  the *deployment* refusing until device-side interlocks are verified. 403 would send an operator
  to ask for permissions that would not help.

Authorization is not duplicated in the handlers: `internal/control` does the whole check (role tier,
device binding, interlock gate) and the handler only maps its sentinel errors onto status codes, so
there is exactly one place a control action can be permitted. 18 new tests in
`internal/api/control_test.go`, including that an unowned device and a nonexistent one return the
*same* code — differing codes would let a caller enumerate real device names.

**Still open in #10 after this pass**: the four legacy read endpoints (`/api/alarms`,
`/api/oil-in-out`, `/api/shifts`, `/api/oilcan/latest`) still read tables only the old MQTT
ingestion populated, so they return nothing on a single-stack deployment — deciding their
replacement is the next real piece of work; the full multi-tenant model does not exist; and nothing
from the legacy stack has been deleted yet.

**2026-09-01 read-path migration pass** (issue #10, same branch) — deleted `/api/oilcan/latest`
and moved `/api/alarms` onto ThingsBoard.

`/api/oilcan/latest` (and `internal/api/oilcan.go`, `oilcan_test.go`) is gone: it served a
`oilcan:latest:{device}` Redis key that only the legacy MQTT poller wrote, and
`/api/devices/:device/telemetry/latest` replaces it. Three ownership tests in `devices_test.go` had
been using it as their device-scoped probe and were repointed at `GET /api/devices/:device/commands`,
which needs neither ThingsBoard nor ingested data (an owned device with no commands is an empty
list).

`/api/alarms` now reads the `alarm` telemetry key's history out of ThingsBoard
(`internal/api/alarms.go`) instead of the `alarms` table. **Deliberately not using ThingsBoard's
native Alarm entities**: those would be a better home for operator acknowledgement, but they only
exist once a rule chain maps the telemetry into them, and that chain is not configured. The response
shape is therefore written to look like an alarm record rather than a telemetry point, so moving to
`/api/alarm/DEVICE/{id}` later does not change the contract. **This is a breaking response change** —
the old Java field names (`guanhao`/`jiezhi`/`info`) are gone, because the Alink payloads they came
from are.

`tbclient.Timeseries` was refactored to take a `TimeseriesQuery` with a `Descending` flag, pushing
the sort down to ThingsBoard. **This is a correctness fix, not tidying**: with a limit in play,
ascending returns the oldest N in the range and descending the newest N — *different sets, not
different orders* — so reversing an ascending page in Go would answer "the 20 most recent alarms"
with the 20 oldest. `TestListAlarms_SortReturnsTheNewestWhenLimited` raises three alarms and pins
that `limit=1&sort=false` returns the newest, which is the assertion that fails if anyone
"simplifies" this back into a client-side reverse.

`offset` is rejected with 400 on `/api/alarms` rather than ignored: ThingsBoard pages by time window,
not row offset, and silently accepting it would hand a paginating client the same page forever.

Alarm tests that need real data moved to `internal/api/alarms_test.go`, which provisions a device and
publishes a real alarm through `internal/devicesim` — a seeded database row would now prove nothing,
since nothing reads that table. The test also asserts that serving alarms writes *nothing* to the
business `alarms` table.

**2026-09-01 cross-repo protocol reconciliation** (`docs/protocol-v1-reconciliation.md`) — no code,
but the most consequential finding of the day. While drafting an issue about the missing
出入库/交班 uplink, discovered the Android repo had **already migrated to ThingsBoard the same day**
(`d9796b0`, `3175a52`, `272d433`, `94fed27` — Alink deleted on their side too) with **its own,
incompatible protocol V1**.

Both sides' fixtures carry a comment saying the other side's fixtures did not exist yet, so each
defined its own and proceeded. #7's acceptance condition — "协议冻结后再开始生产代码迁移" — was not
honoured by either repo. **As it stands a device's telemetry does not parse on the cloud side at
all.**

What actually converged, independently and without coordination: the MQTT topics, the RPC envelope
nesting inside `params`, `commandId`/`operator`/`reason`/`args` field names, the six-state command
machine, idempotency-by-commandId, the expiry rule, and — notably — **high-risk physical actions
disabled by default** (their `FEATURE_DISABLED` + issue #34, our `AllowPhysicalCommands`). The
safety model was arrived at twice.

What diverged: the telemetry *structure* (our flat `tank1.oilLevelMm` keys vs their nested `tanks`
array), unit encoding (in key names vs a separate `units` object), alarm key/field names
(`alarm`/`alarmId`/`status`/`type`-as-string vs `alarmEvent`/`eventId`/`state`/`alarmType`-as-int),
and three RPC envelope field names. The units *themselves* (mm/L/kg/°C/percent) agree — which is
the part that would have been genuinely expensive to get wrong.

The document argues flat keys on functional grounds (ThingsBoard's aggregation, TTL and trend
widgets work per numeric key; a nested JSON array is one opaque key, which would break the
history-trend and 7-day-downsampling requirements in issue #1) while flagging honestly that this
rests on an untested assumption about charting nested JSON, and that it is the one point worth an
experiment if contested.

It also records a real trade-off in their `eventId`: derived from (alarmType, tank) so it is
deterministic and needs no device-side state — their comment says so explicitly, and that is a
genuine benefit on an embedded target — but consequently not unique across two occurrences of the
same alarm on the same tank, which breaks ACTIVE/CLEARED pairing after an offline backfill. Suggested
compromise: keep it derived, append the occurrence's start timestamp.

**Confirmed by reading their source, not assumed: neither side has any 出入库/交班 uplink.** The old
`requestUnloadOil`/`requestShift` were polled queries that V1 dropped on both sides. That remains the
last blocker on deleting the legacy stack here, and it is a product decision (is it in scope for
launch?) rather than a technical one.

**2026-09-01 telemetry-shape experiment** — settled the one open structural question in the
cross-repo protocol dispute by measurement instead of argument, and corrected a claim this file and
zhongchengyeweiyi#39 had both stated too weakly.

The question: can ThingsBoard aggregate or chart a field *inside* a nested JSON telemetry value, the
way it can for a flat numeric key? The cloud protocol bets on flat `tank1.oilLevelMm` keys; the
Android side bets on a nested `tanks` array. Nobody had actually tried it.

**Method** (worth reusing — the trick is publishing both shapes from one device so the comparison
cannot be confounded by device or transport differences): one throwaway device, ten points a minute
apart, each payload carrying `"tank1.oilLevelMm": <v>` *and*
`"tanks": [{"id":1,"oilLevel":<v>,...}]` with the same `<v>` ramping 1500→1590, so AVG/MIN/MAX are
1545/1500/1590 by hand. Published over ThingsBoard's HTTP device API
(`POST /api/v1/{accessToken}/telemetry` — simpler than MQTT for a one-off), then queried
`values/timeseries?...&interval=...&agg=...`.

**Result**: flat key returns AVG 1545.0, MIN 1500.0, MAX 1590.0, and 5-minute downsampling buckets
of 1500/1530/1575 — all correct. The nested key returns **`AVG = "0.0"`**, `SUM = "0"`, empty for
MIN/MAX, `0.0` in every downsampling bucket — but `COUNT = "10"`, correctly. Inner fields cannot be
addressed at all: `tanks.oilLevel`, `tanks[0].oilLevel` and `oilLevel` all return `{}`, and
`keys/timeseries` reports the device as having exactly two keys.

**The correction**: both this file's earlier framing and the issue said ThingsBoard "cannot
aggregate" a nested field. It does not refuse — **it silently returns zero**. That is a materially
worse failure mode and the real argument: an error gets noticed, whereas a flat line at zero looks
like a working device reading empty. Combined with issue #1's "delete raw detail after 7 days, keep
minute- and hour-level rollups", the rollups would be all zeros *after* the raw data was already
TTL'd away — permanent silent data loss. `COUNT` being correct while `AVG` is wrong is the nastiest
part: the dashboard shows data present, row count right, value zero.

So the structure question is no longer a trade-off to negotiate. Recorded with reproduction steps in
`docs/protocol-v1-reconciliation.md` §A and summarised in `docs/protocol-v1.md` §3; the throwaway
device was deleted afterwards.

**2026-09-01 configuration pass** (`internal/config`, `config.example.yaml`) — moved the runtime
knobs out of scattered env-var reads and package constants into one loader, on request, so intervals
are adjustable without a rebuild.

Precedence is defaults → YAML → environment. Environment wins so a container can override one value
without editing a baked-in file; the YAML exists because this ships into offline customer networks
(issue #1) where on-site staff need somewhere to change an interval that carries comments explaining
what is unsafe — a list of env vars in a unit file cannot do that. `config.example.yaml` is written
for that reader and is itself covered by a test, so a key renamed in Go without updating the example
fails the build rather than handing an operator a config that will not start.

Four choices worth keeping:
- **Durations are `"30s"` strings, never bare numbers.** Go parses a unit-less duration as
  nanoseconds, so `commandTTL: 30` would turn a 30-second window into 30ns and the expiry sweep into
  a busy loop. Refusing beats guessing — same reasoning as units-in-key-names in the protocol.
- **Unknown YAML keys are a startup error** (`yaml.Strict()`). The motivating case is
  `allowPhysicalCommand` (singular) being silently ignored while an operator believes they changed
  pump control.
- **`ALLOW_PHYSICAL_COMMANDS` rejects anything that is not true/false/1/0** rather than defaulting a
  typo to false. Both directions are dangerous: believing pumps are controllable when they are not
  is as bad as the reverse.
- **Cross-field validation at startup**, naming the offending key: `ackTimeout` must be shorter than
  `commandTTL` (otherwise every command expires before the device can answer), the sweep interval
  must not exceed the TTL, and no interval may be zero (a zero ticker panics at runtime — better a
  startup error than a 3am crash).

Deliberately *not* added: a `device.offlineAfter` key. Nothing reads it — ThingsBoard already tracks
device inactivity natively on the device profile, so "how long offline counts as abnormal" is a
ThingsBoard setting, not ours. A config key that nothing consumes is worse than none.

13 tests, no containers needed. Verified end to end by running the built binary against a real config
file: values applied, `COMMAND_TTL=120s` in the environment beat the file's `90s`, and a deliberately
conflicting `ackTimeout`/`commandTTL` pair failed at startup with both values printed.

**2026-09-01 stock-event pass** (`internal/protocol/stock.go`, `internal/api/stock.go`) — migrated
`/api/oil-in-out` onto ThingsBoard and deleted `/api/shifts`, on the product call that 出入库 is in
scope and 交班 is not.

The scope decision was cheap to make only because of a correction found while researching it: the
device **already publishes** 出入库 (`InOilManager:214 → AIDL → reportRawEvent("stockEvent", …)`,
reusing the alarm replay queue, so it survives an outage). An earlier claim here and in
zhongchengyeweiyi#39 — "neither side has an uplink" — was true for 交班 and wrong for 出入库; the
first search only looked for the `OilInOut`/`CommitWork` class names and that chain uses different
ones. Corrected publicly.

**But what it publishes is unusable**: `detailInoutRecord.toString()`, i.e. Java's debug format
(`DetailInoutRecord{oilcanId=1, beginHeith=120.5, …}`), not JSON. Consuming it would mean writing a
parser for a format with no contract — add a field or regenerate `toString()` and the cloud silently
stops parsing. The device-side fix is one line plus agreed field names; requested in #39. Until it
lands, `listOilInOutHandler` **skips** unparseable records rather than failing, so the endpoint
degrades to an empty list instead of a 500 (`TestListOilInOut_SkipsUnparseableRecords` publishes
exactly today's malformed string alongside a good record and asserts the good one still comes back).

Two modelling decisions worth keeping:
- **`netVolumeL` is tank-relative** (end − begin), so it is negative for a withdrawal, and a reader
  never branches on `direction` to interpret it. The API additionally computes `deliveredVolumeL` —
  the quantity that actually moved — because for an inbound delivery that is
  `netVolumeL + duringDeliveryVolumeL`, not the tank's net change. A depot that keeps selling while
  a tanker unloads will otherwise under-report every delivery, and that is the number people argue
  with suppliers about. Pinned by `TestListOilInOut_OutboundDeliveredVolumeIsPositive`.
- **Two device fields were deliberately left out of the protocol**: `beginTcVolume`/`endTcVolume`
  reads as temperature-compensated volume but is assigned from `OilCan.getSurPlus()`, and nothing
  documents what `surPlus` holds. A field named for one quantity and filled from another is the
  worst thing to freeze into V1 — same call as `config.setTank`'s arguments. Recorded in
  `docs/protocol-v1.md` §12.

`/api/shifts` was **deleted, not left returning an empty list** — a route that answers 200 with
nothing looks like a working feature, which is worse than a 404. `TestShiftsEndpointIsGone` pins it
so nobody re-adds it without first adding an uplink. `internal/api/records.go` shrank to just the
shared query-param parsing and was renamed `listparams.go`.

Deliberately untouched: the write-back `POST /api/*/correct` endpoints. They belong to the legacy
river queue, which is its own removal.

**2026-09-02 legacy stack deletion** — removed the Alink/Paho stack in full, closing the last of
issue #10's structural work. Deleted: `internal/mqtt`, `cmd/devicesim`, `internal/queue` (river),
`internal/partitions`, `dev/mosquitto`, the mosquitto compose service, `internal/api/writeback.go`,
four query files, and — via migration `000011` — the tables `alarms`, `oil_in_out`, `commit_works`,
`oil_can_sets` and `write_back_commands` with their partitions. `go.mod` lost river; paho stays
because `internal/devicesim` speaks ThingsBoard's MQTT.

Dropping tables is irreversible, so it was checked first: **all five were empty**, their ingestion
having already been removed. The down migration reproduces their DDL from `000001` (generated from
that file rather than retyped, so it cannot drift) and says plainly that it restores structure, not
data. Partitions are dropped explicitly even though Postgres would cascade them — sqlc tracks tables
statement by statement and would otherwise keep emitting models for partitions it never saw
dropped, i.e. dead types that look live. Deliberately **not** dropped: river's four tables, since
removing them from a later migration while `000002`–`000007` still create them breaks the history in
both directions; retiring those six files together is its own change.

**A real bug surfaced during the cleanup, and it was not the test's fault.**
`TestExpireStale_ClosesCommandsPastTheirWindow` failed roughly one run in three with a constraint
violation on `expires_at > issued_at`. Cause: `issued_at` defaulted to the *database's* `now()` while
`expires_at` was computed from the *application's* clock — two clocks for two fields of one row. With
that test's 1 ms TTL the insert simply outran its own expiry. The everyday consequence was quieter
and worse: every command's real validity window was silently shortened by the round-trip time.
`CreateControlCommand` now takes `issued_at` explicitly so both timestamps come from one clock
reading, which is also the one the device receives in the envelope. Five consecutive package runs
green afterwards.

**Side effect: the old river/mosquitto flakiness is gone** — `internal/api` and `internal/queue`
stealing river jobs from each other and `internal/mqtt`'s timing-sensitive `TestClient_OnlineStatus`
both lived entirely in the deleted packages. Three consecutive parallel runs were green here.
**That "parallel is now reliable" conclusion was drawn from too few runs and did not survive**: once
ThingsBoard had accumulated data and a fourth ThingsBoard-dependent package existed, parallel runs
started missing timeouts again for an unrelated reason (contention on the single ThingsBoard). See
Part 1 — `-p 1` is the standing recommendation.

Data-boundary assertions were upgraded rather than deleted: tests that counted rows in `alarms` /
`oil_in_out` / `commit_works` and required zero now assert those tables **do not exist**, which is
the same property stated more strongly and fails loudly if anyone reintroduces a local telemetry
copy.

Verified after deletion: the binary boots, `/healthz` 200, and `/api/shifts`, `/api/write-back` and
`/api/oilcan/latest` all return 404 (route absent) rather than 401 (route present but unauthorised).

**2026-09-02 offline deployment baseline** (issue #2) — `deploy/` plus `cmd/smoke`.

`deploy/` holds an image manifest pinned **by digest** (a tag can be re-pushed to different content;
`offline-bundle.sh` aborts if a pulled image's digest disagrees with the manifest), scripts to build
and load the offline bundle, a preflight check, backup/restore, three resource profiles, the
verbatim Apache-2.0 text, and `README.md` as the install/operations manual.

**`cmd/smoke` is a compiled binary rather than a `go test`** because the environment that most needs
it — an air-gapped customer network on installation day — has no Go toolchain, no test sources and
no internet. Eight checks: ThingsBoard reachable and authenticating, backend `/healthz`, device
provisioning with credential issuance, MQTT connect, telemetry up, telemetry read back, instant
alarm, and the full RPC → ACK → asynchronous-result loop. It provisions a throwaway device and
deletes it, and uses `alarm.mute` (non-physical) so a run against a live installation cannot move
equipment.

**A bug in that tool, found by running it rather than by reading it**: `os.Exit` in the summary
function skipped every `defer`, so the "self-cleaning" tool left its test devices behind — breaking
the exact promise its own doc comment made. `main` now calls `os.Exit(run())` so the deferred
cleanup runs first.

**Backup covers two databases, and missing that is the trap.** ThingsBoard's `tb-postgres` image
embeds *its own* PostgreSQL (`/data/db` in the container) holding devices, credentials, telemetry
and rule chains; the business PostgreSQL is a separate instance holding commands, audit, users and
bindings. Backing up one gives a half system on restore. Verified rather than assumed: a marker row
was inserted, backed up, deleted, restored, and came back; and the ThingsBoard dump was loaded into
a scratch database inside the container (71 tables, 9 devices) so restorability was proven without
touching the live one.

**Air-gapped *restart* verified** — distinct from the earlier air-gap test, because a service can
work offline yet still reach for something at boot. ThingsBoard was moved to a no-egress network,
restarted there, came up in ~70 s, and the cross-compiled smoke binary passed all eight checks from
inside that same network.

Operational gotcha worth remembering: **restarting a container while it is detached from the bridge
network loses its published host ports**, because Docker establishes port publishing at start from
the networks attached then. Reconnecting the bridge afterwards does not restore them; another
restart does. This bit during the test above and would bite anyone repeating it.

Also removed the two orphaned mosquitto volumes and the still-running mosquitto container left over
from the legacy stack deletion.

Deliberately incomplete, and listed as such in `deploy/README.md`: MQTT/TLS on 8883 is not
configured (devices still use plaintext 1884), no rule chains exist yet to export, offline
*upgrade* and rollback are issue #6, and the cloud resource profile is arithmetic, not a load test
(issue #5).

**2026-09-02 MQTT/TLS** (`deploy/gen-certs.sh`, `docker-compose.thingsboard-tls.yml`) — closes the
TLS item in issue #2's scope. Self-signed CA, because an offline network has no public CA and no way
to run ACME; trust is established by pre-loading `ca.pem` onto each device.

**Three things here were found by running it, and each has a failure mode that misleads:**

1. **ThingsBoard's PEM reader accepts only an *unencrypted PKCS#8* key.** Traditional PKCS#1
   (`openssl genrsa`'s default) fails, and so does PKCS#8 *with* a password. Both report the same
   `Unable to load private key neither from certFile nor from keyFile`, which names neither the
   format nor the real problem — easy to misdiagnose as a wrong path or password. My first two
   attempts were both wrong for this reason. Note the doc comment I originally wrote asserted a
   password was *required*; that was backwards.
2. **Enabling TLS does not disable plaintext.** `mqtt.ssl.bind_port` is a *separate listener*
   alongside `mqtt.bind_port` (verified in the container's own `thingsboard.yml`). "TLS is on" is
   therefore not the same as "plaintext is off" — the override rebinds 1884 to `127.0.0.1` so
   devices on the network cannot reach it while local debugging still can.
3. **Compose merges `ports` by appending, not replacing.** Declaring `1884` in both the base and the
   override produced two rules fighting over one port and the container refused to start with
   `address already in use`. Fixed with `ports: !override`, which is load-bearing, not stylistic.

Client side: `internal/devicesim` gained `CACertFile` and TLS support. Two details worth keeping —
`ServerName` must be set explicitly because paho does not derive it from the broker URL and
crypto/tls then refuses to proceed, and **there is deliberately no skip-verification option**: a
device that does not verify the server it reports levels to and takes pump commands from is not
protected by TLS, only encrypted against a passive observer. Only the private CA is trusted, not the
system roots.

Verified: `openssl s_client` reports `Verify return code: 0 (ok)` over TLSv1.3; the full smoke suite
passes over `ssl://localhost:8883`; and the negative cases fail as they must — no CA given is
rejected with a message naming the missing setting, and a *wrong* CA is rejected by certificate
verification rather than quietly accepted. Three unit tests in `internal/devicesim` cover the
config-validation paths without needing a broker.

`deploy/certs/` is gitignored — it holds private keys, and each deployment's are different.

Still open and listed in `deploy/README.md`: mutual TLS (client certificates) is not enabled —
device identity is still the ThingsBoard access token — and certificate renewal is manual with no
expiry alerting.

**2026-09-02 running the deployment scripts, and what that found** — the previous pass wrote
`deploy/`'s scripts but never executed the bundling path. Executing it found four defects, none of
which review would plausibly have caught:

1. **The ThingsBoard healthcheck could never pass.** It used `curl`; the image ships only `wget`.
   It had failed **7,190 consecutive times** and nobody noticed, because the service itself was
   healthy and nothing consumed the status. Worse, `deploy/README.md` said "unhealthy during
   startup is normal", which actively explained the symptom away. A check that can never pass is
   worse than none: it trains people to ignore the word, and any future
   `depends_on: condition: service_healthy` would hang forever. Now uses `wget`, and reaches
   `healthy` in ~85 s.
2. **`mapfile` is bash 4+; macOS ships bash 3.2.** The bundling script is most likely to be run on
   a developer's Mac, where it died immediately. Replaced with a portable read loop.
3. **`shasum` does not exist on `debian:12-slim`; `sha256sum` does — and macOS is the reverse.**
   Four scripts used `shasum` unconditionally, so `offline-load.sh` and `restore.sh` would have
   failed on the exact Linux machines they are written for, at the *verification* step. All four
   now pick whichever exists.
4. **macOS `tar` embeds xattrs that GNU `tar` warns about per file**, printing a screen of
   `Ignoring unknown extended header keyword` during install. Harmless, but a delivery that makes
   the operator doubt the package at step one is a bad delivery. `COPYFILE_DISABLE=1 --no-xattrs`
   → 0 warnings.

Also made the image pull tolerant of a failed network when the image is already local: the real
guarantee is the digest check, not the freshness of the pull, and re-exporting 1.1 GB because Docker
Hub blipped is pointless. (That blip happened during this very pass.)

Verified end to end: a 1.1 GB bundle builds in ~4 min; extracted on a clean `debian:12-slim` with
`--network none`, the checksums verify, all three static binaries run with no Go toolchain and no
libc, and deliberately corrupting a file makes verification fail.

**2026-09-02 ThingsBoard configuration versioning** (`cmd/tbconfig`, `deploy/tb-config/`) — the
last open scope item in issue #2. ThingsBoard keeps its configuration in its own database; without
this, "install a new environment from the document" means clicking through a console from
screenshots, and nobody can answer whether production matches the repo.

Devices now provision under a project profile, `yeweiyi-tank-terminal`, instead of ThingsBoard's
stock `default` — that one is shared with the demo devices every installation ships with, so
anything configured on it would apply to those too.

**Verified rather than assumed: ThingsBoard auto-creates a device profile when a device is created
with an unknown type.** That is what makes the switch safe — a fresh installation where nobody ran
`tbconfig apply` still provisions devices fine, just with ThingsBoard's own defaults instead of the
versioned settings. `apply` fills settings in; it is not a start-up prerequisite. Without checking
this I would have introduced a bootstrap ordering dependency into the offline install.

**Useful finding for the pending native-alarm decision**: the stock root rule chain already contains
`Device Profile Node`, which evaluates alarm rules defined *on the device profile*. So turning
device-reported alarms into ThingsBoard-native Alarm entities needs **no custom rule chain** — the
rules go in `profileData.alarms` of the device profile and the path is wired out of the box. That
remains a product decision (it changes `/api/alarms` semantics and the mini-program), but the
technical route is now known and cheap. Recorded in `deploy/tb-config/rule-chain-root-baseline.json`
and the deploy README.

Two deliberate limits in the tool: `apply` **never imports rule chains** (overwriting a live chain
can cut the data flow, which is a decision a person must make, not an automatic install step), and
it never takes over the tenant-wide `default` flag from the repo, since which profile is the tenant
default is installation-level state.

Written in Go, not shell: the install targets are minimal and air-gapped — `debian:12-slim` has
neither `python3` nor `curl` (both checked), and the first attempt as a shell script with inline
Python heredocs also hit a self-inflicted bug where the heredoc consumed the stdin the embedded
script wanted to read from. Go removes both problems and ships as another static binary.

`deploy/tb-config/current/` is gitignored — it is an instance snapshot for diffing, including
ThingsBoard's stock demo profiles, not an authoritative definition.

**2026-09-02 capacity testing** (`cmd/loadtest`, `docs/capacity-test.md`) — a start on issue #5.
**Only 200 devices were actually tested**; 1,000 and 10,000 were not, and the report says so in its
first table rather than burying it.

Measured on a 4-core / 8 GB laptop with ThingsBoard capped at a 2 GB heap: 200 devices × 2 tanks on
the production 30 s cadence sustained **174 datapoints/s with zero failures**, end-to-end latency
p50 56 ms / p95 111 ms, ThingsBoard CPU 23 %. End-to-end means device publish → visible to the
backend over ThingsBoard's WebSocket, both timestamps from one process's clock so there is no skew
to argue about.

**A 100 % reconnect storm — all 200 dropped and reconnected in the same instant with no backoff —
recovered in 390 ms**, losing exactly one in-flight snapshot and returning immediately to full
throughput. That is issue #1's named worst case, and the real fleet is gentler still because devices
back off exponentially with jitter.

**500 devices failed, and the failure is informative**: provisioning managed ~109 in ten minutes
while ThingsBoard sat at 1.94 GiB of its 2 GiB heap. Deletion measured 59/s immediately afterwards,
so the API had *not* degraded permanently — it was heap pressure during load, not a leak or a
deadlock. Since CPU was only 23 % at 200 devices while heap was already full, **the binding
constraint here is heap, not CPU**, which is the scaling lever `cloud.yml` already reaches for. What
this does *not* establish is a 1,000-device spec; that stays arithmetic until it runs on real
hardware, and the report says which conclusions are and are not supported.

**The harness panicked the first time the storm fired**: `d.sim` is replaced by the storm goroutine
while the publish goroutine is reading it — an unguarded field shared by two goroutines, nil
dereference inside `PublishSnapshot`. Fixed with a mutex-guarded accessor and re-verified under
`go build -race` (no data race reported). Worth remembering that a load-testing tool is itself
concurrent by construction and deserves the race detector: when it crashes it both leaves hundreds
of provisioned devices behind and looks like a fault in the system under test.

Operational note recorded in the doc: the client ran on the same machine as the server here, so its
own 200 MQTT sessions competed for the same CPU. At real scale the load generator must live on a
separate machine or the bottleneck cannot be attributed to either side.

**2026-09-02 test-flake correction** — while verifying the capacity work,
`TestFullLoop_CommandReachesTerminalStateViaIngest` failed 2 runs in 3 under `go test ./...`. Chased
rather than retried: it passes 4/4 alone (≈2 s each) and 2/2 with its own package, and fails only
when packages run in parallel, always at ≈29 s — its 25 s timeout plus overhead.

Cause is contention, not a defect: three packages now drive the same ThingsBoard, and on a
development machine the load generator shares CPU with the server. ThingsBoard was verified healthy
throughout — up two hours, healthcheck green, `TbSqlBlockingQueue` reporting `totalFailed [0]`.

A false lead worth recording: `docker logs --since "$(date -u ...)"` returned ThingsBoard's *startup*
logs, which read exactly like a crash-and-restart during the test. `RestartCount=0`, `OOMKilled=false`
and a two-hour uptime disproved it — the `--since` timestamp was being interpreted in a different
timezone, so the filter matched from container start. Worth knowing before concluding "it restarted"
from a log window again.

**This also corrects an over-claim I made earlier the same day**: the legacy-deletion entry says
`go test ./...` "is now reliable in parallel", concluded from three green runs. The river/mosquitto
flakiness it described genuinely is gone, but three runs was too small a sample to promote to
"stable", and the claim did not survive a loaded ThingsBoard plus a fourth dependent package.
`-p 1` is the standing recommendation again, now stated in Part 1 with the reason.

Deliberately not "fixed" by raising the timeout: 25 s for an operation that takes 2 s is already
generous, and stretching it would hide real slowness rather than reveal it.

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
internal/protocol/   # 设备协议 V1, the single source of truth for the wire contract: telemetry.go
                     # (30s flat-key tank snapshot), events.go (alarm + cmdResult), stock.go
                     # (出入库), rpc.go (command envelope, method registry, state machine),
                     # attributes.go, convert.go (accepts both the device-side JSON shape and
                     # ThingsBoard's stringified read-back shape), contract_test.go (fixture tests,
                     # no containers needed)
internal/tbclient/   # the ONLY door into ThingsBoard: auth + 401 re-login + error classification,
                     # device provisioning/credentials, REST timeseries/attributes, two-way RPC,
                     # live telemetry WebSocket. Public APIs only — never ThingsBoard's database.
internal/control/    # control command state machine, authorization, audit. Status transitions are
                     # guarded in SQL, not here. AllowPhysicalCommands defaults to false.
internal/ingest/     # one ThingsBoard telemetry subscription per device; routes cmdResult into
                     # internal/control and alarms onto the alarms:live Redis channel. Persists NO
                     # telemetry — that is the data boundary, and its test fails if that changes.
internal/config/     # defaults → config.yaml → environment. See config.example.yaml.
internal/devicesim/  # device-side reference implementation, driven in-process by tests
internal/api/        # REST/WS: auth, device binding, control + audit, and the read-through
                     # endpoints (alarms.go, stock.go, telemetry.go) that proxy ThingsBoard
cmd/server/          # the single binary
cmd/tbdevicesim/     # simulated device CLI (provision / run)
cmd/smoke/           # deployment smoke test, shipped as a binary because air-gapped install
                     # sites have no Go toolchain. Self-cleaning, safe against production.
cmd/tbconfig/        # versions ThingsBoard's own configuration (device profiles, rule chain
                     # baseline) as repo files; apply/export. Go rather than shell because
                     # debian:12-slim has neither python3 nor curl.
cmd/loadtest/        # repeatable capacity harness: provisions N devices, holds production-cadence
                     # load, measures true end-to-end latency, simulates reconnect storms, cleans
                     # up. Results and limits in docs/capacity-test.md.
deploy/              # offline delivery: image manifest with digests, bundle/load scripts,
                     # preflight, backup/restore, resource profiles, Apache-2.0 licence,
                     # and README.md — the install and operations manual
fixtures/protocol/v1/ # cross-repo protocol fixtures, shared with the Android repo
db/migrations/       # 000001 app schema … 000010 control+audit, 000011 drops the legacy tables
db/queries/          # sqlc input: devices, users, user_devices, control_commands, audit_log
docs/                # protocol spec, architecture baseline, cross-repo protocol reconciliation
```

**2026-09-03 PR #11 review-response pass** — fixed all 4 P1s and both P2s from the independent
review recorded on PR #11 (head `f95b33e`), plus the one nit. Nothing here is new scope; each item
below is a fix for something the review found already built.

1. **Cross-device `cmdResult` could complete another device's command (P1, real cross-tenant bug).**
   `MarkControlCommandFinished` (`db/queries/control_commands.sql`) guarded its transition on
   `command_id` alone; `device_name` — which `internal/ingest` determines from *which device's
   subscription the message arrived on*, not from anything inside the payload — was never checked.
   Device B reporting a commandId that actually belonged to device A could therefore move A's
   command to executing/succeeded/failed, and the audit trail would misattribute the result to B.
   Fixed by adding `AND device_name = $2` to the guard and threading `deviceName` through
   `control.Service.ApplyResult` into the sqlc call. Pinned by
   `TestApplyResult_CrossDeviceResultIsRejected` (`internal/control/control_test.go`): device B's
   report does not apply, the record's `device_name` and status are untouched, and the real owner
   can still complete it afterward.
2. **A cached-but-expired ThingsBoard JWT could wedge the telemetry WebSocket indefinitely (P1).**
   `tbclient.Client.Token` returned the cached token as long as it was non-empty, never checking
   whether it had actually expired — so once the process outlived a JWT's ~2.5h lifetime, every
   WebSocket reconnect (`internal/ingest`'s resubscribe loop) kept authenticating with the same
   stale token, with no REST 401 in that path to force a refresh. Fixed two ways: `Token()` now
   parses the JWT's own `exp` claim (no signature verification needed — it is a value ThingsBoard
   itself just vouched for over an authenticated login call) and proactively re-logs-in ahead of
   that expiry, with a conservative fallback TTL when the cached value is not a well-formed JWT (a
   test stub, say); and `SubscribeTelemetry`'s read loop now clears the cached token
   (`invalidateToken`) on any WebSocket read error or in-band subscription error, so a rejected
   socket cannot hand the same rejected token straight back to the next reconnect attempt.
   `TestClient_TokenRefreshesBeforeKnownJWTExpiry` and
   `TestSubscribeTelemetry_ReconnectsWithFreshTokenAfterWSAuthError`
   (`internal/tbclient/client_test.go`) cover both paths with a stub HTTP/WS server — no ThingsBoard
   needed, matching this package's existing style for provoking responses a healthy instance will
   not produce on demand.
3. **`LatestValues` could resurrect a tank the device stopped reporting (P2).** ThingsBoard keeps a
   key's last-ever value as its "latest" forever, even after the key stops being published. The
   telemetry-latest handler asked for a fixed, generously-sized key list (`SnapshotKeys(8)` by
   default) and merged each key's own latest point independently — so after a device was
   reconfigured from, say, two tanks to one, `tank2.*`'s stale values kept reappearing in every
   read, permanently, stamped with whatever other key's timestamp happened to be newest. Fixed by
   adding `tbclient.Client.LatestSnapshot`, which anchors on the `tankCount` key's own latest point
   (pinning both the timestamp and how many `tankN.*` groups are still current) and re-reads exactly
   that snapshot's fields at that exact timestamp via a `[ts, ts+1)` history query, instead of
   trusting each key's independent "latest" cache. `latestTelemetryHandler` now calls this instead
   of `LatestValues` + a caller-guessed tank count, and the `tanks` query-param override is gone
   with it — the tank count now always comes from the device's own last report, not a client guess.
   Verified against a real ThingsBoard container (not a stub — this is exactly the kind of
   ThingsBoard-behavioural claim this project has been burned by trusting unverified before):
   `TestLatestSnapshot_DropsTankRemovedSinceReconfiguration`
   (`internal/tbclient/e2e_test.go`) publishes as a 2-tank device, reconnects the same access token
   as a 1-tank device, and asserts `LatestSnapshot` reports only tank 1 while `LatestValues` (left
   unfixed, as a generic primitive — this is a genuine caller misuse, not a bug in the primitive
   itself) is separately asserted to still show the old bug, proving the fix does real work.
4. **The offline installer's own documented commands could not complete on a clean air-gapped
   machine (P1).** Two independent gaps: `deploy/README.md`'s migration step runs
   `docker run ... migrate/migrate`, but that image was never in `deploy/images.txt` or the exported
   bundle — a from-scratch install would try to pull it and fail with no network. And
   `offline-bundle.sh` copied `tb-config/` to the bundle root, while `cmd/tbconfig`'s default `-dir`
   is `deploy/tb-config` (correct for the dev convention of running `go run ./cmd/tbconfig` from the
   repo root) — so the documented `./bin/tbconfig apply`, run from the bundle root as the README
   says, silently found nothing to apply. Fixed by adding `migrate/migrate:v4.18.1` (pinned by
   digest, same convention as the other three images) to `images.txt` and pinning the README's
   `docker run` to that exact tag (a bare `migrate/migrate` resolves to `:latest`, which does not
   exist locally on an offline machine — this was the same class of mismatch, just one line down),
   and by nesting the bundle's copy under `deploy/tb-config` to match the tool's default instead of
   changing the default itself (which would have broken the working dev convention). Verified by
   actually running `offline-bundle.sh` and then `tbconfig apply` from the produced bundle's root
   directory against the real local ThingsBoard — it applied the device profile template
   successfully, where it previously would have silently no-opped.
5. **`restore.sh` could let ThingsBoard's own application run during the restore it explicitly says
   must not happen (P1).** The script's own comment says ThingsBoard must be stopped for the
   restore because it "holds connections and caches entities" — but the code immediately below that
   comment did `docker stop` followed by `docker start` on the *whole* container, and starting the
   container starts the Java app in the same breath as its embedded PostgreSQL (they are one
   process tree under the image's own `start-tb.sh`), so the app could already be connected or
   writing by the time `psql` ran. Fixed by restoring into a throwaway container that shares the
   real container's volumes (`--volumes-from`, which works against a stopped source container) but
   overrides the entrypoint to run only `start-db.sh` — the same script `start-tb.sh` itself calls
   before ever touching Java — so the app genuinely cannot be running during the restore; the real
   container is started only afterward, on top of already-restored data. Also fixed the review's
   second point in the same finding: the readiness-wait loop now fails explicitly on timeout instead
   of falling through to `psql` regardless, which previously would have produced a confusing
   "restore" error that was actually an unfinished wait. **Verified against the real local stack,
   not just read**: took a real backup, ran the fixed `restore.sh` against it, confirmed via
   `docker inspect`'s `StartedAt` that the real container was started only after the restore
   completed, confirmed no leftover throwaway container, and confirmed ThingsBoard came back
   healthy with the same device count as before.
6. **The deployment smoke test's later steps never actually exercised the Go backend (P2).** Past
   `/healthz`, the tool talked to ThingsBoard directly for telemetry read-back, alarm read-back, and
   the control RPC — so API auth, device-ownership checks, the ingester, the `control_commands`
   state machine, or even a broken migration could all be silently broken while `smoke` printed all
   green, which does not support the install doc's "must be all-green" / "closes the RPC→ACK→result
   loop" claims. `cmd/smoke` now seeds its own throwaway login user directly in the business
   database (there is no self-registration endpoint, by design) when `-api` is given, exchanges it
   for a real JWT through the real `POST /api/login`, and drives every step after that — device
   bind, telemetry read-back, alarm read-back, command issue, terminal-result polling — through the
   real REST API instead of ThingsBoard directly, finishing with a new step that confirms
   `GET /api/audit` actually recorded `control.issue`/`control.ack`/`control.result` for that exact
   commandId. The ThingsBoard-only fallback (no `-api`) is kept for the tool's original,
   still-documented use case. Running this against the real local stack caught a real bug in the
   smoke tool itself before it shipped: `GET /api/alarms` answers with a bare JSON array, not
   `{"alarms": [...]}` like this project's other list endpoints — the first version of the new alarm
   check assumed the wrapped shape and failed decoding a real response. Fixed and re-verified: 12
   steps green end to end with `-api`, 8 green in the ThingsBoard-only fallback, and no leftover
   devices or users in either database afterward.
7. **Nit**: trailing whitespace on `deploy/profiles/small.yml`'s `"-c"` line, removed.

**2026-09-03 PR #11 second review-response round** — pushing the round above (head `81d697d`) got
an independent re-review, which credited the round above's fixes but found 3 more P1s and one more
P2, plus a short list of items from the *first* round that were still unaddressed. Fixed all of it:

1. **`restore.sh` still let the Go backend itself write during a restore (P1).** The round-above fix
   stopped ThingsBoard's own Java app from running during its DB restore, but said nothing about the
   *business* PostgreSQL restore two steps earlier — the Go backend is a separate, independent writer
   this script never touched, and it could still be holding connections or issuing queries against
   tables mid-`DROP`/`CREATE` while `--clean` ran, or write rows after the backup's timestamp that
   then disagree with the ThingsBoard snapshot restored right after. Fixed by adding an explicit
   precondition, checked before the destructive-confirmation prompt: probe `$API_URL/healthz`
   (default `http://localhost:8080`, overridable) and refuse to proceed with instructions if it
   answers — this project has no single canonical way to stop the backend (bare `&`, systemd, and
   the deploy docs mention both), so the script can only detect "is it still up," not safely kill an
   arbitrary process on the operator's behalf. Also added a script-wide `ERR` trap (confirmed by
   testing that it does *not* fire on the script's existing deliberate `exit 1` cancellation paths —
   those are exempt from bash's `set -e`/`ERR` semantics as part of an `||`/`if`) that tells the
   operator the system is still in a stopped, safe state and not to `docker start` the ThingsBoard
   container until the failure is understood. **Verified live, not just read**: ran the script with
   the real dev-stack backend up — refused, correctly, with the new message; stopped it, reran — the
   full restore completed as before (device count intact, ThingsBoard healthy, no leftover
   container).
2. **`LatestSnapshot` trusted the device-reported `tankCount` with no bound (P1).** It passed the
   value straight into `protocol.SnapshotKeys`, which allocates 12 keys per tank — a corrupted or
   compromised device reporting one absurd `tankCount` value would sit as ThingsBoard's permanent
   "latest" for that key, and every subsequent read would build an unbounded key list and query
   string off it. Fixed by adding `protocol.MaxTankCount` (64 — the same ceiling the now-removed
   client-supplied `tanks` query param used to enforce, moved to the trust boundary that actually
   needed it once that param was removed) and rejecting anything outside `[0, MaxTankCount]` before
   the expensive history query is ever built. `TestLatestSnapshot_RejectsOutOfRangeTankCount` (three
   cases: negative, one past the bound, absurdly large) confirms both the rejection and — the part
   that actually matters for the resource-exhaustion concern — that the history query is never
   issued; `TestLatestSnapshot_AllowsBoundaryTankCount` pins that 0 and exactly `MaxTankCount` are
   still accepted, so the fix isn't off-by-one in the strict direction.
3. **A mass WebSocket disconnect could trigger a login storm against a recovering ThingsBoard, and a
   slow-to-report stream could wipe out a fresher token (P1).** `internal/ingest` runs one
   subscription per device, all sharing one `tbclient.Client`; the round-above fix has any WS read
   or in-band error call `invalidateToken()`. Put together: a ThingsBoard restart or network blip
   disconnects every device's subscription near-simultaneously, each independently calls `Token()`
   with an empty/expired cache, and — since `Token()`'s check-then-Login was not coalesced — each
   would fire its own `/api/auth/login` at a platform still recovering, a storm proportional to
   fleet size. Separately, the previous `invalidateToken()` cleared the cache unconditionally, so a
   connection that was slow to notice its own rejection could clear a token some *other* goroutine
   had already refreshed to in the meantime, forcing a needless extra login wave right when load
   should be settling. Fixed both: `Login()` now coalesces concurrent callers through
   `golang.org/x/sync/singleflight` (promoted from an indirect to a direct dependency — no new
   external dependency edge, since it was already vendored transitively), so N concurrent `Token()`
   calls against an empty cache produce exactly one HTTP round trip; and `invalidateToken` became a
   compare-and-clear, taking the specific token that failed and only clearing the cache if it still
   holds that exact value — the WebSocket read loop now passes its own `token` variable through.
   `TestClient_ConcurrentTokenCallsCoalesceIntoOneLogin` drives 50 goroutines at a login held open
   until they have all queued behind it and asserts exactly one `/api/auth/login` hit; the same test,
   like the fix, holds under `go test -race`.
   `TestClient_InvalidateTokenDoesNotClearAFresherToken` opens a stream on token #1, forces an
   independent second login to token #2 while the stream's rejection is deliberately held back (via
   a channel the test controls, not a timing hope), then releases it and confirms the cache still
   holds token #2 and no third login happened. Writing that second test surfaced a real bug in the
   test helper itself, not the fix: `makeJWT` encoded `exp` at whole-second granularity, so two JWTs
   minted within the same wall-clock second (routine for a fast test) came out byte-identical,
   making the compare-and-clear spuriously match — fixed by giving `makeJWT` a monotonically
   incrementing `jti` claim so generated tokens are always distinguishable regardless of timing.
4. **The smoke tool's own doc comment overclaimed what it cleans up (P2).** It said it "never
   touches existing devices, users or business data," but a successful `-api` run permanently leaves
   its `control.issue`/`control.ack`/`control.result` rows in `audit_log` — not a bug, since
   `audit_log` deliberately has no foreign key to `devices` or `control_commands` and never cascades
   a delete (its own migration comment: a denied attempt against a device that turns out not to
   exist is exactly the kind of event worth keeping). Deleting those rows from the smoke tool for its
   own tidiness would punch a hole in that guarantee. Fixed by correcting the doc comment instead of
   the behavior: it now says plainly that audit rows persist by design and are filterable by the
   `smoke-` prefix every run's `device_name` (and, for the actor column, either that same login
   username or `device:smoke-<nanos>`) already carries.
5. **Two items left over from the *first* review round, both textual/behavioural, no dedicated new
   test**: `internal/api/listparams.go`'s `parseListParams` defaulted a caller who omitted
   `beginTime` to "since epoch" — combined with the default ascending sort and 50-row limit, this
   silently answered "the 50 oldest records ever" for the common case of a client just wanting
   recent history. Changed the default window to the last 24h, matching `/api/audit`'s own default
   (`control.go`'s `timeRange`) — the codebase already had the right convention in one place and not
   the other. And this file's own Part 1 quickstart still said "setting `MQTT_BROKER_URL` starts the
   legacy stack" after that stack (`internal/mqtt`, `cmd/devicesim`) was deleted outright on
   2026-09-02 — `cmd/server` no longer reads that variable at all, so the corrected line now says so
   instead of describing a code path that no longer exists. (The PR body's own stale "12 commits"
   count and its claim that parallel `go test ./...` "no longer needs `-p 1`" — the second of which
   this file's own 2026-09-02 test-flake-correction entry above already retracted — were also fixed,
   but in the PR description on GitHub, not in a file this journal tracks.)

Not fixed here, and not this PR's to fix unilaterally: no CI checks exist yet for this repository —
tracked as one of the Draft checklist items on the PR itself, not a code change.

**2026-09-04 PR #11 third review-response round** — pushing the round above (head `3201897`) got a
third independent review (submitted as a plain comment, since GitHub does not let a PR's own author
request changes on it — the conclusion stands as "needs another fix pass" all the same). It credited
everything fixed in the first two rounds and found 2 more P1s and 1 more P2, all genuine and all in
code the second round's own new tests never happened to exercise together. Fixed all three:

1. **A device's `cmdResult` could be silently dropped if it arrived before the synchronous ACK
   bookkeeping caught up (P1).** `control.Service.Issue` calls ThingsBoard's two-way RPC
   synchronously and only writes `sent`/`received` into the row *after* that call returns — but the
   device's `cmdResult` travels over a completely independent channel (the telemetry WebSocket,
   read by `internal/ingest`) with no ordering guarantee against it. A fast device can report its
   result while the row is still `pending` in our own bookkeeping, and `MarkControlCommandFinished`'s
   guard did not accept `pending` — so that `UPDATE` matched zero rows, `ApplyResult` treated the
   result as inapplicable and dropped it, the ACK bookkeeping that ran moments later left the row
   stuck at `received`, and the command eventually expired despite the device having already
   reported success. Fixed by adding `pending` to the guard — safe to accept from there because a
   device can only ever learn a `commandId` by having actually received it over the RPC, which
   cannot happen before the row exists, so there is no earlier state a forged or premature result
   could be exploiting — and backfilling `sent_at` with `COALESCE(sent_at, now())` so a command that
   finishes via this race does not carry a permanently-null `sent_at`.
   `TestApplyResult_AppliesWhileStillPending` (`internal/control/control_test.go`) reproduces the
   real ordering rather than hoping timing lines up: it blocks the fake sender's `SendCommand` on a
   channel so `Issue()` is provably still inside its synchronous RPC call — the row is guaranteed
   `pending` — applies a result from a second goroutine, then releases `SendCommand` and confirms the
   later ACK bookkeeping does not clobber the already-applied terminal result. Passes under
   `-race`.
2. **A platform admin could issue a command against an unbound device but then never read its result
   back (P1).** `control.Service.authorize` already scopes a platform admin to every device by
   definition, but `internal/api/devices.go`'s `requireDeviceOwnership` — called by every
   device-scoped *read* handler, including the only endpoint that polls a command's asynchronous
   result — checked `user_devices` unconditionally, with no exception for role. So a platform admin
   could `POST` a command against a device nobody had bound them to (control's own authorize allows
   it) and then get 403 from `GET /api/devices/:device/commands` when trying to see whether it
   finished. Fixed by mirroring `authorize`'s exact rule in the one place `requireDeviceOwnership` is
   defined, so every one of its callers (commands, audit, telemetry, alarms, stock) picks it up at
   once rather than needing five separate patches.
   `TestListDeviceCommands_PlatformAdminCanPollUnboundDevice` (`internal/api/control_test.go`)
   issues against an unbound (but real) device as a platform admin, confirms the poll now reads
   `200` with the issued command in it, and spot-checks `GET /api/audit` too — proving the fix is in
   the shared helper and not a one-off patch on a single handler.
3. **A whitespace-only 二次确认 reason surfaced as a 500, not a 400 (P2).** `Issue`'s own check was
   `req.Reason == ""`, and Gin's `binding:"required"` on the HTTP side rejects only a literally empty
   string — so `"   "` sailed past both and was only caught by the database's
   `CHECK(length(btrim(reason)) > 0)` constraint, surfacing as an error `writeControlError` does not
   recognize and therefore maps to a generic 500 instead of the `ErrReasonRequired` → 400 a bad
   user input deserves. Fixed by trimming `req.Reason` before checking it (and using the trimmed
   value from there on, so the stored record does not carry the whitespace either).
   `TestIssue_RejectsWhitespaceOnlyReason` pins the correct sentinel/status for a `"   \t  "` reason.

**2026-09-04 PR #11 fourth review-response round** — pushing the round above (head `c66e229`) got a
fourth independent review (again recorded as a plain comment, since GitHub does not let a PR's own
author request changes on their own PR). It credited every fix from the first three rounds and found
exactly one more P1, in code none of those rounds' own new tests happened to touch.

1. **A platform admin could read a device's REST data and issue commands against it, but was refused
   a live alarm subscription for that same unbound device (P1).** Round 3 added a platform-admin
   shortcut to `requireDeviceOwnership` (`internal/api/devices.go`), which every REST device-scoped
   handler calls. `wsAlarmsHandler` (`internal/api/ws.go`) never routed through it — it authenticates
   its own JWT independently of `authMiddleware`, because a WebSocket handshake cannot carry the
   usual header, and had its own separate, unconditional `UserOwnsDevice` call that round 3's fix
   never touched. So a platform admin could `POST` a command against an unbound device and read it
   back over REST, yet get 403 from `GET /ws/alarms?device=<unbound>` — exactly the path a platform
   on-call operator needs to work across every device, not just their own. Fixed by extracting the
   actual rule (`userMayAccessDevice`) out of `requireDeviceOwnership` into its own function that
   both the REST helper and `wsAlarmsHandler` call, so the two authorization paths cannot drift apart
   a second time — a lesson from round 3's fix only catching one of the two call sites the first
   time.
   `TestWSAlarms_PlatformAdminCanSubscribeToUnboundDevice` and `TestWSAlarms_ForbiddenForUnownedDevice`
   (new file, `internal/api/ws_test.go` — `/ws/alarms` had no automated coverage at all before this,
   only the 2026-08-20 manual verification recorded further down this file) cover both directions.

   **Writing that test surfaced a real, separate gorilla/websocket gotcha, not a bug in the fix**:
   the first version of the test retried `conn.ReadMessage()` after each deadline timeout while
   republishing, and failed deterministically about half the time — with server-side logs showing
   every single publish being received and written to the connection successfully. Root cause:
   gorilla/websocket documents that once `ReadMessage` returns *any* error, including a plain
   deadline timeout, that error is permanent — every later call on the same `Conn` returns the same
   cached error, no matter how much data arrives afterward. A retry loop built around "read, and on
   timeout, read again" can therefore never recover once it hits its first timeout; the fix was to
   retry the *publish* side instead (a background goroutine republishing every 200ms) while calling
   `ReadMessage` exactly once with a generous overall deadline. Worth remembering for any future test
   against this or any other gorilla/websocket connection: never loop `ReadMessage` across more than
   one deadline on the same `Conn`.

**2026-09-04 PR #11 fifth review-response round** — pushing the round above (head `44ae750`) got a
plain PR comment (not a formal review — same GitHub author-can't-request-changes-on-own-PR
limitation as before) flagging one more P1, in the device-pairing endpoint none of the first four
rounds' own new tests happened to touch.

**Superseded by the very next round, below: reusing ThingsBoard's device access token as the
pairing proof (this entry's fix) was itself a P1 — it leaked the device's MQTT identity. Read this
entry for the "why deviceName alone isn't enough" history; read the 2026-09-04 sixth round below for
the design actually in the code now.**

1. **Any logged-in user could take over any device just by knowing or guessing its `deviceName`
   (P1, real cross-tenant takeover).** `bindDeviceHandler` (`internal/api/devices.go`) accepted a
   bare `{"deviceName": "..."}` body and unconditionally `EnsureDevice`+`BindUserDevice`'d it — the
   2026-08-29 authorization pass's "possession of the physical device is the authorization" design
   intent was never actually enforced in code. Since `deviceName`s are human-readable and plausibly
   patterned (e.g. `tank-farm-014`), any authenticated attacker could bind themselves to a device
   that was never theirs and, from there, read its telemetry/alarms and issue whatever control
   commands their role allows — no scan, no secret, no server-side check of any kind stood between
   a guess and full device access.

   Fixed by requiring the bind request to also carry `pairingToken`, verified
   (`verifyPairingToken`) against the actual ThingsBoard access token ThingsBoard issued that
   device — the same "one-device-one-secret" value the device already uses as its MQTT credential
   (`tbclient.DeviceCredentials.CredentialsID`), read back via the tenant-admin session
   `internal/tbclient` already holds, and compared with `crypto/subtle.ConstantTimeCompare` since
   it is a bearer secret. This closes the actual hole (forged/guessed `deviceName` can no longer
   bind anything) without inventing a second, parallel secret/provisioning flow, and without a new
   migration — reusing a value ThingsBoard was already the source of truth for.

   Two things this fix deliberately does **not** do, both because they would silently break or
   preempt an existing, separately-made product decision rather than because they weren't
   considered:
   - **Not single-use.** The reviewer's own suggestion floated a one-time pairing token; a durable
     per-device secret was chosen instead because CLAUDE.md's 2026-08-29 authorization pass made
     `user_devices` many-to-many specifically so more than one legitimate staff member can bind the
     same physical device from the same QR code over time (e.g. multiple technicians on one tank) —
     a token redeemable only once would break that on the very first successful bind.
     `TestBindDevice_SecondUserCanBindSameDeviceWithSameToken` pins that a second real user can still
     bind with the same token, as a regression guard against accidentally narrowing this later.
   - **Not a redesign of the QR code's content format.** CLAUDE.md's "Open product decisions"
     already flags the QR payload shape as unresolved and cross-repo (whoever designs the physical
     label/screen, not this repo unilaterally) — this fix answers "what must the server verify"
     without answering "what exactly goes on the label," which stays open.

   Errors are deliberately **not** distinguished between "no such device in ThingsBoard" and
   "device exists but the token is wrong" — both answer the same 401 with the same message, the
   same anti-oracle reasoning `requireDeviceOwnership`'s own doc comment already gives for every
   other device-scoped endpoint (CLAUDE.md invariant 6: authorization checked before existence).
   `TestBindDevice_UnknownDeviceAndWrongTokenAreIndistinguishable` asserts the two cases produce
   byte-identical status and body, which is what actually pins that property — a test that only
   checked "both are 401" would pass even if the two cases carried different, name-leaking messages.

   `internal/api/devices_test.go` gained a real-ThingsBoard test environment (`newBindEnv`,
   `provisionTBDevice`) — the previous bind tests only touched the business database, but this
   handler's whole job now is checking a real ThingsBoard credential, so a stub can't stand in the
   way `internal/control`'s tests get away with. Also fixed `cmd/smoke/main.go`'s own bind call,
   which — being written before this fix — was itself relying on the exact hole being closed: it
   already fetches the device's real access token for the MQTT step right above the bind step, so
   the fix was passing that same value through as `pairingToken` rather than any new provisioning
   work. Verified against the real local stack, not just the test suite: ran the built server and
   `cmd/smoke -api` end to end (12/12 steps green, unchanged from before this fix), then separately
   probed the running server directly with `curl` as a freshly-seeded throwaway user attempting to
   bind a plausible-looking but nonexistent device name with a fabricated token — `401
   {"error":"invalid device name or pairing token"}`, and confirmed via `psql` that no `devices` row
   was created. All throwaway users/rows removed afterward.

**2026-09-04 PR #11 sixth review-response round** — pushing the round above (head `c64d25f`) got
another plain PR comment finding one more P1, this time in the fix itself rather than in
previously-untouched code.

1. **The round-5 pairing fix leaked the device's MQTT identity (P1).** Round 5 verified
   `pairingToken` against `tbclient.GetDeviceCredentials`' `ACCESS_TOKEN` — the device's real
   ThingsBoard credential, which is also its passwordless MQTT username (`internal/devicesim`'s
   `SetUsername(AccessToken)`). Putting that value in a QR code and treating it as a *pairing*
   secret meant anyone who photographed the code, or captured it off a proxy or client log, could
   connect to MQTT **as the device itself** — forge telemetry, alarms, and `cmdResult`s — not merely
   bind themselves to it in this backend. A pairing credential leaking should cost, at worst, a
   binding; round 5's design let it cost the device's entire on-wire identity.

   Fixed by giving pairing its own secret, fully independent of ThingsBoard: `device_pairing_secrets`
   (migration `000012`) stores only a bcrypt hash of a randomly-minted 32-byte value, the same way
   `users.password_hash` stores passwords — never the plaintext, never anything ThingsBoard issued.
   A new endpoint, `POST /api/devices/:device/pairing-secret` (`mintPairingSecretHandler`,
   `internal/api/devices.go`), mints and returns the plaintext exactly once; `bindDeviceHandler`'s
   `verifyPairingToken` now checks the candidate against that hash via
   `bcrypt.CompareHashAndPassword` and never calls `deps.TB` at all. Two side effects worth knowing,
   both improvements rather than incidental:
   - **Binding no longer depends on ThingsBoard being up.** It used to 503 when `deps.TB == nil`;
     now it's a pure business-database operation, consistent with this package's existing "business
     endpoints work without a 2 GB container" convention (the same reasoning behind every other
     `deps.TB == nil` check in this file).
   - **Minting is gated to `platform_admin`, not any authenticated user.** This is a new trust
     boundary the previous design didn't need (ThingsBoard was already the source of truth for which
     credentials were real): without the gate, any `customer_user` could mint a fresh secret for
     *any* `deviceName` — including one already provisioned and bound to somebody else — and
     immediately bind themselves with it, reopening round 5's exact takeover through a different
     door. `platform_admin` is already the unconditionally-trusted operator tier elsewhere in this
     codebase (`control.Service.authorize` grants it every device by definition), so reusing that
     tier for a provisioning action is consistent, not a new policy question.

   Still deliberately **not single-use**, for the same reason round 5 gave: `user_devices` is
   many-to-many by product decision (2026-08-29), so more than one legitimate staff member must be
   able to redeem the same physical QR code over time. Minting always *rotates* instead — replacing
   the previous secret outright — so a leaked or lost secret is fixed by re-minting, which does not
   evict anyone already bound. `TestMintPairingSecret_RotatingReplacesThePreviousSecretButKeepsExistingBindings`
   pins both halves of that: the old secret stops working, the old binding doesn't.

   Also fixed `cmd/smoke/main.go`, which — being written for round 5's design — was passing the
   ThingsBoard access token through as `pairingToken`. Rather than granting the tool's throwaway
   login user `platform_admin` just to call the new mint endpoint (which would have made every later
   step in the run exercise the admin-bypass authorization path instead of the ordinary
   `customer_user` ownership-gated path a real mini-program user actually takes), it now mints the
   secret as a **direct fixture write** to the business database
   (`mintPairingSecretFixture`) — the same "direct-database step is fixture setup only; every check
   afterward goes through the API" convention the tool already uses for seeding its throwaway login
   user.

   New tests in `internal/api/devices_test.go`: the bind tests no longer need a real ThingsBoard at
   all (rewritten around a new `env.mintPairingSecret` helper that mints through the real endpoint as
   a throwaway admin — `internal/api/api_test.go` gained `otherUserToken(role)` to create that user),
   plus three new ones specific to this round —
   `TestMintPairingSecret_ForbiddenForNonAdmin` (pins the role gate, and that a rejected attempt
   creates no row), `TestMintPairingSecret_DoesNotReturnOrAcceptThingsBoardAccessToken` (mints and
   binds with **no** `TB` wired into `Deps` at all, so any accidental ThingsBoard dependency would
   fail loudly rather than silently), and the rotation test above. Verified against the real local
   stack: `migrate up`/`down 1`/`up` round-tripped cleanly on migration `000012`; the full built
   binary plus `cmd/smoke -api` ran end to end (12/12, unchanged); and a live `curl` probe as a
   throwaway `customer_user` confirmed both `POST /api/devices/some-devicename/pairing-secret` → 403
   with no row created, and a guessed-device-name bind attempt → 401, against the running server.
   `go test ./... -p 1 -count=1` stayed green throughout.

   One operational note worth recording since it cost real debugging time on this pass: a stale
   `cmd/server` process from an *earlier* verification round was still holding the test port after
   being (incorrectly assumed) killed via a backgrounded shell job — each `Bash` tool invocation in
   this environment does not reliably share job-control state (`%1`) with a previous invocation, so
   `kill %1` silently no-opped. The symptom was a confusing `404` on the new route (the old binary
   genuinely didn't have it) that briefly looked like a routing bug. `lsof -i :<port>` to find the
   actual PID holding a port, rather than trusting a job-control kill, is the reliable way to check
   before concluding a rebuilt binary isn't behaving as built.

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
