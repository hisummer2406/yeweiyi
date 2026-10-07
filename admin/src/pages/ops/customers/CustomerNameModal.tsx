import { useIntl } from '@umijs/max';
import { Form, Input, Modal, message } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import type { ErrorMessage } from '../devices/errors';

type Props = {
  open: boolean;
  title: string;
  okText: string;
  /** 重命名时预填当前名称 */
  initialName?: string;
  /** 提交修剪后的名称；失败抛 ApiError */
  onSubmit: (name: string) => Promise<unknown>;
  describeError: (status: number, error?: string) => ErrorMessage;
  successId: string;
  onClose: () => void;
  onDone: () => void;
};

/**
 * 新建客户和重命名客户共用的名称弹窗：名称必填（去掉首尾空白后不能为空）、防重复提交（ref，同一帧连点
 * state 来不及更新）、失败按状态码提示且弹窗保持打开；鉴权失败已转交全局回登录页，不再提示。
 */
const CustomerNameModal: React.FC<Props> = ({
  open,
  title,
  okText,
  initialName,
  onSubmit,
  describeError,
  successId,
  onClose,
  onDone,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [form] = Form.useForm<{ name: string }>();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const close = () => {
    onClose();
    form.resetFields();
  };

  const submit = async ({ name }: { name: string }) => {
    if (busy.current) return;
    const trimmed = name.trim();
    busy.current = true;
    setSubmitting(true);
    try {
      await onSubmit(trimmed);
      message.success(t(successId, { name: trimmed }));
      close();
      onDone();
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      const m = describeError(
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
      open={open}
      title={title}
      okText={okText}
      cancelText={t('pages.ops.devices.form.cancel')}
      confirmLoading={submitting}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ name: initialName }}
        onFinish={submit}
      >
        <Form.Item
          name="name"
          label={t('pages.ops.customers.form.name.label')}
          rules={[
            {
              required: true,
              whitespace: true,
              message: t('pages.ops.customers.form.name.required'),
            },
          ]}
        >
          <Input
            autoFocus
            placeholder={t('pages.ops.devices.form.customer.new.placeholder')}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default CustomerNameModal;
