// The send-to-device flow shared by 储罐设置 and 参数修正 (miniapp#6). Uses `wx`; the decisions are in
// remoteConfig.ts (pure) and stationDevice.ts commandGuard.
//
// One confirmation = one requestId. A timed-out submission is first looked up by that id; if it cannot be
// found the user may only retry with the *same* id and the *same* content (the cloud then replays instead of
// sending twice) — never with a new one. It stays on the phone (per account, per device + tank) until the
// cloud has answered for that id, and blocks new submissions to that tank until then (issue #18 F2).
import { t } from './i18n';
import { describeError } from './errors';
import { userKeyFromToken } from './auth';
import { ApiError, get } from './request';
import {
  classifySubmit,
  newRequestId,
  pendingAfter,
  pendingStorageKey,
  readPending,
  withPending,
  withoutPending,
  type PendingSubmit,
} from './remoteConfig';
import { getToken } from './storage';
import { findByRequestId, issueConfig } from './remoteConfigApi';
import { commandGuard } from './stationDevice';
import type { ControlCommand, Device } from '../types/api';

export type ChangeLine = { label: string; before: string; after: string };

function modal(title: string, content: string, confirmText?: string, cancelText?: string): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title,
      content,
      confirmText: confirmText || t('cfgSend.ok'),
      cancelText: cancelText || t('cfgSend.cancel'),
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    });
  });
}

function openCommand(device: string, cmd: ControlCommand, tank: number) {
  wx.navigateTo({
    url: `/pages/issue-command/issue-command?device=${encodeURIComponent(device)}&commandId=${encodeURIComponent(cmd.commandId)}&tank=${tank}`,
  });
}

function pendingStore(): { key: string; value: unknown } {
  const key = pendingStorageKey(userKeyFromToken(getToken()));
  let value: unknown;
  try {
    value = wx.getStorageSync(key);
  } catch {
    value = undefined;
  }
  return { key, value };
}

function keepPending(p: PendingSubmit) {
  const { key, value } = pendingStore();
  try {
    wx.setStorageSync(key, withPending(value, p));
  } catch {
    // storage full: the in-memory retry below still reuses the id
  }
}

function dropPending(device: string, tank: number) {
  const { key, value } = pendingStore();
  try {
    wx.setStorageSync(key, withoutPending(value, device, tank));
  } catch {
    // nothing to clear
  }
}

function linesText(lines: ChangeLine[]): string {
  return lines.map((l) => t('cfgSend.line', { label: l.label, before: l.before, after: l.after })).join('\n');
}

// Sends one snapshot with its own requestId; on an unconfirmed answer looks it up and offers only a retry
// of the *same* snapshot. The snapshot is kept on the phone (per account) until the cloud has answered
// for that requestId, so 「稍后查看」 or leaving the page cannot lose it (issue #18 F2).
async function sendSnapshot(p: PendingSubmit, retry: boolean): Promise<void> {
  const { device, stationId, tank, args, reason, requestId } = p;
  wx.showLoading({ title: t('cfgSend.sending'), mask: true });
  try {
    if (!retry) {
      // Last check: is this still the station's device? A failed read blocks too (commandGuard). Only for
      // a first send: a kept snapshot may already be on the cloud, whose replay lookup comes before its
      // own expectedStationId check, so it is resent as it was and the cloud decides.
      let devices: Device[] = [];
      try {
        devices = (await get<{ rows: Device[] }>('/api/devices')).rows || [];
      } catch {
        devices = [];
      }
      const guard = commandGuard(devices, device, stationId);
      if (!guard.ok) {
        wx.hideLoading();
        if (pendingAfter('notSent') === 'clear') dropPending(device, tank);
        await modal(t('cfgSend.failTitle'), t(guard.messageKey), t('cfgSend.know'), t('cfgSend.cancel'));
        return;
      }
    }
    const cmd = await issueConfig(device, {
      method: 'config.setTankSettings',
      args: { tank, ...args },
      reason,
      requestId,
      expectedStationId: stationId,
    });
    wx.hideLoading();
    dropPending(device, tank);
    openCommand(device, cmd, tank);
  } catch (err) {
    wx.hideLoading();
    const status = err instanceof ApiError ? err.status : 0;
    const kind = classifySubmit(status, err instanceof ApiError ? err.body : undefined);
    if (pendingAfter(kind, retry) === 'clear') dropPending(device, tank);
    if (kind === 'unconfirmed') {
      if (await lookUp(p)) return;
      const again = await modal(t('cfgSend.unconfirmedTitle'), t('cfgSend.unconfirmedBody'), t('cfgSend.retrySame'), t('cfgSend.later'));
      if (again) return sendSnapshot(p, true); // same requestId, same snapshot
      return;
    }
    if (kind === 'inFlight') {
      const holder = err instanceof ApiError ? (err.body as { commandId?: string } | undefined)?.commandId : undefined;
      await modal(t('cfgSend.failTitle'), t('cfgSend.err.inFlight'), t('cfgSend.know'), t('cfgSend.cancel'));
      if (holder) openCommand(device, { commandId: holder } as ControlCommand, tank);
      return;
    }
    const text = kind === 'other' ? describeError(err) : t(`cfgSend.err.${kind}`);
    await modal(t('cfgSend.failTitle'), text, t('cfgSend.know'), t('cfgSend.cancel'));
  }
}

// Looks a kept submission up by its requestId. Found → settled, its progress opens. Returns whether found.
async function lookUp(p: PendingSubmit): Promise<boolean> {
  try {
    const found = await findByRequestId(p.device, p.requestId);
    if (found) {
      dropPending(p.device, p.tank);
      openCommand(p.device, found, p.tank);
      return true;
    }
  } catch {
    // still unconfirmed
  }
  return false;
}

// A kept submission for this tank comes first: looked up again, else offered as a retry of exactly what
// was confirmed then (never the new form under the old id, never a new id). The new form is not sent in
// this round; once the old one is settled the user confirms the current values again.
async function settlePending(p: PendingSubmit): Promise<void> {
  wx.showLoading({ title: t('cfgSend.checking'), mask: true });
  const found = await lookUp(p);
  wx.hideLoading();
  if (found) {
    wx.showToast({ title: t('cfgSend.pendingFound'), icon: 'none' });
    return;
  }
  const retry = await modal(
    t('cfgSend.pendingTitle'),
    `${t('cfgSend.pendingBody', { device: p.device, tank: p.tank })}\n${linesText(p.lines)}`,
    t('cfgSend.retrySame'),
    t('cfgSend.later'),
  );
  if (retry) await sendSnapshot(p, true);
}

// One flow at a time (issue #18 F4): a second tap on 「下发到设备」 while the confirm dialog, the send, the
// lookup or the same-id retry of the first is still open is ignored, so two taps cannot mint two requestIds.
// Released when the flow ends, whichever way. Module-level, so it also covers two pages (tank-config and
// calibration) racing on the same phone.
let busy = false;

export function sendInProgress(): boolean {
  return busy;
}

// Asks for confirmation, sends, and opens the command page on success. Returns after the flow ends.
export async function confirmAndSend(opts: {
  device: string;
  stationId: number | null;
  tank: number;
  args: Record<string, number>;
  reason: string;
  lines: ChangeLine[];
}): Promise<void> {
  if (busy) return;
  busy = true;
  try {
    await runFlow(opts);
  } finally {
    busy = false;
  }
}

async function runFlow(opts: {
  device: string;
  stationId: number | null;
  tank: number;
  args: Record<string, number>;
  reason: string;
  lines: ChangeLine[];
}): Promise<void> {
  const { device, stationId, tank, args, reason, lines } = opts;
  const kept = readPending(pendingStore().value, device, tank);
  if (kept) {
    await settlePending(kept);
    return;
  }
  const ok = await modal(
    t('cfgSend.confirmTitle'),
    `${t('cfgSend.target', { device, tank })}\n${linesText(lines)}\n${t('cfgSend.note')}`,
  );
  if (!ok) return;

  // One confirmation = one requestId, kept on the phone before it goes out (an app kill mid-request
  // must not lose it either).
  const snapshot: PendingSubmit = {
    requestId: newRequestId('mp'),
    device,
    stationId,
    tank,
    args: { ...args },
    reason,
    lines: lines.map((l) => ({ ...l })),
    savedAt: Date.now(),
  };
  keepPending(snapshot);
  await sendSnapshot(snapshot, false);
}
