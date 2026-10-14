import { PageContainer } from '@ant-design/pro-components';
import { useIntl, useNavigate, useParams, useSearchParams } from '@umijs/max';
import { Tabs } from 'antd';
import type React from 'react';
import { useEffect } from 'react';
import AlarmRecords from '../components/AlarmRecords';
import StockRecords from '../components/StockRecords';
import { useStationCurrentDevice } from '../components/useStationCurrentDevice';
import DeviceOwnership from './DeviceOwnership';
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
 * 页签内容按设备编号做 key：路由从一台设备换到另一台（如换机后改看新设备）时整体重建，
 * 不会在新设备标题下闪出旧设备的最新值或记录，在途的旧请求也随卸载作废。
 */
const ConsoleDeviceDetail: React.FC = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const { deviceName = '' } = useParams<{ deviceName: string }>();
  const [params, setParams] = useSearchParams();
  const tab = parseTab(params.get('tab'));
  const navigate = useNavigate();

  // 油站入口（?station=）：换机后自动切到该油站的新当前设备（契约 §8.1）。页签内容按设备编号 key，
  // 跳转即整体重建，A 的数据/缓存/定时器随之清掉，A 的迟到响应不会写进 B 的页面。
  // 没有 ?station= 的独立入口按设备编码直接看，不跟随。
  const stationRaw = params.get('station');
  const stationId =
    stationRaw && /^\d+$/.test(stationRaw) ? Number(stationRaw) : undefined;
  const current = useStationCurrentDevice(stationId);
  useEffect(() => {
    if (typeof current === 'string' && current !== deviceName) {
      navigate(
        `/console/devices/${encodeURIComponent(current)}?${params.toString()}`,
        { replace: true },
      );
    }
  }, [current, deviceName, navigate, params]);

  const onChange = (key: string) => {
    const next = new URLSearchParams(params);
    next.set('tab', key);
    setParams(next, { replace: true });
  };

  return (
    <PageContainer title={`${t('pages.console.detail.title')}：${deviceName}`}>
      <DeviceOwnership key={deviceName} device={deviceName} />
      <Tabs
        activeKey={tab}
        onChange={onChange}
        destroyOnHidden
        items={[
          {
            key: 'snapshot',
            label: t('pages.console.detail.tab.snapshot'),
            children: <SnapshotTab key={deviceName} device={deviceName} />,
          },
          {
            key: 'alarms',
            label: t('pages.console.detail.tab.alarms'),
            children: <AlarmRecords key={deviceName} device={deviceName} />,
          },
          {
            key: 'stock',
            label: t('pages.console.detail.tab.stock'),
            children: <StockRecords key={deviceName} device={deviceName} />,
          },
        ]}
      />
    </PageContainer>
  );
};

export default ConsoleDeviceDetail;
