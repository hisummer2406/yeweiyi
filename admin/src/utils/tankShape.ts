/** 罐型只来自 tank-settings；配置行不能决定哪些罐应显示。 */
export type TankShapeRow = {
  tank: number;
  tankShape: number | null;
  reportedAt: string;
};
export type TankShapeState = {
  rows: TankShapeRow[];
  status: 'loading' | 'ok' | 'error' | 'stale';
};
// 未上报或无法识别的罐型一律按卧罐处理（产品决定 2026-11-10），不展示「罐型未知」。
export type TankShape = 'horizontal' | 'vertical';

export const emptyTankShapes = (): TankShapeState => ({
  rows: [],
  status: 'loading',
});

export function tankShapeFailure(
  previous: TankShapeState,
  status: number,
): TankShapeState {
  // 对象不再可访问时不能继续展示该对象的缓存，404 也不能沿用已删除设备的配置。
  const keep = ![401, 403, 404].includes(status) && previous.rows.length > 0;
  return { rows: keep ? previous.rows : [], status: keep ? 'stale' : 'error' };
}

export function tankShapeOf(state: TankShapeState | undefined, tank: number) {
  const row = state?.rows.find((r) => r.tank === tank);
  const shape: TankShape = row?.tankShape === 1 ? 'vertical' : 'horizontal';
  return {
    shape,
    reportedAt: row?.reportedAt || '',
    status: state?.status ?? 'loading',
  };
}
