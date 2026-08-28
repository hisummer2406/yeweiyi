import { usernameFromToken } from '../../utils/auth';
import { clearToken, getToken } from '../../utils/storage';

Page({
  data: {
    username: '',
  },

  onShow() {
    const token = getToken();
    this.setData({ username: token ? usernameFromToken(token) : '' });
  },

  onLogout() {
    clearToken();
    wx.reLaunch({ url: '/pages/login/login' });
  },
});
