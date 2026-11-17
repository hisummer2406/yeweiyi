import { describe, expect, it } from 'vitest';
import enPages from '@/locales/en-US/pages';
import zhPages from '@/locales/zh-CN/pages';
import { mediumText, oilTypeKey } from './oilType';
import { DASH } from './records';

const zt = (k: string) => (zhPages as Record<string, string>)[k] ?? k;
const et = (k: string) => (enPages as Record<string, string>)[k] ?? k;

describe('mediumText (cloud#85 §5, same as miniapp recordColumns)', () => {
  it('builtin index 1–17 in the UI language; the Chinese table decides', () => {
    expect(mediumText(5, 'whatever', zt)).toBe('92#');
    // 设备英文表错位（2 号写成 Sulfuric Acid）：按下标
    expect(mediumText(2, 'Sulfuric Acid', et)).toBe('-10# Diesel');
    expect(mediumText(10, 'Kerosene', zt)).toBe('煤油');
  });

  it('builtin index 18–31 (#11) in the UI language; the device English abbreviation is not shown', () => {
    expect(mediumText(18, 'PG', et)).toBe('Propylene Glycol');
    expect(mediumText(19, '乙醇', zt)).toBe('乙醇');
    expect(mediumText(26, '二厂酸蚀', et)).toBe('Plant 2 Etching Acid');
    expect(mediumText(31, '', zt)).toBe('西排水'); // 26–31 never were custom on any firmware
  });

  it('18–25 from old firmware (custom, #11): only the device name of the new built-in counts', () => {
    expect(mediumText(22, '', zt)).toBe(DASH); // 生产上见到的旧固件自定义空白项
    expect(mediumText(22, '   ', zt)).toBe(DASH);
    expect(mediumText(20, '自定义油', zt)).toBe('自定义油');
    expect(mediumText(20, 'Soy Oil', zt)).toBe('大豆油');
  });

  it('0, 32–39, non-integers and missing index fall back to the device text, else a dash', () => {
    expect(oilTypeKey(0)).toBeNull();
    expect(oilTypeKey(32)).toBeNull();
    expect(mediumText(33, '自定义B', zt)).toBe('自定义B');
    expect(oilTypeKey(1.5)).toBeNull();
    expect(oilTypeKey(null)).toBeNull();
    expect(mediumText(0, '', zt)).toBe(DASH);
    expect(mediumText(null, null, zt)).toBe(DASH);
    expect(mediumText(undefined, '95#', zt)).toBe('95#');
  });

  it('every builtin index has a name in zh-CN and en-US', () => {
    for (let i = 1; i <= 31; i++) {
      expect(zt(`pages.console.oil.${i}`)).not.toBe(`pages.console.oil.${i}`);
      expect(et(`pages.console.oil.${i}`)).not.toBe(`pages.console.oil.${i}`);
    }
  });
});
