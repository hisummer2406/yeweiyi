// Customer brand logo (#86, yeweiyi-cloud docs/brand-logo-contract.md): the pure part. Pure (no `wx`),
// checked in Node by `npm run check` (scripts/brand-logo.check.mjs).
//
// The logo belongs to the account that asked for it. Every answer is checked against the account
// signed in when it arrives, so a late answer for A can never paint B's screens.

export const DEFAULT_BRAND_MARK = '/assets/illustrations/brand-mark.png';
export const DEFAULT_BRAND_AVATAR = '/assets/icons/avatar-logo.png';

// What this phone remembers about one account's logo. `src` is a local file (or a data URL when the
// file could not be written); `version` is the cloud's content hash.
export interface BrandLogoCache {
  account: string;
  version: string;
  src: string;
}

export interface BrandLogoAnswer {
  configured: boolean;
  version: string;
  notModified: boolean;
  data: string; // base64, '' when absent
}

// The cloud body, or null when it is not a brand-logo answer (old cloud, proxy page...).
export function parseBrandLogo(body: unknown): BrandLogoAnswer | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;
  if (typeof b.configured !== 'boolean') return null;
  if (!b.configured) return { configured: false, version: '', notModified: false, data: '' };
  if (typeof b.version !== 'string' || b.version === '') return null;
  const notModified = b.notModified === true;
  const data = typeof b.data === 'string' ? b.data : '';
  if (!notModified && data === '') return null;
  return { configured: true, version: b.version, notModified, data };
}

// The version to send as ?known=: only the cache of the same account counts.
export function knownVersion(cache: BrandLogoCache | null | undefined, account: string): string {
  return cache && account && cache.account === account ? cache.version : '';
}

export type BrandLogoStep =
  | { kind: 'drop' } // answer for another session: change nothing
  | { kind: 'default' } // no custom logo: forget the cache
  | { kind: 'keep' } // the cache is current
  | { kind: 'store'; version: string; data: string }; // write the new picture

export function brandLogoStep(input: {
  requestAccount: string;
  currentAccount: string;
  cache: BrandLogoCache | null | undefined;
  answer: BrandLogoAnswer | null;
}): BrandLogoStep {
  const { requestAccount, currentAccount, cache, answer } = input;
  if (!requestAccount || requestAccount !== currentAccount) return { kind: 'drop' };
  if (!answer || !answer.configured) return { kind: 'default' };
  if (answer.notModified) {
    // Only trust "not modified" when we really hold that version for this account.
    return knownVersion(cache, currentAccount) === answer.version ? { kind: 'keep' } : { kind: 'default' };
  }
  if (knownVersion(cache, currentAccount) === answer.version) return { kind: 'keep' };
  return { kind: 'store', version: answer.version, data: answer.data };
}

// What a page shows: the custom logo of the account signed in now, else the default.
export function brandLogoSrc(cache: BrandLogoCache | null | undefined, account: string, fallback: string): string {
  return cache && account && cache.account === account && cache.src ? cache.src : fallback;
}

// Local file name for one version. The account and version are both safe path parts (u12, hex).
export function brandLogoFileName(account: string, version: string): string {
  const safe = (s: string) => s.replace(/[^0-9A-Za-z_-]/g, '');
  return `brand-logo-${safe(account)}-${safe(version)}.png`;
}
