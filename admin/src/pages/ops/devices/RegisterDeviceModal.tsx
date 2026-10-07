import { PlusOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  Checkbox,
  Divider,
  Form,
  Input,
  Modal,
  message,
  Select,
  Space,
  Typography,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  createCustomer,
  listCustomers,
} from '@/services/ant-design-pro/customers';
import { registerDevice } from '@/services/ant-design-pro/devices';
import { normalizeDeviceCode } from '@/utils/deviceCode';
import {
  describeCustomerError,
  describeRegisterError,
  type ErrorMessage,
} from './errors';

type Props = {
  open: boolean;
  onClose: () => void;
  /** 登记成功（含"已登记"）后调用，用于刷新列表 */
  onRegistered: () => void;
};

type FormValues = {
  deviceCode: string;
  customerId: number;
};

const RegisterForm: React.FC<Omit<Props, 'open'>> = ({
  onClose,
  onRegistered,
}) => {
  const intl = useIntl();
  const [form] = Form.useForm<FormValues>();
  const text = useCallback(
    (id: string, values?: Record<string, string>) =>
      intl.formatMessage({ id }, values),
    [intl],
  );
  const render = useCallback(
    (m: ErrorMessage) => text(m.id, { detail: m.detail ?? '' }),
    [text],
  );

  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [loadingCustomers, setLoadingCustomers] = useState(true);
  const [customersFailed, setCustomersFailed] = useState(false);
  const [newName, setNewName] = useState('');
  const [creating, setCreating] = useState(false);
  const creatingRef = useRef(false);

  const [submitting, setSubmitting] = useState(false);
  // state 在同一帧内连点来不及更新，防重以 ref 为准
  const submittingRef = useRef(false);
  const [formError, setFormError] = useState<string>();

  const [confirmed, setConfirmed] = useState(false);
  const [confirmError, setConfirmError] = useState(false);

  const deviceCode = Form.useWatch('deviceCode', form);
  const parsed = useMemo(
    () => normalizeDeviceCode(deviceCode ?? ''),
    [deviceCode],
  );

  // 编号变了，之前的确认作废
  useEffect(() => {
    setConfirmed(false);
    setConfirmError(false);
  }, [parsed.code]);

  const loadCustomers = useCallback(async () => {
    setLoadingCustomers(true);
    try {
      setCustomers(await listCustomers());
      setCustomersFailed(false);
    } catch {
      // 全局错误处理已提示，这里只标记，避免把失败当成"没有客户"
      setCustomersFailed(true);
    } finally {
      setLoadingCustomers(false);
    }
  }, []);

  useEffect(() => {
    void loadCustomers();
  }, [loadCustomers]);

  const onCreateCustomer = async () => {
    const name = newName.trim();
    if (!name || creatingRef.current) return;
    creatingRef.current = true;
    setCreating(true);
    try {
      const created = await createCustomer({ name });
      message.success(text('pages.ops.devices.customer.created', { name }));
      setNewName('');
      try {
        const list = await listCustomers();
        setCustomers(
          list.some((c) => c.id === created.id) ? list : [...list, created],
        );
        setCustomersFailed(false);
      } catch {
        setCustomers((prev) => [...prev, created]);
      }
      form.setFieldValue('customerId', created.id);
      form.setFields([{ name: 'customerId', errors: [] }]);
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      message.error(
        render(
          describeCustomerError(
            apiErr?.status ?? -1,
            apiErr?.error ?? (err as Error)?.message,
          ),
        ),
      );
    } finally {
      creatingRef.current = false;
      setCreating(false);
    }
  };

  const onFinish = async (values: FormValues) => {
    if (submittingRef.current) return;
    const result = normalizeDeviceCode(values.deviceCode);
    if (result.status === 'invalid') return;
    if (result.status === 'needsConfirm' && !confirmed) {
      setConfirmError(true);
      return;
    }
    submittingRef.current = true;
    setSubmitting(true);
    setFormError(undefined);
    try {
      const outcome = await registerDevice({
        deviceName: result.code,
        customerId: values.customerId,
      });
      const customer =
        customers.find((c) => c.id === outcome.customerId)?.name ??
        String(outcome.customerId);
      message.success(
        text(
          outcome.kind === 'created'
            ? 'pages.ops.devices.success.created'
            : 'pages.ops.devices.success.exists',
          { device: outcome.deviceName, customer },
        ),
      );
      onRegistered();
      onClose();
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
      setFormError(
        render(
          describeRegisterError(
            apiErr?.status ?? -1,
            apiErr?.error ?? (err as Error)?.message,
          ),
        ),
      );
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Form<FormValues>
      form={form}
      layout="vertical"
      onFinish={onFinish}
      onValuesChange={() => setFormError(undefined)}
    >
      {formError && (
        <Alert
          type="error"
          showIcon
          title={formError}
          style={{ marginBottom: 16 }}
        />
      )}
      <Form.Item
        name="deviceCode"
        label={text('pages.ops.devices.form.code.label')}
        validateTrigger="onBlur"
        rules={[
          {
            validator: async (_, value?: string) => {
              const r = normalizeDeviceCode(value ?? '');
              if (r.status !== 'invalid') return;
              throw new Error(
                text(
                  r.reason === 'empty'
                    ? 'pages.ops.devices.form.code.required'
                    : 'pages.ops.devices.form.code.invalid',
                ),
              );
            },
          },
        ]}
      >
        <Input
          autoComplete="off"
          placeholder={text('pages.ops.devices.form.code.placeholder')}
        />
      </Form.Item>
      {parsed.status === 'needsConfirm' && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title={text('pages.ops.devices.form.code.confirm.tip', {
            code: parsed.code,
          })}
          description={
            <>
              <Checkbox
                checked={confirmed}
                onChange={(e) => {
                  setConfirmed(e.target.checked);
                  setConfirmError(false);
                }}
              >
                {text('pages.ops.devices.form.code.confirm.checkbox', {
                  code: parsed.code,
                })}
              </Checkbox>
              {confirmError && (
                <div>
                  <Typography.Text type="danger">
                    {text('pages.ops.devices.form.code.confirm.required')}
                  </Typography.Text>
                </div>
              )}
            </>
          }
        />
      )}
      <Form.Item
        name="customerId"
        label={text('pages.ops.devices.form.customer.label')}
        rules={[
          {
            required: true,
            message: text('pages.ops.devices.form.customer.required'),
          },
        ]}
        validateStatus={customersFailed ? 'warning' : undefined}
        help={
          customersFailed
            ? text('pages.ops.devices.form.customer.loadFailed')
            : undefined
        }
      >
        <Select<number>
          showSearch={{ optionFilterProp: 'label' }}
          loading={loadingCustomers}
          placeholder={text('pages.ops.devices.form.customer.placeholder')}
          options={customers.map((c) => ({ value: c.id, label: c.name }))}
          notFoundContent={text('pages.ops.devices.form.customer.empty')}
          popupRender={(menu) => (
            <>
              {menu}
              <Divider style={{ margin: '8px 0' }} />
              <Space style={{ padding: '0 8px 4px' }}>
                <Input
                  maxLength={100}
                  value={newName}
                  placeholder={text(
                    'pages.ops.devices.form.customer.new.placeholder',
                  )}
                  onChange={(e) => setNewName(e.target.value)}
                  onKeyDown={(e) => e.stopPropagation()}
                />
                <Button
                  type="text"
                  icon={<PlusOutlined />}
                  loading={creating}
                  onClick={onCreateCustomer}
                >
                  {text('pages.ops.devices.form.customer.new.button')}
                </Button>
              </Space>
            </>
          )}
        />
      </Form.Item>
      <Typography.Paragraph type="secondary">
        {text('pages.ops.devices.form.hint')}
      </Typography.Paragraph>
      <Space style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button onClick={onClose}>
          {text('pages.ops.devices.form.cancel')}
        </Button>
        <Button type="primary" htmlType="submit" loading={submitting}>
          {text('pages.ops.devices.form.submit')}
        </Button>
      </Space>
    </Form>
  );
};

const RegisterDeviceModal: React.FC<Props> = ({
  open,
  onClose,
  onRegistered,
}) => {
  const intl = useIntl();
  return (
    <Modal
      open={open}
      title={intl.formatMessage({ id: 'pages.ops.devices.form.title' })}
      footer={null}
      onCancel={onClose}
      destroyOnHidden
      mask={{ closable: false }}
    >
      <RegisterForm onClose={onClose} onRegistered={onRegistered} />
    </Modal>
  );
};

export default RegisterDeviceModal;
