/**
 * 监控页按油站筛选的纯函数。油站筛选值：
 * - 'all'：全部油站（URL 上省略 ?station=）
 * - 'none'：未分组（station_id 为 null）
 * - number：某个油站 id
 * 设备行没有 station_id 字段 = 旧版云端，既不属于任何油站也不是「未分组」，只在 'all' 下可见。
 */
export type StationFilterValue = 'all' | 'none' | number;

type HasStation = { station_id?: number | null };

/** 解析 ?station=。非法值，或数字但不在可见油站里，回退为全部。 */
export function parseStationParam(
  raw: string | null | undefined,
  visibleIds: number[],
): StationFilterValue {
  if (raw === 'none') return 'none';
  if (raw && /^\d+$/.test(raw)) {
    const id = Number(raw);
    if (visibleIds.includes(id)) return id;
  }
  return 'all';
}

export function stationParam(value: StationFilterValue): string | undefined {
  return value === 'all' ? undefined : String(value);
}

export function matchesStation(
  device: HasStation,
  value: StationFilterValue,
): boolean {
  if (value === 'all') return true;
  if (value === 'none') return device.station_id === null;
  return device.station_id === value;
}

/** 是否应该出现筛选与分段：要有油站可选，且云端新到会返回 station_id。 */
export function stationFilterAvailable(
  stationCount: number,
  devices: HasStation[] | undefined,
): boolean {
  return (
    stationCount > 0 &&
    !!devices &&
    devices.some((d) => d.station_id !== undefined)
  );
}

export type StationSection<T> = {
  /** 'none' 是末尾的「未分组」段；number 是油站 id（可能不在可见列表里，标题由调用方退化为 #id） */
  key: 'none' | number;
  items: T[];
};

/**
 * 按油站分段：可见油站按列表顺序，其后是不在可见列表里的油站 id（按 id 升序），「未分组」最后。
 * 没有 station_id 字段的设备并入「未分组」，这只会发生在 stationFilterAvailable 为 false 之外的混合行里。
 */
export function sectionByStation<T extends HasStation>(
  items: T[],
  visibleIds: number[],
): StationSection<T>[] {
  const byId = new Map<number, T[]>();
  const none: T[] = [];
  for (const it of items) {
    if (typeof it.station_id === 'number') {
      const list = byId.get(it.station_id) ?? [];
      list.push(it);
      byId.set(it.station_id, list);
    } else {
      none.push(it);
    }
  }
  const sections: StationSection<T>[] = [];
  for (const id of visibleIds) {
    const list = byId.get(id);
    if (list) sections.push({ key: id, items: list });
  }
  const hidden = [...byId.keys()]
    .filter((id) => !visibleIds.includes(id))
    .sort((a, b) => a - b);
  for (const id of hidden) {
    sections.push({ key: id, items: byId.get(id) as T[] });
  }
  if (none.length > 0) sections.push({ key: 'none', items: none });
  return sections;
}
