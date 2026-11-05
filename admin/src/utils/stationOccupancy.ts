/**
 * 油站与大屏一对一（yeweiyi-admin#15 / yeweiyi-cloud#61）：一个油站同一时刻至多一台设备。
 * 这里只从 GET /api/devices 的行推导「谁占着哪个油站」，用于界面预提示和展示；
 * 云端的唯一约束才是最终裁决，列表可能已过期，所以服务端冲突仍要按响应处理。
 * 设备行没有 station_id 字段 = 旧版云端，无从判断，不当作空闲也不当作占用。
 */
type HasStation = { device_name: string; station_id?: number | null };

/** 油站 id → 当前挂在它名下的设备编号（保持列表顺序）。正常数据每个油站至多一项；多于一项是历史冲突数据。 */
export function occupantsByStation(
  devices: HasStation[],
): Map<number, string[]> {
  const map = new Map<number, string[]>();
  for (const d of devices) {
    if (typeof d.station_id !== 'number') continue;
    const list = map.get(d.station_id) ?? [];
    list.push(d.device_name);
    map.set(d.station_id, list);
  }
  return map;
}

/**
 * 除 `self` 之外占着该油站的第一台设备；没有则 undefined。
 * 用于分配/换机的预提示：这台设备自己已在该油站不算被占。
 */
export function occupantOf(
  devices: HasStation[],
  stationId: number,
  self?: string,
): string | undefined {
  return devices.find(
    (d) => d.station_id === stationId && d.device_name !== self,
  )?.device_name;
}

/**
 * 油站当前设备（只读推导）：恰好一台 → 其编号；没有 → null；多于一台（历史冲突数据）→ undefined，
 * 调用方不得据此择一或自动切换。
 */
export function currentDeviceOf(
  devices: HasStation[],
  stationId: number,
): string | null | undefined {
  const names = devices
    .filter((d) => d.station_id === stationId)
    .map((d) => d.device_name);
  if (names.length === 0) return null;
  return names.length === 1 ? names[0] : undefined;
}
