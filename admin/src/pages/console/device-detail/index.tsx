import { PageContainer } from '@ant-design/pro-components';
import { useIntl, useParams, useSearchParams } from '@umijs/max';
import { Tabs } from 'antd';
import type React from 'react';
import AlarmRecords from '../components/AlarmRecords';
import StockRecords from '../components/StockRecords';
import SnapshotTab from './SnapshotTab';

const TAB_KEYS = ['snapshot', 'alarms', 'stock'] as const;
type TabKey = (typeof TAB_KEYS)[number];

/** `?tab=` 取值之外（缺省、拼错）一律回到实时数据。 */
const parseTab = (v: string | null): TabKey =>
  (TAB_KEYS as readonly string[]).includes(v ?? '')
    ? (v as TabKey)
    : 'snapshot';

/**
 * 设备详情——PC 端对应小程序 pages/device-detail 的前三个页签：实时数据、报警记录、出入库记录
 * （控制命令页签随远程控制另做）。设备名取自路由 `:deviceName`（路由库已解码），
 * 记录页签复用与记录页相同的组件，设备来自路由所以不显示设备选择。
 * 非活动页签不挂载，避免三个页签同时轮询。
 */
const ConsoleDeviceDetail: React.FC = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const { deviceName = '' } = useParams<{ deviceName: string }>();
  const [params, setParams] = useSearchParams();
  const tab = parseTab(params.get('tab'));

  const onChange = (key: string) => {
    const next = new URLSearchParams(params);
    next.set('tab', key);
    setParams(next, { replace: true });
  };

  return (
    <PageContainer title={`${t('pages.console.detail.title')}：${deviceName}`}>
      <Tabs
        activeKey={tab}
        onChange={onChange}
        destroyOnHidden
        items={[
          {
            key: 'snapshot',
            label: t('pages.console.detail.tab.snapshot'),
            children: <SnapshotTab device={deviceName} />,
          },
          {
            key: 'alarms',
            label: t('pages.console.detail.tab.alarms'),
            children: <AlarmRecords device={deviceName} />,
          },
          {
            key: 'stock',
            label: t('pages.console.detail.tab.stock'),
            children: <StockRecords device={deviceName} />,
          },
        ]}
      />
    </PageContainer>
  );
};

export default ConsoleDeviceDetail;
