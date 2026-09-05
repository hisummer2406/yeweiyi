import { PageContainer } from '@ant-design/pro-components';

/**
 * 进销存记录——对接 yeweiyi-cloud 的 GET /api/oil-in-out。
 * 该接口目前会返回 unparsable/legacySkipped 字段，需要在 UI 上如实展示降级信息，
 * 不能假装全部记录都解析成功（见 yeweiyi-cloud CLAUDE.md 未定案事项）。
 */
const OilInOut: React.FC = () => {
  return <PageContainer title="进销存记录" />;
};

export default OilInOut;
