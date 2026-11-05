import { passwordLogin, wechatPhoneLogin, wechatRestore } from '../../utils/auth';
import { LOCALE_NAMES, currentLocale, setNavTitle, t, texts } from '../../utils/i18n';
import { WECHAT_LOGIN } from '../../utils/platform';
import {
  buildPasswordLogin,
  classifyPhoneAuth,
  codeOfBody,
  describeLoginFailure,
  noticeFromQuery,
} from '../../utils/loginFlow';
import { ApiError } from '../../utils/request';
import { autoLoginEnabled, getToken } from '../../utils/storage';
import { detailUrlForScene, sceneFromQuery } from '../../utils/sceneEntry';

// Where the login page is in the flow:
//   restoring - a silent attempt for an already-bound WeChat is in flight (once per page load)
//   ready     - nothing in flight; the user taps 微信登录 (after 退出登录, or a failed silent attempt)
//   needPhone - this WeChat is not bound yet: one phone authorization binds it to the account the
//               administrator provisioned for that number
type Stage = 'restoring' | 'ready' | 'needPhone';

// Which way in the page offers: in the mini-program WeChat is the default and the password form is a
// second tab; in the app there is no WeChat (utils/platform.ts) and the password form is all there is.
// The password form is the same single input as the web back office (POST /api/login): the account
// (phone number or username) plus the password.
type Mode = 'wechat' | 'password';

const HOME = '/pages/devices/devices';

Page({
  // Set when the login was started by a device mini-program code: after signing in, go to that device.
  scene: null as string | null,

  // The i18n key of the message on screen, kept so it can be worded again when the language changes.
  errorKey: '',

  data: {
    text: {} as Record<string, string>,
    wechatLogin: WECHAT_LOGIN,
    mode: (WECHAT_LOGIN ? 'wechat' : 'password') as Mode,
    stage: 'ready' as Stage,
    busy: false,
    errorMsg: '',
    account: '',
    password: '',
    langLabel: '',
    langName: '',
  },

  onShow() {
    this.renderTexts();
  },

  renderTexts() {
    setNavTitle('login.navTitle');
    this.setData({
      text: texts('login'),
      errorMsg: this.errorKey ? t(this.errorKey) : '',
      langLabel: t('lang.label'),
      langName: LOCALE_NAMES[currentLocale()],
    });
  },

  // Shows a message by key (or clears it with ''), in the language of the moment.
  showError(key: string) {
    this.errorKey = key;
    this.setData({ errorMsg: key ? t(key) : '' });
  },

  onLanguageTap() {
    wx.navigateTo({ url: '/pages/language/language?from=login' });
  },

  onLoad(query: Record<string, string>) {
    this.scene = sceneFromQuery(query);
    // Bounced here by a session that was refused mid-use (e.g. customer not onboarded): say why and
    // wait for the user, rather than silently re-asking the cloud the same question.
    const notice = noticeFromQuery(query);
    if (notice) {
      this.showError(notice);
      return;
    }
    if (getToken()) {
      this.enter();
      return;
    }
    // After an explicit logout the same WeChat must not be signed straight back in, or logging out
    // would do nothing. The user taps the WeChat button instead. Never in the app: no WeChat there.
    if (WECHAT_LOGIN && autoLoginEnabled()) {
      this.restore();
    }
  },

  enter() {
    if (this.scene) {
      wx.reLaunch({ url: detailUrlForScene(this.scene) });
      return;
    }
    wx.switchTab({ url: HOME });
  },

  onSwitchMode(e: WechatMiniprogram.TouchEvent) {
    if (this.data.busy) return;
    const mode = e.currentTarget.dataset.mode as Mode;
    if (mode === this.data.mode) return;
    this.errorKey = '';
    this.setData({ mode, errorMsg: '', account: '', password: '' });
  },

  onAccountInput(e: WechatMiniprogram.Input) {
    this.setData({ account: e.detail.value });
  },

  onPasswordInput(e: WechatMiniprogram.Input) {
    this.setData({ password: e.detail.value });
  },

  onPasswordLogin() {
    if (this.data.busy) return;
    const form = buildPasswordLogin(this.data.account, this.data.password);
    if (!form.ok) {
      this.showError(form.messageKey);
      return;
    }
    this.showError('');
    this.setData({ busy: true });
    passwordLogin(form.body)
      .then(() => {
        // Do not keep the password around once it has been used.
        this.setData({ password: '' });
        this.enter();
      })
      .catch((err: Error) => {
        const status = err instanceof ApiError ? err.status : 0;
        const code = err instanceof ApiError ? codeOfBody(err.body) : undefined;
        this.showError(describeLoginFailure(status, code).messageKey || 'loginErr.unknown');
      })
      .finally(() => this.setData({ busy: false }));
  },

  onWechatLogin() {
    if (!WECHAT_LOGIN || this.data.busy) return;
    this.restore();
  },

  restore() {
    this.showError('');
    this.setData({ stage: 'restoring', busy: true });
    wechatRestore()
      .then(() => this.enter())
      .catch((err: Error) => this.fail(err, 'restore'))
      .finally(() => this.setData({ busy: false }));
  },

  // <button open-type="getPhoneNumber">. The authorization code arrives in e.detail.code, valid once.
  onGetPhoneNumber(e: WechatMiniprogram.CustomEvent) {
    if (!WECHAT_LOGIN || this.data.busy) return;
    const result = classifyPhoneAuth(e.detail);
    if (result.kind === 'denied') {
      // Declining is a normal answer: stay here, no error, free to try again.
      this.showError('loginErr.phoneDenied');
      return;
    }
    if (result.kind === 'failed') {
      this.showError(result.messageKey);
      return;
    }
    this.showError('');
    this.setData({ busy: true });
    wechatPhoneLogin(result.code)
      .then(() => this.enter())
      .catch((err: Error) => this.fail(err, 'phone'))
      .finally(() => this.setData({ busy: false }));
  },

  fail(err: Error, from: 'restore' | 'phone') {
    const status = err instanceof ApiError ? err.status : 0;
    const code = err instanceof ApiError ? codeOfBody(err.body) : undefined;
    const failure = describeLoginFailure(status, code);
    if (failure.offerPhone) {
      // Not bound yet: show the phone button. Not an error to display.
      this.showError('');
      this.setData({ stage: 'needPhone' });
      return;
    }
    // Stay on this page and let the user decide to retry; nothing retries by itself, so a broken
    // WeChat or cloud can never turn into a request loop.
    this.showError(failure.messageKey || 'loginErr.unknown');
    this.setData({ stage: from === 'phone' ? 'needPhone' : 'ready' });
  },
});
