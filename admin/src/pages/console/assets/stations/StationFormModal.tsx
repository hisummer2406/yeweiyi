import { useIntl } from '@umijs/max';
import { Form, Input, Modal, message, Select } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import type { ErrorMessage } from '../devices/errors';

export type StationFormValues = {
  customerId?: number;
  name: string;
  address: string;
};

type Props = {
  open: boolean;
  title: string;
  okText: string;
  /** 编辑时预填 */
  initial?: { name: string; address: string };
  /** 只有平台管理员新建时传入：要求选择客户，因为云端要求平台管理员显式给出 customerId */
  customers?: API.Customer[];
  /** 提交修剪后的值；失败抛 ApiError */
  onSubmit: (values: StationFormValues) => Promise<unknown>;
  describeError: (status: number, error?: string) => ErrorMessage;
  successId: string;
  onClose: () => void;
  onDone: () => void;
};

/**
 * 新建和编辑油站共用的弹窗。名称必填（去首尾空白后不能为空）；防重复提交用 ref（同一帧连点时 state 还没更新）；
 * 失败按状态码提示且弹窗保持打开；鉴权失败已转交全局回登录页，不再提示。
 */
const StationFormModalInner: React.FC<Props> = ({
  open,
  title,
  okText,
  initial,
  customers,
  onSubmit,
  describeError,
  successId,
  onClose,
  onDone,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [form] = Form.useForm<StationFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const close = () => {
    onClose();
    form.resetFields();
  };

  const submit = async (values: StationFormValues) => {
    if (busy.current) return;
    const name = values.name.trim();
    busy.current = true;
    setSubmitting(true);
    try {
      await onSubmit({
        customerId: values.customerId,
        name,
        address: (values.address ?? '').trim(),
      });
      message.success(t(successId, { name }));
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
      cancelText={t('pages.console.stations.form.cancel')}
      confirmLoading={submitting}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ name: initial?.name, address: initial?.address ?? '' }}
        onFinish={submit}
      >
        {customers && (
          <Form.Item
            name="customerId"
            label={t('pages.console.stations.form.customer.label')}
            rules={[
              {
                required: true,
                message: t('pages.console.stations.form.customer.required'),
              },
            ]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder={t(
                'pages.console.stations.form.customer.placeholder',
              )}
              options={customers.map((c) => ({ value: c.id, label: c.name }))}
            />
          </Form.Item>
        )}
        <Form.Item
          name="name"
          label={t('pages.console.stations.form.name.label')}
          rules={[
            {
              required: true,
              whitespace: true,
              message: t('pages.console.stations.form.name.required'),
            },
          ]}
        >
          <Input
            autoFocus
            placeholder={t('pages.console.stations.form.name.placeholder')}
          />
        </Form.Item>
        <Form.Item
          name="address"
          label={t('pages.console.stations.form.address.label')}
        >
          <Input
            placeholder={t('pages.console.stations.form.address.placeholder')}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};

/**
 * 打开状态或编辑目标一变就整体重建内部的表单实例，原因同 AssignStationModal：字段仓库会跨弹窗保留，
 * 不重建的话为下一个油站打开编辑会显示并保存上一个油站的名称和地址（Codex 评审 P2）。
 */
const StationFormModal: React.FC<Props> = (props) => (
  <StationFormModalInner
    key={`${props.open}|${props.initial?.name ?? ''}|${props.initial?.address ?? ''}`}
    {...props}
  />
);

export default StationFormModal;
