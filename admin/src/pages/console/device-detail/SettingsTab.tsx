import { useAccess, useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Collapse,
  message,
  Segmented,
  Space,
  Spin,
  Table,
  Typography,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  getDeviceSettings,
  getTankCalibration,
  getTankSettings,
} from '@/services/ant-design-pro/deviceConfig';
import { listDevices } from '@/services/ant-design-pro/devices';
import { getDeviceMediaOptions } from '@/services/ant-design-pro/media';
import { listStations } from '@/services/ant-design-pro/stations';
import {
  DEVICE_FIELDS,
  type FieldDef,
  TANK_FIELDS,
  trackedArgs,
} from '@/utils/deviceConfig';
import CommandProgress from './CommandProgress';
import ConfigSubmitModal, { type SubmitRequest } from './ConfigSubmitModal';
import SettingsForm, { useFormatValue } from './SettingsForm';
import { type Tracked, useCommandTracker } from './useCommandTracker';

type Loaded = {
  row?: API.DeviceRow;
  stationLabel: string;
  tanks: API.TankSettings[];
  deviceSettings: API.DeviceSettings | null;
  /** 整机镜像读取失败（不是"未上报"）：不显示空表单，给重试 */
  deviceSettingsFailed: boolean;
};

const fmtTime = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * 参数设置页签（#4，cloud config-command-contract）。管理员直接修改，没有逐台开放（2026-11-09）：设备上报的当前配置 → 业务表单 → 确认下发 → 跟踪命令 → 核对回显。
 * 设备是配置的权威来源：页面只显示设备回报，不乐观更新；命令成功后也要读到一致的回报才说"已一致"。
 */
const SettingsTab: React.FC<{ device: string }> = ({ device }) => {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  const access = useAccess();
  const isPlatform = access.canPlatformAdmin;
  const fmt = useFormatValue();

  const [data, setData] = useState<Loaded>();
  const [loadError, setLoadError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  // 编辑会话：油站关系变化（onStale）时加一，两个表单的草稿随之清空，旧油站下的改动不会按新油站发出（Codex 评审 PR #14）
  const [session, setSession] = useState(0);
  const [tankNo, setTankNo] = useState<number>();
  const [request, setRequest] = useState<SubmitRequest>();
  const [tracked, setTracked] = useState<Tracked>();

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setLoadError(false);
    (async () => {
      try {
        const [rows, tanks, deviceSettings] = await Promise.all([
          listDevices(),
          getTankSettings(device),
          // 旧版云端没有整机镜像接口：退化为未上报，不影响罐设置
          // 只有旧版云端（没有该接口，裸 404）才退化为未上报；其他失败如实显示，不当成"从未上报"
          getDeviceSettings(device).catch((err) => {
            if (err instanceof ApiError && err.authHandled) throw err;
            if (err instanceof ApiError && err.status === 404 && !err.code)
              return null;
            return 'failed' as const;
          }),
        ]);
        const row = rows.find((r) => r.device_name === device);
        let stationLabel = t('pages.console.stations.devices.station.unknown');
        if (row?.station_id === null) {
          stationLabel = t('pages.console.stations.devices.station.none');
        } else if (typeof row?.station_id === 'number') {
          const stations = await listStations({ silent: true }).catch(
            () => [] as API.Station[],
          );
          stationLabel =
            stations.find((s) => s.id === row.station_id)?.name ??
            `#${row.station_id}`;
        }
        if (!alive) return;
        setData({
          row,
          stationLabel,
          tanks,
          deviceSettings: deviceSettings === 'failed' ? null : deviceSettings,
          deviceSettingsFailed: deviceSettings === 'failed',
        });
        setTankNo((cur) =>
          cur !== undefined && tanks.some((x) => x.tank === cur)
            ? cur
            : tanks[0]?.tank,
        );
      } catch (err) {
        if (!alive) return;
        if (err instanceof ApiError && err.authHandled) return;
        setLoadError(true);
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [device, reload]);

  // 介质选项仅平台管理员可读；客户侧只显示当前介质（#27）
  const [media, setMedia] = useState<API.MediaOptions>();
  const [mediaFailed, setMediaFailed] = useState(false);
  useEffect(() => {
    if (!isPlatform) return;
    let alive = true;
    getDeviceMediaOptions(device)
      .then((m) => {
        if (!alive) return;
        setMedia(m);
        setMediaFailed(false);
      })
      .catch(() => {
        if (alive) setMediaFailed(true);
      });
    return () => {
      alive = false;
    };
  }, [device, reload, isPlatform]);

  const target = tracked?.command.configTarget;
  const readMirror = useCallback(async () => {
    if (target === 'device') return (await getDeviceSettings(device)) as never;
    const n = Number(
      /^tank:(\d+)$/.exec(target ?? '')?.[1] ?? tracked?.args.tank,
    );
    return ((await getTankSettings(device)).find((x) => x.tank === n) ??
      null) as never;
  }, [device, target, tracked?.args.tank]);
  const tracker = useCommandTracker(device, tracked, readMirror);

  // 回显一致后刷新当前值，表单随之清空
  useEffect(() => {
    if (tracker.echo === 'matched') setReload((n) => n + 1);
  }, [tracker.echo]);

  const tank = data?.tanks.find((x) => x.tank === tankNo);
  const stationKnown =
    data?.row !== undefined && data.row.station_id !== undefined;
  const busyCommand =
    tracker.command !== undefined && !tracker.command.terminal;
  // 没有设备级开放步骤（2026-11-09 用户决定）：管理员直接修改；系统总闸关闭时提交会得到 config_commands_disabled
  const canSubmitBase = stationKnown && !busyCommand;

  const openSubmit = (
    fields: FieldDef[],
    current: Record<string, unknown> | null,
    args: Record<string, number>,
    method: SubmitRequest['method'],
    tankNumber?: number,
  ) => {
    const rows = fields
      .filter((f) => f.key in args)
      .map((f) => ({
        field: f.key,
        label: t(`pages.console.settings.field.${f.key}`),
        before: fmt(f, current?.[f.key], current?.oilType),
        after: fmt(f, args[f.key]),
      }));
    const medium = [...(media?.public ?? []), ...(media?.customer ?? [])].find(
      (m) => m.id === args.mediaId,
    );
    const mediumField = fields.find((f) => f.kind === 'oil');
    if (medium && mediumField) {
      rows.push({
        field: mediumField.key,
        label: t(`pages.console.settings.field.${mediumField.key}`),
        before: fmt(mediumField, current?.[mediumField.key], current?.oilType),
        after: medium.name,
      });
    }
    setRequest({
      method,
      args: tankNumber !== undefined ? { tank: tankNumber, ...args } : args,
      rows,
      tank: tankNumber,
    });
  };

  if (loading && !data) return <Spin />;
  if (loadError && !data) {
    return (
      <Alert
        type="error"
        showIcon
        message={t('pages.console.settings.loadFailed')}
        action={
          <Button onClick={() => setReload((n) => n + 1)}>
            {t('pages.console.settings.retry')}
          </Button>
        }
      />
    );
  }
  if (!data) return null;

  const stationAlert = !stationKnown && (
    <Alert
      style={{ marginBottom: 16 }}
      type="warning"
      showIcon
      message={t('pages.console.settings.stationUnknown')}
    />
  );

  return (
    <div>
      {stationAlert}
      <CommandProgress
        {...tracker}
        onRefreshEcho={tracker.refreshEcho}
        onDismiss={() => setTracked(undefined)}
      />

      <Card
        size="small"
        title={t('pages.console.settings.tankTitle')}
        style={{ marginBottom: 16 }}
        extra={
          tank && (
            <Typography.Text type="secondary">
              {t('pages.console.settings.reportedAt', {
                time: fmtTime(tank.reportedAt),
              })}
            </Typography.Text>
          )
        }
      >
        {data.tanks.length === 0 ? (
          <Typography.Text type="secondary">
            {t('pages.console.settings.notReported')}
          </Typography.Text>
        ) : (
          <>
            {data.tanks.length > 1 && (
              <Segmented
                style={{ marginBottom: 12 }}
                value={tankNo}
                onChange={(v) => setTankNo(Number(v))}
                options={data.tanks.map((x) => ({
                  value: x.tank,
                  label: t('pages.console.settings.tankN', { tank: x.tank }),
                }))}
              />
            )}
            {isPlatform && mediaFailed && (
              <Alert
                style={{ marginBottom: 12 }}
                type="warning"
                showIcon
                message={t('pages.console.settings.medium.loadFailed')}
              />
            )}
            {media && (
              <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                {media.customMediaSync
                  ? t('pages.console.settings.medium.slots', {
                      used: media.slotsUsed,
                      total: media.slotsTotal,
                    })
                  : t('pages.console.settings.medium.noSync')}
              </Typography.Paragraph>
            )}
            {tank && (
              <SettingsForm
                fields={TANK_FIELDS}
                current={tank as unknown as Record<string, unknown>}
                tank={tank}
                canEdit={canSubmitBase}
                media={media}
                readOnlyNote="pages.console.settings.readOnlyNote"
                resetKey={`${session}-${data.row?.station_id}-${tank.tank}-${tank.reportedAt}`}
                onSubmit={(args) =>
                  openSubmit(
                    TANK_FIELDS,
                    tank as unknown as Record<string, unknown>,
                    args,
                    'config.setTankSettings',
                    tank.tank,
                  )
                }
              />
            )}
            {tank && (
              <CalibrationPanel
                key={`${device}-${tank.tank}`}
                device={device}
                tank={tank.tank}
              />
            )}
          </>
        )}
      </Card>

      <Card
        size="small"
        title={t('pages.console.settings.deviceTitle')}
        extra={
          data.deviceSettings && (
            <Typography.Text type="secondary">
              {t('pages.console.settings.reportedAt', {
                time: fmtTime(data.deviceSettings.reportedAt),
              })}
            </Typography.Text>
          )
        }
      >
        {data.deviceSettingsFailed && (
          <Alert
            type="error"
            showIcon
            message={t('pages.console.settings.deviceLoadFailed')}
            action={
              <Button size="small" onClick={() => setReload((n) => n + 1)}>
                {t('pages.console.settings.retry')}
              </Button>
            }
          />
        )}
        {!data.deviceSettings && !data.deviceSettingsFailed && (
          <Typography.Paragraph type="secondary">
            {t('pages.console.settings.deviceNotReported')}
          </Typography.Paragraph>
        )}
        {!data.deviceSettingsFailed && (
          <SettingsForm
            fields={DEVICE_FIELDS}
            current={
              (data.deviceSettings as unknown as Record<string, unknown>) ??
              null
            }
            canEdit={isPlatform && canSubmitBase}
            readOnlyNote={
              isPlatform
                ? undefined
                : 'pages.console.settings.devicePlatformOnly'
            }
            resetKey={`${session}-${data.row?.station_id}-${data.deviceSettings?.reportedAt ?? 'none'}`}
            onSubmit={(args) =>
              openSubmit(
                DEVICE_FIELDS,
                (data.deviceSettings as unknown as Record<string, unknown>) ??
                  null,
                args,
                'config.setDeviceSettings',
              )
            }
          />
        )}
      </Card>

      <ConfigSubmitModal
        device={device}
        expectedStationId={data.row?.station_id ?? null}
        stationLabel={data.stationLabel}
        request={request}
        onClose={() => setRequest(undefined)}
        onTracked={(command, args) =>
          setTracked({ command, args: trackedArgs(command, args) })
        }
        onStale={() => {
          setRequest(undefined);
          setSession((n) => n + 1);
          message.warning(
            t('pages.console.settings.submit.error.stationChanged'),
          );
          setReload((n) => n + 1);
        }}
      />
    </div>
  );
};

/** 标定表（只读，按需加载）。设备只在 MQTT 重连时发布，所以可能滞后于现场。 */
const CalibrationPanel: React.FC<{ device: string; tank: number }> = ({
  device,
  tank,
}) => {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<{
    cal?: API.TankCalibration | null;
    error?: boolean;
  }>();

  useEffect(() => {
    if (!open || state) return;
    getTankCalibration(device, tank)
      .then((cal) => setState({ cal }))
      .catch((err) => {
        if (!(err instanceof ApiError && err.authHandled))
          setState({ error: true });
      });
  }, [open, state, device, tank]);

  const rows = useMemo(
    () =>
      state?.cal
        ? state.cal.heightMm.map((h, i) => ({ i, h, v: state.cal?.volumeL[i] }))
        : [],
    [state],
  );

  return (
    <Collapse
      style={{ marginTop: 16 }}
      size="small"
      onChange={(keys) =>
        setOpen((Array.isArray(keys) ? keys : [keys]).length > 0)
      }
      items={[
        {
          key: 'cal',
          label: t('pages.console.settings.calibration.title'),
          children: !state ? (
            <Spin size="small" />
          ) : state.error ? (
            <Typography.Text type="danger">
              {t('pages.console.settings.calibration.failed')}
            </Typography.Text>
          ) : !state.cal ? (
            <Typography.Text type="secondary">
              {t('pages.console.settings.calibration.none')}
            </Typography.Text>
          ) : (
            <Space direction="vertical" style={{ width: '100%' }}>
              <Typography.Text type="secondary">
                {t('pages.console.settings.calibration.meta', {
                  n: state.cal.pointCount,
                  time: fmtTime(state.cal.reportedAt),
                })}
              </Typography.Text>
              <Table
                size="small"
                rowKey="i"
                dataSource={rows}
                pagination={{ pageSize: 20, size: 'small' }}
                columns={[
                  {
                    title: t('pages.console.settings.calibration.height'),
                    dataIndex: 'h',
                  },
                  {
                    title: t('pages.console.settings.calibration.volume'),
                    dataIndex: 'v',
                  },
                ]}
              />
            </Space>
          ),
        },
      ]}
    />
  );
};

export default SettingsTab;
