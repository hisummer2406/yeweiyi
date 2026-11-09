import { PageContainer } from '@ant-design/pro-components';
import { useIntl, useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  message,
  Radio,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import type React from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { listCustomers } from '@/services/ant-design-pro/customers';
import {
  createMedia,
  listMedia,
  updateMedia,
} from '@/services/ant-design-pro/media';

type FormValues = {
  name: string;
  nameEn?: string;
  scope: 'public' | 'customer';
  customerId?: number;
};

const KNOWN_ERRORS = new Set([
  'invalid_media_name',
  'invalid_media_scope',
  'unknown_customer',
  'media_not_found',
  'media_name_exists',
  'media_builtin_readonly',
]);

/** 手动维护公共介质和客户专属介质（#27，仅平台管理员）。保存只改字典，不会修改任何油罐或设备。 */
const MediaLibrary: React.FC = () => {
  const intl = useIntl();
  const t = (id: string, v?: Record<string, string | number>) =>
    intl.formatMessage({ id }, v);
  const [params] = useSearchParams();
  const lockedCustomer = Number(params.get('customer')) || undefined;

  const [rows, setRows] = useState<API.Medium[]>([]);
  const [customers, setCustomers] = useState<API.Customer[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [filter, setFilter] = useState<number | 'public' | undefined>(
    lockedCustomer,
  );
  const [editing, setEditing] = useState<API.Medium | 'new'>();
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<FormValues>();
  const scope = Form.useWatch('scope', form);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [media, list] = await Promise.all([listMedia(), listCustomers()]);
      setRows(media);
      setCustomers(list);
      setLoadFailed(false);
    } catch {
      setLoadFailed(true);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    load();
  }, [load]);

  const customerName = useMemo(() => {
    const m = new Map(customers.map((c) => [c.id, c.name]));
    return (id?: number | null) =>
      id ? (m.get(id) ?? `#${id}`) : t('pages.console.media.scope.public');
  }, [customers, intl.locale]);

  const shown = rows.filter((r) =>
    filter === undefined
      ? true
      : filter === 'public'
        ? r.scope === 'public'
        : r.customerId === filter,
  );

  const showError = (err: unknown) => {
    const api = err instanceof ApiError ? err : undefined;
    if (api?.authHandled) return;
    message.error(
      t(
        `pages.console.media.error.${
          api?.code && KNOWN_ERRORS.has(api.code) ? api.code : 'other'
        }`,
      ),
    );
  };

  const openNew = () => {
    form.resetFields();
    form.setFieldsValue(
      lockedCustomer
        ? { scope: 'customer', customerId: lockedCustomer }
        : { scope: 'public' },
    );
    setEditing('new');
  };

  const openEdit = (row: API.Medium) => {
    form.resetFields();
    form.setFieldsValue({
      name: row.name,
      nameEn: row.nameEn ?? '',
      scope: row.scope,
      customerId: row.customerId ?? undefined,
    });
    setEditing(row);
  };

  const save = async () => {
    const v = await form.validateFields();
    setSaving(true);
    try {
      if (editing === 'new') {
        await createMedia({
          name: v.name,
          nameEn: v.nameEn || undefined,
          scope: v.scope,
          customerId: v.scope === 'customer' ? v.customerId : undefined,
        });
      } else if (editing) {
        await updateMedia(editing.id, { name: v.name, nameEn: v.nameEn ?? '' });
      }
      message.success(t('pages.console.media.saved'));
      setEditing(undefined);
      load();
    } catch (err) {
      showError(err);
    } finally {
      setSaving(false);
    }
  };

  const toggle = async (row: API.Medium) => {
    try {
      await updateMedia(row.id, { enabled: !row.enabled });
      load();
    } catch (err) {
      showError(err);
    }
  };

  return (
    <PageContainer>
      <Alert
        showIcon
        type="info"
        style={{ marginBottom: 16 }}
        message={t('pages.console.media.note')}
      />
      {loadFailed && (
        <Alert
          showIcon
          type="error"
          style={{ marginBottom: 16 }}
          message={t('pages.console.media.loadFailed')}
          action={
            <Button onClick={load}>{t('pages.console.media.retry')}</Button>
          }
        />
      )}
      <Space style={{ marginBottom: 12 }} wrap>
        <Select
          aria-label={t('pages.console.media.col.owner')}
          style={{ width: 220 }}
          allowClear={!lockedCustomer}
          disabled={!!lockedCustomer}
          placeholder={t('pages.console.media.filter.all')}
          value={filter}
          onChange={setFilter}
          showSearch
          optionFilterProp="label"
          options={[
            { value: 'public', label: t('pages.console.media.scope.public') },
            ...customers.map((c) => ({ value: c.id, label: c.name })),
          ]}
        />
        <Button type="primary" onClick={openNew}>
          {t('pages.console.media.add')}
        </Button>
      </Space>
      <Table<API.Medium>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={shown}
        pagination={{ pageSize: 50, hideOnSinglePage: true }}
        columns={[
          { title: t('pages.console.media.col.name'), dataIndex: 'name' },
          {
            title: t('pages.console.media.col.nameEn'),
            render: (_, r) => r.nameEn ?? '—',
          },
          {
            title: t('pages.console.media.col.owner'),
            render: (_, r) => customerName(r.customerId),
          },
          {
            title: t('pages.console.media.col.status'),
            render: (_, r) => (
              <Tag color={r.enabled ? 'success' : 'default'}>
                {t(`pages.console.media.status.${r.enabled ? 'on' : 'off'}`)}
              </Tag>
            ),
          },
          {
            title: t('pages.console.media.col.actions'),
            render: (_, r) => (
              <Space>
                {r.builtinIndex === null && (
                  <a onClick={() => openEdit(r)}>
                    {t('pages.console.media.edit')}
                  </a>
                )}
                <a onClick={() => toggle(r)}>
                  {t(`pages.console.media.${r.enabled ? 'disable' : 'enable'}`)}
                </a>
              </Space>
            ),
          },
        ]}
      />
      <Modal
        open={editing !== undefined}
        title={t(`pages.console.media.${editing === 'new' ? 'add' : 'edit'}`)}
        confirmLoading={saving}
        onOk={save}
        onCancel={() => setEditing(undefined)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="name"
            label={t('pages.console.media.col.name')}
            rules={[{ required: true, whitespace: true }, { max: 20 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="nameEn"
            label={t('pages.console.media.col.nameEn')}
            rules={[{ max: 40 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="scope"
            label={t('pages.console.media.col.scope')}
            rules={[{ required: true }]}
          >
            <Radio.Group disabled={editing !== 'new' || !!lockedCustomer}>
              <Radio value="public">
                {t('pages.console.media.scope.public')}
              </Radio>
              <Radio value="customer">
                {t('pages.console.media.scope.customer')}
              </Radio>
            </Radio.Group>
          </Form.Item>
          {scope === 'customer' && (
            <Form.Item
              name="customerId"
              label={t('pages.console.media.col.owner')}
              rules={[{ required: true }]}
            >
              <Select
                disabled={editing !== 'new' || !!lockedCustomer}
                showSearch
                optionFilterProp="label"
                options={customers.map((c) => ({
                  value: c.id,
                  label: c.name,
                }))}
              />
            </Form.Item>
          )}
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default MediaLibrary;
