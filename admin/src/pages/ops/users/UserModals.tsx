import { useIntl } from '@umijs/max';
import { Alert, Form, Input, Modal, message, Select } from 'antd';
import type React from 'react';
import { useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  createUser,
  moveUser,
  updateUser,
} from '@/services/ant-design-pro/users';
import {
  describeUserError,
  normalizePhone,
  PASSWORD_MAX_BYTES,
  PASSWORD_MIN_BYTES,
  passwordByteLength,
  userLabel,
} from './errors';

/**
 * 创建账号可选的角色。客户管理员不在这里建：它和客户一一对应，随「新建客户」一起创建（CreateCustomerModal），
 * 旧数据里缺管理员的客户走开通向导补。云端接口本身仍接受 customer_admin。
 */
const ROLES: API.UserRole[] = ['platform_admin', 'customer_user'];

/** 创建表单的字段：每个角色只用其中一部分（cloud 契约 §4.5），提交时按角色挑。 */
type CreateUserValues = {
  role: API.UserRole;
  username?: string;
  phone?: string;
  password?: string;
  customerId?: number;
};

/** 按角色组出云端要的请求体：员工不带密码，客户侧不带用户名，平台管理员不带手机号和客户。 */
export function buildCreateUserParams(
  v: CreateUserValues,
): API.CreateUserParams {
  switch (v.role) {
    case 'platform_admin':
      return {
        role: v.role,
        username: (v.username ?? '').trim(),
        password: v.password ?? '',
      };
    case 'customer_admin':
      return {
        role: v.role,
        phone: normalizePhone(v.phone ?? '') ?? (v.phone ?? '').trim(),
        password: v.password ?? '',
        customerId: v.customerId as number,
      };
    default:
      return {
        role: 'customer_user',
        phone: normalizePhone(v.phone ?? '') ?? (v.phone ?? '').trim(),
        customerId: v.customerId as number,
      };
  }
}

type ModalProps = {
  open: boolean;
  onClose: () => void;
  /** 成功后调用（页面据此刷新列表）；创建账号时带上新账号，页面可据此跳转（员工 → 员工授权） */
  onDone: (created?: API.AdminUser) => void;
  customers: API.Customer[];
  /** 列表已按某个客户筛选时带入并锁定，不再让人在下拉里找 */
  defaultCustomerId?: number;
  /** 限定可建的角色：只剩一个时直接用它、不再显示角色下拉。默认 平台管理员 / 员工 */
  allowedRoles?: API.UserRole[];
};

/**
 * 三个弹窗共用的提交流程：防重（ref，同一帧连点 state 来不及更新）、失败提示按状态码归类、
 * 鉴权失败（已转交全局回登录页）时不再提示。失败时弹窗保持打开，方便改完重试。
 */
function useSubmit<R = unknown>(onSuccess: (result: R) => void) {
  const intl = useIntl();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);
  const run = async (action: () => Promise<R>, successId: string) => {
    if (busy.current) return;
    busy.current = true;
    setSubmitting(true);
    try {
      const result = await action();
      message.success(intl.formatMessage({ id: successId }));
      onSuccess(result);
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return;
      const m = describeUserError(
        apiErr?.status ?? -1,
        apiErr?.error ?? (err as Error)?.message,
        apiErr?.code,
      );
      message.error(
        intl.formatMessage({ id: m.id }, { detail: m.detail ?? '' }),
      );
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  };
  return { submitting, run };
}

const useRules = () => {
  const intl = useIntl();
  const t = (id: string) => intl.formatMessage({ id });
  return {
    t,
    passwordRules: [
      { required: true, message: t('pages.ops.users.form.password.required') },
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
    ],
  };
};

/** `taken`：已有客户管理员的客户 id。一个客户只设一个管理员，选管理员的客户时这些客户置灰。 */
const customerOptions = (customers: API.Customer[], taken: number[] = []) =>
  customers.map((c) => ({
    value: c.id,
    label: taken.includes(c.id) ? `${c.name}（已有管理员）` : c.name,
    disabled: taken.includes(c.id),
  }));

export const CreateUserModal: React.FC<ModalProps> = ({
  open,
  onClose,
  onDone,
  customers,
  defaultCustomerId,
  allowedRoles = ROLES,
}) => {
  const [form] = Form.useForm<CreateUserValues>();
  const { t, passwordRules } = useRules();
  const role = Form.useWatch('role', form);
  const close = () => {
    onClose();
    form.resetFields();
  };
  const { submitting, run } = useSubmit<API.AdminUser>((created) => {
    close();
    onDone(created);
  });

  return (
    <Modal
      open={open}
      title={t('pages.ops.users.create')}
      okText={t('pages.ops.users.form.submit')}
      cancelText={t('pages.ops.devices.form.cancel')}
      confirmLoading={submitting}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        autoComplete="off"
        initialValues={{
          customerId: defaultCustomerId,
          role: allowedRoles.length === 1 ? allowedRoles[0] : undefined,
        }}
        onFinish={(v) =>
          run(
            () => createUser(buildCreateUserParams(v)),
            'pages.ops.users.success.created',
          )
        }
      >
        {allowedRoles.length === 1 ? (
          // 只允许一种角色：值来自 initialValues，不显示下拉
          <Form.Item name="role" hidden />
        ) : (
          <Form.Item
            name="role"
            label={t('pages.ops.users.col.role')}
            rules={[
              {
                required: true,
                message: t('pages.ops.users.form.role.required'),
              },
            ]}
          >
            <Select
              autoFocus
              options={allowedRoles.map((r) => ({
                value: r,
                label: t(`pages.ops.users.role.${r}`),
              }))}
            />
          </Form.Item>
        )}
        {role === 'platform_admin' && (
          <Form.Item
            name="username"
            label={t('pages.ops.users.col.username')}
            rules={[
              {
                required: true,
                whitespace: true,
                message: t('pages.ops.users.form.username.required'),
              },
              { max: 64, message: t('pages.ops.users.form.username.max') },
            ]}
          >
            <Input autoComplete="off" />
          </Form.Item>
        )}
        {(role === 'customer_admin' || role === 'customer_user') && (
          <Form.Item
            name="phone"
            label={t('pages.ops.users.col.phone')}
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
        )}
        {/* 员工没有网页密码：表单里不出现密码框，也不会提交密码。 */}
        {(role === 'platform_admin' || role === 'customer_admin') && (
          <Form.Item
            name="password"
            label={t('pages.ops.users.form.password.label')}
            rules={passwordRules}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        )}
        {role === 'customer_user' && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            title={t('pages.ops.users.form.staff.hint')}
          />
        )}
        {role && role !== 'platform_admin' && (
          <Form.Item
            name="customerId"
            label={t('pages.ops.users.col.customer')}
            rules={[
              {
                required: true,
                message: t('pages.ops.users.form.customer.required'),
              },
            ]}
          >
            <Select
              showSearch
              disabled={defaultCustomerId != null}
              optionFilterProp="label"
              placeholder={t('pages.ops.devices.form.customer.placeholder')}
              options={customerOptions(customers)}
            />
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
};

type TargetProps = Omit<ModalProps, 'customers'> & {
  user?: API.AdminUser;
};

export const ResetPasswordModal: React.FC<TargetProps> = ({
  open,
  onClose,
  onDone,
  user,
}) => {
  const [form] = Form.useForm<{ password: string }>();
  const { t, passwordRules } = useRules();
  const close = () => {
    onClose();
    form.resetFields();
  };
  const { submitting, run } = useSubmit(() => {
    close();
    onDone();
  });

  return (
    <Modal
      open={open}
      title={`${t('pages.ops.users.action.resetPassword')}：${user ? userLabel(user) : ''}`}
      okText={t('pages.ops.users.form.submit.reset')}
      cancelText={t('pages.ops.devices.form.cancel')}
      confirmLoading={submitting}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        autoComplete="off"
        onFinish={(v) =>
          user &&
          run(
            () => updateUser(user.id, { password: v.password }),
            'pages.ops.users.success.reset',
          )
        }
      >
        <Form.Item
          name="password"
          label={t('pages.ops.users.form.password.new')}
          rules={passwordRules}
        >
          <Input.Password autoFocus autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export const MoveUserModal: React.FC<
  TargetProps & { customers: API.Customer[]; adminCustomerIds?: number[] }
> = ({ open, onClose, onDone, user, customers, adminCustomerIds = [] }) => {
  const [form] = Form.useForm<{ customerId: number }>();
  const { t } = useRules();
  const close = () => {
    onClose();
    form.resetFields();
  };
  const { submitting, run } = useSubmit(() => {
    close();
    onDone();
  });

  return (
    <Modal
      open={open}
      title={`${t('pages.ops.users.action.move')}：${user ? userLabel(user) : ''}`}
      okText={t('pages.ops.users.form.submit.move')}
      cancelText={t('pages.ops.devices.form.cancel')}
      confirmLoading={submitting}
      onOk={() => form.submit()}
      onCancel={close}
      destroyOnHidden
    >
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        title={t('pages.ops.users.move.warning')}
      />
      <Form
        form={form}
        layout="vertical"
        onFinish={(v) =>
          user &&
          run(
            () => moveUser(user.id, v.customerId),
            'pages.ops.users.success.moved',
          )
        }
      >
        <Form.Item
          name="customerId"
          label={t('pages.ops.users.move.target')}
          rules={[
            {
              required: true,
              message: t('pages.ops.users.form.customer.required'),
            },
          ]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder={t('pages.ops.devices.form.customer.placeholder')}
            options={customerOptions(
              customers.filter((c) => c.id !== user?.customerId),
              user?.role === 'customer_admin' ? adminCustomerIds : [],
            )}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
};
