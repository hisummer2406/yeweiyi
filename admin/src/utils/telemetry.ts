/** 罐告警状态的中文名；未知值原样显示，不假装认识。 */
const ALARM_LABELS: Record<string, string> = {
  NORMAL: '正常',
  ALARM: '报警',
  FAULT: '故障',
};

export const alarmLabel = (state: string): string =>
  ALARM_LABELS[state] ?? state;

/** 告警状态对应的 antd Tag 颜色。 */
export const alarmColor = (state: string): string => {
  if (state === 'ALARM') return 'error';
  if (state === 'FAULT') return 'warning';
  return 'success';
};

/** 数值保留 1 位小数；非有限数显示 "--"。 */
export const fmt = (n: number | null | undefined): string =>
  typeof n === 'number' && Number.isFinite(n) ? n.toFixed(1) : '--';

/** RFC3339 时间显示为本地 "YYYY-MM-DD HH:mm:ss"；空或非法显示 "--"。 */
export const fmtTime = (iso: string | null | undefined): string => {
  if (!iso) return '--';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '--';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};
