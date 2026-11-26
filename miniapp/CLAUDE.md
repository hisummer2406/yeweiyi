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

Stations (cloud main 1d9d0be, yeweiyi-cloud#53): `/api/stations` returns all of the customer's stations (including empty ones) to `customer_admin`, and only the stations holding the user's bound devices to `customer_user`. A missing `station_id` on a device row means an old cloud; `customerId` on a station is not used.

## 小程序统一字号（2026-11-10）

- 字号定义在 `miniprogram/styles/typography.wxss`：标题/主要读数 36rpx、分组标题 32rpx、正文/表单/按钮 30rpx、标签/单位 28rpx、说明 26rpx、时间/徽标 24rpx。页面与组件按语义引用变量，不再逐页使用适老化放大表。
- `app.wxss` 引入统一字号；隔离的 `grid-picker`、`side-picker` 在自身 WXSS 中显式引入，由面板根节点提供变量。旧 `styles/tokens.wxss` 的字体别名也指向同一份定义。
- 首页沿用已确认的四罐排布，品牌 34rpx、状态 22rpx、短屏读数 32rpx；字号数值和首屏高度不因此调整。
- 长按钮文字允许换行并撑高，确认弹窗随内容增高。原生导航栏、tabBar 与系统弹窗的字号由微信控制。
- 验证包括类型/项目检查，以及由实际 WXML/WXSS 生成的浏览器多尺寸、多语言预览；不代表微信真机验收。

## Android App (Donut) and languages (2026-10-19, openspec change `donut-android-app`, issue #12)

The same code is built twice: as the WeChat mini-program and, through Donut (小程序多端框架), as an Android
app for users **without WeChat** (overseas). Requirements and decisions live in
`openspec/changes/donut-android-app/`; the cloud dependency (customer-side usernames, staff passwords,
first-login password change) is `yeweiyi-cloud#77` — until it lands, staff cannot sign in on the app.

- **One platform switch, one place**: `utils/platform.ts` `WECHAT_LOGIN`, true only in the mini-program
  build (`// #if MP`). The login page shows the WeChat tab/button only when it is true; the app shows the
  account + password form alone and never calls `wx.login` / `getPhoneNumber`. The conditional-compilation
  switch must be on in WeChat DevTools (详情 → 本地设置 → 启用条件编译). Everything else is shared code.
- **Languages: zh / en / es.** Texts live in `miniprogram/locales/{zh,en,es}.ts` (flat keys `group.name`,
  `{placeholder}` values). Pages read `texts('group')` into `data.text` and use `{{text.name}}`; code uses
  `t('group.name', params)`. Missing text falls back to English, then to the key. The language is the
  user's saved choice, else the phone's, else English (`utils/i18n.ts`, pure rules in `utils/i18nCore.ts`).
  The picker is its own page, `pages/language` (opened from a row on the settings page and a small link on the login page; each language in its own name, plus 「跟随系统」 which clears the saved choice); from settings it `reLaunch`es so
  every page is rebuilt in the new language. `app.json` and page `.json` titles cannot be translated: the
  tab bar and titles are set at runtime (`applyTabBar`, `setNavTitle`) and `app.json` carries English.
- **Pure modules return keys, not text** (they must stay `wx`-free for `npm run check`): `loginFlow.ts`,
  `pairing.ts`, `stationDevice.ts`, `oilInOut.ts`, `recordsRange.ts` hand back i18n keys / `{key, params}`
  and the page renders them with `t()`. `labels.ts` maps cloud enums to keys.
- **Cloud errors are never shown as the cloud wrote them**: `utils/errorKey.ts` (pure) picks the key from
  the cloud `code`, then the HTTP status; `utils/errors.ts` `describeError(err)` renders it. Device- or
  cloud-generated content (alarm `msg`, command `errorReason`) is data and is shown as is.
- `npm run check` now includes `scripts/i18n.check.mjs`: the three tables must have the same keys and
  placeholders, and every `group.name` string literal in the code must exist. Spanish is machine-translated
  and unreviewed (`openspec/changes/donut-android-app/i18n-glossary.md`).
- **Network (the cloud is in mainland China)**: requests time out after 20s; a failed refresh keeps the last
  data and shows "may be out of date, last updated …" (home cards, device-detail tabs); a first-load failure
  is an error with a retry, never an empty list; `AlarmSocket` reports `down`, `reconnectNow()` is called when
  the page comes back to the foreground or the network returns (`utils/network.ts`), and the page says live
  alarms are unavailable while it is down. While visible, the home page and the device-detail 实时数据 tab
  re-read `telemetry/latest` silently every 5 s (`LIVE_REFRESH_MS`, openspec `auto-refresh-live-readings`);
  a read still in flight is not repeated. Local drafts of the static settings pages are cleared at logout.
- Not verified on a device: nothing of this has run in DevTools, in the app, or without WeChat installed.

## What this project is

The WeChat mini-program (微信小程序) frontend for **需求B（PC 端 / 小程序远程数据监控）** of
安卓液位仪 (Android Liquid Level Meter, package `com.sansen.yeweiyi`) — the client the field/office
user opens on their phone to view oil-tank monitoring data and issue remote control commands for
devices that aren't on their local network. This repo is the frontend only. It consumes the
REST/WebSocket API exposed by `../cloud/` — it does not talk to ThingsBoard or the
device directly, and it does not implement any backend logic.

This project was split out of `yeweiyi-cloud` on 2026-08-20, matching the existing pattern where
each deployable/runtime unit in this project family gets its own repo (Android device app, cloud
backend, mini-program frontend are three separate repos coordinated by shared design docs, not
shared code).

## Sibling repos — read before designing anything

- `../cloud/` — the cloud backend this mini-program talks to. **Its `CLAUDE.md` is
  the authoritative source for the current REST/WS surface — read it, not just this summary, before
  wiring up an API call**, and re-check `types/api.d.ts` against its handler structs
  (`internal/api/*.go`) and protocol structs (`internal/protocol/*.go`) before trusting either if
  enough time has passed that the two could have drifted; there is no shared code or generated
  client between the two repos, so nothing catches a drift automatically except `npm run
  typecheck` failing on a call site once someone notices and fixes the type.
- `../android/` — the Android device-side repo. Not needed for wiring up API calls
  (that's `yeweiyi-cloud`'s job to mediate), but relevant background if a screen's behavior depends
  on device-side reality — e.g. why a physical control command is refused, or why 出入库 history can
  come back partial.
- `../admin/` — the desktop equivalent of this app: one Ant Design Pro project with
  an `/ops/*` section (platform_admin only) and a `/console/*` section that is meant to cover the
  same capability set this mini-program covers (device list, device detail/telemetry, issue-command,
  oil-in-out) for the same customer roles. If a capability is added here, it should end up on both
  clients — check that repo's `CLAUDE.md` when adding or renaming a screen so the two don't drift.

Every route below except `/api/login` requires `Authorization: Bearer {jwt}` (from `/api/login`'s
response). Every route that takes a `device` is scoped to devices *this user has bound* — an
unbound-or-nonexistent device answers the same 403/401 either way, by design (device-name
enumeration prevention), so don't treat those as necessarily "not yours," and don't build UI that
assumes a `device` string typed/pasted by the user will work without binding first.

- **Login is WeChat phone authorization by default** (yeweiyi-cloud#59, miniapp #4; contract
  `yeweiyi-cloud/docs/account-phone-wechat-contract.md`): the administrator provisions the account
  (phone + customer) in the web back office, and the user proves the number with
  `<button open-type="getPhoneNumber">`. The login page also offers
  账号密码登录 — the same single form as yeweiyi-admin (one 手机号 / 用户名 input + password,
  `POST /api/login`, contract §4.1; `utils/auth.ts` `passwordLogin`, `buildPasswordLogin` in
  `utils/loginFlow.ts` mirrors admin's `utils/loginIdentifier.ts`): a mainland number is sent as `phone`
  (customer_admin), anything else as `username` (platform_admin). **customer_user (staff) has no
  password** — the cloud answers them the same `401 invalid_credentials` as a wrong password. There is
  no SMS-code login (the cloud has none). `POST /api/auth/wechat/login` `{loginCode}` restores a
  session for a WeChat already bound (401 `wechat_not_bound` → show the phone button);
  `POST /api/auth/wechat/phone-login` `{loginCode, phoneCode}` binds on first use. The two codes are
  single-use — a **fresh `wx.login` code is fetched for every call**, never cached — and the app never
  sends a phone number or openid itself. Failures carry a `code` (`utils/loginFlow.ts` maps each to
  its own message: not provisioned / disabled / customer not onboarded (#5) / identity conflict /
  code invalid / WeChat unavailable). Login calls go out with `needAuth=false`, so no failure there can
  be taken for a dead session. The JWT's `sub` is the **user id**, not a username (customer-side
  accounts have none): read identity from the login response (`utils/storage` profile) and key local
  storage by `userKeyFromToken` ("u12"). `role` is still read from the token for gating. A role change
  does not take effect until the next login. After 退出登录 `autoLogin` is switched off so the login page
  does not silently restore the same WeChat; the next successful login switches it back on.
  **Not verified on a real device or against real WeChat** — see the PR.
- **Scan locates, it never grants** (yeweiyi-cloud#63, `docs/tank-grants-contract.md` v1.0). `POST
  /api/devices/resolve` `{code}` (the label text exactly as scanned) → `200 {deviceName}`: the actual
  device name, used for everything after. It writes nothing, so scanning twice changes nothing.
  Every "cannot locate" case (unknown, other customer, no granted tank, disabled) is the same **404**
  `code=device_not_found` — shown as "未找到该设备，或你暂无…请联系平台管理员（油罐授权仅平台管理员可分配，cloud#63 v1.1）"; it is not a dead session.
  `POST /api/devices/bind` is retired (always 410 `bind_removed`): **do not call it**, and nothing in
  the app may grant visibility. What a customer_user can see is decided by the cloud per
  `(device_name, tank)`: `GET /api/devices` lists only devices with a granted tank (rows carry
  `granted_tanks`), `telemetry/latest`, alarms, oil-in-out and `/ws/alarms` carry only granted tanks,
  and `telemetry/history` for another tank is `403 code=tank_not_authorized`. Three 403s stay distinct:
  `not authorized for this device` (device level), `tank_not_authorized` (tank level), bare
  `not authorized` (dead account — the only one that ends the session). customer_user is view-only:
  no 控制命令 tab. `utils/pairing.ts` only validates the text (`[0-9A-Za-z_-]`, 1–64; no padding, no
  prefix, no length assumption). `POST /api/devices/unbind` is a no-op.
  `GET /api/devices` → `{rows: [Device...]}` — call this to populate the device list;
  there is no other way to discover which `device_name`s a user may even query. **`is_online` on
  each row is real but lags** — `yeweiyi-cloud` `internal/ingest/online.go` re-syncs it every 30s
  from ThingsBoard's `active` flag, which only flips to inactive after ThingsBoard's inactivity
  timeout (default 10 minutes), so a device that just dropped still reads online for that long. This
  app does **not display it** (product decision, `openspec/config.yaml`); the reason is the lag, not
  that it is dead. See `types/api.d.ts`'s note on `Device`.
- **Read-only history**, `GET /api/alarms` and `GET /api/oil-in-out` — share one query-param shape:
  `device` (required) + `beginTime`/`endTime` (RFC3339, optional, default last 24h) + `limit`/`offset`
  (paginate, default 50/max 500 — `offset` is a plain non-negative row offset: both endpoints are
  served from the cloud's business database now (yeweiyi-cloud#29), not read through from
  ThingsBoard, which is why an `offset` used to be a 400. This app does not page yet: it asks for the
  first `limit: 50` records only) + `sort` (`true`=ascending, default `false`≈newest first passed explicitly by this app).
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
- **Scan to open a device**: `wx.scanCode()` → `POST /api/devices/resolve` → the device detail page,
  which shows only the tanks the cloud granted this user. A user with no grants sees "contact the
  customer administrator" — there is no scan-to-authorize (see above).
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
2. `npm run typecheck` — `tsc --noEmit` over `miniprogram/**/*.ts`. Run this after any `.ts` change.
   There is no test runner; the only other automated check is `npm run check` below.
   `npm run check` — Node checks for `utils/stations.ts`, `utils/recordsRange.ts`, `utils/loginFlow.ts`, `utils/stationDevice.ts`, `utils/pairing.ts`/`utils/roles.ts`/`utils/tankFill.ts`/`utils/tankPick.ts`/`utils/recordColumns.ts`/`utils/paramDraft.ts`/`utils/virtualTable.ts`/`utils/tankTable.ts` (+ `scripts/param-editor.check.mjs`, the real `components/param-editor` against a `wx` stand-in) and the language tables / rules (`scripts/i18n.check.mjs`) (needs Node 22.6+).
3. Open the repo root in WeChat DevTools (`project.config.json` sets `miniprogramRoot: miniprogram/`
   already). `appid` in `project.config.json` is `wx2f6409ecf18a918b` (set in commit `415250e`; it used
   to be the tourist placeholder `"touristappid"`). This repo cannot tell whether that is the AppID of
   the mini-program that will be published — confirm it in the WeChat admin console before uploading.
   `project.private.config.json` is gitignored and holds no appid.
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
  **403 `code=customer_not_onboarded`** (yeweiyi-cloud#68, `docs/customer-onboarding-contract.md` §3/§4,
  miniapp#5): the customer is still being configured. Not a dead account and not a wrong password: the
  token is dropped, the app returns to login with `?notice=customer_not_onboarded` (alongside a `scene`
  if one is active) and the login page shows "所在客户尚未完成开通，请联系平台管理员" without silently
  restoring. It carries no `X-Renewed-Token`. Whether a failed answer may end the session is decided by
  `sessionDecision` (`utils/loginFlow.ts`, Node-checked): an answer to a request sent with a token that
  is no longer the stored one (the user signed in again meanwhile) never clears the new session. The
  `/ws/alarms` handshake refusal exposes no status to `wx`, so the notice there relies on another HTTP
  request of the same page.
- `utils/ws.ts` — `AlarmSocket` wraps `wx.connectSocket` for `/ws/alarms` with reconnect-on-close.
- `utils/auth.ts` — `wechatRestore()` / `wechatPhoneLogin()`; `roleFromToken`/`userKeyFromToken`
  decode the JWT's `role`/`uid` claims client-side purely for display, UI gating and storage keys
  (real authorization is always server-side). `utils/loginFlow.ts` — the pure part (failure → message,
  getPhoneNumber result, phone masking), checked in Node by `npm run check`.
- `utils/pairing.ts` — validates the label text and words a failed resolve. Pure.
- `utils/labels.ts` — Chinese labels for the closed enums the cloud API returns (alarm type/severity/
  status, tank alarm state, stock direction) and the mirrored control-method table. One place so an
  unmapped value falls back to itself rather than rendering blank.
- `types/api.d.ts` — hand-copied from `yeweiyi-cloud`'s handler/protocol structs. Re-diff before
  trusting it if time has passed.
- `utils/telemetry.ts` — telemetry snapshot → tank view-model and the "never reported" (404) test,
  shared by the home page and `device-detail`. Pure, no `wx`.
- `utils/tankTable.ts` — 罐表设置「生成罐表」：照搬设备端 `factory/EllipseTable.java`（卧罐筒体+单侧封头、立罐圆柱、长方体，1 cm 一档，
  设备的取整与浮点细节一并照搬）。`scripts/fixtures/tank-table-golden.json` 由设备 Java 代码直接运行生成，check 逐点比对。
  字母：卧罐 H 直径/L 筒长/R 封头高度（可 0）；立罐 H 罐高/R（设备按直径算）；长方体 H 高/L 长/R 宽。
  「保存到控制器」（#49）：只下发罐型 + 尺寸（`config.setTankTable`，`tableArgs` 映射为 diameterMm/lengthMm/headMm/heightMm/widthMm），
  设备自行生成；对象 = 首页当前油站的设备 + 选择的可见罐，必填原因，走 `configSubmit` 同一提交保护（快照带 `method`），受理后进操作记录，
  成功后按 `tableEcho` 核对标定镜像（`GET .../tanks/:tank/calibration`，晚于 finishedAt，点数与末点等于回执）。仅管理员。
- `utils/virtualTable.ts` / `utils/virtualTableStore.ts` — 虚拟罐表（#56，openspec `virtual-tank-table`；**#44 显示倍率已下线**）：
  罐表设置生成后管理员「启用为虚拟罐表」，按 `virtual-table:<账号>:<设备>#<罐>` 存完整表在本机，只有 platform_admin / customer_admin 读写和应用。
  首页/详情用设备液位在完整表上查容积（表点取值、两点线性、0–首点按 (0,0) 线性；缺失/负数/高于末点不外推，四项回退设备原值并标出）；
  质量 = V虚 × (原质量 ÷ 原容积)，空容 = 末点 − V虚，水体积 = V虚(水高)；液位/水高/温度/占比/罐图不变（设备 oilVolumeL 是含水总体积）。
  罐表设置页「本机虚拟罐表」区块可查看/停用。旧 `display-scale:` 记录在首页清掉并提示一次。不上云、不下发，报警/记录/报表用设备值。
- `utils/scan.ts` — `wx.scanCode` → `POST /api/devices/resolve`, shared by the home page and
  `device-add`; hands the caller the actual device name. `utils/roles.ts` — `isReadOnlyRole`.
- `utils/sceneEntry.ts` — device mini-program code entry (miniapp#7): path `pages/device-detail/device-detail`,
  `scene` = device name as printed. `device-detail` resolves it with `resolveDevice` (`utils/scan.ts`); not
  signed in → `login?scene=` and login returns to `device-detail?scene=`. A failed resolve is a notice on the
  page, never a dead session. Pure, Node-checked (`scripts/scene-entry.check.mjs`).
- `utils/oilInOut.ts` — runtime guard for the `/api/oil-in-out` envelope and the "list is not
  complete" notes (unparsable / legacySkipped / partial / truncated / invalidSource). Pure.
- `utils/stations.ts` — station options, grouping, default selection and search, and `recordsContext`
  (which station and device the 我的记录 pages show: the home page's current station by the same rules,
  its one device, never a guess); pure functions, checked in Node by `npm run check`.
  `utils/stationPref.ts` — remembers the selected station; `loadRecordsContext` reads devices + stations
  and applies `recordsContext`.
- `utils/tankPick.ts` — single-tank detail (miniapp#15): visible tanks (snapshot ∩ `granted_tanks`) and
  which one to select (the tapped one, else the lowest; a hidden one is reported). Pure, Node-checked.
- `utils/recordColumns.ts` — the five record tables (miniapp#17): column order as on the device display,
  units, cell values (missing → "—", never 0 or derived), row keys, 介质 by `oilTypeIndex` (1–17 → `oil.N`
  in the app language, from the Chinese names — the device's English list is misaligned, zhongchengyeweiyi#52;
  else the device's `oilType` text), and `syncSummary` for 交班/库存 (cloud#85: `UNSUPPORTED` → "设备暂不支持",
  `SYNCING` → "同步中", only `SYNCED` + empty may say "no records", `unparsable` > 0 → "may be incomplete").
  Pure, Node-checked.
- `utils/recordFeed.ts` — one paged alarm or stock list for one device/window/filter, shared by
  device-detail and `record-list`: sends `tank`/`direction` to the cloud (#84) and filters the rows again
  for an older cloud, seqs that drop late answers, bounded auto-continue on filtered lists, the 502
  envelope, stale/first-load error/footer states.
- `utils/stationDevice.ts` — station → current device from `GET /api/devices` `station_id` (cloud
  contract `docs/station-device-replacement-contract.md` v1.3: no read-by-station endpoint; one device
  per station; data never merged by station/tank number), `tankKey` (device_name + tank = data/cache
  identity), and the follow decision `device-detail` uses after a replacement (A→B: drop A's data,
  socket, poll and in-flight requests, load B; B not visible to this user → notice, no fallback to A). `commandGuard` is the last check before `issue-command` sends: it re-reads `GET /api/devices` and
  blocks (never redirects) unless the device is still the station's current one; a failed read blocks too.
  Pure, Node-checked (contract v1.3).
- `utils/recordsRange.ts` — the time range, paging and in-app filter rules of the record lists:
  presets (最近24小时/近7天/近30天, rolling from "now") and custom whole local days → `beginTime`/
  `endTime`; page queries (alarms ask for `limit+1` as a "more" probe because `/api/alarms` has no flag,
  stock uses `truncated`); the offset steps by the page size, not by rows shown. Pure, Node-checked.
- `pages/` — three tabBar tabs plus pushed pages:
  - **首页 `devices`** (tabBar) — tank cards grouped by device: `GET /api/devices`, then one
    `telemetry/latest` per device in parallel, each device succeeding/failing independently. When
    the cloud has station data the top-left shows the current station name with a dropdown
    (`pages/station-select`) and the page lists only that station's devices; without station data
    (old cloud, `/api/stations` failing, no device belongs to any station) there is no dropdown and
    the header says "我的设备". The selected station is remembered per account (user id key) in `wx.storage`
    (`utils/stationPref.ts`). "+" menu: 扫码查看设备 / 油站管理 (placeholder). **No 邀请加入**: accounts are created
    in the back office (by the platform administrator: phone + customer); there is no registration or
    invite in the mini-program.
    The tank illustration carries a level fill (oil on top of water, `tankFill` in `utils/tankFill.ts`,
    from `oilRatioPct`/`waterRatioPct`; oil in the device-reported tank colour `oilColor` from `tank-settings`, default orange when unreported; red on ALARM, grey on FAULT, nothing without a probe). The
    fill rectangle was measured against `tank-horizontal-home.png`, not verified in DevTools.
    首页按设计图比例排布（2026-11-10）：读数与左侧标签同字号（#56，常规 30rpx、短屏 28rpx，单行不换行）；#59 起标签—数值—单位三列：单位列定宽 56rpx（短屏 48rpx）、单位文字左对齐，数值右对齐紧靠单位并加粗 600，单位 28rpx（短屏 24rpx）。`measureHomeLayout` 实测头部和第一组上报时间，从 `wx.getWindowInfo().windowHeight` 扣除后四等分作为每罐最小高度；原生 tabBar / 安全区不重复扣。数据到达和窗口变化时重算，滚动不影响结果。保留所有罐，第五罐起自然下滑查看；长介质名和异常文字可撑开、不裁切。浏览器已验证实际 WXML/WXSS 和高度计算方法，未作微信真机验收。
    Tank type comes from `tank-settings`; confirmed types select horizontal/vertical images. Before confirmation, keep a generic illustration with an explicit label; do not replace the tank with a question mark.
  - **我的记录 `records`** (tabBar) — the home page's current station (name on top) and five entries
    (报警/出库/入库/交班/库存), each opening `record-list?type=`. **No device picker**: no valid station
    (old cloud, ungrouped, none) → go choose one on the home page; empty or multi-device station → says
    so. `record-list` re-decides station and device on every show (a home station switch or a device
    replacement drops the old list), offers 全部罐 + the visible tanks (`GET /api/devices/:device/tanks`),
    the same time range, and a table with the tank column fixed and the rest scrolling sideways. 交班/库存
    read `GET /api/shift-records` / `GET /api/stock-records` (cloud#85, `docs/shift-stock-records-contract.md`
    §4/§5; one row per record × tank, rows of one shift/snapshot share a shade; an open shift has dashes for
    its handover time and end figures); a cloud without those routes (bare 404) shows "not open yet". `record-list?station=` is only used by a verified old link and
    then pins that station.
  - **设置 `more`** (tabBar) — entries to the pages below, plus `profile` (account, role, logout).
  - `login`, `station-select` (station list with search, tap to pick and return), `device-add` (扫码查看设备), `device-detail` (**one tank at a time**: opened with `tank=` from
    the home tank row, else the lowest visible tank; no in-page tank switcher (user 2026-11-14: the tapped tank is the one shown); shows every reading of the
    selected tank as a grid, no tank picture; tabs 实时数据 / 参数修正 / 储罐设置 (#43: the two editor tabs act on the
    tank shown in the info bar 油站 · 设备 · 罐, see "Remote configuration"; 控制命令 entry closed), plus the live-alarm banner from `AlarmSocket` for that tank only. **No alarm or stock
    record tab**: records have one entry only, 我的记录 (2026-10-26); polls the command list every 3s while any command is non-terminal;
    **no 出入库 tab** — an old `tab=oilInOut` link moves to `record-list` when its station is verified,
    otherwise stays and says where the records went), `issue-command` (操作记录, see "Remote configuration" below).
    The record lists have a time range bar and page by scrolling to the bottom
    (`onReachBottom`). Without it the cloud's default window is the last 24h and the app used to show
    only the newest 50 of that, i.e. nothing older was reachable. The window never starts before the
    device was assigned to the caller's customer, so an empty older range says so. Tank and 入库/出库
    direction are sent to the cloud (`tank` / `direction`, filtered before paging: yeweiyi-cloud
    `docs/records-filter-contract.md`, #84 — on cloud branch `feat/records-tank-filter-84`, not yet on main);
    the rows are filtered again for an older cloud that ignores them, so filtered lists still fetch bigger
    pages and keep going (bounded) until enough rows were added; only the cloud's end ends them. Times are shown in the phone's local zone (`formatLocalTime`),
    not as the cloud's raw UTC strings. Page behaviour is **not verified in DevTools**.

## 客户品牌 Logo（#86，云端契约 `cloud/docs/brand-logo-contract.md`）

首页顶部标题左侧（`brandMark`，含无设备空状态）、设置页账号行与账号详情页头像（`brandAvatar`）显示登录账号所属客户的 Logo；
登录页、未配置、请求失败、图片加载失败（`binderror`）显示默认图（`brand-mark.png` / `avatar-logo.png`），首页标题文案不变。
`GET /api/me/brand-logo` 返回 base64，写到 `USER_DATA_PATH` 下按「账号 + 版本」命名的文件（写不了时用 data URL），缓存项 `brand-logo`
记账号、版本、路径，下次带 `?known=<版本>`。首页每次显示/下拉刷新都向云端核对（不阻塞、不影响读数），设置页/账号页每次运行核对一次。
晚到的响应按账号丢弃（`brandLogoStep`，`utils/brandLogoCore.ts`，`scripts/brand-logo.check.mjs`）；断网时保留同一账号上次确认的 Logo；
`clearToken` 删除缓存项和文件。
**开发者工具编译坑（2026-11-27 实测）**：小程序的 `.ts` 里不要写再导出（`export { … } from`）。开发者工具编译它时不报错，却继续运行引用它的页面的旧 JS，
WXML 是新的，表现为绑定的字段不存在（这次是 Logo 消失）；常量从定义它的模块直接引入。走现有业务请求域名，不需要配置 downloadFile 域名。**未在开发者工具、真机或 Donut 安装包里验证。**

## Remote configuration and 补录 (miniapp#6, 2026-11-08; openspec `tank-settings-remote-apply`)

Cloud contracts: `yeweiyi-cloud docs/config-command-contract.md` (cloud PR #13: `requestId`, `expectedStationId`,
single-command lookup; the per-device gate was removed 2026-11-09) and `docs/stock-manual-entry-contract.md` (#69). Scope: configuration only,
**density read-only**; no action commands. Pure rules in `utils/remoteConfig.ts` (Node-checked,
`scripts/remote-config.check.mjs`), requests in `utils/remoteConfigApi.ts`, the send flow in `utils/configSubmit.ts`.

- **One editor, `components/param-editor` (Issue #43, openspec `tank-detail-param-tabs`)**, used by the 参数修正 / 储罐设置
  tabs of `device-detail` and by the old 设置 entries `calibration` / `tank-config` (now thin pages, `utils/paramPage.ts`:
  home station → device → `?tank=` or the only tank; several tanks and none given → asks, never guesses). The editor is
  given device + tank + the device row's `station_id` (-2 = not known yet → cannot send) and:
  fills the form with the device's last report (`tank-settings` mirror, its `reportedAt` shown, missing = 未上报, never 0);
  an edit is a value different from the report (`utils/paramDraft.ts`), marked 原值 → 新值; edits auto-save as a draft keyed
  `param-draft:<kind>:<account>:<device>#<tank>` with the report they were made against, restored with a notice and
  「放弃修改」; a new report never overwrites input (untouched fields follow it, a moved edit is flagged for review, an
  edit the device now reports disappears). 「下发修改」 sends only changed, valid fields (`buildArgs`); the tank switch of the 设置 entries asks
  keep draft / discard / cancel (`utils/paramSwitch.ts`); 罐详情 has no tank switch. `customer_user` sees the report read-only, no draft, no send.
  Old drafts (`calibration-draft`, `tankcfg-draft:*`) are no longer read.
- Fields: corrections (no range limit, #28; ± toggle, #30, `toggleSign`; every number input is `type=digit` and a signed field keeps its minus outside the input, shown beside it, `splitSigned`/`joinSigned`, #56); 储罐设置 edits `TANK_SEND_FIELDS` (4 level alarms,
  high water, high temp, capacity, density in g/cm³ 0<v≤2 — a report >2 is flagged as probably kg/m³); height (1–100000 mm) and
  low temp (−50–100 ℃, 0 = off) are editable only when `media-options.tankHeightLowTempSync` is true (#65; else read-only with an
  upgrade note, `sendableTankFields`), checked before sending against thresholds / high temp (`overHeight`, `tempOrder`); low water is gone (no device field). **Medium and colour (2026-11-14, user rule: every change is for administrators,
  employees read-only)**: administrators pick the medium from `GET /media-options` (sent as `mediaId`, the cloud converts) and a
  colour from the 20-colour palette (`utils/oilColor.ts`, checked against the cloud's shared fixture), `buildMediumArgs`; options
  unreadable → medium read-only; `oilColorSync` false (old firmware) → colour read-only. Choices are not kept in the draft. A
  succeeded command with `resultDetail.ignored` says which fields the device did not apply (`ignoredFields`). No per-device opening since 2026-11-09; the cloud's deployment
  switch answers `config_commands_disabled` when off.
- Send flow: the reason is a form row (no `showModal` `editable`); the confirm dialog lists before → after; then
  `commandGuard` re-reads `GET /api/devices` (station's device changed → stop); one `requestId` per confirmation,
  `expectedStationId` always sent (null allowed). Timeout/`command_delivery_unconfirmed`: look up `?requestId=`; not
  found → only 「用同一编号重试」, never a new id. Accepted: with `stay` (the editor) the command is returned and its
  progress shown in place — polled by `terminal`, succeeded → fresh echo (`echoState`), rejected/failed → the device's
  reason (#42), expired → 结果无法确认; bound to its device + tank, stopped on hide/detach; 「查看操作记录」 stays. Without
  `stay` it opens `issue-command?device=&commandId=&tank=`.
  **The confirmed snapshot is kept on the phone** (`cfg-pending:<userKey>`, per device + tank, written before the
  request goes out; issue #18 F2) until the cloud answers anything but "unconfirmed". The next send to that tank
  first looks it up again, else offers only a retry of the *kept* content with its id (no client station guard
  on that retry: the cloud's replay lookup precedes its `expectedStationId` check); the new form waits. Not
  cleared at logout (the key carries the account). One send flow at a time (module lock in `confirmAndSend`, #18 F4):
  a second tap while the confirm dialog / send / lookup / same-id retry is open is ignored.
- Sliding renewal is adopted only when the request's token is still the stored one (`renewedTokenToAdopt`,
  `loginFlow.ts`; #18 F5): a late answer after logout or an account switch never overwrites the new session.
- `scripts/config-flow.check.mjs` runs the real `request.ts` / `configSubmit.ts` against an in-memory `wx`
  stand-in (F5/F2 flows); `scripts/param-editor.check.mjs` does the same for the editor. Neither is DevTools or device acceptance.
- `issue-command` is now **操作记录** (the raw-JSON command form is gone): tracks one command by `terminal`, then reads
  the mirror back (`reportedAt` strictly later than the command's `finishedAt` and equal fields = 已一致; no valid `finishedAt` = still waiting, #18 F3), lists the device's last 20 commands; polling
  stops in `onHide`/`onUnload`. Entry: 设置 → 操作记录 (administrators only).
- `backfill` posts `POST /api/devices/:device/stock-events` (administrators): date + time (phone offset → RFC 3339,
  not in the future), level, volume, reason, one `requestId` per confirmation (a retry reuses it; the cloud replays).
- **Not verified in DevTools, in the app, or against a real cloud/device.**

## 罐型同步展示（Issue #40，2026-11-10）

首页与详情独立读取现有 `tank-settings`，按设备及实际罐号合并；只有 tankShape=1 显示立罐，未上报、未知值或读取失败一律按卧罐，不出现「罐型未知」（用户 2026-11-10 决定）；首页罐卡不显示罐型文字，只换罐图。详情显示罐型及其上报时间。立罐用设备端 `min_oilcan.png` 的完整副本，通过 CSS 视口去掉透明边，液位窗口另行适配。保留四罐首屏布局和字号。配置每分钟复核，隐藏停止，恢复／手动刷新重读；临时失败沿用旧值，401/403/404 清除罐型缓存，迟到响应按账号、设备及页面代次丢弃。纯逻辑及真实页面加载方法的检查在 `scripts/tank-shape.check.mjs`，不等于微信／Donut／现场设备验收。规格和待验收项见 `openspec/changes/sync-reported-tank-shape/`。 2026-11-10 已在微信开发者工具真实模拟器核对测试站点首页卧罐展示；若热重载后新方法未加载，应清理编译缓存后重新编译，不能把浏览器预览当作目标端验证。

## Static design-draft pages (not wired to the cloud — on purpose)

**罐表设置 is no longer static**: `tank-shape-edit` generates and sends tables (#49, see `utils/tankTable.ts`), and
`tank-shape` (#41) shows 设备当前罐表 below the shape cards — the home station's device, its visible tanks (`/tanks`,
granted only for employees), one tank's calibration mirror (`GET .../tanks/:tank/calibration`, `deviceTableView`:
finite pairs only, sampled like the preview, `reportedAt`); 404 `calibration_not_reported` or no valid point →
「设备尚未上报该罐罐表」. Read-only for every role. Not verified in DevTools.

`record-settings`, `report-settings` (and originally `tank-shape`) are laid out from the design draft (checked against the exported artboard PNGs by
measuring pixels, see `openspec/changes/home-by-station/tasks.md` 3.8–3.10) and say "暂未开放 /
云端没有对应接口" when saved. Shared pieces: form/button/switch styles in `app.wxss`,
`components/grid-picker` (3-column tank-number sheet) and `components/side-picker` (type dropdown,
12-colour palette). Do not make them send requests until the cloud side exists:

- `config.setTank` / `config.setProbe` / `config.setAlarmThresholds` have method names but no
  argument schema (`yeweiyi-cloud/docs/miniprogram-design-gaps.md` §1); remote configuration uses
  `config.setTankSettings` only (see above).
- Tank-table push, record clearing/generation switches, report export have no cloud method or endpoint
  (and no device field to carry them).
- Who may configure: cloud#44's latest decision — no station-administrator role; platform and customer
  administrators may send tank settings, employees are read-only.

## Spec workflow

Requirements for new work live in `openspec/` (initialised 2026-10-07, schema `spec-driven`, zh-CN).
`openspec/config.yaml` carries the project context and rules that every proposal must follow
(the cloud owns the contract, specs do not copy cloud fields, the do-not-build list). Active change:
`openspec/changes/home-by-station/` (home page by station, with its dependency on the cloud and the
filed issues in `cloud-issues.md`), `openspec/changes/records-by-station-and-single-tank-detail/` (miniapp
#15–#17: single-tank detail, 我的记录 by the home station, record tables; cloud#84 on a cloud branch, #85 merged — cloud main 0335ce3). Use `/opsx:propose`, `/opsx:apply`, `/opsx:archive`.

## What's explicitly NOT this project's problem

- ThingsBoard, MQTT, control-command state machine internals, database schema — all
  `yeweiyi-cloud`'s responsibility. This repo only calls its REST/WebSocket API.
- LAN-only PC monitoring (需求A) and the PC remote client — separate concern, not this repo.
- Anything about the Android device app itself — `zhongchengyeweiyi`.
- 出入库/交班 record correction, and tank/probe config write — no working design exists yet on
  either side (see "Removed from yeweiyi-cloud" above); do not invent one here.
