import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useAccess, useIntl } from '@umijs/max';
import { Tag } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listDevices } from '@/services/ant-design-pro/devices';
import { listStations } from '@/services/ant-design-pro/stations';
import { listUsers } from '@/services/ant-design-pro/users';
import TankGrantsModal from './TankGrantsModal';

type Row = API.AdminUser & { customerName?: string };

/**
 * 资产管理 · 员工授权（/console/assets/staff，yeweiyi-admin#17，云端契约 tank-grants-contract v1.0）：
 * 列出可见的员工（customer_user），给选中的员工按 油站 → 设备 → 罐 分配/撤销查看权限。
 * - 平台管理员看到全部客户的员工；客户管理员只看到本客户员工，手机号是脱敏的（138****0000）。
 * - 这里**不创建、停用、改客户**员工账号：账号管理仍只归平台管理员（/ops/users，#13）。
 * - 员工没有网页入口，授权只影响小程序与云端接口；没有分配的员工看不到任何罐。
 */
const StaffGrants: React.FC = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const { canPlatformAdmin } = useAccess();
  const actionRef = useRef<ActionType>(undefined);
  const [devices, setDevices] = useState<API.DeviceRow[]>([]);
  const [stations, setStations] = useState<API.Station[]>([]);
  const [target, setTarget] = useState<Row>();

  const columns: ProColumns<Row>[] = [
    {
      title: t('pages.console.staff.col.phone'),
      dataIndex: 'phone',
      render: (_, row) => row.phone ?? `#${row.id}`,
    },
    ...(canPlatformAdmin
      ? [
          {
            title: t('pages.console.staff.col.customer'),
            dataIndex: 'customerName',
            render: (_: unknown, row: Row) =>
              row.customerName ?? (row.customerId ? `#${row.customerId}` : '-'),
          } as ProColumns<Row>,
        ]
      : []),
    {
      title: t('pages.console.staff.col.status'),
      dataIndex: 'disabled',
      render: (_, row) =>
        row.disabled ? (
          <Tag>{t('pages.console.staff.status.disabled')}</Tag>
        ) : (
          <Tag color="green">{t('pages.console.staff.status.enabled')}</Tag>
        ),
    },
    {
      title: t('pages.console.staff.col.actions'),
      valueType: 'option',
      render: (_, row) => [
        <a key="grant" onClick={() => setTarget(row)}>
          {t('pages.console.staff.action.assign')}
        </a>,
      ],
    },
  ];

  return (
    <PageContainer>
      <ProTable<Row>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        search={false}
        options={{ density: false, setting: false }}
        pagination={{ pageSize: 20, showSizeChanger: false }}
        request={async () => {
          const [users, ds, ss, cs] = await Promise.all([
            listUsers(),
            listDevices(),
            // 油站/客户名只是显示用，失败退化，不拖垮员工列表
            listStations({ silent: true }).catch(() => [] as API.Station[]),
            canPlatformAdmin
              ? listCustomers().catch(() => [] as API.Customer[])
              : Promise.resolve([] as API.Customer[]),
          ]);
          setDevices(ds);
          setStations(ss);
          const names = new Map(cs.map((c) => [c.id, c.name]));
          return {
            // 只列员工：管理员账号不属于油罐授权（云端对管理员目标 409 grant_not_applicable）
            data: users
              .filter((u) => u.role === 'customer_user')
              .map((u) => ({
                ...u,
                customerName:
                  u.customerId == null ? undefined : names.get(u.customerId),
              })),
            success: true,
          };
        }}
        onRequestError={() => undefined}
        locale={{ emptyText: t('pages.console.staff.empty') }}
      />
      <TankGrantsModal
        user={target}
        devices={devices}
        stations={stations}
        onClose={() => setTarget(undefined)}
      />
    </PageContainer>
  );
};

export default StaffGrants;
