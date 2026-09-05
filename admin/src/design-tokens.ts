/**
 * 中成液位系统 · 设计令牌（Ant Design / UmiJS 消费版）
 *
 * 来源: docs/design-language/DESIGN_LANGUAGE.md（程序化抽取自 docs/中成系统.psd）
 * 用法:
 *   1) Umi 运行时主题（config/config.ts 的 antd 插件）:
 *      import { antdTheme } from '@/design-tokens';
 *      antd: { theme: antdTheme }
 *   2) 组件内取色: import { dl } from '@/design-tokens';
 *      style={{ background: dl.brand.primary }}
 *
 * 坐标换算基准: 设计稿 1280x800 = 100vw/100vh。
 */

/* ---------- 原始令牌（与 design_tokens.json 保持同构） ---------- */
export const dl = {
  color: {
    bgPage: '#E8F8F8',
    bgSurface: '#F8F8F8',
    bgSurfaceAlt: '#E8E8E8',
    bgMask: 'rgba(184, 184, 184, 0.5)',

    primary: '#086898',
    primaryDeep: '#085878',
    primaryDark: '#084868',
    sky: '#C8E8F8',

    textPrimary: '#3C3C3C',
    textSecondary: '#5C5C5C',
    textTertiary: '#999999',
    textInverse: '#FFFFFF',
    textOnDark: '#EDEDED',
    textBrandDark: '#002C47',

    accentCyan: '#45BAFF',
    border: '#D8D8D8',
  },
  font: {
    family: "'MiSans', 'PingFang SC', 'Microsoft YaHei', sans-serif",
    familyNumeric: "'Arial', 'DIN Alternate', sans-serif",
    familyDisplay: "'YouSheBiaoTiHei', 'MiSans', sans-serif",
    size: {
      caption: 12,
      body: 13,
      bodyLg: 14,
      subtitle: 16,
      title: 18,
      display: 20,
      displayLg: 24,
    },
  },
  layout: {
    canvasW: 1280,
    canvasH: 800,
    headerH: 84,
    footerNavH: 65,
    contentPadX: 36,
    tableRowH: 60,
    cardW: 375,
    cardH: 200,
    cardGap: 28,
  },
} as const;

/* ---------- 映射为 antd v5 token（css var 之外的 js 接口） ---------- */
export const antdTheme = {
  token: {
    colorPrimary: dl.color.primary,
    colorInfo: dl.color.primary,
    colorSuccess: '#2E9E6B', // PSD 未含成功态，工业惯例绿，待产品确认
    colorWarning: '#E6A23C', // PSD 未含警告态，暂用 antd 默认
    colorError: '#D93026',   // PSD 未含错误态，暂用工业红，待产品确认
    colorBgLayout: dl.color.bgPage,
    colorBgContainer: dl.color.bgSurface,
    colorText: dl.color.textPrimary,
    colorTextSecondary: dl.color.textSecondary,
    colorTextTertiary: dl.color.textTertiary,
    borderRadius: 4,
    fontFamily: dl.font.family,
    fontSize: 13, // PSD 表格正文 12.8 -> 13
    controlHeight: 40,      // 触屏加高
    lineWidth: 1,
    colorBorder: dl.color.border,
    colorBorderSecondary: '#E8E8E8',
  },
  components: {
    Table: {
      headerBg: dl.color.primary,
      headerColor: dl.color.textInverse,
      cellPaddingBlock: 18, // 行高≈60 = 13px 文字 + 18*2 padding
      rowHoverBg: dl.color.sky,
    },
    Layout: {
      headerBg: dl.color.primary,
      headerHeight: 84,
      headerPadding: '0 24px',
    },
    Menu: {
      itemHeight: 48,
      itemSelectedBg: dl.color.sky,
      itemSelectedColor: dl.color.primaryDeep,
    },
    Modal: {
      contentBg: dl.color.bgSurface,
      titleFontSize: dl.font.size.subtitle,
    },
    Button: {
      borderRadius: 4,
      controlHeight: 44,
      fontWeight: 500,
    },
  },
} as const;

export default dl;
