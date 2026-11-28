import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { message } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import zhPages from '@/locales/zh-CN/pages';

const getCustomerBrandLogo = vi.fn();
const putCustomerBrandLogo = vi.fn();
const resetCustomerBrandLogo = vi.fn();

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    formatMessage: ({ id }: { id: string }, values?: Record<string, string>) =>
      ((zhPages as Record<string, string>)[id] ?? id).replace(
        /\{(\w+)\}/g,
        (_, k) => values?.[k] ?? '',
      ),
  }),
}));
vi.mock('@/services/ant-design-pro/customers', () => ({
  getCustomerBrandLogo,
  putCustomerBrandLogo,
  resetCustomerBrandLogo,
}));

const { default: BrandLogoModal } = await import('./BrandLogoModal');
const { ApiError } = await import('@/services/ant-design-pro/apiError');

const customer = { id: 7, name: 'A 油站', createdAt: '2026-11-01T00:00:00Z' };
const homeImg = () =>
  screen
    .getByTestId('brand-preview-home')
    .querySelector('img') as HTMLImageElement;

// jsdom 不解码图片：按 src 立即触发 onload，宽高由测试指定
let nextSize = { width: 128, height: 128 };
class FakeImage {
  naturalWidth = 0;
  naturalHeight = 0;
  onload?: () => void;
  onerror?: () => void;
  set src(_: string) {
    this.naturalWidth = nextSize.width;
    this.naturalHeight = nextSize.height;
    setTimeout(() => this.onload?.(), 0);
  }
}

const pngFile = (len = 32) => {
  const b = new Uint8Array(len);
  b.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  return new File([b], 'logo.png', { type: 'image/png' });
};
const choose = (file: File) => {
  const input = document.querySelector('input[type=file]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [file] } });
};

describe('BrandLogoModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('Image', FakeImage);
    nextSize = { width: 128, height: 128 };
  });
  // message 必须替换成空实现：真弹出的提示在单独的 React 根里，cleanup 清不掉，
  // 慢机器上测试环境拆掉后它的动画还在跑，会报 window is not defined（CI 上复现过）。
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows the customer, the default logo, and saves an uploaded PNG', async () => {
    getCustomerBrandLogo.mockResolvedValue({
      customerId: 7,
      configured: false,
    });
    putCustomerBrandLogo.mockResolvedValue({
      customerId: 7,
      configured: true,
      width: 128,
      height: 128,
      data: 'AAAA',
    });
    const success = vi
      .spyOn(message, 'success')
      .mockImplementation((() => {}) as never);
    render(<BrandLogoModal customer={customer} onClose={() => {}} />);

    expect(await screen.findByText('品牌设置：A 油站')).toBeTruthy();
    await screen.findByText('当前使用中成电气默认 Logo');
    expect(homeImg().getAttribute('src')).toBe(
      '/images/brand-default-mark.png',
    );
    expect(getCustomerBrandLogo).toHaveBeenCalledWith(7);

    choose(pngFile());
    await screen.findByText('预览待保存的新 Logo，保存后生效');
    expect(homeImg().getAttribute('src')).toMatch(/^data:image\/png;base64,/);

    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => expect(putCustomerBrandLogo).toHaveBeenCalled());
    expect(putCustomerBrandLogo.mock.calls[0][0]).toBe(7);
    await screen.findByText('当前使用自定义 Logo（128×128 像素）');
    expect(success).toHaveBeenCalledWith('已保存「A 油站」的 Logo');
  });

  it('refuses a non-PNG or an out-of-range image before sending', async () => {
    getCustomerBrandLogo.mockResolvedValue({
      customerId: 7,
      configured: false,
    });
    const error = vi
      .spyOn(message, 'error')
      .mockImplementation((() => {}) as never);
    render(<BrandLogoModal customer={customer} onClose={() => {}} />);
    await screen.findByText('当前使用中成电气默认 Logo');

    choose(
      new File([new Uint8Array([0xff, 0xd8, 0xff])], 'x.png', {
        type: 'image/png',
      }),
    );
    await waitFor(() => expect(error).toHaveBeenCalledWith('请选择 PNG 图片'));

    nextSize = { width: 32, height: 32 };
    choose(pngFile());
    await waitFor(() =>
      expect(error).toHaveBeenCalledWith('图片宽高须在 64–1024 像素之间'),
    );
    expect(
      (screen.getByRole('button', { name: /保\s*存/ }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(putCustomerBrandLogo).not.toHaveBeenCalled();
  });

  it('restores the default after confirmation', async () => {
    getCustomerBrandLogo.mockResolvedValue({
      customerId: 7,
      configured: true,
      width: 64,
      height: 64,
      data: 'AAAA',
    });
    resetCustomerBrandLogo.mockResolvedValue({
      customerId: 7,
      configured: false,
    });
    render(<BrandLogoModal customer={customer} onClose={() => {}} />);
    await screen.findByText('当前使用自定义 Logo（64×64 像素）');

    fireEvent.click(screen.getByRole('button', { name: '恢复默认' }));
    fireEvent.click(await screen.findByRole('button', { name: /确\s*定|OK/ }));
    await waitFor(() => expect(resetCustomerBrandLogo).toHaveBeenCalledWith(7));
    await screen.findByText('当前使用中成电气默认 Logo');
  });

  it('shows the cloud reason when the image is rejected', async () => {
    getCustomerBrandLogo.mockResolvedValue({
      customerId: 7,
      configured: false,
    });
    putCustomerBrandLogo.mockRejectedValue(
      new ApiError(400, 'image must be a PNG', false, 'brand_logo_invalid'),
    );
    const error = vi
      .spyOn(message, 'error')
      .mockImplementation((() => {}) as never);
    render(<BrandLogoModal customer={customer} onClose={() => {}} />);
    await screen.findByText('当前使用中成电气默认 Logo');
    choose(pngFile());
    await screen.findByText('预览待保存的新 Logo，保存后生效');
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() =>
      expect(error).toHaveBeenCalledWith('图片不符合要求：image must be a PNG'),
    );
  });
});
