// @ts-ignore
/* eslint-disable */

// Wire types for `yeweiyi-cloud`'s device/telemetry endpoints. Field names deliberately match
// the backend's JSON tags (snake_case `Device`, camelCase `Tank`) rather than a normalized TS
// style, and mirror `yeweiyi-miniapp/miniprogram/types/api.d.ts` field-for-field so the two
// clients don't drift on the same wire shape. Kept out of `API` (src/services/ant-design-pro/*)
// on purpose — that namespace is still the unmodified Pro template.
declare namespace DeviceAPI {
  // Mirrors yeweiyi-cloud internal/store/db/models.go's Device struct.
  type Device = {
    device_name: string;
    // is_online is part of the wire response but must never be shown as connectivity status:
    // nothing in the backend writes it any more (see root CLAUDE.md / yeweiyi-cloud CLAUDE.md
    // "Not verified"). Real connectivity comes from a tank's own alarmState/probeConnected.
    is_online: boolean;
    last_online_at: string | null;
    last_offline_at: string | null;
    updated_at: string;
  };

  // GET /api/devices
  type ListDevicesResponse = {
    rows: Device[];
  };

  // Mirrors yeweiyi-cloud internal/protocol/telemetry.go's Tank struct. Every field carries its
  // unit in the name; do not add a display-only conversion here — the backend does not either.
  type Tank = {
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
  };

  // GET /api/devices/:device/telemetry/latest. A 404 response means "authorized and
  // provisioned, but has never reported" — a real state, not an error.
  type LatestTelemetryResponse = {
    deviceName: string;
    ts: string;
    snapshotSeq: number;
    tanks: Tank[];
  };
}
