/** 设备总览每页最多的设备数；按油站分段时是「每页尽量装满、不拆开油站」的上限。 */
export const OVERVIEW_PAGE_SIZE = 12;

/** 总览对遥测请求的并发上限：后端没有批量接口，每台设备一次请求。 */
export const TELEMETRY_CONCURRENCY = 5;

/**
 * 按权重顺序装页：单元不拆开，累计权重达到 limit 就换页；
 * 超过 limit 的单元独占一页。设备分页时权重恒为 1，油站分段时权重是该油站的设备数。
 */
export function packPages<T>(
  units: T[],
  weight: (u: T) => number,
  limit: number,
): T[][] {
  const pages: T[][] = [];
  let used = 0;
  for (const u of units) {
    const w = weight(u);
    const last = pages[pages.length - 1];
    if (!last || used >= limit || used + w > limit) {
      pages.push([u]);
      used = w;
    } else {
      last.push(u);
      used += w;
    }
  }
  return pages;
}

/** 带并发上限的 map，结果顺序与输入一致；fn 抛错时整体拒绝（和 Promise.all 一样）。 */
export async function mapLimit<T, R>(
  items: T[],
  limit: number,
  fn: (item: T) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i]);
    }
  };
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, worker),
  );
  return results;
}
