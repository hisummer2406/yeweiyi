import { post } from '../../utils/request';
import type { Shift, ShiftCorrectionRequest, WriteBackAccepted } from '../../types/api';

Page({
  data: {
    device: '',
    submitting: false,
    form: {
      deviceLocalId: 0,
      workId: 0,
      time: '',
      endTime: '',
      info: '',
    },
  },

  onLoad(query: Record<string, string>) {
    const device = query.device || '';
    const record: Shift | null = query.record ? JSON.parse(decodeURIComponent(query.record)) : null;
    if (!record) {
      this.setData({ device });
      return;
    }
    this.setData({
      device,
      form: {
        deviceLocalId: record.device_local_id,
        workId: record.work_id,
        time: record.time,
        endTime: record.end_time,
        info: record.info,
      },
    });
  },

  onInput(e: WechatMiniprogram.Input) {
    const field = e.currentTarget.dataset.field as string;
    const raw = e.detail.value;
    const isNumeric = field === 'workId';
    this.setData({
      [`form.${field}`]: isNumeric ? Number(raw) : raw,
    });
  },

  onSubmit() {
    const body: ShiftCorrectionRequest = { device: this.data.device, ...this.data.form };
    this.setData({ submitting: true });
    post<WriteBackAccepted>('/api/shifts/correct', body as unknown as Record<string, unknown>)
      .then(() => {
        wx.showToast({ title: '已提交，等待设备确认', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 800);
      })
      .catch((err: Error) => {
        wx.showToast({ title: err.message, icon: 'none' });
      })
      .finally(() => this.setData({ submitting: false }));
  },
});
