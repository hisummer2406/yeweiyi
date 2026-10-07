import { login } from '../../utils/auth';
import { ApiError } from '../../utils/request';
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
    const username = this.data.username.trim();
    const { password } = this.data;
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
        // 400 here is the cloud's request-binding failure (a Go validator string), not something a
        // user can act on; every other message is a human string from the cloud and is shown as is.
        const msg = err instanceof ApiError && err.status === 400 ? '请输入账号和密码' : err.message;
        this.setData({ errorMsg: msg });
      })
      .finally(() => {
        this.setData({ loading: false });
      });
  },
});
