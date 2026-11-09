// Field names/casing here are copied verbatim from yeweiyi-cloud's handler structs
// (internal/api/*.go) and internal/protocol structs, current as of the yeweiyi-cloud commit named
// in that repo's PR #12. This repo has no shared code with the backend, so this file is the one
// place a wire-shape drift gets caught (`npm run typecheck` fails on a missing/renamed field used
// somewhere) instead of failing silently on-device. Re-diff against yeweiyi-cloud before trusting
// it if enough time has passed that the two could have drifted.
//
// Rewritten wholesale for the ThingsBoard single-stack backend (yeweiyi-cloud issue #10 / PR #11).
// The previous version of this file described an API that no longer exists on the cloud side at
// all: MQTT-derived Postgres tables, the async write-back queue, `/api/oilcan/latest`,
// `/api/shifts`. See yeweiyi-cloud's CLAUDE.md "API surface" for the current, authoritative list.

// ---- Auth ----

// The user object of the WeChat login responses (yeweiyi-cloud docs/account-phone-wechat-contract.md
// §3.1). A customer-side account has no username: `phone` identifies it, and the user sees their own
// number in full. The JWT's `sub` is the user id, NOT a username — read identity from here.
export interface SessionUser {
  id: number;
  role: 'platform_admin' | 'customer_admin' | 'customer_user';
  customerId: number | null;
  username: string | null;
  phone: string | null;
  disabled: boolean;
}

export interface LoginResponse {
  token: string;
  user: SessionUser;
}

// ---- Devices ----

export interface Device {
  device_name: string;
  // is_online is part of the wire response but is deliberately NOT surfaced anywhere in this app's
  // UI. It is not dead: yeweiyi-cloud internal/ingest/online.go re-syncs it every 30s from
  // ThingsBoard's `active` flag. But `active` only turns false after ThingsBoard's inactivity timeout
  // (default 10 minutes), so a device that just dropped keeps reading online for that long, and
  // last_online_at / last_offline_at have the same granularity. Showing it would mean showing
  // "online" for a device that is already gone; if a status badge is ever wanted, label it with that
  // lag or take connectivity from the telemetry timestamp instead.
  is_online: boolean;
  last_online_at: string | null;
  last_offline_at: string | null;
  updated_at: string;
  // GET /api/devices rows carry the station the device sits in (yeweiyi-cloud#53). null = not in any
  // station ("未分组"); a MISSING key means an older cloud. Both fall back to "no station" here.
  station_id?: number | null;
  // customer_user rows: the tank numbers granted to this user on the device (yeweiyi-cloud#63). The
  // server already filters every read to these tanks; this is informational. null/missing for admins.
  granted_tanks?: number[] | null;
}

// GET /api/devices/:device/tanks (yeweiyi-cloud internal/api/tankgrants.go listDeviceTanksHandler,
// checked against main c34316b): the tanks the caller may see on the device, taken from the device's
// latest snapshot and already filtered to the grants for a customer_user. A device that never reported
// answers 200 with reported=false and no tanks.
export interface DeviceTanksResponse {
  deviceName: string;
  reported: boolean;
  tanks: { index: number }[];
}

// POST /api/devices/resolve (yeweiyi-cloud tank-grants-contract v1.0 §3). Locates a device from the
// scanned label text; writes nothing — no binding, no grant. `code` is the label text as scanned.
// POST /api/devices/bind is retired (always 410 bind_removed) and must not be called.
export interface ResolveDeviceRequest {
  code: string;
}

// `deviceName` is the RESOLVED actual device name — use it, not the scanned text. Every "cannot
// locate" case (unknown, other customer, no granted tank, disabled) is the same
// 404 {error:"device not found", code:"device_not_found"}; none of them is a dead session.
export interface ResolveDeviceResponse {
  deviceName: string;
}

// ---- Stations ----

// GET /api/stations -> { rows: Station[] } (yeweiyi-cloud internal/api/stations.go stationResponse).
// A customer_user only gets the stations of devices they have bound; a customer_admin gets every
// station of their customer, including ones with no devices. `customerId` is there for the operations
// console — this app must not depend on it.
export interface Station {
  id: number;
  customerId: number;
  name: string;
  address: string;
  createdAt: string;
}

// ---- Alarms ----

// Mirrors yeweiyi-cloud internal/api/alarms.go's alarmResponse (itself internal/protocol.AlarmEvent
// reshaped for the wire). GET /api/alarms answers a bare array, not {rows: [...]} — unlike every
// other list endpoint in this file. That inconsistency is real and tracked cloud-side
// (yeweiyi-cloud docs/protocol-v1-open-items.md §D item 2, left unresolved because the device-side
// telemetry key this endpoint reads — `alarm` vs the Android repo's `alarmEvent` — is itself still
// open); it is not a typo here.
//
// type/severity/status are closed enums server-side (internal/protocol/events.go); Chinese labels
// for them live in utils/labels.ts rather than duplicated per screen.
export interface Alarm {
  alarmId: string;
  tank: number;
  type: string; // e.g. "HIGH_HIGH_LEVEL" — see ALARM_TYPE_LABELS
  severity: string; // ThingsBoard's own enum: CRITICAL/MAJOR/MINOR/WARNING/INDETERMINATE
  status: 'ACTIVE' | 'CLEARED';
  text: string;
  value?: number;
  time: string;
  // The tank's oil when the alarm fired (cloud#85, newer firmware); null on device-level alarms and older
  // records. Absent on a cloud before #85. Show by oilTypeIndex first (utils/recordColumns.ts mediumText).
  oilType?: string | null;
  oilTypeIndex?: number | null;
}

// The live-push message /ws/alarms sends (yeweiyi-cloud internal/ingest.handleAlarm's Redis
// fanout). Richer than a v1 client would guess from the field name alone: `msg` is the alarm's
// display text (kept under that name for continuity with the socket wrapper), the rest mirrors
// Alarm above so the live banner and the history list can share the same label helpers.
export interface AlarmPushMessage {
  deviceName: string;
  alarmId: string;
  tank: number;
  type: string;
  severity: string;
  status: 'ACTIVE' | 'CLEARED';
  msg: string;
  value?: number;
  tsMs: number;
}

// ---- 出入库 (stock in/out) ----

// Mirrors yeweiyi-cloud internal/api/stock.go's stockEventResponse. This endpoint depends on a
// device-side change that has not shipped yet (zhongchengyeweiyi#39) — see OilInOutListResponse
// below for what a real device on today's firmware actually produces.
export interface OilInOutRow {
  eventId: string;
  tank: number;
  direction: 'IN' | 'OUT';
  oilType?: string;
  // "AUTO" (device-detected) or "MANUAL" (operator backfill); always present on the wire.
  source: 'AUTO' | 'MANUAL';
  beginTime: string;
  endTime: string;
  beginLevelMm: number;
  endLevelMm: number;
  beginVolumeL: number;
  endVolumeL: number;
  beginTempC: number;
  endTempC: number;
  beginWeightKg: number;
  endWeightKg: number;
  netVolumeL: number;
  duringDeliveryVolumeL?: number;
  deliveredVolumeL: number;
  // Delivered mass computed by the device (cloud#85, newer firmware); null on older records, absent on a
  // cloud before #85. Not end minus begin weight (that includes sales during the delivery).
  deliveredWeightKg?: number | null;
  oilTypeIndex?: number | null;
  time: string;
}

// ---- 交班 / 库存 records (yeweiyi-cloud#85, docs/shift-stock-records-contract.md §4) ----

// GET /api/shift-records and GET /api/stock-records: one row per (record, tank); tank filter and grants
// apply before paging. Every missing reading is null — shown as a dash, never 0.
// UNSUPPORTED = the device never reported this kind of record; SYNCING = the initial takeover has not
// finished. Only SYNCED with no rows may be shown as "no records".
export type RecordSyncStatus = 'UNSUPPORTED' | 'SYNCING' | 'SYNCED';

export interface RecordListResponse<T> {
  rows: T[];
  truncated: boolean;
  partial: boolean;
  // Device-wide count of originals the cloud could not read (not attributable to a tank).
  unparsable: number;
  syncStatus: RecordSyncStatus;
}

// Mirrors internal/api/shiftstock.go shiftRecordRow. Rows of one shift share shiftId. An open shift
// (status OPEN) has endTime and every end/out figure null.
export interface ShiftRecordRow {
  shiftId: string;
  status: string; // "OPEN" | "CLOSED"
  beginTime: string; // 接班时间
  endTime: string | null; // 交班时间
  tank: number;
  oilType: string | null;
  oilTypeIndex: number | null;
  beginLevelMm: number | null;
  beginVolumeL: number | null;
  endLevelMm: number | null;
  endVolumeL: number | null;
  outVolumeL: number | null;
  outWeightKg: number | null;
}

// Mirrors internal/api/shiftstock.go stockRecordRow. Level and water are millimetres.
export interface StockRecordRow {
  recordId: string;
  time: string; // 库存生成时间
  trigger: string | null; // "SCHEDULED" | "MANUAL"
  tank: number;
  oilType: string | null;
  oilTypeIndex: number | null;
  levelMm: number | null;
  volumeL: number | null;
  waterLevelMm: number | null;
  tempC: number | null;
  weightKg: number | null;
}

// GET /api/oil-in-out returns this shape on BOTH the 200 and the 502 path (yeweiyi-cloud
// docs/protocol-v1-stock-event.md). `rows` is always present and always an array — render it
// before checking the status code, and a device still on pre-V1 firmware still shows whatever it
// can instead of an error screen. `unparsable`/`legacySkipped` must be surfaced, not swallowed: a
// caller that only checks `rows.length === 0` cannot tell "no deliveries" from "the records are
// unreadable", which is the exact failure this response shape exists to prevent — see
// device-detail.ts's loadOilInOut for how the 502 path recovers this full body via ApiError.body
// rather than just the error string.
export interface OilInOutListResponse {
  rows: OilInOutRow[];
  parsed: number;
  unparsable: number;
  legacySkipped: number;
  // Each of these means "this list is not known to be whole" (yeweiyi-cloud internal/api/stock.go,
  // stockListResponse); none of them is a 502. Surface them, do not swallow them.
  invalidSource: number; // parsed but off-protocol `source`; left out of rows
  folded: number; // superseded re-reports of an eventId already listed; explains a short count only
  truncated: boolean; // more matched the window than `limit` allowed
  partial: boolean; // the device's initial history takeover has not finished
  error?: string;
  code?: string; // "STOCK_EVENT_FORMAT_UNSUPPORTED" today; match on this, not on `error`'s text
  device?: string;
  sample?: string;
  legacyCutoff?: string;
}

// ---- Telemetry ----

// Mirrors internal/protocol.Tank. Every field carries its unit in the name; do not add a
// display-only conversion here — the backend does not either. See yeweiyi-cloud
// docs/protocol-v1-reconciliation.md §A on why units live in the key name rather than a side
// "units" object (ThingsBoard aggregates a nested/nominal shape to a silent 0, not an error).
export interface Tank {
  index: number;
  oilType: string;
  oilLevelMm: number;
  waterLevelMm: number;
  tempC: number;
  oilVolumeL: number;
  waterVolumeL: number;
  oilWeightKg: number;
  nullVolumeL: number;
  oilRatioPct: number;
  waterRatioPct: number;
  alarmState: 'NORMAL' | 'ALARM' | 'FAULT' | '';
  probeConnected: boolean;
}

// GET /api/devices/:device/telemetry/latest. Replaces the deleted /api/oilcan/latest entirely —
// different endpoint, different shape, no relation to the old NetOilCan payload. 404 means
// "authorized and provisioned, but has never reported" — a real state, not an error; see
// device-detail.ts's loadSnapshot.
export interface LatestTelemetryResponse {
  deviceName: string;
  ts: string;
  snapshotSeq: number;
  tanks: Tank[];
}

// ---- Control commands ----
//
// Replaces the old asynchronous write-back queue entirely. `POST /api/oil-in-out/correct`,
// `POST /api/shifts/correct` and `POST /api/devices/{device}/oil-can-set` are all gone with no
// direct replacement — record *correction* specifically has no redesign yet (yeweiyi-cloud
// CLAUDE.md: "If correcting a stored record remains a product requirement it needs redesigning on
// top of internal/control, not restoring"). What replaced the queue is a synchronous-request /
// asynchronous-result control command, which this app now exposes as "发送控制命令" rather than as
// a record-specific correction form.

export interface IssueCommandRequest {
  method: string;
  args?: Record<string, unknown>;
  reason: string;
}

// Mirrors yeweiyi-cloud internal/api/control.go's commandResponse. `terminal` is computed
// server-side specifically so a client never has to hard-code the status list
// (pending/sent/received/rejected/executing/succeeded/failed/expired) — always branch on this
// field to decide whether to keep polling, never on `status` directly.
//
// Two invariants worth knowing before building UI around this: an unacknowledged command is
// `sent`, never `failed` (the device may have acted and lost its connection before replying), and
// an ACK is not success — only a later, separate `succeeded`/`failed` means the action actually
// happened. Do not render `received` as "done".
export interface ControlCommand {
  commandId: string;
  deviceName: string;
  method: string;
  args?: Record<string, unknown>;
  operator: string;
  reason: string;
  status: string;
  terminal: boolean;
  errorCode?: string;
  errorReason?: string;
  resultDetail?: unknown;
  issuedAt?: string;
  expiresAt?: string;
  sentAt?: string;
  ackedAt?: string;
  finishedAt?: string;
  // Submission context (yeweiyi-cloud#3); null when submitted without it.
  requestId?: string | null;
  expectedStationId?: number | null;
  configTarget?: string | null;
}

export interface ListCommandsResponse {
  commands: ControlCommand[];
}

// GET /api/devices/:device/tank-settings (yeweiyi-cloud docs/tank-settings-contract.md): the device's last
// report per tank. null = the device did not report that key; 0 is a real value (alarm 0 = off).
export interface TankSettings {
  tank: number;
  reportedAt: string;
  tankHeightMm: number | null;
  sensorLengthMm: number | null;
  tankShape: number | null;
  oilType: string | null;
  oilTypeIndex: number | null;
  alarmHighHighLevelMm: number | null;
  alarmHighLevelMm: number | null;
  alarmLowLevelMm: number | null;
  alarmLowLowLevelMm: number | null;
  alarmHighWaterMm: number | null;
  alarmHighTempC: number | null;
  alarmLowTempC: number | null;
  levelCorrectionMm: number | null;
  waterCorrectionMm: number | null;
  tempCorrectionC: number | null;
  density: number | null;
  capacityL: number | null;
}

export interface TankSettingsResponse {
  deviceName: string;
  tanks: TankSettings[];
}
