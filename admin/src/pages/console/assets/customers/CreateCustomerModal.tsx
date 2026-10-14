import { useIntl } from '@umijs/max';
import { Form, Input, Modal, message } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import {
  describeUserError,
  normalizePhone,
  PASSWORD_MAX_BYTES,
  PASSWORD_MIN_BYTES,
  passwordByteLength,
} from '@/pages/ops/users/errors';
import { buildCreateUserParams } from '@/pages/ops/users/UserModals';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  createCustomer,
  deleteCustomer,
} from '@/services/ant-design-pro/customers';
import { createUser } from '@/services/ant-design-pro/users';
import {
  CUSTOMER_NAME_MAX,
  customerNameCount,
  customerNameLength,
} from '@/utils/customerName';
import { describeCustomerError } from '../devices/errors';

type Values = { name: string; phone: string; password: string };

type Props = {
  open: boolean;
  onClose: () => void;
  /** 客户和管理员都建好后调用；`customer` 带云端回报的开通状态，供页面决定是否进向导 */
  onDone: (customer: API.Customer) => void;
  /** 客户建了但管理员没建成、回滚也失败时调用：列表需要刷新，客户留在「没有管理员」状态 */
  onPartial: () => void;
};

/**
 * 新建客户 = 客户 + 它唯一的客户管理员（一对一）。云端没有「一次建两个」的接口，所以串两次请求：
 * 先建客户，再建管理员；管理员失败（手机号已被占用最常见）就删掉刚建的空客户，弹窗保持打开、
 * 表单保留，改完直接重试，不留下没有管理员的孤儿客户。回滚本身失败才会留下孤儿，此时明确提示。
 */
const CreateCustomerModal: React.FC<Props> = ({
  open,
  onClose,
  onDone,
  onPartial,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [form] = Form.useForm<Values>();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const close = () => {
    onClose();
    form.resetFields();
  };

  const showError = (err: unknown, describe: typeof describeUserError) => {
    const apiErr = err instanceof ApiError ? err : undefined;
    if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
    const m = describe(
      apiErr?.status ?? -1,
      apiErr?.error ?? (err as Error)?.message,
      apiErr?.code,
    );
    message.error(t(m.id, { detail: m.detail ?? '' }));
  };

  const submit = async (v: Values) => {
    if (busy.current) return;
    busy.current = true;
    setSubmitting(true);
    const name = v.name.trim();
    try {
      let created: API.Customer;
      try {
        created = await createCustomer({ name });
      } catch (err) {
        showError(err, describeCustomerError);
        return;
      }
      try {
        await createUser(
          buildCreateUserParams({
            role: 'customer_admin',
            phone: v.phone,
            password: v.password,
            customerId: created.id,
          }),
        );
      } catch (err) {
        const apiErr = err instanceof ApiError ? err : undefined;
        if (apiErr?.authHandled) return;
        try {
          await deleteCustomer(created.id);
          showError(err, describeUserError); // 已回滚：表单保留，改完重试
        } catch {
          message.warning(t('pages.ops.customers.create.partial', { name }));
          close();
          onPartial();
        }
        return;
      }
      message.success(t('pages.ops.customers.create.success', { name }));
      close();
      onDone(created);
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title={t('pages.ops.customers.create')}
      okText={t('pages.ops.customers.form.submit')}
      cancelText={t('pages.ops.devices.form.cancel')}
      confirmLoading={submitting}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" autoComplete="off" onFinish={submit}>
        <Form.Item
          name="name"
          label={t('pages.ops.customers.form.name.label')}
          rules={[
            {
              required: true,
              whitespace: true,
              message: t('pages.ops.customers.form.name.required'),
            },
            {
              validator: (_: unknown, value?: string) =>
                customerNameLength((value ?? '').trim()) <= CUSTOMER_NAME_MAX
                  ? Promise.resolve()
                  : Promise.reject(
                      new Error(
                        t('pages.ops.customers.form.name.max', {
                          max: String(CUSTOMER_NAME_MAX),
                        }),
                      ),
                    ),
            },
          ]}
        >
          <Input
            autoFocus
            count={customerNameCount}
            placeholder={t('pages.ops.devices.form.customer.new.placeholder')}
          />
        </Form.Item>
        <Form.Item
          name="phone"
          label={t('pages.ops.customers.form.adminPhone.label')}
          rules={[
            {
              validator: (_: unknown, value?: string) =>
                value && normalizePhone(value)
                  ? Promise.resolve()
                  : Promise.reject(
                      new Error(t('pages.ops.users.form.phone.invalid')),
                    ),
            },
          ]}
        >
          <Input autoComplete="off" inputMode="tel" />
        </Form.Item>
        <Form.Item
          name="password"
          label={t('pages.ops.users.form.password.label')}
          rules={[
            {
              required: true,
              message: t('pages.ops.users.form.password.required'),
            },
            {
              validator: (_: unknown, value?: string) => {
                if (!value) return Promise.resolve();
                const n = passwordByteLength(value);
                return n >= PASSWORD_MIN_BYTES && n <= PASSWORD_MAX_BYTES
                  ? Promise.resolve()
                  : Promise.reject(
                      new Error(t('pages.ops.users.form.password.length')),
                    );
              },
            },
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default CreateCustomerModal;
