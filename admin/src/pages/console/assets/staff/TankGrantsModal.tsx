import { useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  Checkbox,
  Collapse,
  Empty,
  Modal,
  message,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { userLabel } from '@/pages/ops/users/errors';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  getDeviceTanks,
  listUserTankGrants,
  setUserTankGrants,
} from '@/services/ant-design-pro/tankGrants';
import { describeTankGrantError, isStaleGrantError } from './errors';

type Props = {
  /** 要分配油罐的员工；undefined 时弹窗关闭 */
  user?: API.AdminUser;
  devices: API.DeviceRow[];
  stations: API.Station[];
  onClose: () => void;
};

const sameSet = (a: number[], b: number[]) =>
  a.length === b.length && [...a].sort().join() === [...b].sort().join();

/** 一台设备的罐勾选。授权键是 (员工, device_name, 罐号)，保存是对该设备的整组替换。 */
const DevicePanel: React.FC<{
  userId: number;
  device: string;
  saved: number[];
  onSaved: (device: string, tanks: number[]) => void;
  onUserGone: () => void;
}> = ({ userId, device, saved, onSaved, onUserGone }) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [info, setInfo] = useState<API.DeviceTanks>();
  const [loadError, setLoadError] = useState(false);
  const [selected, setSelected] = useState<number[]>(saved);
  const [saving, setSaving] = useState(false);
  const busy = useRef(false);
  // 每次重新加载自增；弹窗换员工/设备或卸载后迟到的响应丢弃
  const seq = useRef(0);

  const load = useCallback(async () => {
    const mine = ++seq.current;
    setLoadError(false);
    try {
      const res = await getDeviceTanks(device);
      if (mine === seq.current) setInfo(res);
    } catch (err) {
      if (mine !== seq.current) return;
      if (err instanceof ApiError && err.authHandled) return;
      setLoadError(true);
    }
  }, [device]);

  useEffect(() => {
    void load();
    return () => {
      seq.current++;
    };
  }, [load]);

  useEffect(() => setSelected(saved), [saved]);

  const reportedIdx = (info?.tanks ?? []).map((x) => x.index);
  // 已授权但设备不再上报的罐：保留可见、可撤销（契约：罐从快照消失后授权保留）
  const all = [...new Set([...reportedIdx, ...saved])].sort((a, b) => a - b);
  const reported = info?.reported ?? false;

  const save = async () => {
    if (busy.current) return;
    busy.current = true;
    setSaving(true);
    try {
      const res = await setUserTankGrants(userId, device, selected);
      message.success(
        t(
          res.changed
            ? 'pages.console.staff.save.success'
            : 'pages.console.staff.save.unchanged',
          { device },
        ),
      );
      onSaved(device, res.tanks);
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return;
      const m = describeTankGrantError(
        apiErr?.status ?? -1,
        apiErr?.code,
        apiErr?.error ?? (err as Error)?.message,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
      if (isStaleGrantError(apiErr?.code)) {
        if (
          apiErr?.code === 'user_not_found' ||
          apiErr?.code === 'grant_not_applicable'
        )
          onUserGone();
        else {
          // 罐/设备已变化：刷新真实可选罐，并丢弃页面上过期的勾选（否则被拒的罐不再渲染，无法取消）
          setSelected(saved);
          void load();
        }
      }
    } finally {
      busy.current = false;
      setSaving(false);
    }
  };

  if (!info && !loadError) return <Skeleton active paragraph={{ rows: 1 }} />;
  if (loadError)
    return (
      <Alert
        type="error"
        showIcon
        title={t('pages.console.staff.tanks.loadFailed')}
        action={
          <Button size="small" onClick={() => void load()}>
            {t('pages.console.records.retry')}
          </Button>
        }
      />
    );

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {!reported && (
        <Alert
          type="info"
          showIcon
          title={t('pages.console.staff.tanks.notReported')}
        />
      )}
      {all.length === 0 ? (
        <Typography.Text type="secondary">
          {t('pages.console.staff.tanks.none')}
        </Typography.Text>
      ) : (
        <Checkbox.Group
          value={selected}
          onChange={(v) => setSelected(v as number[])}
          options={all.map((n) => ({
            value: n,
            label:
              t('pages.console.staff.tanks.label', { n: String(n) }) +
              (reportedIdx.includes(n)
                ? ''
                : t('pages.console.staff.tanks.gone')),
            // 设备从未上报时只能整组撤销（云端只接受空集合，否则 409 tanks_unknown）：
            // 复选框全部禁用，由「撤销全部」清空，避免提交必然失败的非空子集
            disabled: !reported,
          }))}
        />
      )}
      <Space>
        <Button
          type="primary"
          size="small"
          loading={saving}
          disabled={sameSet(selected, saved)}
          onClick={() => void save()}
        >
          {t('pages.console.staff.save')}
        </Button>
        {!reported && saved.length > 0 && (
          <Button
            size="small"
            danger
            disabled={selected.length === 0}
            onClick={() => setSelected([])}
          >
            {t('pages.console.staff.revokeAll')}
          </Button>
        )}
        {selected.length === 0 && (
          <Typography.Text type="secondary">
            {t('pages.console.staff.emptyMeansNone')}
          </Typography.Text>
        )}
      </Space>
    </Space>
  );
};

/**
 * 员工按罐授权（yeweiyi-admin#17，tank-grants-contract v1.0）：按 油站 → 设备 → 罐 组织，可选多个罐并回显已授权项。
 * - 授权键是 (员工, device_name, 罐号)；保存是对单台设备的整组替换，空选择 = 撤销该设备全部授权，**不会**自动全选。
 * - 罐号只来自设备已上报的快照（GET /api/devices/:device/tanks）；设备从未上报时只能撤销。
 * - 候选设备限员工所属客户：平台管理员的设备行带 customer_id，据此过滤；客户管理员的行没有该字段，它能看到的设备
 *   本来就是本客户的。这只是不让用户选到，跨客户由云端 409 device_customer_mismatch 裁决。
 * - 这里不创建/修改账号，也不给员工网页入口。
 */
const TankGrantsModal: React.FC<Props> = ({
  user,
  devices,
  stations,
  onClose,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [grants, setGrants] = useState<Record<string, number[]>>();
  const [failed, setFailed] = useState(false);
  const seq = useRef(0);

  const userId = user?.id;
  const loadGrants = useCallback(async () => {
    if (userId === undefined) return;
    const mine = ++seq.current;
    setFailed(false);
    try {
      const res = await listUserTankGrants(userId);
      if (mine !== seq.current) return;
      setGrants(
        Object.fromEntries(res.grants.map((g) => [g.deviceName, g.tanks])),
      );
    } catch (err) {
      if (mine !== seq.current) return;
      if (err instanceof ApiError && err.authHandled) return;
      const apiErr = err instanceof ApiError ? err : undefined;
      const m = describeTankGrantError(
        apiErr?.status ?? -1,
        apiErr?.code,
        apiErr?.error,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
      // 员工已不存在：重试必然失败，和保存时一致地关闭弹窗
      if (apiErr?.code === 'user_not_found') {
        onClose();
        return;
      }
      setFailed(true);
    }
  }, [userId]);

  useEffect(() => {
    setGrants(undefined);
    void loadGrants();
    return () => {
      seq.current++;
    };
  }, [loadGrants]);

  const onSaved = (device: string, tanks: number[]) =>
    setGrants((g) => ({ ...(g ?? {}), [device]: tanks }));

  const customerId = user?.customerId;
  const visible = devices.filter(
    (d) =>
      d.customer_id !== null &&
      (typeof d.customer_id !== 'number' || d.customer_id === customerId),
  );
  const visibleNames = new Set(visible.map((d) => d.device_name));
  const orphans = Object.entries(grants ?? {}).filter(
    ([name, tanks]) => !visibleNames.has(name) && tanks.length > 0,
  );

  const stationName = (id: number | null | undefined) => {
    if (id === undefined)
      return t('pages.console.stations.devices.station.unknown');
    if (id === null) return t('pages.console.stations.devices.station.none');
    return stations.find((s) => s.id === id)?.name ?? `#${id}`;
  };
  const sections = new Map<string, API.DeviceRow[]>();
  for (const d of visible) {
    const key = stationName(d.station_id);
    sections.set(key, [...(sections.get(key) ?? []), d]);
  }

  return (
    <Modal
      open={!!user}
      title={t('pages.console.staff.modal.title', {
        phone: user ? userLabel(user) : '',
      })}
      footer={
        <Button onClick={onClose}>
          {t('pages.console.staff.modal.close')}
        </Button>
      }
      onCancel={onClose}
      width={720}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">
        {t('pages.console.staff.modal.hint')}
      </Typography.Paragraph>
      {failed && (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 12 }}
          title={t('pages.console.staff.grants.loadFailed')}
          action={
            <Button size="small" onClick={() => void loadGrants()}>
              {t('pages.console.records.retry')}
            </Button>
          }
        />
      )}
      {!grants && !failed && <Skeleton active />}
      {grants && visible.length === 0 && orphans.length === 0 && (
        <Empty description={t('pages.console.staff.noDevices')} />
      )}
      {grants &&
        [...sections.entries()].map(([station, ds]) => (
          <div key={station} style={{ marginBottom: 12 }}>
            <Typography.Title level={5}>{station}</Typography.Title>
            <Collapse
              size="small"
              defaultActiveKey={ds
                .filter((d) => (grants[d.device_name] ?? []).length > 0)
                .map((d) => d.device_name)}
              items={ds.map((d) => ({
                key: d.device_name,
                label: (
                  <Space>
                    <span>{d.device_name}</span>
                    {(grants[d.device_name] ?? []).length > 0 && (
                      <Tag color="blue">
                        {t('pages.console.staff.granted', {
                          n: String((grants[d.device_name] ?? []).length),
                        })}
                      </Tag>
                    )}
                  </Space>
                ),
                children: user && (
                  <DevicePanel
                    userId={user.id}
                    device={d.device_name}
                    saved={grants[d.device_name] ?? []}
                    onSaved={onSaved}
                    onUserGone={onClose}
                  />
                ),
              }))}
            />
          </div>
        ))}
      {orphans.length > 0 && user && (
        <div>
          <Typography.Title level={5}>
            {t('pages.console.staff.orphans')}
          </Typography.Title>
          {orphans.map(([name, tanks]) => (
            <OrphanRow
              key={name}
              userId={user.id}
              device={name}
              tanks={tanks}
              onSaved={onSaved}
            />
          ))}
        </div>
      )}
    </Modal>
  );
};

/** 当前设备列表里看不到、但仍有授权的设备（例如设备已被转走）：只提供撤销。 */
const OrphanRow: React.FC<{
  userId: number;
  device: string;
  tanks: number[];
  onSaved: (device: string, tanks: number[]) => void;
}> = ({ userId, device, tanks, onSaved }) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [busy, setBusy] = useState(false);
  const revoke = async () => {
    setBusy(true);
    try {
      await setUserTankGrants(userId, device, []);
      message.success(t('pages.console.staff.save.success', { device }));
      onSaved(device, []);
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return;
      const m = describeTankGrantError(
        apiErr?.status ?? -1,
        apiErr?.code,
        apiErr?.error,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Space style={{ marginBottom: 8 }}>
      <span>{device}</span>
      {tanks.map((n) => (
        <Tag key={n}>
          {t('pages.console.staff.tanks.label', { n: String(n) })}
        </Tag>
      ))}
      <Button size="small" danger loading={busy} onClick={() => void revoke()}>
        {t('pages.console.staff.revoke')}
      </Button>
    </Space>
  );
};

export default TankGrantsModal;
