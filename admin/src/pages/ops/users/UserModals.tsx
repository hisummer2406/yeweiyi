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
  isValidCustomerUsername,
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

/** 创建表单的字段：每个角色只用其中一部分（cloud 契约 §4.5、§11.3），提交时按角色挑。 */
type CreateUserValues = {
  role: API.UserRole;
  /** 平台管理员的用户名 */
  username?: string;
  /** 客户侧账号的唯一输入：手机号或用户名，按内容判断（见 buildCreateUserParams） */
  account?: string;
  password?: string;
  customerId?: number;
};

/**
 * 按角色组出云端要的请求体：平台管理员=用户名+密码；客户侧账号只有一个输入框，大陆手机号形态发 phone，
 * 其余发 username（同登录框的分流，cloud §11.1 两种账号二选一、恰好给一个）。用户名账号密码必填，
 * 手机号账号管理员必填、员工可选（空串不发，不设则员工只能微信）。
 */
export function buildCreateUserParams(
  v: CreateUserValues,
): API.CreateUserParams {
  if (v.role === 'platform_admin') {
    return {
      role: v.role,
      username: (v.username ?? '').trim(),
      password: v.password ?? '',
    };
  }
  const role = v.role === 'customer_admin' ? 'customer_admin' : 'customer_user';
  const customerId = v.customerId as number;
  const account = (v.account ?? '').trim();
  const phone = normalizePhone(account);
  if (!phone) {
    return { role, username: account, password: v.password ?? '', customerId };
  }
  if (role === 'customer_admin') {
    return { role, phone, password: v.password ?? '', customerId };
  }
  return {
    role,
    phone,
    customerId,
    ...(v.password ? { password: v.password } : {}),
  };
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
  const lengthRule = {
    validator: (_: unknown, value?: string) => {
      if (!value) return Promise.resolve();
      const n = passwordByteLength(value);
      return n >= PASSWORD_MIN_BYTES && n <= PASSWORD_MAX_BYTES
        ? Promise.resolve()
        : Promise.reject(new Error(t('pages.ops.users.form.password.length')));
    },
  };
  return {
    t,
    passwordRules: [
      { required: true, message: t('pages.ops.users.form.password.required') },
      lengthRule,
    ],
    /** 手机号员工的密码可不填，填了才校验长度 */
    optionalPasswordRules: [lengthRule],
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
  const { t, passwordRules, optionalPasswordRules } = useRules();
  const role = Form.useWatch('role', form);
  const account = Form.useWatch('account', form) ?? '';
  // 手机号形态的员工密码可不设（只能微信登录）；用户名账号一定要密码
  const passwordOptional =
    role === 'customer_user' && !!normalizePhone(account.trim());
  const customerSide = role === 'customer_admin' || role === 'customer_user';
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
        {customerSide && (
          <Form.Item
            name="account"
            label={t('pages.ops.users.col.account')}
            extra={t('pages.ops.users.form.account.hint')}
            rules={[
              {
                validator: (_: unknown, value?: string) => {
                  const v = (value ?? '').trim();
                  return normalizePhone(v) || isValidCustomerUsername(v)
                    ? Promise.resolve()
                    : Promise.reject(
                        new Error(t('pages.ops.users.form.account.invalid')),
                      );
                },
              },
            ]}
          >
            <Input
              autoComplete="off"
              placeholder={t('pages.ops.users.form.account.placeholder')}
            />
          </Form.Item>
        )}
        {/* 初始密码由管理员输入，只出现在请求里，不回显。手机号员工可不设（只能微信登录）。 */}
        {role && (
          <Form.Item
            name="password"
            label={t(
              passwordOptional
                ? 'pages.ops.users.form.password.optional'
                : 'pages.ops.users.form.password.label',
            )}
            rules={passwordOptional ? optionalPasswordRules : passwordRules}
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

/**
 * 编辑账号：手机号和密码合在一个框里，都是可选项，只发实际改动的字段（云端 PUT /api/users/:id 一次接收，至少一个）。
 * 手机号只对手机号账号（客户管理员/员工）出现；密码留空不改。
 */
export const EditAccountModal: React.FC<TargetProps> = ({
  open,
  onClose,
  onDone,
  user,
}) => {
  const [form] = Form.useForm<{ phone?: string; password?: string }>();
  const { t, optionalPasswordRules } = useRules();
  const canEditPhone =
    !!user && user.role !== 'platform_admin' && user.phone != null;
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
      title={`${t('pages.ops.users.action.edit')}：${user ? userLabel(user) : ''}`}
      okText={t('pages.ops.users.form.submit.save')}
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
        initialValues={{ phone: user?.phone ?? '' }}
        onFinish={(v) => {
          if (!user) return;
          const body: API.UpdateUserParams = {};
          const phone = canEditPhone
            ? normalizePhone((v.phone ?? '').trim())
            : '';
          if (phone && phone !== user.phone) body.phone = phone;
          if (v.password) body.password = v.password;
          if (Object.keys(body).length === 0) {
            message.warning(t('pages.ops.users.edit.nothing'));
            return;
          }
          return run(
            () => updateUser(user.id, body),
            'pages.ops.users.success.edited',
          );
        }}
      >
        {canEditPhone && (
          <Form.Item
            name="phone"
            label={t('pages.ops.users.form.phone.label')}
            extra={t('pages.ops.users.changePhone.hint')}
            rules={[
              {
                validator: (_, value?: string) =>
                  value && normalizePhone(value.trim())
                    ? Promise.resolve()
                    : Promise.reject(
                        new Error(t('pages.ops.users.form.phone.invalid')),
                      ),
              },
            ]}
          >
            <Input autoFocus autoComplete="off" inputMode="tel" />
          </Form.Item>
        )}
        <Form.Item
          name="password"
          label={t('pages.ops.users.form.password.new')}
          extra={t('pages.ops.users.edit.passwordHint')}
          rules={optionalPasswordRules}
        >
          <Input.Password
            autoFocus={!canEditPhone}
            autoComplete="new-password"
          />
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
