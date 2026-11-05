// Where this code is running, decided at build time by Donut's conditional compilation
// (`// #if MP` ... `// #endif`, openspec design.md D1). MP = the WeChat mini-program; the Android app
// is a different target. The conditional-compilation switch must be on in WeChat DevTools
// (详情 → 本地设置 → 启用条件编译) or the line below runs in every build.
//
// The default is "no WeChat": only the mini-program build turns it on, so an app build can never
// show a WeChat entry by accident of a missing branch. If conditional compilation is not processed
// the app would show the entry; the first real-device check (tasks 2.3 / 4.1) catches that.
function detectWechatLogin(): boolean {
  let available = false;
  // #if MP
  available = true;
  // #endif
  return available;
}

// WeChat sign-in (wx.login, getPhoneNumber) exists only in the mini-program. In the app nothing may
// call or show it.
export const WECHAT_LOGIN = detectWechatLogin();
