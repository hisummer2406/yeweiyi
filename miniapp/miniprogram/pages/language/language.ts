import { LOCALES, LOCALE_NAMES, chosenLocale, setLocale, setNavTitle, systemLocale, t } from '../../utils/i18n';
import type { Locale } from '../../utils/i18n';

// The one place to choose the language, opened from the settings page and from the login page
// (`?from=login` | `?from=more`). Every language is written in itself so a user can find theirs without
// reading the current one; "follow system" is the way back to the phone's language.
const SYSTEM = 'system';

Page({
  from: '',

  data: {
    rows: [] as { value: string; name: string; hint: string }[],
    selected: SYSTEM,
  },

  onLoad(query: Record<string, string | undefined>) {
    this.from = query.from || '';
  },

  onShow() {
    this.render();
  },

  render() {
    setNavTitle('lang.navTitle');
    this.setData({
      selected: chosenLocale() || SYSTEM,
      rows: [
        { value: SYSTEM, name: t('lang.system'), hint: LOCALE_NAMES[systemLocale()] },
        ...LOCALES.map((l) => ({ value: l, name: LOCALE_NAMES[l], hint: '' })),
      ],
    });
  },

  onPick(e: WechatMiniprogram.BaseEvent) {
    const value = e.currentTarget.dataset.value as string;
    if (value === this.data.selected) return;
    setLocale(value === SYSTEM ? SYSTEM : (value as Locale));
    this.render();
    if (this.from === 'more') {
      // Pages already open hold their texts in their data, so start over from the settings page.
      wx.reLaunch({ url: '/pages/more/more' });
    } else {
      // The login page redraws itself when it is shown again; nothing else is open before signing in.
      wx.navigateBack({ fail: () => wx.reLaunch({ url: '/pages/login/login' }) });
    }
  },
});

export {};
