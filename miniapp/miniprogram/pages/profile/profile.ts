import { roleFromToken } from '../../utils/auth';
import { maskPhone } from '../../utils/loginFlow';
import { clearToken, getProfile, getToken, setAutoLogin } from '../../utils/storage';

const ROLE_LABELS: Record<string, string> = {
  platform_admin: '平台管理员',
  customer_admin: '客户管理员',
  customer_user: '客户普通用户',
};

Page({
  data: {
    // The account is shown by its masked phone number: the full number is not needed on screen.
    account: '',
    roleLabel: '',
  },

  onShow() {
    const token = getToken();
    const role = token ? roleFromToken(token) : '';
    this.setData({
      account: maskPhone(getProfile()?.phone),
      roleLabel: ROLE_LABELS[role] || role,
    });
  },

  onLogout() {
    // Remember that the user chose to leave, so the login page does not sign this WeChat straight
    // back in (the cloud would restore it).
    setAutoLogin(false);
    clearToken();
    wx.reLaunch({ url: '/pages/login/login' });
  },
});
