import { PageContainer } from '@ant-design/pro-components';
import { useIntl, useLocation } from '@umijs/max';
import DevicePicker from '../components/DevicePicker';
import StockRecords from '../components/StockRecords';

/**
 * 出入库记录——对接 yeweiyi-cloud 的 GET /api/oil-in-out，入库/出库两个菜单入口共用本页，
 * 由路径决定方向（对应设备端底栏「入库记录」「出库记录」与小程序的 direction=IN/OUT）。
 * 云端没有方向过滤，所以方向在前端过滤。该接口会返回 unparsable/legacySkipped 等降级信息，
 * 由 StockRecords 如实展示，不能假装全部记录都解析成功（见 yeweiyi-cloud CLAUDE.md 未定案事项）。
 */
const OilInOut: React.FC = () => {
  const intl = useIntl();
  const { pathname } = useLocation();
  const out = pathname.endsWith('/stock-out');
  return (
    <PageContainer
      title={intl.formatMessage({
        id: out
          ? 'pages.console.records.title.stockOut'
          : 'pages.console.records.title.stockIn',
      })}
    >
      <DevicePicker>
        {(device) => (
          <StockRecords device={device} direction={out ? 'OUT' : 'IN'} />
        )}
      </DevicePicker>
    </PageContainer>
  );
};

export default OilInOut;
