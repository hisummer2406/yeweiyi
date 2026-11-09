import { PageContainer } from '@ant-design/pro-components';
import { Link, useIntl } from '@umijs/max';
import { Alert, Table } from 'antd';
import type React from 'react';
import { useEffect, useState } from 'react';
import { listDevices } from '@/services/ant-design-pro/devices';
import { listStations } from '@/services/ant-design-pro/stations';

/**
 * 远程控制入口（#4）：列出调用者可见的设备，点进设备详情的「参数设置」页签。
 * 本期只有配置类命令；消音、清警、重启、泵阀等动作不提供入口（设备端未实现/未验证，云端物理命令开关关闭）。
 */
const ConsoleIssueCommand: React.FC = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const [rows, setRows] = useState<API.DeviceRow[]>();
  const [stations, setStations] = useState<API.Station[]>([]);

  useEffect(() => {
    listDevices()
      .then(setRows)
      .catch(() => setRows([]));
    listStations({ silent: true })
      .then(setStations)
      .catch(() => setStations([]));
  }, []);

  const stationName = (id: number | null | undefined) =>
    id === undefined
      ? t('pages.console.stations.devices.station.unknown')
      : id === null
        ? t('pages.console.stations.devices.station.none')
        : (stations.find((s) => s.id === id)?.name ?? `#${id}`);

  return (
    <PageContainer title={t('pages.console.control.title')}>
      <Alert
        style={{ marginBottom: 16 }}
        type="info"
        showIcon
        message={t('pages.console.control.scope')}
      />
      <Table<API.DeviceRow>
        rowKey="device_name"
        loading={rows === undefined}
        dataSource={rows ?? []}
        pagination={{ pageSize: 20 }}
        columns={[
          {
            title: t('pages.console.control.col.station'),
            render: (_, r) => stationName(r.station_id),
          },
          {
            title: t('pages.console.control.col.device'),
            render: (_, r) => (
              <Link
                to={`/console/devices/${encodeURIComponent(r.device_name)}?tab=settings`}
              >
                {r.device_name}
              </Link>
            ),
          },
        ]}
      />
    </PageContainer>
  );
};

export default ConsoleIssueCommand;
