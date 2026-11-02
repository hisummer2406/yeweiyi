// 设备详情一次只显示一个罐（对应小程序 utils/tankPick.ts，miniapp#15）。纯函数。
//
// 罐用协议罐号标识（Tank.index），不是它在列表里的位置：授权变化或换机后位置会变，按位置会悄悄指到另一个罐。

/** `?tank=` 取值：非负整数；其它（缺省、拼错）一律当作没给。 */
export function parseTankParam(
  value: string | null | undefined,
): number | undefined {
  return value != null && /^\d+$/.test(value) ? Number(value) : undefined;
}

/**
 * 选中哪个罐：请求的罐在可见罐里就是它；否则回到最小罐号。`hidden` 表示请求了某个罐但它不可见
 * （授权被撤销、罐已不存在，或这是另一台设备的罐号）：页面要说明，并改显示第一个可见罐。
 */
export type TankPick =
  | { kind: 'tank'; tank: number; hidden?: number }
  | { kind: 'none'; hidden?: number };

export function pickTank(visible: number[], requested?: number): TankPick {
  if (requested !== undefined && visible.includes(requested))
    return { kind: 'tank', tank: requested };
  const hidden = requested;
  if (visible.length === 0)
    return hidden === undefined ? { kind: 'none' } : { kind: 'none', hidden };
  const first = Math.min(...visible);
  return hidden === undefined
    ? { kind: 'tank', tank: first }
    : { kind: 'tank', tank: first, hidden };
}
