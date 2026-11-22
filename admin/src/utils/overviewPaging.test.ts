import { describe, expect, it } from 'vitest';
import { createLimiter, mapLimit, packPages } from './overviewPaging';

describe('packPages', () => {
  const one = () => 1;

  it('returns no pages for no units', () => {
    expect(packPages([], one, 12)).toEqual([]);
  });

  it('chunks unit-weight items by the limit', () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    const pages = packPages(items, one, 12);
    expect(pages.map((p) => p.length)).toEqual([12, 12, 1]);
    expect(pages.flat()).toEqual(items);
  });

  it('keeps a unit whole and packs greedily by weight', () => {
    const units = [
      { k: 'a', n: 5 },
      { k: 'b', n: 5 },
      { k: 'c', n: 5 },
    ];
    const pages = packPages(units, (u) => u.n, 12);
    expect(pages.map((p) => p.map((u) => u.k))).toEqual([['a', 'b'], ['c']]);
  });

  it('puts a unit heavier than the limit on a page of its own', () => {
    const units = [
      { k: 'a', n: 3 },
      { k: 'b', n: 20 },
      { k: 'c', n: 3 },
    ];
    const pages = packPages(units, (u) => u.n, 12);
    expect(pages.map((p) => p.map((u) => u.k))).toEqual([['a'], ['b'], ['c']]);
  });
});

describe('mapLimit', () => {
  it('never runs more than `limit` at once and keeps result order', async () => {
    let running = 0;
    let peak = 0;
    const out = await mapLimit(
      Array.from({ length: 10 }, (_, i) => i),
      3,
      async (n) => {
        running++;
        peak = Math.max(peak, running);
        await new Promise((r) => setTimeout(r, 5 - (n % 5)));
        running--;
        return n * 2;
      },
    );
    expect(peak).toBe(3);
    expect(out).toEqual([0, 2, 4, 6, 8, 10, 12, 14, 16, 18]);
  });

  it('resolves immediately for an empty list', async () => {
    await expect(mapLimit([], 5, async () => 1)).resolves.toEqual([]);
  });
});

describe('createLimiter', () => {
  it('caps concurrency across overlapping batches and passes results through', async () => {
    const run = createLimiter(2);
    let running = 0;
    let peak = 0;
    const job = (v: number) => async () => {
      running++;
      peak = Math.max(peak, running);
      await new Promise((r) => setTimeout(r, 5));
      running--;
      return v;
    };
    const a = Promise.all([1, 2, 3].map((v) => run(job(v))));
    const b = Promise.all([4, 5].map((v) => run(job(v))));
    await expect(a).resolves.toEqual([1, 2, 3]);
    await expect(b).resolves.toEqual([4, 5]);
    expect(peak).toBe(2);
  });

  it('frees the slot when a job throws', async () => {
    const run = createLimiter(1);
    await expect(run(() => Promise.reject(new Error('x')))).rejects.toThrow(
      'x',
    );
    await expect(run(async () => 'ok')).resolves.toBe('ok');
  });
});
