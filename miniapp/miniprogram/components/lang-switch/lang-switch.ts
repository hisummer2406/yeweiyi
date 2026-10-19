import { LOCALES, LOCALE_NAMES, currentLocale, setLocale, texts } from '../../utils/i18n';
import type { Locale } from '../../utils/i18n';

// The language picker: every language written in itself. It saves the choice and tells the page
// (`change`), which decides how to redraw. Used by the login page and the settings page.
Component({
  data: {
    current: 'en' as Locale,
    options: [] as { locale: Locale; name: string }[],
    label: '',
  },
  lifetimes: {
    attached() {
      this.setData({
        current: currentLocale(),
        options: LOCALES.map((locale) => ({ locale, name: LOCALE_NAMES[locale] })),
        label: texts('lang').label,
      });
    },
  },
  methods: {
    onPick(e: WechatMiniprogram.BaseEvent) {
      const locale = e.currentTarget.dataset.locale as Locale;
      if (locale === this.data.current) return;
      setLocale(locale);
      this.setData({ current: locale, label: texts('lang').label });
      this.triggerEvent('change', { locale });
    },
  },
});
