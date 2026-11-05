import { t } from './i18n';

// Features the cloud or the device has no capability for yet all answer with this one line: developer
// notes such as "the protocol has no such method" are never shown to users. The specific reason of
// each entry lives in its own comment and in openspec, not on screen.
export function showComingSoon(): void {
  wx.showToast({ title: t('common.comingSoon'), icon: 'none', duration: 2000 });
}
