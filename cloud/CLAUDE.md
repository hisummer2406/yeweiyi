# CLAUDE.md / AGENTS.md

> **`AGENTS.md` is a symlink to this file.** Codex, Claude Code, Cursor and anything else that reads
> one of those two names gets exactly this content, so there is a single file to maintain and no way
> for the two to drift apart. Edit `CLAUDE.md`; never replace `AGENTS.md` with a real file.
> (Git stores it as a symlink, mode `120000`. A Windows checkout without `core.symlinks=true` would
> materialise it as a 9-byte text file containing the string `CLAUDE.md` — harmless here, since dev
> is macOS and deployment is Linux, but that is what you are looking at if it ever appears empty.)
>
> **This file is the operating guide, and it describes only the present.** Architecture, commands,
> layout, API surface, invariants, conventions. Keep it short and keep it true: when a fact here
> goes stale, **fix the line** rather than appending a correction under it.
>
> **The reasoning and the history live in [`docs/engineering-journal.md`](docs/engineering-journal.md)**
> — ~2,000 lines, split out of this file on 2026-09-04. Go there for *why* a decision was made,
> which roads were already walked down and walked back, and what a past pass actually verified as
> opposed to assumed. It describes superseded architectures in the present tense, so **where the two
> disagree about present-day fact, this file wins.** Record substantial new work there, not here.

---

## Architecture in one line

**Devices connect only to ThingsBoard CE.** There is no EMQX, no second MQTT stack, no Alink/Paho
compatibility layer, and no local copy of telemetry. The legacy Alink stack was **deleted in full**
on 2026-09-02 (issue #10): `internal/mqtt`, `cmd/devicesim`, `internal/queue` (river),
`internal/partitions`, the mosquitto service and the five legacy tables are all gone.

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

Scale is **~100 devices today, under 1,000 within 3–5 years** — not the ~10,000 the journal's older
sections assume. Sibling repositories (do not edit from here; protocol changes require agreement
with their owners):

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
#   device MQTT  tcp://localhost:1884   (plaintext; TLS is docker-compose.thingsboard-tls.yml)

go build ./... && go vet ./...

migrate -database "postgres://yeweiyi:yeweiyi@localhost:5432/yeweiyi?sslmode=disable" \
        -path db/migrations up

# a simulated device speaking 设备协议 V1
go run ./cmd/tbdevicesim provision -name tank-farm-01
go run ./cmd/tbdevicesim run -token <token> -tanks 2 -interval 10s -alarm-after 15s

# the backend. There is no broker env var of any kind — ThingsBoard is the only device door.
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
go test ./internal/config/     # needs NO containers
go test ./internal/tbclient/   # needs ThingsBoard
go test ./internal/control/    # needs Postgres
go test ./internal/api/        # needs Postgres + Redis (+ ThingsBoard for some)
go test ./internal/ingest/     # needs ThingsBoard + Postgres + Redis
```

Tests **skip with an explanatory message** when their infrastructure is absent rather than failing.
If you see a skip, start the container it names — do not assume the test is broken.

**Use `go test ./... -p 1`.** Three packages (`internal/api`, `internal/tbclient`,
`internal/ingest`) each drive the same ThingsBoard instance, and on a development machine the load
generator and the server share the same CPU. Run in parallel they occasionally miss a 25-second
timeout — most often `TestFullLoop_CommandReachesTerminalStateViaIngest`. Serialised, the suite is
green; each package alone is green; ThingsBoard itself stays healthy throughout (its queues report
`totalFailed [0]`), so this is contention, not a defect in the system under test. Do not "fix" it by
raising the timeout — 25 s for a 2 s operation is already generous, and stretching it hides real
slowness.

`cmd/smoke` is the deployment check, not a `go test`: it ships as a static binary because an
air-gapped install site has no Go toolchain. Run it with `-api` so it exercises this backend rather
than only ThingsBoard.

---

## Layout

```
internal/protocol/    设备协议 V1 — the single source of truth for the wire contract
internal/tbclient/    the ONLY door into ThingsBoard (public REST/WS; never its database)
internal/control/     control command state machine, authorization, audit
internal/ingest/      per-device ThingsBoard telemetry subscription → business layer
internal/api/         REST/WebSocket surface; device data is read through to ThingsBoard on demand
internal/config/      defaults → config.yaml → environment
internal/devicesim/   device-side reference implementation, driven in-process by tests
internal/store/db/    sqlc OUTPUT — generated, never hand-edit
cmd/server/           the single binary
cmd/tbdevicesim/      simulated device CLI (provision / run)
cmd/smoke/            deployment smoke test; self-cleaning, safe against a live installation
cmd/tbconfig/         versions ThingsBoard's own config (device profiles) as repo files
cmd/loadtest/         capacity harness; results and limits in docs/capacity-test.md
db/migrations/        golang-migrate schema (000001…000012); also sqlc's schema input
db/queries/           sqlc query input
deploy/               offline delivery: digest-pinned images, bundle/load, preflight,
                      backup/restore, TLS certs, resource profiles, README = the install manual
fixtures/protocol/v1/ cross-repo protocol fixtures, shared with the Android repo
docs/                 protocol spec + reconciliation, TB baseline, capacity report, journal
```

---

## API surface

Everything under `/api` except `/api/login` requires the JWT from `POST /api/login`; every
device-scoped route additionally passes through `userMayAccessDevice` (`internal/api/devices.go`),
which is the single definition of "may this caller touch this device" — `platform_admin` reaches
every device, everyone else needs a `user_devices` binding. `/ws/alarms` authenticates its own
token because a WebSocket handshake cannot carry the header; it calls the same function, and
**must keep doing so** — those two paths have drifted apart once already.

| route | notes |
|---|---|
| `POST /api/login` | HS256 JWT, 24h, carries `uid` and `role` |
| `GET /api/devices`, `POST /api/devices/{bind,unbind}` | bind requires `deviceName` **and** `pairingToken` |
| `POST /api/devices/:device/pairing-secret` | `platform_admin` only; mints + rotates, returns plaintext once |
| `GET /api/devices/:device/telemetry/latest` | read through to ThingsBoard; tank count comes from the device's own last report, never a query param |
| `POST /api/devices/:device/commands` | **202, never 200** — reports only how far the command got synchronously; carries a server-computed `terminal` flag |
| `GET /api/devices/:device/commands` | poll here for the asynchronous outcome |
| `GET /api/alarms`, `GET /api/oil-in-out` | read through to ThingsBoard, not from any local table. `offset` is a 400: ThingsBoard pages by time window |
| `GET /api/audit` | default window 24h, like the list endpoints |
| `GET /ws/alarms?device=…&token=…` | fan-out off the `alarms:live` Redis channel |
| `GET /healthz` | no auth |

Two routes were **deleted rather than migrated** and must not come back without the uplink to
justify them: `/api/oilcan/latest` (superseded by `telemetry/latest`) and `/api/shifts` (设备协议 V1
has no 交班 uplink on either side, so it could only ever answer an empty list; `TestShiftsEndpointIsGone`
pins this). The interlock gate answers **409, not 403** — the caller may well be entitled to the
method; it is the deployment refusing until device-side interlocks are verified.

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
   (`WHERE status IN (...) AND device_name = ...`), so a late, duplicated or cross-device message
   cannot rewrite a terminal row. Do not move these checks into Go. `pending` is deliberately an
   accepted source state: a fast device can report its result before our own ACK bookkeeping lands.
5. **Telemetry is never copied into the business database.** It lives in ThingsBoard and is read
   back on demand. `TestIngest_DoesNotPersistTelemetry` fails if this is violated. Only commands,
   audit and business records cross the boundary.
6. **Authorization is checked before existence**, everywhere — and identically, in both body *and*
   timing. An unauthorized caller must get the same answer whether the device belongs to someone
   else or does not exist, or error codes become a device-name oracle. See
   `internal/api/devices.go` and `control.Service.authorize`.
7. **Device pairing uses this backend's own minted secret**, stored bcrypt-hashed in
   `device_pairing_secrets` — **never** ThingsBoard's device access token, which is also the
   device's MQTT identity, so leaking it would cost the device's whole on-wire identity rather than
   one binding. Secrets rotate rather than being single-use: `user_devices` is many-to-many by
   product decision, so several staff must be able to redeem the same physical QR code over time.
8. **Never touch ThingsBoard's own database.** Public REST/WebSocket APIs only, so their upgrades
   cannot break us through a schema change.
9. **CE-only, offline-capable.** No ThingsBoard PE feature, no ThingsBoard Cloud, no online license
   check, no reliance on outbound internet — the private deployment is fully air-gapped.

---

## Conventions

**Go.** Standard `gofmt`. `gin` for REST, `gorilla/websocket`, `pgx/v5` + `sqlc`, `golang-migrate`,
`paho.mqtt.golang` (device side only, in `internal/devicesim`), `golang-jwt/jwt` + bcrypt. Prefer
the stdlib over a new dependency for something small (the UUID generator in `internal/control` is
hand-rolled for exactly this reason).

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
is actually caught instead of the code agreeing with itself. Prefer a counted seam over a wall-clock
assertion when testing timing (see `internal/api/pairing_timing_test.go`), and never loop
`ReadMessage` across more than one deadline on a gorilla/websocket connection — the first error it
returns is permanent and cached.

**Verify against the real thing before writing conclusions.** This project has been burned by
research-derived assumptions twice: Aliyun IoT Platform's ACL model, and its purchase availability.
Several ThingsBoard behaviours documented here were found by running it and contradicted
expectations — telemetry values come back **stringified** from REST and WebSocket while attribute
values do **not**; a nested-JSON telemetry key aggregates to a silent `0`, not an error. If you
write "X works", have run it.

**Commit messages are in Chinese**, subject line ≤ ~50 chars, body explaining the *why* and calling
out anything non-obvious or deliberately not done. Look at `git log` for the register. End with:

```
Claude-Session: <session url>     # or your agent's equivalent; omit if not applicable
```

Commit only when asked. Work on a feature branch; the default branch is `main`.

**Documentation.** Record substantial work in `docs/engineering-journal.md` under "Build" (it is the
project's long-term memory — additive, superseded decisions marked rather than deleted), update
`docs/` when architecture or protocol changes, and correct *this* file whenever the layout, the API
surface or the invariants move.

---

## Do not assume these are decided

- **设备协议 V1 is a draft, not frozen — and the Android side independently implemented an
  INCOMPATIBLE version of it on the same day.** Both repos built "ThingsBoard single-stack protocol
  V1" in parallel, each noting the other's fixtures did not exist yet. As things stand a device's
  telemetry does not parse on the cloud side at all: flat `tank1.oilLevelMm` keys here versus a
  nested `tanks` array there, `alarm` versus `alarmEvent`, and a dozen renamed fields.
  **Read `docs/protocol-v1-reconciliation.md` before touching anything protocol-shaped**, and do not
  add new fields to either side until the structure question is settled — renaming is cheap now and
  gets expensive fast. The topics, the RPC envelope and the safety model did converge independently,
  and the flat-key question is settled on evidence (§A of that document), not preference.
- `docs/protocol-v1.md` §12 lists ten further open items requiring the Android owner or product.
  Most consequential: the **units** (mm/L/kg/°C) are industry-convention guesses never checked
  against the device's real register scaling, and `config.setTank`/`config.setProbe` argument shapes
  are undefined on purpose because the device has no handler for them yet. **Do not freeze the
  protocol or start device-side migration** without that sign-off.
- **`/api/oil-in-out` depends on an unmerged device-side change** (zhongchengyeweiyi#39): the device
  already publishes `stockEvent` on every movement, but as `DetailInoutRecord.toString()`, a Java
  debug string rather than JSON, so real devices produce records the endpoint skips. It degrades to
  an empty list, not a 500.
- **交班 (shift handover) is out of scope** unless product says otherwise: V1 has no uplink for it on
  either side.
- **Multi-tenancy is not built.** There are no customer entities and no tenant-scoped query
  isolation. `users.role` (`platform_admin`/`customer_admin`/`customer_user`) plus the
  `user_devices` binding is the entire authorization model today.
- **Record correction has no replacement yet.** The old write-back endpoints
  (`POST /api/oil-in-out/correct` etc.) went with the river queue. If correcting a stored record
  remains a product requirement it needs redesigning on top of `internal/control`, not restoring.
- **The QR code's content format is not designed here.** Binding needs `deviceName` and a minted
  pairing secret; how they are encoded on the physical label (bare strings? a deeplink? one value or
  two?) and who calls the minting endpoint at install time belong to whoever designs that label.
- **Not verified:** OTA delivery (the device-side OTA client does not exist at all), mutual TLS
  (device identity is still the ThingsBoard access token; certificate renewal is manual with no
  expiry alerting), ThingsBoard-side tenant isolation, and capacity beyond **200 devices** — 1,000
  and 10,000 are arithmetic, not measurements (`docs/capacity-test.md`).
- **There is no CI.** Every check in this file is something a human or an agent runs locally.
- **river's own tables remain** (`river_job`/`river_leader`/`river_queue`/`river_notification`,
  migrations 000002–000007) even though river itself is gone from `go.mod`. Dropping them from a
  later migration while those six files still claim to create them makes the history inconsistent
  both ways; retiring the six files together is a schema-history change worth doing on its own.
