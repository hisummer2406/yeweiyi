import { PageContainer } from '@ant-design/pro-components';
import { history, useIntl, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  message,
  Result,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
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
  completeCustomerOnboarding,
  getCustomerOnboarding,
  listCustomers,
} from '@/services/ant-design-pro/customers';
import { listDevices, registerDevice } from '@/services/ant-design-pro/devices';
import {
  assignDeviceStation,
  createStation,
  deleteStation,
  listStations,
} from '@/services/ant-design-pro/stations';
import { createUser } from '@/services/ant-design-pro/users';
import { normalizeDeviceCode } from '@/utils/deviceCode';
import { describeRegisterError } from '../../devices/errors';
import {
  describeAssignStationError,
  describeStationError,
} from '../../stations/errors';
import { describeOnboardingError } from '../errors';

const MISSING_ORDER: API.OnboardingMissing[] = [
  'customer_admin',
  'station',
  'device_in_station',
];

/**
 * 客户开通向导（yeweiyi-admin#14，契约 yeweiyi-cloud docs/customer-onboarding-contract.md）：
 * 只是把现有的账号/油站/设备接口按开通顺序串起来，不是客户详情中心。每步即时保存；进度一律以
 * GET /api/customers/:id/onboarding 为准（刷新后恢复），不在前端另存状态。条件齐全后才能「完成开通」，
 * 云端在事务内重新检查，409 onboarding_incomplete 时刷新进度并提示缺什么。
 */
const CustomerOnboarding: React.FC = () => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const params = useParams<{ id: string }>();
  const customerId = Number(params.id);

  const [customer, setCustomer] = useState<API.Customer>();
  const [progress, setProgress] = useState<API.CustomerOnboarding>();
  const [stations, setStations] = useState<API.Station[]>([]);
  // 油站 id → 已装的大屏编码。一油站一台大屏：已被占用的油站不能再选。取不到时为空（云端 409 仍会兜底）
  const [occupied, setOccupied] = useState<Map<number, string>>(new Map());
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<'notFound' | 'failed'>();

  const fail = (err: unknown, describe: (e?: ApiError) => string) => {
    const apiErr = err instanceof ApiError ? err : undefined;
    if (apiErr?.authHandled) return; // 鉴权失败，已在回登录页
    message.error(describe(apiErr));
  };

  const loaded = useRef(false);

  const load = useCallback(async (): Promise<
    API.CustomerOnboarding | undefined
  > => {
    try {
      let stationsFailed = false;
      const [p, customers, sts, devs] = await Promise.all([
        getCustomerOnboarding(customerId),
        listCustomers(),
        // 油站是向导的必需数据：取不到不能当成「没有油站」（否则会误导用户重复建站）。
        // 单独标记失败，避免旧版云端 /api/stations 的 404 被下面当成「客户不存在」
        listStations({ silent: true }).catch(() => {
          stationsFailed = true;
          return [] as API.Station[];
        }),
        Promise.resolve()
          .then(() => listDevices())
          .catch(() => [] as API.DeviceRow[]),
      ]);
      if (stationsFailed) {
        if (loaded.current)
          message.error(t('pages.ops.customers.onboarding.loadFailed'));
        else setLoadError('failed');
        return undefined;
      }
      loaded.current = true;
      setProgress(p);
      setCustomer(customers.find((c) => c.id === customerId));
      setStations(sts.filter((s) => s.customerId === customerId));
      const occ = new Map<number, string>();
      for (const d of devs ?? []) {
        if (typeof d.station_id === 'number' && !occ.has(d.station_id)) {
          occ.set(d.station_id, d.device_name);
        }
      }
      setOccupied(occ);
      setLoadError(undefined);
      return p;
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return undefined;
      // 已有进度时（保存某一步后的刷新失败）保留页面和表单，只提示，不把整页换成错误页
      if (loaded.current) {
        message.error(t('pages.ops.customers.onboarding.loadFailed'));
        return undefined;
      }
      setLoadError(
        apiErr?.status === 404 || apiErr?.code === 'customer_not_found'
          ? 'notFound'
          : 'failed',
      );
      return undefined;
    } finally {
      setLoading(false);
    }
  }, [customerId]);

  useEffect(() => {
    if (!Number.isInteger(customerId) || customerId <= 0) {
      setLoadError('notFound');
      setLoading(false);
      return;
    }
    void load();
  }, [customerId, load]);

  const name = customer?.name ?? `#${customerId}`;
  const { complete, submitting: completing } = useComplete(
    customerId,
    name,
    load,
    fail,
  );
  const back = () => history.push('/assets/customers');

  if (loading) {
    return (
      <PageContainer>
        <Spin />
      </PageContainer>
    );
  }
  if (loadError || !progress) {
    return (
      <PageContainer>
        <Result
          status={loadError === 'notFound' ? '404' : 'error'}
          title={t(
            loadError === 'notFound'
              ? 'pages.ops.customers.error.notFound'
              : 'pages.ops.customers.onboarding.loadFailed',
          )}
          extra={
            <Space>
              {loadError !== 'notFound' && (
                <Button
                  onClick={() => {
                    setLoading(true);
                    void load();
                  }}
                >
                  {t('pages.ops.customers.onboarding.retry')}
                </Button>
              )}
              <Button onClick={back}>
                {t('pages.ops.customers.onboarding.back')}
              </Button>
            </Space>
          }
        />
      </PageContainer>
    );
  }

  const active = progress.status === 'active';
  const missing = new Set(progress.missing);

  return (
    <PageContainer
      title={t('pages.ops.customers.onboarding.title', { name })}
      extra={[
        <Button key="back" onClick={back}>
          {t('pages.ops.customers.onboarding.back')}
        </Button>,
      ]}
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Alert
          type={active ? 'success' : 'info'}
          showIcon
          message={t(
            active
              ? 'pages.ops.customers.onboarding.active'
              : 'pages.ops.customers.onboarding.intro',
          )}
        />
        {/* 新建客户时管理员已一起建好；只有旧数据里缺管理员的客户才需要这一步 */}
        {missing.has('customer_admin') && (
          <AdminStep
            customerId={customerId}
            done={!missing.has('customer_admin')}
            count={progress.counts.customerAdmins}
            disabled={active}
            onChanged={load}
            fail={fail}
          />
        )}
        <StationDeviceStep
          customerId={customerId}
          done={!missing.has('station') && !missing.has('device_in_station')}
          count={progress.counts.devicesInStation}
          stations={stations}
          occupied={occupied}
          disabled={active}
          onChanged={load}
          adminDone={!missing.has('customer_admin')}
          onReady={complete}
          fail={fail}
        />
        {/* 一般不用点：添加油站和大屏时条件一满足就自动开通。这个按钮只在条件已齐、但上次开通没成功时出现 */}
        {!active && progress.complete && (
          <Button
            type="primary"
            size="large"
            loading={completing}
            onClick={complete}
          >
            {t('pages.ops.customers.onboarding.complete')}
          </Button>
        )}
      </Space>
    </PageContainer>
  );
};

type StepFail = (err: unknown, describe: (e?: ApiError) => string) => void;

const StepCard: React.FC<{
  title: string;
  done: boolean;
  children: React.ReactNode;
}> = ({ title, done, children }) => {
  const intl = useIntl();
  return (
    <Card
      title={title}
      extra={
        <Tag color={done ? 'success' : 'default'}>
          {intl.formatMessage({
            id: done
              ? 'pages.ops.customers.onboarding.step.done'
              : 'pages.ops.customers.onboarding.step.todo',
          })}
        </Tag>
      }
    >
      {children}
    </Card>
  );
};

const AdminStep: React.FC<{
  customerId: number;
  done: boolean;
  count: number;
  disabled: boolean;
  onChanged: () => void;
  fail: StepFail;
}> = ({ customerId, done, count, disabled, onChanged, fail }) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [form] = Form.useForm<{ phone: string; password: string }>();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const submit = async (v: { phone: string; password: string }) => {
    if (busy.current) return;
    busy.current = true;
    setSubmitting(true);
    try {
      await createUser(
        buildCreateUserParams({ role: 'customer_admin', customerId, ...v }),
      );
      message.success(t('pages.ops.customers.onboarding.admin.created'));
      form.resetFields();
      onChanged();
    } catch (err) {
      fail(err, (e) => {
        const m = describeUserError(
          e?.status ?? -1,
          e?.error ?? (err as Error)?.message,
          e?.code,
        );
        return t(m.id, { detail: m.detail ?? '' });
      });
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  };

  return (
    <StepCard
      title={t('pages.ops.customers.onboarding.step.admin')}
      done={done}
    >
      {done && (
        <Typography.Paragraph>
          {t('pages.ops.customers.onboarding.step.admin.done', {
            count: String(count),
          })}
        </Typography.Paragraph>
      )}
      {!done && !disabled && (
        <>
          <Typography.Paragraph type="secondary">
            {t('pages.ops.customers.onboarding.step.admin.hint')}
          </Typography.Paragraph>
          <Form
            form={form}
            layout="vertical"
            autoComplete="off"
            onFinish={submit}
            style={{ maxWidth: 360 }}
          >
            <Form.Item
              name="phone"
              label={t('pages.ops.customers.onboarding.admin.phone')}
              rules={[
                {
                  validator: (_, value?: string) =>
                    value && normalizePhone(value)
                      ? Promise.resolve()
                      : Promise.reject(
                          new Error(
                            t(
                              'pages.ops.customers.onboarding.admin.phone.invalid',
                            ),
                          ),
                        ),
                },
              ]}
            >
              <Input autoComplete="off" inputMode="tel" />
            </Form.Item>
            <Form.Item
              name="password"
              label={t('pages.ops.customers.onboarding.admin.password')}
              rules={[
                {
                  validator: (_, value?: string) => {
                    if (!value)
                      return Promise.reject(
                        new Error(
                          t(
                            'pages.ops.customers.onboarding.admin.password.required',
                          ),
                        ),
                      );
                    const n = passwordByteLength(value);
                    return n >= PASSWORD_MIN_BYTES && n <= PASSWORD_MAX_BYTES
                      ? Promise.resolve()
                      : Promise.reject(
                          new Error(
                            t(
                              'pages.ops.customers.onboarding.admin.password.length',
                            ),
                          ),
                        );
                  },
                },
              ]}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={submitting}>
              {t('pages.ops.customers.onboarding.admin.submit')}
            </Button>
          </Form>
        </>
      )}
    </StepCard>
  );
};

/**
 * 油站与大屏合为一步：一个油站只有一台大屏，所以一次提交 = 新建油站 + 登记设备 + 分配到该油站。
 * 云端没有一次建三样的接口，这里串起来：设备登记失败就删掉刚建的空油站（回滚）；分配失败时设备已登记，
 * 提示去设备页重试（油站也留着）。已有的油站和各自的大屏列在下面，可以继续添加下一个。
 */
const StationDeviceStep: React.FC<{
  customerId: number;
  done: boolean;
  count: number;
  stations: API.Station[];
  occupied: Map<number, string>;
  disabled: boolean;
  onChanged: () => Promise<API.CustomerOnboarding | undefined>;
  /** 管理员已建：此时添加完油站和大屏就满足开通条件，自动开通 */
  adminDone: boolean;
  onReady: () => Promise<void>;
  fail: StepFail;
}> = ({
  customerId,
  done,
  count,
  stations,
  occupied,
  disabled,
  onChanged,
  adminDone,
  onReady,
  fail,
}) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [form] = Form.useForm<{ stationName: string; deviceCode: string }>();
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const submit = async (v: { stationName: string; deviceCode: string }) => {
    if (busy.current) return;
    busy.current = true;
    setSubmitting(true);
    try {
      // 只收完整出厂编号：短码补前缀属于「需管理员确认」的流程，向导里不做猜测
      const parsed = normalizeDeviceCode(v.deviceCode);
      if (parsed.status !== 'full') return;
      const deviceName = parsed.code;
      const stationName = v.stationName.trim();
      let station: API.Station;
      try {
        station = await createStation({ customerId, name: stationName });
      } catch (err) {
        fail(err, (e) => {
          const m = describeStationError(
            e?.status ?? -1,
            e?.error ?? (err as Error)?.message,
          );
          return t(m.id, { detail: m.detail ?? '' });
        });
        return;
      }
      try {
        await registerDevice({ deviceName, customerId });
      } catch (err) {
        // 设备登记失败：删掉刚建的空油站，表单保留，用户改完重试
        await deleteStation(station.id).catch(() => undefined);
        fail(err, (e) => {
          const m = describeRegisterError(
            e?.status ?? -1,
            e?.error ?? (err as Error)?.message,
          );
          return t(m.id, { detail: m.detail ?? '' });
        });
        onChanged();
        return;
      }
      try {
        await assignDeviceStation(deviceName, station.id);
      } catch (err) {
        fail(err, (e) => {
          const m = describeAssignStationError(
            e?.status ?? -1,
            e?.error ?? (err as Error)?.message,
            e?.code,
            e?.occupiedBy,
          );
          return t('pages.ops.customers.onboarding.device.assignFailed', {
            device: deviceName,
            detail: t(m.id, {
              detail: m.detail ?? '',
              device: e?.occupiedBy ?? '',
            }),
          });
        });
        onChanged();
        return;
      }
      message.success(
        t('pages.ops.customers.onboarding.device.success', {
          device: deviceName,
          station: stationName,
        }),
      );
      form.resetFields();
      const fresh = await onChanged();
      // 管理员、油站、大屏都齐了就直接开通，不再要用户去点第二个按钮
      if (fresh?.complete && fresh.status !== 'active') await onReady();
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  };

  return (
    <StepCard
      title={t('pages.ops.customers.onboarding.step.stationDevice')}
      done={done}
    >
      <Typography.Paragraph type={done ? undefined : 'secondary'}>
        {done
          ? t('pages.ops.customers.onboarding.step.stationDevice.done', {
              count: String(count),
            })
          : t('pages.ops.customers.onboarding.step.stationDevice.hint')}
      </Typography.Paragraph>
      {stations.length > 0 && (
        <ul style={{ paddingLeft: 20 }}>
          {stations.map((s) => (
            <li key={s.id}>
              {s.name}
              {'：'}
              {occupied.get(s.id) ??
                t('pages.ops.customers.onboarding.station.noDevice')}
            </li>
          ))}
        </ul>
      )}
      {!disabled && (
        <Form
          form={form}
          layout="vertical"
          autoComplete="off"
          onFinish={submit}
          style={{ maxWidth: 360 }}
        >
          <Form.Item
            name="stationName"
            label={t('pages.ops.customers.onboarding.station.name')}
            rules={[
              {
                required: true,
                whitespace: true,
                message: t(
                  'pages.ops.customers.onboarding.station.name.required',
                ),
              },
            ]}
          >
            <Input autoComplete="off" />
          </Form.Item>
          <Form.Item
            name="deviceCode"
            label={t('pages.ops.customers.onboarding.device.code')}
            rules={[
              {
                validator: (_, value?: string) =>
                  normalizeDeviceCode(value ?? '').status === 'full'
                    ? Promise.resolve()
                    : Promise.reject(
                        new Error(
                          t(
                            'pages.ops.customers.onboarding.device.code.invalid',
                          ),
                        ),
                      ),
              },
            ]}
          >
            <Input
              autoComplete="off"
              placeholder={t(
                'pages.ops.customers.onboarding.device.code.placeholder',
              )}
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={submitting}>
            {t(
              adminDone
                ? 'pages.ops.customers.onboarding.stationDevice.submitAndOpen'
                : 'pages.ops.customers.onboarding.stationDevice.submit',
            )}
          </Button>
        </Form>
      )}
    </StepCard>
  );
};

/** 完成开通：成功后回客户列表；409 onboarding_incomplete 时刷新进度并提示缺什么。 */
function useComplete(
  customerId: number,
  name: string,
  onChanged: () => Promise<API.CustomerOnboarding | undefined>,
  fail: StepFail,
) {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [submitting, setSubmitting] = useState(false);
  const busy = useRef(false);

  const complete = async () => {
    if (busy.current) return;
    busy.current = true;
    setSubmitting(true);
    try {
      await completeCustomerOnboarding(customerId);
      message.success(
        t('pages.ops.customers.onboarding.complete.success', { name }),
      );
      history.push('/assets/customers');
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.code === 'onboarding_incomplete') {
        // 页面上的进度过期了（别人删了油站、停用了管理员…）：刷新进度（一次请求），并提示缺什么
        const fresh = await onChanged();
        message.error(
          t('pages.ops.customers.onboarding.complete.incomplete', {
            items: missingLabels(fresh?.missing ?? [], t),
          }),
        );
      } else {
        fail(err, (e) => {
          const m = describeOnboardingError(
            e?.status ?? -1,
            e?.error ?? (err as Error)?.message,
            e?.code,
          );
          return t(m.id, { detail: m.detail ?? '' });
        });
      }
    } finally {
      busy.current = false;
      setSubmitting(false);
    }
  };
  return { complete, submitting };
}

const missingLabels = (
  missing: API.OnboardingMissing[],
  t: (id: string) => string,
) =>
  MISSING_ORDER.filter((m) => missing.includes(m))
    .map((m) => t(`pages.ops.customers.onboarding.missing.${m}`))
    .join('、');

export default CustomerOnboarding;
