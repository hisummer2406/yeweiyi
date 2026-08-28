import { post } from '../../utils/request';
import type { OilInOut, OilInOutCorrectionRequest, WriteBackAccepted } from '../../types/api';

Page({
  data: {
    device: '',
    submitting: false,
    form: {
      deviceLocalId: 0,
      oilId: 0,
      jieZhi: '',
      time: '',
      beginHeigth: 0,
      endHeigth: 0,
      beginVolum: 0,
      endVolum: 0,
      beginWeight: 0,
      endWeight: 0,
    },
  },

  onLoad(query: Record<string, string>) {
    const device = query.device || '';
    const record: OilInOut | null = query.record ? JSON.parse(decodeURIComponent(query.record)) : null;
    if (!record) {
      this.setData({ device });
      return;
    }
    this.setData({
      device,
      form: {
        deviceLocalId: record.device_local_id,
        oilId: record.oil_id,
        jieZhi: record.jiezhi,
        time: record.time,
        beginHeigth: record.begin_height,
        endHeigth: record.end_height,
        beginVolum: record.begin_volume,
        endVolum: record.end_volume,
        beginWeight: record.begin_weight,
        endWeight: record.end_weight,
      },
    });
  },

  onInput(e: WechatMiniprogram.Input) {
    const field = e.currentTarget.dataset.field as string;
    const raw = e.detail.value;
    const isNumeric = field !== 'jieZhi' && field !== 'time';
    this.setData({
      [`form.${field}`]: isNumeric ? Number(raw) : raw,
    });
  },

  onSubmit() {
    const body: OilInOutCorrectionRequest = { device: this.data.device, ...this.data.form };
    this.setData({ submitting: true });
    post<WriteBackAccepted>('/api/oil-in-out/correct', body as unknown as Record<string, unknown>)
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
