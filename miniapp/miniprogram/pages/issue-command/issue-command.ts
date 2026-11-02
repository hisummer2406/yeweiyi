import { describeError } from '../../utils/errors';
import { commandOutcomeUnknown } from '../../utils/failureState';
import { setNavTitle, t, texts } from '../../utils/i18n';
import { ApiError, get, post } from '../../utils/request';
import { commandGuard, stationIdFromKey } from '../../utils/stationDevice';
import { COMMAND_STATUS_KEYS, CONTROL_METHODS, label, type ControlMethodSpec } from '../../utils/labels';
import { roleFromToken } from '../../utils/auth';
import { getToken } from '../../utils/storage';
import type { ControlCommand, Device } from '../../types/api';

// customer_user may only issue PermControl methods (yeweiyi-cloud internal/protocol.PermControl);
// everything flagged adminOnly here needs customer_admin or platform_admin. Filtering client-side
// is a UX convenience only — the real check is server-side (control.Service.authorize) and a
// filtered-out method would still 403 if called directly, this just avoids offering a button that
// is guaranteed to fail.
function methodsForRole(role: string): ControlMethodSpec[] {
  if (role === 'customer_admin' || role === 'platform_admin') {
    return CONTROL_METHODS;
  }
  return CONTROL_METHODS.filter((m) => !m.adminOnly);
}

// The last check before sending refused the command (the station's device changed, or the check itself could
// not be made): not an API error, so it carries its own i18n key.
class Blocked extends Error {
  messageKey: string;

  constructor(messageKey: string) {
    super(messageKey);
    this.messageKey = messageKey;
  }
}

type MethodRow = ControlMethodSpec & { label: string };

Page({
  // Station the detail page was opened under (see device-detail onIssueCommand); undefined when it was
  // opened by device name alone.
  stationId: undefined as number | null | undefined,

  data: {
    device: '',
    text: texts('issueCmd'),
    methods: [] as MethodRow[],
    methodIndex: 0,
    reason: '',
    // Raw JSON textarea for args, same pattern the retired oil-can-set page used for its opaque
    // schema. Not incidental here either: config.setTank/config.setProbe's argument shapes are
    // deliberately undefined on the cloud side until the device implements a handler for them
    // (yeweiyi-cloud docs/protocol-v1.md §12), so this app cannot build a typed form for them
    // without guessing at a shape nobody has settled on.
    argsText: '{}',
    submitting: false,
    errorMsg: '',
  },

  onLoad(query: Record<string, string>) {
    const token = getToken();
    const role = token ? roleFromToken(token) : '';
    this.stationId = stationIdFromKey(query.station);
    this.setData({
      device: query.device || '',
      methods: methodsForRole(role).map((m) => ({ ...m, label: t(m.labelKey) })),
    });
  },

  onShow() {
    setNavTitle('issueCmd.navTitle');
  },

  onMethodChange(e: WechatMiniprogram.PickerChange) {
    this.setData({ methodIndex: Number(e.detail.value) });
  },

  onReasonInput(e: WechatMiniprogram.Input) {
    this.setData({ reason: e.detail.value });
  },

  onArgsInput(e: WechatMiniprogram.Input) {
    this.setData({ argsText: e.detail.value });
  },

  onSubmit() {
    const method = this.data.methods[this.data.methodIndex];
    if (!method) {
      this.setData({ errorMsg: t('issueCmd.errPick') });
      return;
    }
    // Required by the protocol, the database constraint, and internal/control — a whitespace-only
    // reason is rejected server-side too, checked here only so the round trip isn't wasted.
    const reason = this.data.reason.trim();
    if (!reason) {
      this.setData({ errorMsg: t('issueCmd.errReason') });
      return;
    }
    let args: Record<string, unknown> | undefined;
    const text = this.data.argsText.trim();
    if (text) {
      try {
        args = JSON.parse(text);
      } catch {
        this.setData({ errorMsg: t('issueCmd.errJson') });
        return;
      }
    }

    if (this.data.submitting) return;
    this.setData({ submitting: true, errorMsg: '' });
    const device = this.data.device as string;
    // Only a failure of the POST itself can leave the outcome unknown: if the device-list read below fails,
    // nothing was sent, and telling the user to check the command history would be wrong.
    let posted = false;
    // Re-read the device list first: the station's device may have been replaced while this form was
    // open, and the replaced device can still be visible to this user. If it cannot be confirmed, the
    // command is not sent (a control command is not something to send on a guess).
    get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        const guard = commandGuard(res.rows, device, this.stationId);
        if (!guard.ok) throw new Blocked(guard.messageKey);
        posted = true;
        return post<ControlCommand>(`/api/devices/${encodeURIComponent(device)}/commands`, {
          method: method.method,
          reason,
          args,
        });
      })
      .then((cmd) => {
        // 202 Accepted server-side (yeweiyi-cloud CLAUDE.md: "202, never 200" — this only reports
        // how far the command got synchronously, not that it finished). Report that state honestly
        // rather than a blanket "success".
        wx.showToast({
          title: t('issueCmd.submitted', { status: label(COMMAND_STATUS_KEYS, cmd.status) }),
          icon: 'none',
        });
        wx.navigateBack();
      })
      .catch((err: Error) => {
        if (err instanceof Blocked) {
          this.setData({ errorMsg: t(err.messageKey) });
        } else if (posted && err instanceof ApiError && commandOutcomeUnknown(err.status, err.body)) {
          // The device may be acting on it (yeweiyi-cloud invariant 2): send the user to the command list,
          // never suggest sending it again.
          this.setData({ errorMsg: t('issueCmd.unconfirmed') });
        } else if (err instanceof ApiError && err.status === 409) {
          // The cloud refused it in the current state, e.g. a physical action that stays disabled.
          this.setData({ errorMsg: t('issueCmd.refused') });
        } else {
          this.setData({ errorMsg: describeError(err) });
        }
      })
      .finally(() => this.setData({ submitting: false }));
  },
});
