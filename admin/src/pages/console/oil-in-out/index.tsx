import { PageContainer } from '@ant-design/pro-components';
import { useLocation } from '@umijs/max';

/**
 * 出入库记录——对接 yeweiyi-cloud 的 GET /api/oil-in-out，入库/出库两个菜单入口共用本页，
 * 由路径决定方向（对应设备端底栏「入库记录」「出库记录」与小程序的 direction=IN/OUT）。
 * 该接口目前会返回 unparsable/legacySkipped 字段，需要在 UI 上如实展示降级信息，
 * 不能假装全部记录都解析成功（见 yeweiyi-cloud CLAUDE.md 未定案事项）。
 */
const OilInOut: React.FC = () => {
  const { pathname } = useLocation();
  const title = pathname.endsWith('/stock-out') ? '出库记录' : '入库记录';
  return <PageContainer title={title} />;
};

export default OilInOut;
