// 云端或设备端还没有对应能力的功能，点了统一提示这一句：不把"协议里没有…""云端没有接口"这类开发备注展示给用户。
// 各入口的具体原因写在各自的注释和 openspec 里，不在界面上说。
export const COMING_SOON_TEXT = '功能开发中，敬请期待';

export function showComingSoon(): void {
  wx.showToast({ title: COMING_SOON_TEXT, icon: 'none', duration: 2000 });
}
