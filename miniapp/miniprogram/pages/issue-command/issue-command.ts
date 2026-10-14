import { get, post } from '../../utils/request';
import { commandGuard, stationIdFromKey } from '../../utils/stationDevice';
import { CONTROL_METHODS, type ControlMethodSpec } from '../../utils/labels';
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

Page({
  // Station the detail page was opened under (see device-detail onIssueCommand); undefined when it was
  // opened by device name alone.
  stationId: undefined as number | null | undefined,

  data: {
    device: '',
    methods: [] as ControlMethodSpec[],
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
    this.setData({ device: query.device || '', methods: methodsForRole(role) });
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
      this.setData({ errorMsg: '请选择要下发的命令' });
      return;
    }
    // Required by the protocol, the database constraint, and internal/control — a whitespace-only
    // reason is rejected server-side too, checked here only so the round trip isn't wasted.
    const reason = this.data.reason.trim();
    if (!reason) {
      this.setData({ errorMsg: '请填写操作原因（二次确认必填）' });
      return;
    }
    let args: Record<string, unknown> | undefined;
    const text = this.data.argsText.trim();
    if (text) {
      try {
        args = JSON.parse(text);
      } catch {
        this.setData({ errorMsg: '参数必须是合法 JSON' });
        return;
      }
    }

    if (this.data.submitting) return;
    this.setData({ submitting: true, errorMsg: '' });
    const device = this.data.device as string;
    // Re-read the device list first: the station's device may have been replaced while this form was
    // open, and the replaced device can still be visible to this user. If it cannot be confirmed, the
    // command is not sent (a control command is not something to send on a guess).
    get<{ rows: Device[] }>('/api/devices')
      .then((res) => {
        const guard = commandGuard(res.rows, device, this.stationId);
        if (!guard.ok) throw new Error(guard.message);
        return post<ControlCommand>(`/api/devices/${encodeURIComponent(device)}/commands`, {
          method: method.method,
          reason,
          args,
        });
      })
      .then((cmd) => {
        // 202 Accepted server-side (yeweiyi-cloud CLAUDE.md: "202, never 200" — this only reports
        // how far the command got synchronously, not that it finished). Report that state honestly
        // rather than a blanket "成功".
        wx.showToast({ title: `已提交，当前状态：${cmd.status}`, icon: 'none' });
        wx.navigateBack();
      })
      .catch((err: Error) => {
        this.setData({ errorMsg: err.message });
      })
      .finally(() => this.setData({ submitting: false }));
  },
});
