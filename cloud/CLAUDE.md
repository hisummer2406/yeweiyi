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
  The mechanism now exists (`internal/queue`'s `TTLSweepWorker`, default 30 days via
  `queue.DefaultTTL`, overridable with `WRITE_BACK_TTL_DAYS` — see "Build"/"Architecture" below) —
  don't read the 30-day default as the product decision, it's a placeholder mirroring the design
  doc's example number.
- ~~Whether PC-remote and mini-program share one account/auth system or two independent ones.~~
  **Decided 2026-08-20: one shared account/auth system.** A single user table and JWT issuance path
  serves both PC-remote and mini-program clients — no per-client-type account silo. See "Tech stack"
  below for the auth library choice this unblocked.

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
- **MQTT**: `eclipse/paho.mqtt.golang` against the Aliyun IoT broker's standard MQTT protocol — no
  Aliyun-specific SDK required, just the topic contract above.
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
- **Hosting**: Aliyun (阿里云) ECS — decided 2026-08-20. Same cloud as the MQTT broker (Aliyun IoT),
  so the ingestion path (broker → backend) stays intra-cloud rather than crossing the public
  internet. Plain VM over 容器服务 ACK/函数计算 — no containerization/serverless complexity needed
  for a single Go binary at this stage.
- **Auth**: JWT via `golang-jwt/jwt` for token issuance/validation, `golang.org/x/crypto/bcrypt` for
  password hashing at `/api/login`. One shared user table/token scheme serves both PC-remote and
  mini-program clients (see "Open product decisions" above — that question is now resolved).
  Explicitly not the device's own LAN `/login` pattern (plaintext table lookup, no token, no
  session) — design doc §4.4 flags that as something to not replicate.

**Why Go, over the Python/Node alternatives that were also considered**: expected device count is
~10,000. At the product-confirmed 10–30s polling cadence (see "MQTT contract" above), the polling/
MQTT-ingestion core sees roughly 330–1,000 service/response round-trips per second — a concurrency
profile Go's goroutine-per-device model absorbs natively, without the multi-process device-sharding
a Python asyncio service would need to engineer in from day one at this scale. The team is equally
comfortable in Go and Python, so that wasn't the deciding factor; what tipped it is that this
codebase will be written primarily by Claude, and Go's compiler catches a class of AI-generated-code
mistakes (type mismatches, unhandled error paths) at `go build` time rather than at runtime — a
tighter, more deterministic feedback loop than Python offers when a human isn't hand-authoring every
line.

## Build

Module `yeweiyi-cloud` (Go 1.26, see `go.mod`). Verified 2026-08-20 against the local dev stack
(`docker compose up -d`, see `docker-compose.yml`):

```
go build ./...                  # compiles cleanly
go vet ./...                    # clean
sqlc generate                   # regenerates internal/store/db from db/migrations + db/queries
migrate -database "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable" \
        -path db/migrations up  # applies all migrations; down-then-up round-trip verified for each
go test ./...                   # internal/queue has real integration tests against local postgres;
                                 # everything else has no test files yet
JWT_SECRET=... go run ./cmd/server   # listens on :8080
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

**What this proves**: the module wiring (gin, pgx/v5 pool, go-redis client, JWT/bcrypt auth,
river/riverpgxv5), the sqlc-generated query layer, the migration schema (including river's), the
four read endpoints, and the write-back queue's pending→sent hop all fit together and work against
the local Postgres/Redis containers. **What it does not prove**: no MQTT code exists yet
(`internal/mqtt` is empty), so nothing populates real device data — everything above was tested
against hand-seeded rows, not real ingestion; the queue's `Publisher` is a placeholder that always
errors in the real binary (`unbuiltMQTTPublisher` in `main.go`) since nothing can actually reach a
device yet, and nothing currently calls `EnqueueWriteBack` in the running server either (the
tank/probe config write endpoints aren't built); the sent→acked transition and the TTL day-count are
still open per "Open product decisions" below; `/ws/alarms` isn't implemented; and none of this has
been exercised against a real Aliyun IoT broker — there isn't one provisioned yet (see "MQTT
contract" above).

Module layout (current, not just planned):

```
cmd/server/main.go   # entrypoint: wires gin + pgxpool + redis client + river queue client via
                     # internal/api.NewRouter / internal/queue.NewClient
internal/mqtt/       # empty — Aliyun IoT subscribe/publish, topic routing (design doc §4.2)
internal/api/        # router.go, health.go, auth.go (JWT login + middleware), records.go
                     # (alarms/oil-in-out/shifts list handlers), oilcan.go (Redis-cached snapshot
                     # read) — no /ws/alarms yet
internal/queue/      # client.go (river wiring), enqueue.go (transactional insert+enqueue),
                     # publish.go (pending->sent worker + Publisher interface), ttl_sweep.go
                     # (§5.3 periodic sweep), queue_test.go (integration tests, see above)
internal/store/db/   # sqlc-generated: db.go, models.go, querier.go, + one .sql.go per query file
db/migrations/       # golang-migrate schema; 000001 is the app schema, 000002-000007 are river's
                     # (see above)
db/queries/          # sqlc query input (devices.sql, users.sql, alarms.sql, oil_in_out.sql,
                     # commit_works.sql, write_back_commands.sql) — sqlc.yaml at repo root wires
                     # schema=db/migrations, queries=db/queries, out=internal/store/db
```

## Architecture

- `cmd/server/main.go` builds a `pgxpool.Pool` and a `redis.Client` from `DATABASE_URL`/
  `REDIS_ADDR` (env, with localhost defaults matching `docker-compose.yml`), a JWT signing key from
  the required `JWT_SECRET` env var, and a `queue.Client` (TTL overridable via
  `WRITE_BACK_TTL_DAYS`, see "Open product decisions"), then hands the API deps to
  `internal/api.NewRouter` and starts both. Listens on `LISTEN_ADDR` (default `:8080`). No MQTT
  client is wired in yet.
- `internal/api` is the REST surface from design doc §4.4, deliberately deviating from that doc's
  draft routes in one way: every list endpoint requires a `device` query param, since CLAUDE.md's
  multi-device cloud table design means there's no well-defined "all devices" listing. `POST
  /api/login` issues an HS256 JWT (24h TTL, see `tokenTTL` in `internal/api/auth.go`) after a bcrypt
  password check against `users`; `GET /api/alarms`, `/api/oil-in-out`, `/api/shifts` all share the
  same `device`+`beginTime`+`endTime`+`limit`+`offset`+`sort` query-param shape and are behind the
  JWT `authMiddleware`; `GET /api/oilcan/latest` reads a `oilcan:latest:{device}` Redis key that
  nothing writes yet (that's `internal/mqtt`'s future job per §4.3's polling design) — it 404s until
  that ingestion exists, by design, not as a bug.
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
- `internal/store/db` is the sqlc output over the schema in `db/migrations/000001_init_schema.up.sql`
  — that migration already creates `users`, `devices`, `alarms`/`oil_in_out` (time-partitioned,
  partitions pre-created through 2026-09), `commit_works`, `oil_can_sets`, and
  `write_back_commands`, matching the device data model in the design doc §二 and the write-back
  queue shape in §5.2. `alarms`/`oil_in_out`/`commit_works` were checked field-for-field directly
  against the Android repo's `db/Alarm.java`/`OilInOut.java`/`CommitWork.java` (2026-08-20, not just
  the design doc's summary table) and match exactly. `oil_can_sets` did not — the design doc's §二
  table only listed 13 of `OilCanSet.java`'s ~26 real columns; the missing ones (`pk_d2dId`,
  `pk_tanZhenId`, `oilHeight`, `heightWaterAlarm`, `oilCorrection`/`waterCorrection`/
  `tempCorrection`, `density`/`densVar`, `alarmByte`, `gmt_create`/`gmt_modified`,
  `chuanGanQiHight`) have been added to the migration. `gmt_create`/`gmt_modified` are kept as raw
  nullable `TEXT`, not parsed to a timestamp, since their on-device string format isn't documented
  anywhere. Confirmed via `grep` that `OilCanSet` (not the unrelated `OilCanTable`/`OilTable`
  entities in the same package) is the live, actively-referenced entity.
- `internal/mqtt` is still unimplemented — blocked on Aliyun IoT credentials (see "MQTT contract").
  The next real architectural work once those exist is the subscribe/poll loop (design doc §4.1 jobs
  1–2), a real `queue.Publisher` implementation to replace `unbuiltMQTTPublisher`, and the
  sent→acked confirmation handler described above.
