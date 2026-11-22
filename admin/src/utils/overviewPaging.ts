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

/**
 * 共享的并发闸门：多轮轮询重叠时（上一轮还没跑完下一轮又到了）合计也不超过 limit。
 * 返回的 run(fn) 排队执行 fn，结果/异常原样交回。
 */
export function createLimiter(limit: number) {
  let active = 0;
  const queue: (() => void)[] = [];
  // 有人排队时名额直接转交，不先归还，避免新来的请求插队造成超额
  const release = () => {
    const next = queue.shift();
    if (next) next();
    else active--;
  };
  return async function run<R>(fn: () => Promise<R>): Promise<R> {
    if (active >= limit) {
      await new Promise<void>((resolve) => queue.push(resolve));
    } else {
      active++;
    }
    try {
      return await fn();
    } finally {
      release();
    }
  };
}
