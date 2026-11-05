/**
 * 设备出厂编号规范化。编号是铭牌上的字符串，前导零必须保留，所以全程不转数字。
 *
 * yeweiyi-cloud 的 POST /api/devices 不强制前缀或长度（只 trim，并拒绝含 / ? # \ 的名称），
 * 这里的规则只用来拦截明显的输入错误，不是跨型号的产品协议。
 */

/**
 * 短码补全用的默认前缀。
 * TODO(产品确认)：各型号是否统一使用 ZCT 前缀、长度规则和短码唯一性尚未确认；
 * 在确认前，短码只给出候选，必须由管理员确认后才能提交。
 */
export const DEFAULT_DEVICE_PREFIX = 'ZCT';

const FULL_CODE = /^[A-Z]{3}\d+$/;
const SHORT_CODE = /^\d+$/;

/** invalid 的原因：empty 未填写；format 格式不对。文案由页面按 i18n 映射。 */
export type DeviceCodeInvalidReason = 'empty' | 'format';

export type DeviceCodeResult =
  | { status: 'full'; code: string }
  | { status: 'needsConfirm'; code: string }
  | { status: 'invalid'; code: string; reason: DeviceCodeInvalidReason };

export function normalizeDeviceCode(input: string): DeviceCodeResult {
  const code = input.trim().toUpperCase();
  if (!code) {
    return { status: 'invalid', code, reason: 'empty' };
  }
  if (FULL_CODE.test(code)) {
    return { status: 'full', code };
  }
  if (SHORT_CODE.test(code)) {
    return {
      status: 'needsConfirm',
      code: `${DEFAULT_DEVICE_PREFIX}${code}`,
    };
  }
  return { status: 'invalid', code, reason: 'format' };
}
