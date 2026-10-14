import { useEffect, useState } from 'react';
import { listDevices } from '@/services/ant-design-pro/devices';
import { currentDeviceOf } from '@/utils/stationOccupancy';
import { REFRESH_MS } from './useRecordsQuery';

/**
 * 从油站入口打开监控页时，跟踪该油站「当前关联的设备」（契约 §8.1）：云端不推送换机事件，
 * 靠定期刷新 GET /api/devices 的 station_id 得知。返回：设备编号 / null（油站暂无设备）/
 * undefined（还不知道、取不到，或历史冲突数据有多台——都不据此自动切换）。
 * 刷新失败时保留上一次的结果，不当作换机。stationId 为 undefined 时不请求。
 */
export function useStationCurrentDevice(
  stationId: number | undefined,
): string | null | undefined {
  const [current, setCurrent] = useState<string | null | undefined>();

  useEffect(() => {
    setCurrent(undefined); // 换油站：不沿用上一个油站的结果
    if (stationId === undefined) return;
    let alive = true;
    const load = async () => {
      try {
        const rows = await listDevices();
        if (alive) setCurrent(currentDeviceOf(rows, stationId));
      } catch {
        // 保留上一次结果；鉴权失败由全局处理
      }
    };
    void load();
    const timer = setInterval(() => void load(), REFRESH_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [stationId]);

  return current;
}
