import { roleFromToken, userKeyFromToken } from '../../utils/auth';
import { maskPhone } from '../../utils/loginFlow';
import { setNavTitle, texts } from '../../utils/i18n';
import { ROLE_KEYS, label } from '../../utils/labels';
import { clearToken, getProfile, getToken, setAutoLogin } from '../../utils/storage';
import { brandLogoFor, forgetBrokenBrandLogo, refreshBrandLogo } from '../../utils/brandLogo';
import { DEFAULT_BRAND_AVATAR } from '../../utils/brandLogoCore';

Page({
  data: {
    // 客户品牌 Logo（#86），未配置/失败时为默认头像
    brandAvatar: DEFAULT_BRAND_AVATAR,
    text: texts('profile'),
    // The account is shown by its masked phone number (the full number is not needed on screen), or by its
    // username when it has no phone number.
    account: '',
    roleLabel: '',
  },

  onShow() {
    this.showBrandLogo();
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

  showBrandLogo() {
    const token = getToken();
    const account = token ? userKeyFromToken(token) : '';
    this.setData({ brandAvatar: brandLogoFor(DEFAULT_BRAND_AVATAR) });
    void refreshBrandLogo().then(() => {
      const now = getToken();
      if (!now || userKeyFromToken(now) !== account) return; // signed out or another account meanwhile
      const brandAvatar = brandLogoFor(DEFAULT_BRAND_AVATAR);
      if (brandAvatar !== this.data.brandAvatar) this.setData({ brandAvatar });
    });
  },

  onBrandLogoError() {
    forgetBrokenBrandLogo(this.data.brandAvatar);
    this.setData({ brandAvatar: DEFAULT_BRAND_AVATAR });
  },

  onLogout() {
    // Remember that the user chose to leave, so the login page does not sign this WeChat straight
    // back in (the cloud would restore it).
    setAutoLogin(false);
    clearToken();
    wx.reLaunch({ url: '/pages/login/login' });
  },
});
