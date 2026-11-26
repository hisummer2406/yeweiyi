import { UploadOutlined } from '@ant-design/icons';
import { useIntl } from '@umijs/max';
import {
  Alert,
  Button,
  Modal,
  message,
  Popconfirm,
  Space,
  Spin,
  Typography,
  Upload,
} from 'antd';
import type React from 'react';
import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import {
  getCustomerBrandLogo,
  putCustomerBrandLogo,
  resetCustomerBrandLogo,
} from '@/services/ant-design-pro/customers';
import {
  type BrandLogoProblem,
  brandLogoDataUrl,
  bytesToBase64,
  checkBrandLogoBytes,
  checkBrandLogoDimensions,
} from '@/utils/brandLogo';

/** 小程序默认 Logo（与 miniapp 的 brand-mark.png / avatar-logo.png 同一张图） */
const DEFAULT_MARK = '/images/brand-default-mark.png';
const DEFAULT_AVATAR = '/images/brand-default-avatar.png';

type Props = {
  customer?: API.Customer;
  onClose: () => void;
};

const readImageSize = (src: string) =>
  new Promise<{ width: number; height: number }>((resolve, reject) => {
    const img = new Image();
    img.onload = () =>
      resolve({ width: img.naturalWidth, height: img.naturalHeight });
    img.onerror = () => reject(new Error('decode'));
    img.src = src;
  });

/**
 * 品牌设置（#86，云端 docs/brand-logo-contract.md）：平台管理员为客户上传、预览、替换小程序 Logo，或恢复默认。
 * 预览按小程序首页蓝底标题栏和设置页白底账号行两种底色显示。本地预检只为早点提示，云端按实际内容解码才是裁决。
 * 保存、恢复由云端写审计。小程序在下次打开或刷新时读到新配置。
 */
const BrandLogoModal: React.FC<Props> = ({ customer, onClose }) => {
  const intl = useIntl();
  const t = (id: string, values?: Record<string, string>) =>
    intl.formatMessage({ id }, values);
  const [current, setCurrent] = useState<API.BrandLogo>();
  const [loadError, setLoadError] = useState<string>();
  const [draft, setDraft] = useState<string>();
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  // 弹窗换了客户后，前一个客户迟到的响应不能落到这个弹窗上
  const openFor = useRef<number | undefined>(undefined);

  const id = customer?.id;
  useEffect(() => {
    openFor.current = id;
    setCurrent(undefined);
    setDraft(undefined);
    setLoadError(undefined);
    if (id === undefined) return;
    getCustomerBrandLogo(id).then(
      (logo) => {
        if (openFor.current === id) setCurrent(logo);
      },
      (err) => {
        if (openFor.current !== id) return;
        const apiErr = err instanceof ApiError ? err : undefined;
        if (apiErr?.authHandled) return;
        setLoadError(describe(apiErr));
      },
    );
  }, [id]);

  const describe = (err?: ApiError) => {
    if (err?.code === 'brand_logo_invalid')
      return t('pages.ops.customers.brand.error.invalid', {
        detail: err.error ?? '',
      });
    if (err?.status === 404)
      return t('pages.ops.customers.brand.error.notFound');
    if (err?.status === 0) return t('pages.ops.customers.brand.error.network');
    return t('pages.ops.customers.brand.error.generic', {
      detail: err?.error ?? String(err?.status ?? ''),
    });
  };

  const problemText = (p: BrandLogoProblem) =>
    t(`pages.ops.customers.brand.problem.${p}`);

  const pick = async (file: File) => {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const p = checkBrandLogoBytes(bytes);
    if (p) {
      message.error(problemText(p));
      return;
    }
    const b64 = bytesToBase64(bytes);
    try {
      const { width, height } = await readImageSize(brandLogoDataUrl(b64));
      const d = checkBrandLogoDimensions(width, height);
      if (d) {
        message.error(problemText(d));
        return;
      }
    } catch {
      message.error(problemText('type'));
      return;
    }
    setDraft(b64);
  };

  const run = async (
    action: () => Promise<API.BrandLogo>,
    successId: string,
  ) => {
    if (busyRef.current || id === undefined) return;
    busyRef.current = true;
    setBusy(true);
    try {
      const logo = await action();
      if (openFor.current !== id) return;
      setCurrent(logo);
      setDraft(undefined);
      message.success(t(successId, { name: customer?.name ?? '' }));
    } catch (err) {
      const apiErr = err instanceof ApiError ? err : undefined;
      if (apiErr?.authHandled) return;
      message.error(describe(apiErr));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const save = () =>
    draft !== undefined &&
    run(
      () => putCustomerBrandLogo(id as number, draft),
      'pages.ops.customers.brand.saved',
    );
  const reset = () =>
    run(
      () => resetCustomerBrandLogo(id as number),
      'pages.ops.customers.brand.resetDone',
    );

  const shown =
    draft !== undefined
      ? brandLogoDataUrl(draft)
      : current?.configured && current.data
        ? brandLogoDataUrl(current.data)
        : undefined;
  const loading = id !== undefined && !current && !loadError;

  return (
    <Modal
      open={!!customer}
      title={t('pages.ops.customers.brand.title', {
        name: customer?.name ?? '',
      })}
      onCancel={onClose}
      destroyOnHidden
      width={560}
      footer={
        <Space>
          <Button onClick={onClose}>
            {t('pages.ops.customers.brand.close')}
          </Button>
          <Popconfirm
            title={t('pages.ops.customers.brand.reset.confirm', {
              name: customer?.name ?? '',
            })}
            onConfirm={reset}
            disabled={!current?.configured || busy}
          >
            <Button disabled={!current?.configured || busy}>
              {t('pages.ops.customers.brand.reset')}
            </Button>
          </Popconfirm>
          <Button
            type="primary"
            loading={busy}
            disabled={draft === undefined || !current}
            onClick={save}
          >
            {t('pages.ops.customers.brand.save')}
          </Button>
        </Space>
      }
    >
      {loadError ? (
        <Alert type="error" showIcon message={loadError} />
      ) : (
        <Spin spinning={loading}>
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Typography.Text type="secondary">
              {draft !== undefined
                ? t('pages.ops.customers.brand.state.draft')
                : current?.configured
                  ? t('pages.ops.customers.brand.state.custom', {
                      size: `${current.width}×${current.height}`,
                    })
                  : t('pages.ops.customers.brand.state.default')}
            </Typography.Text>

            {/* 小程序首页顶部：蓝底，Logo 在系统标题左侧 */}
            <div
              data-testid="brand-preview-home"
              style={{
                display: 'flex',
                alignItems: 'center',
                padding: '14px 16px',
                borderRadius: 8,
                background: 'linear-gradient(180deg, #05629b 0%, #004873 100%)',
                color: '#fff',
              }}
            >
              <img
                alt=""
                src={shown ?? DEFAULT_MARK}
                style={{
                  width: 28,
                  height: 28,
                  objectFit: 'contain',
                  marginRight: 6,
                }}
              />
              <span style={{ fontSize: 17 }}>
                {t('pages.ops.customers.brand.preview.title')}
              </span>
            </div>

            {/* 小程序设置页 / 账号详情：白底账号行，圆形头像 */}
            <div
              data-testid="brand-preview-settings"
              style={{
                display: 'flex',
                alignItems: 'center',
                padding: '12px 16px',
                borderRadius: 8,
                background: '#fff',
                border: '1px solid #f0f0f0',
              }}
            >
              <img
                alt=""
                src={shown ?? DEFAULT_AVATAR}
                style={{
                  width: 32,
                  height: 32,
                  borderRadius: '50%',
                  objectFit: 'contain',
                  marginRight: 10,
                }}
              />
              <div>
                <div>138****0000</div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {t('pages.ops.customers.brand.preview.role')}
                </Typography.Text>
              </div>
            </div>

            <Upload
              accept=".png,image/png"
              showUploadList={false}
              disabled={busy || !current}
              beforeUpload={(file) => {
                pick(file);
                return false;
              }}
            >
              <Button icon={<UploadOutlined />} disabled={busy || !current}>
                {current?.configured || draft !== undefined
                  ? t('pages.ops.customers.brand.replace')
                  : t('pages.ops.customers.brand.upload')}
              </Button>
            </Upload>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {t('pages.ops.customers.brand.hint')}
            </Typography.Text>
          </Space>
        </Spin>
      )}
    </Modal>
  );
};

export default BrandLogoModal;
