// Customer brand logo (#86, yeweiyi-cloud docs/brand-logo-contract.md). The home header, the settings
// account row and the account page show the logo of the signed-in account's customer, read from
// GET /api/me/brand-logo; before login, with no custom logo, on failure or when the picture does not
// load, they show the default. Decisions live in brandLogoCore.ts (Node-checked).
//
// The picture is written to a local file named by account + version, and the version is sent back as
// ?known= so an unchanged logo costs a few bytes. Offline, the last confirmed logo of the same account
// stays. Logout removes it (storage.clearToken deletes the cache entry and its file; this module keeps no
// copy in memory, so nothing of the previous account survives).
import { get } from './request';
import { userKeyFromToken } from './auth';
import { BRAND_LOGO_CACHE_KEY, getToken, removeLocalFile } from './storage';
import {
  type BrandLogoCache,
  brandLogoFileName,
  brandLogoSrc,
  brandLogoStep,
  knownVersion,
  parseBrandLogo,
} from './brandLogoCore';

export { DEFAULT_BRAND_AVATAR, DEFAULT_BRAND_MARK } from './brandLogoCore';

const CACHE_KEY = BRAND_LOGO_CACHE_KEY;

let inFlight: { account: string; promise: Promise<void> } | null = null;
let fetchedFor = ''; // the account already refreshed during this run

function currentAccount(): string {
  const token = getToken();
  return token ? userKeyFromToken(token) : '';
}

function readCache(): BrandLogoCache | null {
  try {
    const c = wx.getStorageSync<BrandLogoCache>(CACHE_KEY);
    return c && typeof c.account === 'string' && typeof c.version === 'string' && typeof c.src === 'string' ? c : null;
  } catch {
    return null;
  }
}

function writeCache(next: BrandLogoCache | null): void {
  const prev = readCache();
  if (prev && prev.src !== next?.src) removeLocalFile(prev.src);
  try {
    if (next) wx.setStorageSync(CACHE_KEY, next);
    else wx.removeStorageSync(CACHE_KEY);
  } catch {
    // the default shows instead
  }
}

// Writes the picture to a file; a data URL is the fallback when the file system refuses.
function storePicture(account: string, version: string, data: string): string {
  const path = `${wx.env.USER_DATA_PATH}/${brandLogoFileName(account, version)}`;
  try {
    wx.getFileSystemManager().writeFileSync(path, data, 'base64');
    return path;
  } catch {
    return `data:image/png;base64,${data}`;
  }
}

// The logo to show now for the signed-in account (synchronous, never a request).
export function brandLogoFor(fallback: string): string {
  return brandLogoSrc(readCache(), currentAccount(), fallback);
}

// Asks the cloud for the current logo. `force` re-asks even if this account was refreshed during this
// run (the home page on every show and pull-down); other pages only ask once per run. Never rejects:
// a failure keeps what is shown.
export function refreshBrandLogo(force = false): Promise<void> {
  const account = currentAccount();
  if (!account) return Promise.resolve();
  if (inFlight && inFlight.account === account) return inFlight.promise;
  if (!force && fetchedFor === account) return Promise.resolve();
  const known = knownVersion(readCache(), account);
  const promise = get<unknown>('/api/me/brand-logo', known ? { known } : undefined)
    .then((body) => {
      const step = brandLogoStep({
        requestAccount: account,
        currentAccount: currentAccount(),
        cache: readCache(),
        answer: parseBrandLogo(body),
      });
      if (step.kind === 'drop') return;
      fetchedFor = account;
      if (step.kind === 'default') writeCache(null);
      else if (step.kind === 'store') {
        writeCache({ account, version: step.version, src: storePicture(account, step.version, step.data) });
      }
    })
    .catch(() => {
      // offline or an old cloud: keep what is shown
    })
    .finally(() => {
      if (inFlight?.promise === promise) inFlight = null;
    });
  inFlight = { account, promise };
  return promise;
}

// A picture that fails to load is forgotten, so the default shows and the next refresh fetches it again.
export function forgetBrokenBrandLogo(src: string): void {
  const c = readCache();
  if (c && c.src === src) {
    writeCache(null);
    fetchedFor = '';
  }
}
