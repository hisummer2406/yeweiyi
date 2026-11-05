import { useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  Descriptions,
  Input,
  Modal,
  Space,
  Steps,
  Typography,
} from 'antd';
import type React from 'react';
import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  getDevicePurge,
  listDevicePurges,
  purgeDevice,
} from '@/services/ant-design-pro/devices';
import { describePurgeError } from './errors';

type Target = API.DeviceRow & { customerName?: string; stationName?: string };

type Props = {
  /** 要彻底删除的设备；undefined 时弹窗关闭 */
  device?: Target;
  onClose: () => void;
  /** 删除任务已建立、或已有结果（完成/中途关闭）后调用，页面据此刷新设备与油站占用 */
  onDone: () => void;
  /** 轮询任务状态的间隔，测试里缩短 */
  pollMs?: number;
};

const STEP_INDEX: Record<API.DevicePurge['step'], number> = {
  thingsboard: 0,
  business: 1,
  verify: 2,
  done: 3,
};

// 完成后列出的删除条数；云端 counts 里没有（为 0 或没到这一步）的不显示
const COUNT_KEYS: [string, string][] = [
  ['stockEvents', 'pages.ops.devices.purge.count.stockEvents'],
  ['alarmEvents', 'pages.ops.devices.purge.count.alarmEvents'],
  ['recordRejects', 'pages.ops.devices.purge.count.recordRejects'],
  ['telemetryHourly', 'pages.ops.devices.purge.count.telemetryHourly'],
  ['controlCommands', 'pages.ops.devices.purge.count.controlCommands'],
  ['tankGrants', 'pages.ops.devices.purge.count.tankGrants'],
];

/**
 * 彻底删除设备及其全部云端历史（cloud#83，admin#29）。不可撤销，所以：
 * - 提交前要输入与设备编号逐字相同的确认，并写删除原因；取消、关闭都不发请求。
 * - 云端是异步任务：201/202 只表示任务存在，页面持续展示云端返回的真实状态，只有 completed 才提示删除成功。
 * - 失败（failed）停在失败步骤，给出云端原因和「重试」；重试只是再发一次同一请求，云端返回原任务继续执行。
 * - 请求超时结果未知：先按设备编号找回任务，找不到也不自动重发，请用户确认后手动再提交。
 * - 打开弹窗时先按设备编号找一下未完成的任务（页面刷新、上次中途关闭），有就直接接着看进度。
 * 后台不编排多个删除请求，也不碰数据库/ThingsBoard，清理范围与失败恢复都在云端。
 */
const PurgeDeviceModal: React.FC<Props> = ({
  device,
  onClose,
  onDone,
  pollMs = 2000,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string | number>) =>
    intl.formatMessage({ id }, values);
  const [typed, setTyped] = useState('');
  const [reason, setReason] = useState('');
  const [purge, setPurge] = useState<API.DevicePurge>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();
  const [pollFailing, setPollFailing] = useState(false);
  const busy = useRef(false);
  const started = useRef(false);
  const doneNotified = useRef(false);

  const name = device?.device_name;

  // 换了目标设备：清空所有状态，并找回这台设备未完成的删除任务
  useEffect(() => {
    setTyped('');
    setReason('');
    setPurge(undefined);
    setError(undefined);
    setPollFailing(false);
    busy.current = false;
    started.current = false;
    doneNotified.current = false;
    if (!name) return;
    let cancelled = false;
    listDevicePurges(name)
      .then((rows) => {
        const open = rows.find((r) => r.status !== 'completed');
        if (!cancelled && open) {
          started.current = true;
          setPurge(open);
        }
      })
      .catch(() => undefined); // 找不到就走确认流程；云端对同一设备的重复请求会返回原任务
    return () => {
      cancelled = true;
    };
  }, [name]);

  // 任务执行中：按间隔取真实状态。取不到只提示，不当成完成也不当成失败，继续取
  const runningId = purge?.status === 'running' ? purge.id : undefined;
  useEffect(() => {
    if (runningId === undefined) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const next = await getDevicePurge(runningId);
        if (cancelled) return;
        setPollFailing(false);
        setPurge(next);
        // 仍在执行就继续取；effect 的依赖（任务号）不变，不会自己重跑
        if (next.status === 'running') timer = setTimeout(tick, pollMs);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.authHandled) return;
        setPollFailing(true);
        timer = setTimeout(tick, pollMs);
      }
    };
    timer = setTimeout(tick, pollMs);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [runningId, pollMs]);

  // 状态一变成 running 以外就刷新一次；完成时设备已不在列表里
  useEffect(() => {
    if (purge && purge.status !== 'running' && !doneNotified.current) {
      doneNotified.current = true;
      onDone();
    }
  }, [purge, onDone]);

  const submit = async (body: { confirm: string; reason: string }) => {
    if (!name || busy.current) return;
    busy.current = true;
    setSubmitting(true);
    setError(undefined);
    try {
      const next = await purgeDevice(name, {
        confirmDeviceName: body.confirm,
        reason: body.reason,
      });
      started.current = true;
      doneNotified.current = false;
      setPurge(next);
      if (next.status === 'running') onDone(); // 任务已建立：设备已被云端挡住写入，先刷新一次
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      if (apiErr?.status === 0) {
        // 超时/断网：云端可能已经受理。先找回任务，找不到也不重发
        try {
          const open = (await listDevicePurges(name)).find(
            (r) => r.status !== 'completed',
          );
          if (open) {
            started.current = true;
            setPurge(open);
            return;
          }
        } catch {
          // 一并按结果未知处理
        }
      }
      const m = describePurgeError(
        apiErr?.status ?? 0,
        apiErr?.code,
        apiErr?.error ?? (err as Error)?.message,
      );
      setError(t(m.id, { detail: m.detail ?? '' }));
      if (apiErr?.status === 404) onDone(); // 设备已经不在了，刷新列表
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  };

  const close = () => {
    // 任务已建立但没看到结果就关闭：云端继续执行，列表刷新一次反映当前状态
    if (started.current && !doneNotified.current) onDone();
    onClose();
  };

  const customer =
    device?.customer_id == null
      ? t('pages.ops.devices.customer.unassigned')
      : (device.customerName ?? `#${device.customer_id}`);
  const station =
    device?.station_id == null
      ? t('pages.console.stations.devices.station.none')
      : (device.stationName ?? `#${device.station_id}`);

  const confirmed = typed === name && reason.trim() !== '';

  const renderConfirm = () => (
    <>
      <Alert
        type="error"
        showIcon
        style={{ marginBottom: 16 }}
        title={t('pages.ops.devices.purge.warning')}
        description={t('pages.ops.devices.purge.warningDetail')}
      />
      <Descriptions
        size="small"
        column={1}
        style={{ marginBottom: 16 }}
        items={[
          {
            key: 'name',
            label: t('pages.ops.devices.col.name'),
            children: <Typography.Text code>{name}</Typography.Text>,
          },
          {
            key: 'customer',
            label: t('pages.ops.devices.col.customer'),
            children: customer,
          },
          {
            key: 'station',
            label: t('pages.console.stations.devices.col.station'),
            children: station,
          },
        ]}
      />
      <Typography.Paragraph style={{ marginBottom: 4 }}>
        {t('pages.ops.devices.purge.reason')}
      </Typography.Paragraph>
      <Input.TextArea
        aria-label={t('pages.ops.devices.purge.reason')}
        rows={2}
        maxLength={500}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        style={{ marginBottom: 16 }}
      />
      <Typography.Paragraph style={{ marginBottom: 4 }}>
        {t('pages.ops.devices.purge.typeName', { device: name ?? '' })}
      </Typography.Paragraph>
      <Input
        aria-label={t('pages.ops.devices.purge.confirmLabel')}
        autoComplete="off"
        value={typed}
        onChange={(e) => setTyped(e.target.value)}
      />
      {error && (
        <Alert type="error" showIcon style={{ marginTop: 16 }} title={error} />
      )}
    </>
  );

  const renderProgress = (p: API.DevicePurge) => {
    const failed = p.status === 'failed';
    const completed = p.status === 'completed';
    return (
      <>
        <Steps
          size="small"
          current={STEP_INDEX[p.step]}
          status={failed ? 'error' : completed ? 'finish' : 'process'}
          style={{ marginBottom: 16 }}
          items={[
            { title: t('pages.ops.devices.purge.step.thingsboard') },
            { title: t('pages.ops.devices.purge.step.business') },
            { title: t('pages.ops.devices.purge.step.verify') },
          ]}
        />
        {completed && (
          <Alert
            type="success"
            showIcon
            title={t('pages.ops.devices.purge.completed', {
              device: p.deviceName,
            })}
            description={
              <>
                <div>{t('pages.ops.devices.purge.completedHint')}</div>
                <div>
                  {COUNT_KEYS.filter(([k]) => (p.counts[k] ?? 0) > 0)
                    .map(([k, id]) => `${t(id)} ${p.counts[k]}`)
                    .join('　')}
                </div>
              </>
            }
          />
        )}
        {failed && (
          <Alert
            type="error"
            showIcon
            title={t('pages.ops.devices.purge.failed', {
              step: t(`pages.ops.devices.purge.step.${p.step}`),
            })}
            description={
              <>
                <div>{p.error}</div>
                <div>{t('pages.ops.devices.purge.failedHint')}</div>
              </>
            }
          />
        )}
        {p.status === 'running' && (
          <Alert
            type="info"
            showIcon
            title={t('pages.ops.devices.purge.running')}
            description={
              pollFailing ? t('pages.ops.devices.purge.pollFailing') : undefined
            }
          />
        )}
        {error && (
          <Alert
            type="error"
            showIcon
            style={{ marginTop: 16 }}
            title={error}
          />
        )}
      </>
    );
  };

  const footer = () => {
    if (!purge) {
      return (
        <Space>
          <Button onClick={close}>{t('pages.ops.devices.form.cancel')}</Button>
          <Button
            danger
            type="primary"
            disabled={!confirmed}
            loading={submitting}
            onClick={() => submit({ confirm: typed, reason: reason.trim() })}
          >
            {t('pages.ops.devices.purge.submit')}
          </Button>
        </Space>
      );
    }
    return (
      <Space>
        {purge.status === 'failed' && (
          <Button
            danger
            type="primary"
            loading={submitting}
            onClick={() =>
              submit({ confirm: purge.deviceName, reason: purge.reason })
            }
          >
            {t('pages.ops.devices.purge.retry')}
          </Button>
        )}
        <Button onClick={close}>{t('pages.ops.devices.purge.close')}</Button>
      </Space>
    );
  };

  return (
    <Modal
      open={!!device}
      title={`${t('pages.ops.devices.purge.title')}：${name ?? ''}`}
      footer={footer()}
      onCancel={close}
      maskClosable={false}
      destroyOnHidden
    >
      {purge ? renderProgress(purge) : renderConfirm()}
    </Modal>
  );
};

export default PurgeDeviceModal;
