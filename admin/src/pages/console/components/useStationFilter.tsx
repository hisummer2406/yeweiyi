import { useAccess, useIntl, useSearchParams } from '@umijs/max';
import { Select } from 'antd';
import type React from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { listCustomers } from '@/services/ant-design-pro/customers';
import { listStations } from '@/services/ant-design-pro/stations';
import {
  matchesStation,
  parseStationParam,
  type StationFilterValue,
  stationFilterAvailable,
  stationParam,
} from '@/utils/stationFilter';

/**
 * 监控页的油站筛选状态：油站选项来自 GET /api/stations（静默请求，失败/旧版云端 404/空列表都退化为
 * 「没有筛选」，不弹错），选中值同步在 `?station=`（省略=全部，none=未分组，数字=油站 id）。
 * 平台管理员的油站跨客户、可能重名，所以标签追加客户名（客户列表取不到就只显示油站名）。
 * 设备行都没有 station_id（旧版云端）时同样不显示筛选。
 */
export function useStationFilter(devices: API.DeviceRow[] | undefined) {
  const { canPlatformAdmin } = useAccess();
  const [params, setParams] = useSearchParams();
  const [stations, setStations] = useState<API.Station[]>([]);
  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let alive = true;
    (async () => {
      const [ss, cs] = await Promise.all([
        listStations({ silent: true }).catch(() => [] as API.Station[]),
        canPlatformAdmin
          ? listCustomers().catch(() => [] as API.Customer[])
          : Promise.resolve([] as API.Customer[]),
      ]);
      if (!alive) return;
      setStations(ss);
      setCustomers(cs);
      setLoaded(true);
    })();
    return () => {
      alive = false;
    };
  }, [canPlatformAdmin]);

  const labelOf = useCallback(
    (id: number) => {
      const s = stations.find((x) => x.id === id);
      if (!s) return `#${id}`;
      const customer = canPlatformAdmin
        ? customers.find((c) => c.id === s.customerId)?.name
        : undefined;
      return customer ? `${s.name}（${customer}）` : s.name;
    },
    [stations, customers, canPlatformAdmin],
  );

  // 客户筛选（仅平台管理员）：?customer=<id>，省略=全部客户。非法或不可见的值回退为全部。
  const customerShow = canPlatformAdmin && customers.length > 0;
  const customerRaw = params.get('customer');
  const customerValue: number | 'all' =
    customerShow &&
    customerRaw &&
    /^\d+$/.test(customerRaw) &&
    customers.some((c) => c.id === Number(customerRaw))
      ? Number(customerRaw)
      : 'all';
  const setCustomer = (next: number | 'all') => {
    const p = new URLSearchParams(params);
    if (next === 'all') p.delete('customer');
    else p.set('customer', String(next));
    // 油站属于客户，换客户后原选中的油站不再适用
    p.delete('station');
    setParams(p, { replace: true });
  };
  const scopedStations = useMemo(
    () =>
      customerValue === 'all'
        ? stations
        : stations.filter((s) => s.customerId === customerValue),
    [stations, customerValue],
  );

  const visibleIds = useMemo(
    () => scopedStations.map((s) => s.id),
    [scopedStations],
  );
  const show = loaded && stationFilterAvailable(scopedStations.length, devices);
  // 筛选不可用时一律当作「全部」，URL 上残留的 ?station= 不应把设备藏起来
  const value: StationFilterValue = show
    ? parseStationParam(params.get('station'), visibleIds)
    : 'all';

  const setValue = (next: StationFilterValue) => {
    const p = new URLSearchParams(params);
    const v = stationParam(next);
    if (v === undefined) p.delete('station');
    else p.set('station', v);
    setParams(p, { replace: true });
  };

  return {
    show,
    value,
    setValue,
    stations: scopedStations,
    visibleIds,
    labelOf,
    customers,
    customerShow,
    customerValue,
    setCustomer,
    matches: (d: API.DeviceRow) =>
      (customerValue === 'all' || d.customer_id === customerValue) &&
      matchesStation(d, value),
  };
}

type FilterProps = {
  value: StationFilterValue;
  onChange: (v: StationFilterValue) => void;
  stations: API.Station[];
  labelOf: (id: number) => string;
};

/** 油站下拉：「全部油站」「未分组」+ 各油站。 */
export const StationFilter: React.FC<FilterProps> = ({
  value,
  onChange,
  stations,
  labelOf,
}) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  return (
    <Select<StationFilterValue>
      showSearch
      optionFilterProp="label"
      aria-label={t('pages.console.stationFilter.label')}
      style={{ minWidth: 220 }}
      value={value}
      onChange={onChange}
      options={[
        { value: 'all', label: t('pages.console.stationFilter.all') },
        { value: 'none', label: t('pages.console.stationFilter.none') },
        ...stations.map((s) => ({ value: s.id, label: labelOf(s.id) })),
      ]}
    />
  );
};

type CustomerFilterProps = {
  value: number | 'all';
  onChange: (v: number | 'all') => void;
  customers: API.Customer[];
};

/** 客户下拉（仅平台管理员，放在筛选条第一位）：「全部客户」+ 各客户。 */
export const CustomerFilter: React.FC<CustomerFilterProps> = ({
  value,
  onChange,
  customers,
}) => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  return (
    <Select<number | 'all'>
      showSearch
      optionFilterProp="label"
      aria-label={t('pages.console.customerFilter.label')}
      style={{ minWidth: 200 }}
      value={value}
      onChange={onChange}
      options={[
        { value: 'all', label: t('pages.console.customerFilter.all') },
        ...customers.map((c) => ({ value: c.id, label: c.name })),
      ]}
    />
  );
};
