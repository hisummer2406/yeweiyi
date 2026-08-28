import { post } from '../../utils/request';
import type { WriteBackAccepted } from '../../types/api';

Page({
  data: {
    device: '',
    payload: '{\n  \n}',
    submitting: false,
  },

  onLoad(query: Record<string, string>) {
    this.setData({ device: query.device || '' });
  },

  onInput(e: WechatMiniprogram.Input) {
    this.setData({ payload: e.detail.value });
  },

  onSubmit() {
    let body: Record<string, unknown>;
    try {
      body = JSON.parse(this.data.payload);
    } catch {
      wx.showToast({ title: 'JSON 格式不合法', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    post<WriteBackAccepted>(`/api/devices/${encodeURIComponent(this.data.device)}/oil-can-set`, body)
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
