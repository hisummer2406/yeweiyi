import { roleFromToken } from '../../utils/auth';
import { maskPhone } from '../../utils/loginFlow';
import { getProfile, getToken } from '../../utils/storage';

const ROLE_LABELS: Record<string, string> = {
  platform_admin: '平台管理员',
  customer_admin: '客户管理员',
  customer_user: '客户普通用户',
};

type MoreItem = {
  title: string;
  icon: string; // /assets/icons/{icon}.png，直接从设计稿 PSD 的「组17」画板抠出来的切图
  reason: string;
  page?: string;
};

// 对应设计稿「首页-设置05」画板，就这 6 项——交班/库存记录属于「我的记录」画板，
// 员工管理/邀请加入/油站选择属于首页"+"号的添加菜单，都不在这个列表里。
const ITEMS: MoreItem[] = [
  {
    title: '参数修正',
    icon: 'settings-calibration',
    reason: '传感器读数校准，协议里没有对应的下发方法',
    page: '/pages/calibration/calibration',
  },
  {
    title: '储罐设置',
    icon: 'settings-tank',
    reason: 'config.setTank / config.setAlarmThresholds 已有方法名，参数结构未定义',
    page: '/pages/tank-config/tank-config',
  },
  {
    title: '罐表设置',
    icon: 'settings-tankshape',
    reason: '下发换算表是全新能力，协议里没有对应方法',
    page: '/pages/tank-shape/tank-shape',
  },
  {
    title: '记录设置',
    icon: 'settings-records',
    reason: '设计稿有画板，云端无对应接口，且涉及不可恢复的清空操作',
    page: '/pages/record-settings/record-settings',
  },
  {
    title: '报表设置',
    // 设计稿里报表设置复用的就是罐表设置那个图标，不是我漏抠
    icon: 'settings-tankshape',
    reason: '设计稿有画板，云端无导出接口',
    page: '/pages/report-settings/report-settings',
  },
  {
    title: '补录记录',
    icon: 'settings-backfill',
    reason: '新增漏报记录，云端无对应接口（不同于记录修正）',
    page: '/pages/backfill/backfill',
  },
];

Page({
  data: {
    items: ITEMS,
    account: '',
    roleLabel: '',
  },

  onShow() {
    const token = getToken();
    const role = token ? roleFromToken(token) : '';
    this.setData({
      account: token ? maskPhone(getProfile()?.phone) : '',
      roleLabel: ROLE_LABELS[role] || role,
    });
  },

  onItemTap(e: WechatMiniprogram.BaseEvent) {
    const page = e.currentTarget.dataset.page as string;
    const reason = e.currentTarget.dataset.reason as string;
    if (page) {
      wx.navigateTo({ url: page });
      return;
    }
    wx.showToast({ title: reason, icon: 'none', duration: 3000 });
  },

  onAccountTap() {
    wx.navigateTo({ url: '/pages/profile/profile' });
  },
});

export {};
