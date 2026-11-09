import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/ant-design-pro/apiError';
import MediaLibrary from './index';

const { listMedia, createMedia, updateMedia, listCustomers, search } =
  vi.hoisted(() => ({
    listMedia: vi.fn(),
    createMedia: vi.fn(),
    updateMedia: vi.fn(),
    listCustomers: vi.fn(),
    search: { value: '' },
  }));

vi.mock('@umijs/max', () => ({
  useIntl: () => ({
    locale: 'zh-CN',
    formatMessage: ({ id }: { id: string }) => id,
  }),
  useSearchParams: () => [new URLSearchParams(search.value)],
}));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
}));
vi.mock('@/services/ant-design-pro/media', () => ({
  listMedia,
  createMedia,
  updateMedia,
}));
vi.mock('@/services/ant-design-pro/customers', () => ({ listCustomers }));
vi.mock('@/requestErrorConfig', () => ({ forwardAuthFailure: vi.fn() }));

const row = (o: Partial<API.Medium>): API.Medium => ({
  id: 1,
  name: '92#',
  nameEn: null,
  scope: 'public',
  customerId: null,
  builtinIndex: 5,
  enabled: true,
  updatedAt: '2026-11-09T00:00:00Z',
  ...o,
});

beforeEach(() => {
  vi.clearAllMocks();
  search.value = '';
  listCustomers.mockResolvedValue([{ id: 9, name: '客户A' }]);
  listMedia.mockResolvedValue([
    row({}),
    row({
      id: 2,
      name: '二厂酸',
      scope: 'customer',
      customerId: 9,
      builtinIndex: null,
    }),
  ]);
});
afterEach(cleanup);

async function addPublic(name: string) {
  await screen.findByText('92#');
  fireEvent.click(
    screen.getByRole('button', { name: 'pages.console.media.add' }),
  );
  fireEvent.change(
    await screen.findByLabelText('pages.console.media.col.name'),
    { target: { value: name } },
  );
  fireEvent.click(screen.getByRole('button', { name: /OK|确\s*定/ }));
}

describe('MediaLibrary', () => {
  it('lets a typed name be saved, not only an existing option', async () => {
    createMedia.mockResolvedValue(row({ id: 3, name: '新介质' }));
    render(<MediaLibrary />);
    await addPublic('新介质');
    await waitFor(() =>
      expect(createMedia).toHaveBeenCalledWith({
        name: '新介质',
        nameEn: undefined,
        scope: 'public',
        customerId: undefined,
      }),
    );
    await waitFor(() => expect(listMedia).toHaveBeenCalledTimes(2));
  });

  it('offers no rename for a built-in medium', async () => {
    render(<MediaLibrary />);
    await screen.findByText('92#');
    expect(screen.getAllByText('pages.console.media.edit')).toHaveLength(1);
  });

  it('keeps the dialog open when the name is taken', async () => {
    createMedia.mockRejectedValue(
      new ApiError(409, 'x', false, 'media_name_exists'),
    );
    render(<MediaLibrary />);
    await addPublic('92#');
    await waitFor(() => expect(createMedia).toHaveBeenCalled());
    expect(
      await screen.findByLabelText('pages.console.media.col.name'),
    ).toBeTruthy();
  });

  it('shows only the locked customer when opened from a customer', async () => {
    search.value = 'customer=9';
    render(<MediaLibrary />);
    await screen.findByText('二厂酸');
    expect(screen.queryByText('92#')).toBeNull();
  });
});
