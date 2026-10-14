import { passwordLogin, wechatPhoneLogin, wechatRestore } from '../../utils/auth';
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

// Which way in the page offers: WeChat is the default; 账号密码登录 is the same single form as the
// web back office (POST /api/login) — one 手机号 / 用户名 input, for administrators. Staff have no
// password.
type Mode = 'wechat' | 'password';

const HOME = '/pages/devices/devices';

Page({
  // Set when the login was started by a device mini-program code: after signing in, go to that device.
  scene: null as string | null,

  data: {
    mode: 'wechat' as Mode,
    stage: 'ready' as Stage,
    busy: false,
    errorMsg: '',
    account: '',
    password: '',
  },

  onLoad(query: Record<string, string>) {
    this.scene = sceneFromQuery(query);
    // Bounced here by a session that was refused mid-use (e.g. customer not onboarded): say why and
    // wait for the user, rather than silently re-asking the cloud the same question.
    const notice = noticeFromQuery(query);
    if (notice) {
      this.setData({ errorMsg: notice });
      return;
    }
    if (getToken()) {
      this.enter();
      return;
    }
    // After an explicit logout the same WeChat must not be signed straight back in, or logging out
    // would do nothing. The user taps 微信登录 instead.
    if (autoLoginEnabled()) {
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
      this.setData({ errorMsg: form.message });
      return;
    }
    this.setData({ busy: true, errorMsg: '' });
    passwordLogin(form.body)
      .then(() => {
        // Do not keep the password around once it has been used.
        this.setData({ password: '' });
        this.enter();
      })
      .catch((err: Error) => {
        const status = err instanceof ApiError ? err.status : 0;
        const code = err instanceof ApiError ? codeOfBody(err.body) : undefined;
        this.setData({ errorMsg: describeLoginFailure(status, code).message });
      })
      .finally(() => this.setData({ busy: false }));
  },

  onWechatLogin() {
    if (this.data.busy) return;
    this.restore();
  },

  restore() {
    this.setData({ stage: 'restoring', busy: true, errorMsg: '' });
    wechatRestore()
      .then(() => this.enter())
      .catch((err: Error) => this.fail(err, 'restore'))
      .finally(() => this.setData({ busy: false }));
  },

  // <button open-type="getPhoneNumber">. The authorization code arrives in e.detail.code, valid once.
  onGetPhoneNumber(e: WechatMiniprogram.CustomEvent) {
    if (this.data.busy) return;
    const result = classifyPhoneAuth(e.detail);
    if (result.kind === 'denied') {
      // Declining is a normal answer: stay here, no error, free to try again.
      this.setData({ errorMsg: '需要授权手机号才能登录' });
      return;
    }
    if (result.kind === 'failed') {
      this.setData({ errorMsg: result.message });
      return;
    }
    this.setData({ busy: true, errorMsg: '' });
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
      this.setData({ stage: 'needPhone', errorMsg: '' });
      return;
    }
    // Stay on this page and let the user decide to retry; nothing retries by itself, so a broken
    // WeChat or cloud can never turn into a request loop.
    this.setData({ stage: from === 'phone' ? 'needPhone' : 'ready', errorMsg: failure.message });
  },
});
