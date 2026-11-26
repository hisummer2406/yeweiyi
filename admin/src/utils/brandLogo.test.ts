import { describe, expect, it } from 'vitest';
import {
  BRAND_LOGO_MAX_BYTES,
  brandLogoDataUrl,
  bytesToBase64,
  checkBrandLogoBytes,
  checkBrandLogoDimensions,
  isPng,
} from './brandLogo';

const png = (len: number) => {
  const b = new Uint8Array(len);
  b.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  return b;
};

describe('brand logo pre-checks', () => {
  it('recognises PNG by its signature, not by name', () => {
    expect(isPng(png(16))).toBe(true);
    expect(isPng(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe(false);
    expect(isPng(new Uint8Array())).toBe(false);
  });

  it('limits the size to 256 KiB', () => {
    expect(checkBrandLogoBytes(png(BRAND_LOGO_MAX_BYTES))).toBeNull();
    expect(checkBrandLogoBytes(png(BRAND_LOGO_MAX_BYTES + 1))).toBe('size');
    expect(checkBrandLogoBytes(new Uint8Array([1, 2, 3]))).toBe('type');
  });

  it('accepts 64-1024 pixels on each side', () => {
    expect(checkBrandLogoDimensions(64, 1024)).toBeNull();
    expect(checkBrandLogoDimensions(63, 100)).toBe('dimensions');
    expect(checkBrandLogoDimensions(100, 1025)).toBe('dimensions');
  });

  it('encodes bytes as base64 for the request and preview', () => {
    const b64 = bytesToBase64(new Uint8Array([104, 105]));
    expect(b64).toBe('aGk=');
    expect(brandLogoDataUrl(b64)).toBe('data:image/png;base64,aGk=');
  });
});
