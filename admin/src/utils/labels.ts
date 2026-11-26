/**
 * 报警类型/级别/状态、出入库方向与来源的中文文案，键与小程序 `miniprogram/utils/labels.ts` 保持一致
 * （同一份云端枚举 internal/protocol）。云端新增枚举成员时，这里加一行即可。
 */

export const ALARM_TYPE_LABELS: Record<string, string> = {
  HIGH_HIGH_LEVEL: '高高液位',
  HIGH_LEVEL: '高液位',
  LOW_LEVEL: '低液位',
  LOW_LOW_LEVEL: '低低液位',
  HIGH_WATER_LEVEL: '高水位',
  HIGH_TEMPERATURE: '高油温',
  LOW_TEMPERATURE: '低油温',
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

export const STOCK_DIRECTION_LABELS: Record<string, string> = {
  IN: '进油',
  OUT: '出油',
};

/** AUTO 为设备自动检测，MANUAL 为补录（云端已把缺失的来源归一为这两个值之一）。 */
export const STOCK_SOURCE_LABELS: Record<string, string> = {
  AUTO: '自动',
  MANUAL: '手动',
};

/**
 * 查映射表；未知值原样显示（协议新增了枚举成员正是最该被注意到的情形，显示成空白会让它看起来像渲染 bug），
 * 空值显示 "--"。
 */
export function label(
  map: Record<string, string>,
  key: string | null | undefined,
): string {
  if (!key) return '--';
  return Object.hasOwn(map, key) ? map[key] : key;
}
