import { roleFromToken, usernameFromToken } from '../../utils/auth';
import { clearToken, getToken } from '../../utils/storage';

const ROLE_LABELS: Record<string, string> = {
  platform_admin: '平台管理员',
  customer_admin: '客户管理员',
  customer_user: '客户普通用户',
};

Page({
  data: {
    username: '',
    roleLabel: '',
  },

  onShow() {
    const token = getToken();
    const role = token ? roleFromToken(token) : '';
    this.setData({
      username: token ? usernameFromToken(token) : '',
      roleLabel: ROLE_LABELS[role] || role,
    });
  },

  onLogout() {
    clearToken();
    wx.reLaunch({ url: '/pages/login/login' });
  },
});
