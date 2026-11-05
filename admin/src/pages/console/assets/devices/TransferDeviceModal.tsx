import { useIntl } from '@umijs/max';
import { Alert, Form, Modal, message, Select } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { assignDeviceCustomer } from '@/services/ant-design-pro/devices';
import { describeAssignError } from './errors';

type Props = {
  /** 要分配/转移的设备；undefined 时弹窗关闭 */
  device?: API.DeviceRow;
  customers: API.Customer[];
  onClose: () => void;
  onDone: () => void;
};

/**
 * 把设备分配（未分配时）或转移（已有客户时）到另一个客户。云端在同一事务里清掉该设备的所有绑定，
 * 所以转移前要明确提示；归属未知（云端旧版本没带 customer_id）时也按转移提示，宁可多提醒。
 */
const TransferDeviceModal: React.FC<Props> = ({
  device,
  customers,
  onClose,
  onDone,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [form] = Form.useForm<{ customerId: number }>();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const currentId = device?.customer_id;
  const unassigned = currentId === null;
  const close = () => {
    onClose();
    form.resetFields();
  };

  const submit = async ({ customerId }: { customerId: number }) => {
    if (!device || busy.current) return;
    busy.current = true;
    setSubmitting(true);
    try {
      const res = await assignDeviceCustomer(device.device_name, customerId);
      const name =
        customers.find((c) => c.id === customerId)?.name ?? String(customerId);
      message.success(
        res.changed
          ? t('pages.ops.devices.transfer.success', {
              device: device.device_name,
              customer: name,
            })
          : t('pages.ops.devices.transfer.unchanged', { customer: name }),
      );
      close();
      onDone();
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      const m = describeAssignError(
        apiErr?.status ?? -1,
        apiErr?.error ?? (err as Error)?.message,
      );
      message.error(t(m.id, { detail: m.detail ?? '' }));
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={!!device}
      title={`${t(
        unassigned
          ? 'pages.ops.devices.transfer.assignTitle'
          : 'pages.ops.devices.transfer.title',
      )}：${device?.device_name ?? ''}`}
      okText={t('pages.ops.devices.transfer.submit')}
      cancelText={t('pages.ops.devices.form.cancel')}
      confirmLoading={submitting}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      {!unassigned && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title={t('pages.ops.devices.transfer.warning')}
        />
      )}
      <Form form={form} layout="vertical" onFinish={submit}>
        <Form.Item
          name="customerId"
          label={t('pages.ops.devices.transfer.target')}
          rules={[
            {
              required: true,
              message: t('pages.ops.devices.form.customer.required'),
            },
          ]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder={t('pages.ops.devices.form.customer.placeholder')}
            options={customers
              .filter((c) => c.id !== currentId)
              .map((c) => ({ value: c.id, label: c.name }))}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default TransferDeviceModal;
