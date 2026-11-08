// The send-to-device flow shared by 储罐设置 and 参数修正 (miniapp#6). Uses `wx`; the decisions are in
// remoteConfig.ts (pure) and stationDevice.ts commandGuard.
//
// One confirmation = one requestId. A timed-out submission is first looked up by that id; if it cannot be
// found the user may only retry with the *same* id (the cloud then replays instead of sending twice) —
// never with a new one.
import { t } from './i18n';
import { describeError } from './errors';
import { ApiError, get } from './request';
import { classifySubmit, newRequestId } from './remoteConfig';
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

// Asks for confirmation, sends, and opens the command page on success. Returns after the flow ends.
export async function confirmAndSend(opts: {
  device: string;
  stationId: number | null;
  tank: number;
  args: Record<string, number>;
  reason: string;
  lines: ChangeLine[];
}): Promise<void> {
  const { device, stationId, tank, args, reason, lines } = opts;
  const body = lines.map((l) => t('cfgSend.line', { label: l.label, before: l.before, after: l.after })).join('\n');
  const ok = await modal(
    t('cfgSend.confirmTitle'),
    `${t('cfgSend.target', { device, tank })}\n${body}\n${t('cfgSend.note')}`,
  );
  if (!ok) return;

  const requestId = newRequestId('mp');
  const send = async (): Promise<void> => {
    wx.showLoading({ title: t('cfgSend.sending'), mask: true });
    try {
      // Last check: is this still the station's device? A failed read blocks too (commandGuard).
      let devices: Device[] = [];
      try {
        devices = (await get<{ rows: Device[] }>('/api/devices')).rows || [];
      } catch {
        devices = [];
      }
      const guard = commandGuard(devices, device, stationId);
      if (!guard.ok) {
        wx.hideLoading();
        await modal(t('cfgSend.failTitle'), t(guard.messageKey), t('cfgSend.know'), t('cfgSend.cancel'));
        return;
      }
      const cmd = await issueConfig(device, {
        method: 'config.setTankSettings',
        args: { tank, ...args },
        reason,
        requestId,
        expectedStationId: stationId,
      });
      wx.hideLoading();
      openCommand(device, cmd, tank);
    } catch (err) {
      wx.hideLoading();
      const status = err instanceof ApiError ? err.status : 0;
      const kind = classifySubmit(status, err instanceof ApiError ? err.body : undefined);
      if (kind === 'unconfirmed') {
        try {
          const found = await findByRequestId(device, requestId);
          if (found) {
            openCommand(device, found, tank);
            return;
          }
        } catch {
          // still unconfirmed
        }
        const retry = await modal(t('cfgSend.unconfirmedTitle'), t('cfgSend.unconfirmedBody'), t('cfgSend.retrySame'), t('cfgSend.later'));
        if (retry) return send(); // same requestId
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
  };
  await send();
}
