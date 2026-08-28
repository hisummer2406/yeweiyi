import { login } from '../../utils/auth';
import { getToken } from '../../utils/storage';

Page({
  data: {
    username: '',
    password: '',
    loading: false,
    errorMsg: '',
  },

  onLoad() {
    if (getToken()) {
      wx.switchTab({ url: '/pages/devices/devices' });
    }
  },

  onUsernameInput(e: WechatMiniprogram.Input) {
    this.setData({ username: e.detail.value });
  },

  onPasswordInput(e: WechatMiniprogram.Input) {
    this.setData({ password: e.detail.value });
  },

  onSubmit() {
    const { username, password } = this.data;
    if (!username || !password) {
      this.setData({ errorMsg: '请输入账号和密码' });
      return;
    }
    this.setData({ loading: true, errorMsg: '' });
    login(username, password)
      .then(() => {
        wx.switchTab({ url: '/pages/devices/devices' });
      })
      .catch((err: Error) => {
        this.setData({ errorMsg: err.message });
      })
      .finally(() => {
        this.setData({ loading: false });
      });
  },
});
