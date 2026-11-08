import { useAccess, useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Input,
  Modal,
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
  setConfigGate,
} from '@/services/ant-design-pro/deviceConfig';
import { listDevices } from '@/services/ant-design-pro/devices';
import { listStations } from '@/services/ant-design-pro/stations';
import {
  DEVICE_FIELDS,
  type FieldDef,
  TANK_FIELDS,
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
};

const fmtTime = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * 参数设置页签（#4，cloud config-command-contract）：设备上报的当前配置 → 业务表单 → 确认下发 → 跟踪命令 → 核对回显。
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
  const [tankNo, setTankNo] = useState<number>();
  const [request, setRequest] = useState<SubmitRequest>();
  const [tracked, setTracked] = useState<Tracked>();
  const [gateModal, setGateModal] = useState<boolean>();
  const [gateReason, setGateReason] = useState('');
  const [gateBusy, setGateBusy] = useState(false);

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
          getDeviceSettings(device).catch(() => null),
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
        setData({ row, stationLabel, tanks, deviceSettings });
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
  const gateKnown = typeof data?.row?.config_commands_enabled === 'boolean';
  const gateOpen = data?.row?.config_commands_enabled === true;
  const busyCommand =
    tracker.command !== undefined && !tracker.command.terminal;
  const canSubmitBase = stationKnown && gateOpen && !busyCommand;

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
    setRequest({
      method,
      args: tankNumber !== undefined ? { tank: tankNumber, ...args } : args,
      rows,
      tank: tankNumber,
    });
  };

  const toggleGate = async () => {
    const why = gateReason.trim();
    if (!why || gateModal === undefined) return;
    setGateBusy(true);
    try {
      await setConfigGate(device, gateModal, why);
      message.success(
        t(
          gateModal
            ? 'pages.console.settings.gate.opened'
            : 'pages.console.settings.gate.closed',
        ),
      );
      setGateModal(undefined);
      setReload((n) => n + 1);
    } catch (err) {
      if (!(err instanceof ApiError && err.authHandled)) {
        message.error(t('pages.console.settings.gate.failed'));
      }
    } finally {
      setGateBusy(false);
    }
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

  const gateAlert = (
    <Alert
      style={{ marginBottom: 16 }}
      type={gateOpen ? 'success' : 'info'}
      showIcon
      message={
        !gateKnown
          ? t('pages.console.settings.gate.unknown')
          : gateOpen
            ? t('pages.console.settings.gate.open')
            : t('pages.console.settings.gate.closedHint')
      }
      description={
        !stationKnown ? t('pages.console.settings.stationUnknown') : undefined
      }
      action={
        isPlatform && gateKnown ? (
          <Button
            size="small"
            onClick={() => {
              setGateReason('');
              setGateModal(!gateOpen);
            }}
          >
            {t(
              gateOpen
                ? 'pages.console.settings.gate.close'
                : 'pages.console.settings.gate.openAction',
            )}
          </Button>
        ) : undefined
      }
    />
  );

  return (
    <div>
      {gateAlert}
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
            {tank && (
              <SettingsForm
                fields={TANK_FIELDS}
                current={tank as unknown as Record<string, unknown>}
                tank={tank}
                canEdit={canSubmitBase}
                readOnlyNote="pages.console.settings.readOnlyNote"
                resetKey={`${tank.tank}-${tank.reportedAt}`}
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
        {!data.deviceSettings && (
          <Typography.Paragraph type="secondary">
            {t('pages.console.settings.deviceNotReported')}
          </Typography.Paragraph>
        )}
        <SettingsForm
          fields={DEVICE_FIELDS}
          current={
            (data.deviceSettings as unknown as Record<string, unknown>) ?? null
          }
          canEdit={isPlatform && canSubmitBase}
          readOnlyNote={
            isPlatform ? undefined : 'pages.console.settings.devicePlatformOnly'
          }
          resetKey={data.deviceSettings?.reportedAt ?? 'none'}
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
      </Card>

      <ConfigSubmitModal
        device={device}
        expectedStationId={data.row?.station_id ?? null}
        stationLabel={data.stationLabel}
        request={request}
        onClose={() => setRequest(undefined)}
        onTracked={(command, args) => setTracked({ command, args })}
        onStale={() => {
          setRequest(undefined);
          message.warning(
            t('pages.console.settings.submit.error.stationChanged'),
          );
          setReload((n) => n + 1);
        }}
      />

      <Modal
        open={gateModal !== undefined}
        title={t(
          gateModal
            ? 'pages.console.settings.gate.openTitle'
            : 'pages.console.settings.gate.closeTitle',
        )}
        okButtonProps={{ disabled: !gateReason.trim(), loading: gateBusy }}
        onOk={toggleGate}
        onCancel={() => setGateModal(undefined)}
        destroyOnHidden
      >
        <Typography.Paragraph>
          {t(
            gateModal
              ? 'pages.console.settings.gate.openWarn'
              : 'pages.console.settings.gate.closeWarn',
            {
              device,
            },
          )}
        </Typography.Paragraph>
        <Input.TextArea
          value={gateReason}
          onChange={(e) => setGateReason(e.target.value)}
          maxLength={200}
          showCount
          aria-label={t('pages.console.settings.submit.reason')}
          placeholder={t('pages.console.settings.submit.reason')}
        />
      </Modal>
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
