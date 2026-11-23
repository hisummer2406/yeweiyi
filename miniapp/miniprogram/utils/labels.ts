import { t } from './i18n';

// Label tables mirroring closed enums defined server-side (yeweiyi-cloud internal/protocol).
// Kept in one place rather than inline in WXML so a value the map does not know about renders as
// itself (see `label` below) instead of a blank string, and so a new enum member added cloud-side
// is a one-line addition here rather than a hunt through every WXML file that renders it.

export const ALARM_TYPE_KEYS: Record<string, string> = {
  HIGH_HIGH_LEVEL: 'alarmType.HIGH_HIGH_LEVEL',
  HIGH_LEVEL: 'alarmType.HIGH_LEVEL',
  LOW_LEVEL: 'alarmType.LOW_LEVEL',
  LOW_LOW_LEVEL: 'alarmType.LOW_LOW_LEVEL',
  HIGH_WATER_LEVEL: 'alarmType.HIGH_WATER_LEVEL',
  HIGH_TEMPERATURE: 'alarmType.HIGH_TEMPERATURE',
  LOW_TEMPERATURE: 'alarmType.LOW_TEMPERATURE',
  PROBE_DISCONNECTED: 'alarmType.PROBE_DISCONNECTED',
  LEAK_SUSPECTED: 'alarmType.LEAK_SUSPECTED',
  OVERFILL_RISK: 'alarmType.OVERFILL_RISK',
};

export const ALARM_SEVERITY_KEYS: Record<string, string> = {
  CRITICAL: 'alarmSeverity.CRITICAL',
  MAJOR: 'alarmSeverity.MAJOR',
  MINOR: 'alarmSeverity.MINOR',
  WARNING: 'alarmSeverity.WARNING',
  INDETERMINATE: 'alarmSeverity.INDETERMINATE',
};

export const ALARM_STATUS_KEYS: Record<string, string> = {
  ACTIVE: 'alarmStatus.ACTIVE',
  CLEARED: 'alarmStatus.CLEARED',
};

export const TANK_ALARM_STATE_KEYS: Record<string, string> = {
  NORMAL: 'tankState.NORMAL',
  ALARM: 'tankState.ALARM',
  FAULT: 'tankState.FAULT',
};

export const STOCK_DIRECTION_KEYS: Record<string, string> = {
  IN: 'direction.IN',
  OUT: 'direction.OUT',
};

// Control command lifecycle, mirrored from yeweiyi-cloud internal/control. This only WORDS a status; whether
// a command is finished is the server's `terminal` flag, never this table.
export const COMMAND_STATUS_KEYS: Record<string, string> = {
  pending: 'cmdStatus.pending',
  sent: 'cmdStatus.sent',
  received: 'cmdStatus.received',
  rejected: 'cmdStatus.rejected',
  executing: 'cmdStatus.executing',
  succeeded: 'cmdStatus.succeeded',
  failed: 'cmdStatus.failed',
  expired: 'cmdStatus.expired',
};

export const ROLE_KEYS: Record<string, string> = {
  platform_admin: 'role.platform_admin',
  customer_admin: 'role.customer_admin',
  customer_user: 'role.customer_user',
};

// label looks a value up in one of the maps above and renders it in the current language, falling back
// to the raw value (never to a blank string) — an unmapped value is exactly the case worth noticing, and
// hiding it as "" would make a protocol drift look like a rendering bug instead.
export function label(map: Record<string, string>, key: string | undefined): string {
  if (!key) return '--';
  return map[key] ? t(map[key]) : key;
}

// Control methods this backend accepts, mirrored from yeweiyi-cloud internal/protocol.Methods
// (rpc.go). Kept as a plain table rather than fetched from the server: the method set is part of
// the protocol contract, not per-deployment configuration, and that repo's CLAUDE.md says not to
// add a method without a protocol decision — this list should only ever change in lockstep with
// that one.
export interface ControlMethodSpec {
  method: string;
  // i18n key (locales/*.ts, `method.<name>`); render it with t().
  labelKey: string;
  // Physical methods are gated off (409, not a bug in this app) on every deployment until
  // device-side interlocks are finished and verified on real hardware (issue #1). They stay in
  // this list — the UI should be able to show why a physical action was refused, not hide the
  // button and leave that undiscoverable.
  physical: boolean;
  // Requires customer_admin or platform_admin; a customer_user issuing one gets 403.
  adminOnly: boolean;
}

export const CONTROL_METHODS: ControlMethodSpec[] = [
  { method: 'alarm.mute', labelKey: 'method.alarm.mute', physical: false, adminOnly: false },
  { method: 'alarm.clear', labelKey: 'method.alarm.clear', physical: false, adminOnly: false },
  { method: 'pump.start', labelKey: 'method.pump.start', physical: true, adminOnly: false },
  { method: 'pump.stop', labelKey: 'method.pump.stop', physical: true, adminOnly: false },
  { method: 'valve.open', labelKey: 'method.valve.open', physical: true, adminOnly: false },
  { method: 'valve.close', labelKey: 'method.valve.close', physical: true, adminOnly: false },
  { method: 'device.reboot', labelKey: 'method.device.reboot', physical: true, adminOnly: true },
  { method: 'config.setAlarmThresholds', labelKey: 'method.config.setAlarmThresholds', physical: false, adminOnly: true },
  { method: 'config.setTank', labelKey: 'method.config.setTank', physical: false, adminOnly: true },
  { method: 'config.setProbe', labelKey: 'method.config.setProbe', physical: false, adminOnly: true },
];

export function controlMethodLabel(method: string): string {
  const spec = CONTROL_METHODS.find((m) => m.method === method);
  return spec ? t(spec.labelKey) : method;
}
