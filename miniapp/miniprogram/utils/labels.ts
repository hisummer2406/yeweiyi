// Label tables mirroring closed enums defined server-side (yeweiyi-cloud internal/protocol).
// Kept in one place rather than inline in WXML so a value the map does not know about renders as
// itself (see `label` below) instead of a blank string, and so a new enum member added cloud-side
// is a one-line addition here rather than a hunt through every WXML file that renders it.

export const ALARM_TYPE_LABELS: Record<string, string> = {
  HIGH_HIGH_LEVEL: '高高液位',
  HIGH_LEVEL: '高液位',
  LOW_LEVEL: '低液位',
  LOW_LOW_LEVEL: '低低液位',
  HIGH_WATER_LEVEL: '高水位',
  HIGH_TEMPERATURE: '高油温',
  PROBE_DISCONNECTED: '探针断线',
  LEAK_SUSPECTED: '疑似泄漏',
  OVERFILL_RISK: '溢罐风险',
};

export const ALARM_SEVERITY_LABELS: Record<string, string> = {
  CRITICAL: '紧急',
  MAJOR: '重要',
  MINOR: '次要',
  WARNING: '警告',
  INDETERMINATE: '未定级',
};

export const ALARM_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '报警中',
  CLEARED: '已恢复',
};

export const TANK_ALARM_STATE_LABELS: Record<string, string> = {
  NORMAL: '正常',
  ALARM: '报警',
  FAULT: '故障',
};

export const STOCK_DIRECTION_LABELS: Record<string, string> = {
  IN: '进油',
  OUT: '出油',
};

// label looks a value up in one of the maps above, falling back to the raw value (never to a blank
// string) — an unmapped value is exactly the case worth noticing, and hiding it as "" would make a
// protocol drift look like a rendering bug instead.
export function label(map: Record<string, string>, key: string | undefined): string {
  if (!key) return '--';
  return map[key] || key;
}

// Control methods this backend accepts, mirrored from yeweiyi-cloud internal/protocol.Methods
// (rpc.go). Kept as a plain table rather than fetched from the server: the method set is part of
// the protocol contract, not per-deployment configuration, and that repo's CLAUDE.md says not to
// add a method without a protocol decision — this list should only ever change in lockstep with
// that one.
export interface ControlMethodSpec {
  method: string;
  label: string;
  // Physical methods are gated off (409, not a bug in this app) on every deployment until
  // device-side interlocks are finished and verified on real hardware (issue #1). They stay in
  // this list — the UI should be able to show why a physical action was refused, not hide the
  // button and leave that undiscoverable.
  physical: boolean;
  // Requires customer_admin or platform_admin; a customer_user issuing one gets 403.
  adminOnly: boolean;
}

export const CONTROL_METHODS: ControlMethodSpec[] = [
  { method: 'alarm.mute', label: '现场消音', physical: false, adminOnly: false },
  { method: 'alarm.clear', label: '清除已恢复的报警', physical: false, adminOnly: false },
  { method: 'pump.start', label: '启动指定泵', physical: true, adminOnly: false },
  { method: 'pump.stop', label: '停止指定泵', physical: true, adminOnly: false },
  { method: 'valve.open', label: '打开指定阀门', physical: true, adminOnly: false },
  { method: 'valve.close', label: '关闭指定阀门', physical: true, adminOnly: false },
  { method: 'device.reboot', label: '重启设备', physical: true, adminOnly: true },
  { method: 'config.setAlarmThresholds', label: '修改报警阈值', physical: false, adminOnly: true },
  { method: 'config.setTank', label: '修改油罐配置', physical: false, adminOnly: true },
  { method: 'config.setProbe', label: '修改探针配置', physical: false, adminOnly: true },
];

export function controlMethodLabel(method: string): string {
  return CONTROL_METHODS.find((m) => m.method === method)?.label || method;
}
