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
import { StationFilter, useStationFilter } from './useStationFilter';

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

  // 选定油站时（油站入口）：该油站的当前设备由 A 变成 B，且正在看的恰是 A，就自动切到 B；
  // 用户手动选了别的设备则不动。多台（历史冲突数据）或暂无设备不据此切换。
  const lastCurrent = useRef<
    { station: number; device?: string | null } | undefined
  >(undefined);
  const stationValue = sf.value;
  useEffect(() => {
    if (typeof stationValue !== 'number' || !devices) {
      lastCurrent.current = undefined;
      return;
    }
    const cur = currentDeviceOf(devices, stationValue);
    const prev = lastCurrent.current;
    lastCurrent.current = { station: stationValue, device: cur };
    if (
      prev &&
      prev.station === stationValue &&
      typeof prev.device === 'string' &&
      typeof cur === 'string' &&
      cur !== prev.device &&
      requested === prev.device
    ) {
      const next = new URLSearchParams(params);
      next.set('device', cur);
      setParams(next, { replace: true });
    }
  }, [devices, stationValue, requested, params, setParams]);

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
    !!requested && !!devices?.some((d) => d.device_name === requested);
  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space wrap>
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
      </Space>
      {requested && !visible && (
        <Alert
          type="warning"
          showIcon
          title={t('pages.console.records.device.notVisible', {
            device: requested,
          })}
        />
      )}
      {!requested && (
        <Typography.Text type="secondary">
          {t('pages.console.records.device.pick')}
        </Typography.Text>
      )}
      {visible && children(requested as string)}
    </Space>
  );
};

export default DevicePicker;
