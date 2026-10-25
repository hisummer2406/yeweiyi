import { useIntl } from '@umijs/max';
import { Select, Space } from 'antd';
import type React from 'react';
import { useEffect, useState } from 'react';
import { getDeviceTanks } from '@/services/ant-design-pro/tankGrants';

type Props = {
  device: string;
  /** undefined = 全部油罐 */
  value?: number;
  onChange: (tank?: number) => void;
};

/**
 * 记录页的罐号筛选：「全部油罐」+ 调用者在该设备上可见的罐（GET /api/devices/:device/tanks，员工只含被授权的罐）。
 * 设备从未上报或读取失败时只有「全部油罐」，记录本身照常按整机查询。换设备时选项随之重取。
 */
const TankFilter: React.FC<Props> = ({ device, value, onChange }) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  const [tanks, setTanks] = useState<number[]>([]);

  useEffect(() => {
    let live = true;
    setTanks([]);
    getDeviceTanks(device)
      .then((res) => {
        if (live)
          setTanks(
            [...new Set(res.tanks.map((x) => x.index))].sort((a, b) => a - b),
          );
      })
      .catch(() => {
        if (live) setTanks([]);
      });
    return () => {
      live = false;
    };
  }, [device]);

  return (
    <Space>
      <span>{t('pages.console.records.filter.tank')}</span>
      <Select<number | 'all'>
        aria-label={t('pages.console.records.filter.tank')}
        style={{ minWidth: 140 }}
        value={value ?? 'all'}
        onChange={(v) => onChange(v === 'all' ? undefined : v)}
        options={[
          { value: 'all', label: t('pages.console.records.filter.allTanks') },
          ...tanks.map((n) => ({
            value: n,
            label: t('pages.console.records.filter.tankNo', { n }),
          })),
        ]}
      />
    </Space>
  );
};

export default TankFilter;
