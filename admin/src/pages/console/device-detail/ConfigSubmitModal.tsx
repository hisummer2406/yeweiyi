import { useIntl } from '@umijs/max';
import { Alert, Descriptions, Input, Modal, Table, Typography } from 'antd';
import type React from 'react';
import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  type ConfigSubmission,
  findCommandByRequestId,
  getCommand,
  issueConfigCommand,
} from '@/services/ant-design-pro/deviceConfig';
import { listDevices } from '@/services/ant-design-pro/devices';
import { decodeToken, getToken } from '@/utils/auth';
import { classifySubmitError, newRequestId } from '@/utils/deviceConfig';

export type ChangeRow = {
  field: string;
  label: string;
  before: string;
  after: string;
};

export type SubmitRequest = {
  method: ConfigSubmission['method'];
  /** 完整 args（含 tank） */
  args: Record<string, number>;
  rows: ChangeRow[];
  /** 罐号；整机设置为 undefined */
  tank?: number;
};

type Props = {
  device: string;
  /** 表单打开时设备所在油站（null = 不在任何油站） */
  expectedStationId: number | null;
  stationLabel: string;
  request?: SubmitRequest;
  onClose: () => void;
  /** 云端已受理（新命令、重放、或超时后找回），交给跟踪器 */
  onTracked: (
    command: API.ControlCommand,
    args: Record<string, unknown>,
  ) => void;
  /** 油站关系已变：调用方丢弃表单并重新读取 */
  onStale: () => void;
};

const MAX_REASON = 200;

type PendingSubmission = {
  body: ConfigSubmission;
  request: SubmitRequest;
  stationLabel: string;
};

function readPending(key: string): PendingSubmission | undefined {
  try {
    const raw = sessionStorage.getItem(key);
    const saved = raw ? JSON.parse(raw) : undefined;
    return saved?.body?.requestId && saved?.request?.rows ? saved : undefined;
  } catch {
    return undefined;
  }
}

/**
 * 配置下发的二次确认（cloud config-command-contract §1、§6）：
 * - 未确认提交按账号和设备保存快照；关闭、换页或刷新后恢复同一请求，不能换编号重复下发。
 * - 确认时先重读设备列表：设备不可见或油站变了，就不发，交给调用方刷新。expectedStationId 仍发表单打开时的值，由云端裁决。
 * - 请求超时或云端答交付未确认：先按 requestId 找回；找不到只给「用同一编号重试」，绝不换新编号自动重发。
 * - 同目标在途：取在途命令交给跟踪器。错误按 code 归类，不显示云端原文。
 */
const ConfigSubmitModal: React.FC<Props> = ({
  device,
  expectedStationId,
  stationLabel,
  request,
  onClose,
  onTracked,
  onStale,
}) => {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [fieldErrors, setFieldErrors] = useState<string[]>([]);
  const [unconfirmed, setUnconfirmed] = useState(false);
  const [pending, setPending] = useState<PendingSubmission>();
  const requestId = useRef('');
  const inFlight = useRef(false);
  const userId = decodeToken(getToken())?.userId;
  const sameActor = () => decodeToken(getToken())?.userId === userId;
  const pendingKey = `yeweiyi-config-pending:${userId ?? 'anonymous'}:${device}`;

  const savePending = (value: PendingSubmission | undefined) => {
    setPending(value);
    try {
      if (value) sessionStorage.setItem(pendingKey, JSON.stringify(value));
      else sessionStorage.removeItem(pendingKey);
    } catch {
      // 禁用浏览器存储时仍保留当前弹窗的快照。
    }
  };

  useEffect(() => {
    if (!request) return;
    const saved = readPending(pendingKey);
    setPending(saved);
    requestId.current = saved?.body.requestId ?? newRequestId();
    setReason(saved?.body.reason ?? '');
    setError(
      saved ? t('pages.console.settings.submit.error.unconfirmed') : undefined,
    );
    setFieldErrors([]);
    setUnconfirmed(!!saved);
    inFlight.current = false;
  }, [request, pendingKey]);

  const activeRequest = pending?.request ?? request;

  const fail = (id: string) =>
    setError(t(`pages.console.settings.submit.error.${id}`));

  const submit = async () => {
    if (!activeRequest || inFlight.current) return;
    const why = reason.trim();
    if (!why) return;
    inFlight.current = true;
    setBusy(true);
    setError(undefined);
    setFieldErrors([]);
    const body: ConfigSubmission = pending?.body ?? {
      method: activeRequest.method,
      args: activeRequest.args,
      reason: why,
      requestId: requestId.current,
      expectedStationId,
    };
    try {
      if (pending) {
        const found = await findCommandByRequestId(device, body.requestId);
        if (found) {
          savePending(undefined);
          if (sameActor()) {
            onTracked(found, body.args);
            onClose();
          }
          return;
        }
      }
      // 换机/改归属预检：列表可能过期，云端事务才是裁决，这里只为不把明知过期的确认发出去
      let row: API.DeviceRow | undefined;
      try {
        row = (await listDevices()).find((r) => r.device_name === device);
      } catch (err) {
        if (!(err instanceof ApiError && err.authHandled)) fail('unavailable');
        return;
      }
      if (
        !row ||
        row.station_id === undefined ||
        row.station_id !== body.expectedStationId
      ) {
        fail('stationChanged');
        onStale();
        return;
      }
      if (!sameActor()) return;
      // 发出前保存：即使请求中途刷新页面，恢复时也只重放这一份快照。
      savePending({
        body,
        request: activeRequest,
        stationLabel: pending?.stationLabel ?? stationLabel,
      });
      const { command } = await issueConfigCommand(device, body);
      savePending(undefined);
      if (sameActor()) {
        onTracked(command, body.args);
        onClose();
      }
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : new ApiError(0);
      if (apiErr.authHandled) return;
      const kind = classifySubmitError(apiErr);
      if (kind === 'unconfirmed') {
        // 结果未知：先找回，找不到也不自动重发
        try {
          const found = await findCommandByRequestId(device, body.requestId);
          if (found) {
            savePending(undefined);
            if (sameActor()) {
              onTracked(found, body.args);
              onClose();
            }
            return;
          }
        } catch {
          // 一并按未确认处理
        }
        setUnconfirmed(true);
        fail('unconfirmed');
        return;
      }
      if (!pending) savePending(undefined);
      if (kind === 'inFlight') {
        const holder = apiErr.data?.commandId;
        if (typeof holder === 'string' && holder) {
          try {
            const cmd = await getCommand(device, holder);
            onTracked(cmd, (cmd.args ?? {}) as Record<string, unknown>);
          } catch {
            // 读不到就只提示
          }
        }
        fail('inFlight');
        return;
      }
      if (kind === 'stationChanged') {
        fail('stationChanged');
        onStale();
        return;
      }
      if (kind === 'invalidArgs') {
        const fields = Array.isArray(apiErr.data?.fields)
          ? apiErr.data.fields
          : [];
        setFieldErrors(
          fields
            .map((f) => (f as { field?: string }).field)
            .filter((f): f is string => typeof f === 'string')
            .map((f) => t(`pages.console.settings.field.${f}`)),
        );
      }
      fail(kind);
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };

  const target =
    activeRequest?.tank !== undefined
      ? t('pages.console.settings.submit.targetTank', {
          tank: activeRequest.tank,
        })
      : t('pages.console.settings.submit.targetDevice');

  return (
    <Modal
      open={!!request}
      title={t('pages.console.settings.submit.title')}
      okText={
        unconfirmed
          ? t('pages.console.settings.submit.retrySame')
          : t('pages.console.settings.submit.ok')
      }
      okButtonProps={{ disabled: !reason.trim(), loading: busy }}
      cancelButtonProps={{ disabled: busy }}
      onOk={submit}
      onCancel={busy ? undefined : onClose}
      maskClosable={false}
      destroyOnHidden
      width={640}
    >
      <Descriptions size="small" column={1} style={{ marginBottom: 12 }}>
        <Descriptions.Item label={t('pages.console.settings.submit.device')}>
          <Typography.Text strong copyable>
            {device}
          </Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('pages.console.settings.submit.station')}>
          {pending?.stationLabel ?? stationLabel}
        </Descriptions.Item>
        <Descriptions.Item label={t('pages.console.settings.submit.target')}>
          {target}
        </Descriptions.Item>
      </Descriptions>
      <Table<ChangeRow>
        size="small"
        pagination={false}
        rowKey="field"
        dataSource={activeRequest?.rows ?? []}
        columns={[
          {
            title: t('pages.console.settings.submit.col.field'),
            dataIndex: 'label',
          },
          {
            title: t('pages.console.settings.submit.col.before'),
            dataIndex: 'before',
          },
          {
            title: t('pages.console.settings.submit.col.after'),
            dataIndex: 'after',
            render: (v: string) => (
              <Typography.Text strong>{v}</Typography.Text>
            ),
          },
        ]}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
        {t('pages.console.settings.submit.note')}
      </Typography.Paragraph>
      <Input.TextArea
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        maxLength={MAX_REASON}
        showCount
        disabled={busy || unconfirmed}
        placeholder={t('pages.console.settings.submit.reason')}
        aria-label={t('pages.console.settings.submit.reason')}
        autoSize={{ minRows: 2, maxRows: 4 }}
      />
      {error && (
        <Alert
          style={{ marginTop: 12 }}
          type={unconfirmed ? 'warning' : 'error'}
          showIcon
          message={error}
          description={fieldErrors.length ? fieldErrors.join('、') : undefined}
        />
      )}
    </Modal>
  );
};

export default ConfigSubmitModal;
