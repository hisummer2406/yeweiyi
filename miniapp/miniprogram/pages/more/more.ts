import { roleFromToken } from '../../utils/auth';
import { maskPhone } from '../../utils/loginFlow';
import { getProfile, getToken } from '../../utils/storage';
import { showComingSoon } from '../../utils/comingSoon';
import { LOCALE_NAMES, applyTabBar, currentLocale, setNavTitle, t, texts } from '../../utils/i18n';
import { ROLE_KEYS, label } from '../../utils/labels';

type MoreItem = {
  titleKey: string;
  icon: string; // /assets/icons/{icon}.png，直接从设计稿 PSD 的「组17」画板抠出来的切图
  page?: string;
};

// 对应设计稿「首页-设置05」画板，就这 6 项——交班/库存记录属于「我的记录」画板，
// 员工管理/邀请加入/油站选择属于首页"+"号的添加菜单，都不在这个列表里。
// Why each entry is a static page (cloud capability missing) is kept in openspec / the page's own comment,
// never on screen.
const ITEMS: MoreItem[] = [
  { titleKey: 'more.calibration', icon: 'settings-calibration', page: '/pages/calibration/calibration' },
  { titleKey: 'more.tank', icon: 'settings-tank', page: '/pages/tank-config/tank-config' },
  { titleKey: 'more.tankTable', icon: 'settings-tankshape', page: '/pages/tank-shape/tank-shape' },
  { titleKey: 'more.recordSettings', icon: 'settings-records', page: '/pages/record-settings/record-settings' },
  // 设计稿里报表设置复用的就是罐表设置那个图标，不是我漏抠
  { titleKey: 'more.reportSettings', icon: 'settings-tankshape', page: '/pages/report-settings/report-settings' },
  { titleKey: 'more.backfill', icon: 'settings-backfill', page: '/pages/backfill/backfill' },
];

Page({
  data: {
    text: texts('more'),
    items: [] as { titleKey: string; title: string; icon: string; page?: string }[],
    account: '',
    roleLabel: '',
    langLabel: '',
    langName: '',
  },

  onShow() {
    applyTabBar();
    setNavTitle('more.navTitle');
    const token = getToken();
    const role = token ? roleFromToken(token) : '';
    const profile = token ? getProfile() : undefined;
    this.setData({
      text: texts('more'),
      items: ITEMS.map((i) => ({ titleKey: i.titleKey, title: t(i.titleKey), icon: i.icon, page: i.page })),
      account: maskPhone(profile?.phone) || profile?.username || '',
      roleLabel: role ? label(ROLE_KEYS, role) : '',
      langLabel: t('lang.label'),
      langName: LOCALE_NAMES[currentLocale()],
    });
  },

  onItemTap(e: WechatMiniprogram.BaseEvent) {
    const page = e.currentTarget.dataset.page as string;
    if (page) {
      wx.navigateTo({ url: page });
      return;
    }
    showComingSoon();
  },

  onAccountTap() {
    wx.navigateTo({ url: '/pages/profile/profile' });
  },

  onLanguageTap() {
    wx.navigateTo({ url: '/pages/language/language?from=more' });
  },
});

export {};
