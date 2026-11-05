import { roleFromToken } from '../../utils/auth';
import { maskPhone } from '../../utils/loginFlow';
import { setNavTitle, texts } from '../../utils/i18n';
import { ROLE_KEYS, label } from '../../utils/labels';
import { clearToken, getProfile, getToken, setAutoLogin } from '../../utils/storage';

Page({
  data: {
    text: texts('profile'),
    // The account is shown by its masked phone number (the full number is not needed on screen), or by its
    // username when it has no phone number.
    account: '',
    roleLabel: '',
  },

  onShow() {
    const token = getToken();
    const role = token ? roleFromToken(token) : '';
    setNavTitle('profile.navTitle');
    const profile = getProfile();
    this.setData({
      text: texts('profile'),
      account: maskPhone(profile?.phone) || profile?.username || '',
      roleLabel: role ? label(ROLE_KEYS, role) : '',
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
