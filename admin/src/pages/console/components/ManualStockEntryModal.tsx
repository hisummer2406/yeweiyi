import { useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Radio,
  Select,
  Typography,
} from 'antd';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import type React from 'react';
import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  addManualStockEvent,
  type ManualStockEntry,
} from '@/services/ant-design-pro/stock';
import { getDeviceTanks } from '@/services/ant-design-pro/tankGrants';

type Props = {
  device: string;
  open: boolean;
  /** 入库页 / 出库页固定方向；设备详情页签不传，让用户选 */
  direction?: 'IN' | 'OUT';
  onClose: () => void;
  /** 已保存（新建或同编号重放），调用方刷新列表 */
  onSaved: () => void;
};

type Values = {
  direction: 'IN' | 'OUT';
  tank: number;
  time: Dayjs;
  beforeLevelMm: number;
  volumeL: number;
  reason: string;
};

const KNOWN_CODES = [
  'time_in_future',
  'time_before_visible',
  'time_invalid',
  'tank_invalid',
  'level_invalid',
  'volume_invalid',
  'reason_required',
  'request_id_reused',
  'device_purge_in_progress',
];

/**
 * 结果未确认的提交（超时）按设备记在这里，跨关闭/重新打开保留：再打开时恢复同一份请求体与 requestId，
 * 重试发送的是这份快照而不是表单当前值，所以既不会换编号重复保存，也不会因改了字段被判 request_id_reused
 * （Codex 评审 PR #16）。只有成功、或用户明确「放弃」才清掉。
 */
type Pending = { body: ManualStockEntry; values: Values };
const pendingByDevice = new Map<string, Pending>();

const newRequestId = () =>
  `admbf-${Date.now().toString(36)}-${Math.floor(Math.random() * 36 ** 8)
    .toString(36)
    .padStart(8, '0')}`;

/**
 * 云端补录（cloud#69，docs/stock-manual-entry-contract.md）：新增一条设备漏报的出入库记录，不改写设备已上报的任何记录。
 * - 打开弹窗生成一次 requestId；这次的重试（含超时后"用同一编号重试"）都复用它，云端对同一编号只建一条（重放 200）。
 * - 时间不能晚于现在（日期选择器挡未来日期，云端再裁决），也不能早于设备归属当前客户的时刻（time_before_visible）。
 * - 补录行只有前高度与升数，其余量是 null，表格显示「—」。
 */
const ManualStockEntryModal: React.FC<Props> = ({
  device,
  open,
  direction,
  onClose,
  onSaved,
}) => {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  const [form] = Form.useForm<Values>();
  const [tanks, setTanks] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [unconfirmed, setUnconfirmed] = useState(false);
  const requestId = useRef('');

  useEffect(() => {
    if (!open) return;
    const pending = pendingByDevice.get(device);
    form.resetFields();
    if (pending) {
      requestId.current = pending.body.requestId;
      form.setFieldsValue(pending.values);
      setUnconfirmed(true);
      setError(t('pages.console.manual.error.unconfirmed'));
    } else {
      requestId.current = newRequestId();
      setError(undefined);
      setUnconfirmed(false);
      if (direction) form.setFieldValue('direction', direction);
    }
    let live = true;
    getDeviceTanks(device)
      .then((res) => {
        if (live)
          setTanks(
            [...new Set(res.tanks.map((x) => x.index))].sort((a, b) => a - b),
          );
      })
      .catch(() => live && setTanks([]));
    return () => {
      live = false;
    };
  }, [open, device, direction, form]);

  const submit = async () => {
    // 未确认的重试：发原样快照
    const pending = pendingByDevice.get(device);
    let body: ManualStockEntry;
    let values: Values;
    if (unconfirmed && pending) {
      body = pending.body;
      values = pending.values;
    } else {
      try {
        values = await form.validateFields();
      } catch {
        return;
      }
      body = {
        direction: values.direction,
        tank: values.tank,
        time: values.time.second(0).millisecond(0).format(),
        beforeLevelMm: values.beforeLevelMm,
        volumeL: values.volumeL,
        reason: values.reason.trim(),
        requestId: requestId.current,
      };
    }
    setBusy(true);
    setError(undefined);
    try {
      await addManualStockEvent(device, body);
      pendingByDevice.delete(device);
      onSaved();
      onClose();
    } catch (err) {
      const e = err instanceof ApiError ? err : new ApiError(0);
      if (e.authHandled) return;
      if (e.status === 0) {
        pendingByDevice.set(device, { body, values });
        setUnconfirmed(true);
        setError(t('pages.console.manual.error.unconfirmed'));
        return;
      }
      setError(
        e.code && KNOWN_CODES.includes(e.code)
          ? t(`pages.console.manual.error.${e.code}`)
          : t('pages.console.manual.error.other'),
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title={t('pages.console.manual.title')}
      okText={
        unconfirmed
          ? t('pages.console.manual.retrySame')
          : t('pages.console.manual.ok')
      }
      okButtonProps={{ loading: busy }}
      cancelButtonProps={{ disabled: busy }}
      onOk={submit}
      onCancel={busy ? undefined : onClose}
      maskClosable={false}
      destroyOnHidden
    >
      <Typography.Paragraph type="secondary">
        {t('pages.console.manual.note', { device })}
      </Typography.Paragraph>
      <Form form={form} layout="vertical" disabled={busy || unconfirmed}>
        <Form.Item
          name="direction"
          label={t('pages.console.manual.direction')}
          rules={[
            { required: true, message: t('pages.console.manual.required') },
          ]}
        >
          <Radio.Group disabled={!!direction || busy || unconfirmed}>
            <Radio value="IN">{t('pages.console.manual.in')}</Radio>
            <Radio value="OUT">{t('pages.console.manual.out')}</Radio>
          </Radio.Group>
        </Form.Item>
        <Form.Item
          name="tank"
          label={t('pages.console.manual.tank')}
          rules={[
            { required: true, message: t('pages.console.manual.required') },
          ]}
        >
          <Select
            aria-label={t('pages.console.manual.tank')}
            options={tanks.map((n) => ({
              value: n,
              label: t('pages.console.manual.tankN', { tank: n }),
            }))}
            notFoundContent={t('pages.console.manual.noTanks')}
          />
        </Form.Item>
        <Form.Item
          name="time"
          label={t('pages.console.manual.time')}
          rules={[
            { required: true, message: t('pages.console.manual.required') },
            {
              validator: (_, v?: Dayjs) =>
                v?.isAfter(dayjs().add(1, 'minute'))
                  ? Promise.reject(
                      new Error(t('pages.console.manual.error.time_in_future')),
                    )
                  : Promise.resolve(),
            },
          ]}
        >
          <DatePicker
            showTime={{ format: 'HH:mm' }}
            format="YYYY-MM-DD HH:mm"
            disabledDate={(d) => d.isAfter(dayjs(), 'day')}
            style={{ width: '100%' }}
          />
        </Form.Item>
        <Form.Item
          name="beforeLevelMm"
          label={t('pages.console.manual.level')}
          rules={[
            { required: true, message: t('pages.console.manual.required') },
          ]}
        >
          <InputNumber
            min={0}
            addonAfter="mm"
            style={{ width: '100%' }}
            aria-label={t('pages.console.manual.level')}
          />
        </Form.Item>
        <Form.Item
          name="volumeL"
          label={t('pages.console.manual.volume')}
          rules={[
            { required: true, message: t('pages.console.manual.required') },
            {
              validator: (_, v?: number) =>
                typeof v === 'number' && v <= 0
                  ? Promise.reject(
                      new Error(t('pages.console.manual.error.volume_invalid')),
                    )
                  : Promise.resolve(),
            },
          ]}
        >
          <InputNumber
            min={0}
            addonAfter="L"
            style={{ width: '100%' }}
            aria-label={t('pages.console.manual.volume')}
          />
        </Form.Item>
        <Form.Item
          name="reason"
          label={t('pages.console.manual.reason')}
          rules={[
            {
              required: true,
              whitespace: true,
              message: t('pages.console.manual.required'),
            },
          ]}
        >
          <Input.TextArea
            maxLength={200}
            showCount
            autoSize={{ minRows: 2 }}
            aria-label={t('pages.console.manual.reason')}
          />
        </Form.Item>
      </Form>
      {error && (
        <Alert
          type={unconfirmed ? 'warning' : 'error'}
          showIcon
          message={error}
          action={
            unconfirmed ? (
              <Button
                size="small"
                disabled={busy}
                onClick={() => {
                  // 用户已在记录里确认没有保存时才用：清掉快照，换新编号重新填写
                  pendingByDevice.delete(device);
                  requestId.current = newRequestId();
                  setUnconfirmed(false);
                  setError(undefined);
                }}
              >
                {t('pages.console.manual.abandon')}
              </Button>
            ) : undefined
          }
        />
      )}
    </Modal>
  );
};

export default ManualStockEntryModal;
