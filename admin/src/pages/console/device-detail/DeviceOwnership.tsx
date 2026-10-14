import { useIntl } from '@umijs/max';
import { Space, Typography } from 'antd';
import type React from 'react';
import { useEffect, useState } from 'react';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listDevices } from '@/services/ant-design-pro/devices';
import { listStations } from '@/services/ant-design-pro/stations';

type Owner = { customer?: string; station?: string };

/**
 * 设备编号旁边的「所属客户 / 所属油站」，供安装人员把大屏回显、外壳贴纸和后台记录放在一屏里核对（#16 G1）。
 * 数据来自设备列表里的这一行：客户只有平台管理员的行带 `customer_id`（客户管理员的行没有，所以不请求
 * GET /api/customers，也不显示客户）；字段缺失 = 旧版云端，显示「未知」而不是「未分配/未分组」。
 * 取不到时什么都不显示，不影响监控内容。
 */
const DeviceOwnership: React.FC<{ device: string }> = ({ device }) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const [owner, setOwner] = useState<Owner>();

  useEffect(() => {
    let alive = true;
    setOwner(undefined); // 换设备：不沿用上一台的归属
    (async () => {
      try {
        const row = (await listDevices()).find((r) => r.device_name === device);
        if (!row) return;
        const out: Owner = {};
        if (row.customer_id !== undefined) {
          if (row.customer_id === null) {
            out.customer = t('pages.ops.devices.customer.unassigned');
          } else {
            const customers = await listCustomers().catch(
              () => [] as API.Customer[],
            );
            out.customer =
              customers.find((c) => c.id === row.customer_id)?.name ??
              `#${row.customer_id}`;
          }
        }
        if (row.station_id === undefined) {
          out.station = t('pages.console.stations.devices.station.unknown');
        } else if (row.station_id === null) {
          out.station = t('pages.console.stations.devices.station.none');
        } else {
          const stations = await listStations({ silent: true }).catch(
            () => [] as API.Station[],
          );
          out.station =
            stations.find((s) => s.id === row.station_id)?.name ??
            `#${row.station_id}`;
        }
        if (alive) setOwner(out);
      } catch {
        // 全局错误处理已提示（或鉴权失败已回登录页）；归属只是辅助信息
      }
    })();
    return () => {
      alive = false;
    };
  }, [device]);

  if (!owner) return null;
  return (
    <Space size="large" style={{ marginBottom: 12 }}>
      {owner.customer !== undefined && (
        <Typography.Text>
          {t('pages.console.detail.owner.customer')}：{owner.customer}
        </Typography.Text>
      )}
      {owner.station !== undefined && (
        <Typography.Text>
          {t('pages.console.detail.owner.station')}：{owner.station}
        </Typography.Text>
      )}
    </Space>
  );
};

export default DeviceOwnership;
