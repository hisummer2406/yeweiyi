/**
 * 客户品牌 Logo 的本地预检（#86，云端 docs/brand-logo-contract.md §2）。规则与云端一致，只为早点提示；
 * 云端按实际内容整图解码，才是裁决。纯函数，不碰 DOM。
 */
export const BRAND_LOGO_MAX_BYTES = 256 * 1024;
export const BRAND_LOGO_MIN_SIDE = 64;
export const BRAND_LOGO_MAX_SIDE = 1024;

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

export type BrandLogoProblem = 'type' | 'size' | 'dimensions';

/** 按文件头判断是不是 PNG（不看扩展名和浏览器给的 MIME） */
export function isPng(bytes: Uint8Array): boolean {
  return PNG_SIGNATURE.every((b, i) => bytes[i] === b);
}

export function checkBrandLogoBytes(
  bytes: Uint8Array,
): BrandLogoProblem | null {
  if (!isPng(bytes)) return 'type';
  if (bytes.length > BRAND_LOGO_MAX_BYTES) return 'size';
  return null;
}

export function checkBrandLogoDimensions(
  width: number,
  height: number,
): BrandLogoProblem | null {
  const ok = (v: number) =>
    Number.isInteger(v) && v >= BRAND_LOGO_MIN_SIDE && v <= BRAND_LOGO_MAX_SIDE;
  return ok(width) && ok(height) ? null : 'dimensions';
}

export function bytesToBase64(bytes: Uint8Array): string {
  let s = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    s += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(s);
}

export function brandLogoDataUrl(base64: string): string {
  return `data:image/png;base64,${base64}`;
}
