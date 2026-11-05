import { useIntl, useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Empty,
  Select,
  Skeleton,
  Space,
  Typography,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { listDevices } from '@/services/ant-design-pro/devices';
import { currentDeviceOf } from '@/utils/stationOccupancy';
import { REFRESH_MS } from './useRecordsQuery';
import {
  CustomerFilter,
  StationFilter,
  useStationFilter,
} from './useStationFilter';

type Props = {
  /** 设备可见且已选中时才渲染；记录请求只会在这里发起，保证没选设备就不请求 */
  children: (device: string) => React.ReactNode;
};

/**
 * 记录页顶部的设备选择。选项来自 GET /api/devices（调用者可见的设备），选中的设备同步在 `?device=`，
 * 所以可以直达某台设备。云端记录接口必须带 device，没有「全部设备」。
 * 有油站数据时多一个油站筛选（`?station=`），它只缩小设备下拉的候选：`?device=` 是否可见仍按完整设备列表判断，
 * 换油站不会清掉已选设备，已选设备不在候选里时下拉仍显示它。云端记录接口必须带 device，没有按油站聚合的查询。
 * 状态：加载中、列表失败（可重试）、没有可见设备、URL 里的设备不可见、尚未选择。
 */
const DevicePicker: React.FC<Props> = ({ children }) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [params, setParams] = useSearchParams();
  const requested = params.get('device') ?? undefined;

  const [devices, setDevices] = useState<API.DeviceRow[]>();
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const sf = useStationFilter(devices);

  // 轮询可能重叠、乱序返回：只采用最后发出的请求，迟到的旧快照丢弃
  const seq = useRef(0);

  const load = useCallback(async (silent = false) => {
    const mine = ++seq.current;
    if (!silent) setLoading(true);
    try {
      const rows = await listDevices();
      if (mine !== seq.current) return;
      setDevices(rows);
      setFailed(false);
    } catch {
      if (mine !== seq.current) return;
      // 全局错误处理已提示；这里只标记，不把失败当成「没有设备」。
      // 后台轮询失败保留已有列表，不闪成失败态
      if (!silent) setFailed(true);
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    // 云端不推送换机事件，靠定期刷新设备的 station_id 得知油站当前设备变化（契约 §8.1）
    const timer = setInterval(() => void load(true), REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const select = (device: string) => {
    const next = new URLSearchParams(params);
    next.set('device', device);
    setParams(next, { replace: true });
  };

  // 一油站一台大屏：选定油站时设备由油站决定（设备下拉隐藏），油站换机后自动跟随（契约 §8.1）。
  // 多于一台（历史冲突数据）或暂无设备时不择一，给出提示。
  const stationValue = sf.value;
  const stationDevice =
    typeof stationValue === 'number' && devices
      ? currentDeviceOf(devices, stationValue)
      : undefined;
  const byStation = typeof stationValue === 'number' && !!devices;
  const effective = byStation
    ? typeof stationDevice === 'string'
      ? stationDevice
      : undefined
    : requested;

  useEffect(() => {
    if (!byStation || typeof stationDevice !== 'string') return;
    if (requested === stationDevice) return;
    const next = new URLSearchParams(params);
    next.set('device', stationDevice);
    setParams(next, { replace: true });
  }, [byStation, stationDevice, requested, params, setParams]);

  // 什么都没指定：默认选第一个有设备的油站（没有油站数据时退回第一台候选设备），让记录表格直接出现
  const firstStation =
    sf.show && devices
      ? sf.stations.find(
          (s) => typeof currentDeviceOf(devices, s.id) === 'string',
        )
      : undefined;
  const firstCandidate = devices?.find((d) => sf.matches(d))?.device_name;
  useEffect(() => {
    if (requested || !devices) return;
    const next = new URLSearchParams(params);
    if (firstStation && sf.value === 'all') {
      next.set('station', String(firstStation.id));
      next.set('device', currentDeviceOf(devices, firstStation.id) as string);
    } else if (firstCandidate) {
      next.set('device', firstCandidate);
    } else {
      return;
    }
    setParams(next, { replace: true });
  }, [
    requested,
    devices,
    firstStation,
    firstCandidate,
    sf.value,
    params,
    setParams,
  ]);

  if (loading && !devices) return <Skeleton active />;
  if (failed && !devices) {
    return (
      <Alert
        type="error"
        showIcon
        title={t('pages.console.records.device.loadFailed')}
        action={
          <Button size="small" onClick={() => void load()}>
            {t('pages.console.records.retry')}
          </Button>
        }
      />
    );
  }
  if (devices && devices.length === 0) {
    return <Empty description={t('pages.console.records.device.none')} />;
  }

  const visible =
    !!effective && !!devices?.some((d) => d.device_name === effective);
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap>
        {sf.customerShow && (
          <>
            <span>{t('pages.console.customerFilter.label')}</span>
            <CustomerFilter
              value={sf.customerValue}
              onChange={sf.setCustomer}
              customers={sf.customers}
            />
          </>
        )}
        {sf.show && (
          <>
            <span>{t('pages.console.stationFilter.label')}</span>
            <StationFilter
              value={sf.value}
              onChange={sf.setValue}
              stations={sf.stations}
              labelOf={sf.labelOf}
            />
          </>
        )}
        {byStation ? (
          visible && (
            <Typography.Text type="secondary">
              {t('pages.console.records.device.current', {
                device: effective as string,
              })}
            </Typography.Text>
          )
        ) : (
          <>
            <span>{t('pages.console.records.device.label')}</span>
            <Select
              showSearch
              style={{ minWidth: 260 }}
              value={visible ? requested : undefined}
              placeholder={t('pages.console.records.device.placeholder')}
              options={(devices ?? [])
                .filter((d) => d.device_name === requested || sf.matches(d))
                .map((d) => ({
                  value: d.device_name,
                  label: d.device_name,
                }))}
              onChange={select}
            />
          </>
        )}
      </Space>
      {byStation && typeof stationDevice !== 'string' && (
        <Alert
          type="info"
          showIcon
          title={t(
            stationDevice === null
              ? 'pages.console.records.station.noDevice'
              : 'pages.console.records.station.multiple',
          )}
        />
      )}
      {!byStation && requested && !visible && (
        <Alert
          type="warning"
          showIcon
          title={t('pages.console.records.device.notVisible', {
            device: requested,
          })}
        />
      )}
      {!byStation && !requested && (
        <Typography.Text type="secondary">
          {t('pages.console.records.device.pick')}
        </Typography.Text>
      )}
      {visible && children(effective as string)}
    </Space>
  );
};

export default DevicePicker;
