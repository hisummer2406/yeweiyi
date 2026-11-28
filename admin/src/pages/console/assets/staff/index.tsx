import type { ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { history, useIntl } from '@umijs/max';
import { Tag } from 'antd';
import type React from 'react';
import { useEffect, useRef, useState } from 'react';
import { KeywordSearchBox, useKeywordSearch } from '@/components/KeywordSearch';
import { userLabel } from '@/pages/ops/users/errors';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listDevices } from '@/services/ant-design-pro/devices';
import { listStations } from '@/services/ant-design-pro/stations';
import { listUsers } from '@/services/ant-design-pro/users';
import { filterByKeyword, matchStaff } from '@/utils/listSearch';
import TankGrantsModal from './TankGrantsModal';

type Row = API.AdminUser & { customerName?: string };

/**
 * 资产管理 · 员工授权（/assets/staff，yeweiyi-admin#17，云端契约 tank-grants-contract v1.1）：
 * 列出可见的员工（customer_user），给选中的员工按 油站 → 设备 → 罐 分配/撤销查看权限。
 * - 仅平台管理员（云端 v1.1：客户管理员读写授权一律 403，路由已按 canPlatformAdmin 收窄）；看到全部客户的员工。
 * - 这里**不创建、停用、改客户**员工账号：在「运维管理 → 账号管理」（/ops/users，#13）里建；建好后自动跳到这里并打开该员工的分配弹窗。
 * - 员工没有网页入口，授权只影响小程序与云端接口；没有分配的员工看不到任何罐。
 */
const StaffGrants: React.FC = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(false);
  const search = useKeywordSearch();
  const [devices, setDevices] = useState<API.DeviceRow[]>([]);
  const [stations, setStations] = useState<API.Station[]>([]);
  const [target, setTarget] = useState<Row>();
  // ?user=<id>：刚建好员工后从账号管理跳来，列表加载完自动打开该员工的分配弹窗（只一次）
  const pendingUser = useRef<number | undefined>(
    (() => {
      const v = new URLSearchParams(window.location.search).get('user');
      return v && /^\d+$/.test(v) ? Number(v) : undefined;
    })(),
  );

  // 一次取全后在前端搜索、分页（#108）；失败提示已由全局请求错误处理弹出，这里只保留旧列表
  const load = async () => {
    setLoading(true);
    try {
      const [users, ds, ss, cs] = await Promise.all([
        listUsers(),
        listDevices(),
        // 油站/客户名只是显示用，失败退化，不拖垮员工列表
        listStations({ silent: true }).catch(() => [] as API.Station[]),
        listCustomers().catch(() => [] as API.Customer[]),
      ]);
      setDevices(ds);
      setStations(ss);
      const names = new Map(cs.map((c) => [c.id, c.name]));
      // 只列员工：管理员账号不属于油罐授权（云端对管理员目标 409 grant_not_applicable）
      const staff = users
        .filter((u) => u.role === 'customer_user')
        .map((u) => ({
          ...u,
          customerName:
            u.customerId == null ? undefined : names.get(u.customerId),
        }));
      setRows(staff);
      const pending = pendingUser.current;
      if (pending != null) {
        pendingUser.current = undefined;
        history.replace(window.location.pathname);
        const row = staff.find((u) => u.id === pending);
        if (row) setTarget(row);
      }
    } catch {
      // 已由全局处理
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const columns: ProColumns<Row>[] = [
    {
      title: t('pages.console.staff.col.phone'),
      dataIndex: 'phone',
      render: (_, row) => userLabel(row),
    },
    {
      title: t('pages.console.staff.col.customer'),
      dataIndex: 'customerName',
      render: (_, row) =>
        row.customerName ?? (row.customerId ? `#${row.customerId}` : '-'),
    },
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
        columns={columns}
        search={false}
        loading={loading}
        dataSource={filterByKeyword(rows, search.keyword, matchStaff)}
        options={{ density: false, setting: false, reload: load }}
        pagination={search.pagination}
        locale={{
          emptyText: t(
            search.searching && rows.length > 0
              ? 'pages.assets.search.empty'
              : 'pages.console.staff.empty',
          ),
        }}
        headerTitle={
          <KeywordSearchBox
            placeholder={t('pages.console.staff.search')}
            onSearch={search.onSearch}
          />
        }
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
