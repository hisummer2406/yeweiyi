/**
 * 罐体状态标签——镜像 yeweiyi-miniapp/miniprogram/utils/labels.ts 的 TANK_ALARM_STATE_LABELS，
 * 保持两端文案一致。alarmState 是后端的封闭枚举（internal/protocol），新增枚举值需要跟后端协议
 * 变更同步，不要在这里单方面猜测新值。
 */
export const TANK_ALARM_STATE_LABELS: Record<string, string> = {
  NORMAL: '正常',
  ALARM: '报警',
  FAULT: '故障',
};

/** 查不到映射时回退为原始值，而不是空字符串——这样协议漂移会显形而不是被吞掉。 */
export function tankAlarmStateLabel(alarmState: string | undefined): string {
  if (!alarmState) return '--';
  return TANK_ALARM_STATE_LABELS[alarmState] || alarmState;
}
