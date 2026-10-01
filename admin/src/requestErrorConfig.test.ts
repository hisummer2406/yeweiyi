import { message, notification } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { errorConfig } from './requestErrorConfig';

vi.mock('antd', () => ({
  message: {
    warning: vi.fn(),
    error: vi.fn(),
  },
  notification: {
    open: vi.fn(),
  },
}));

const { mockHistoryReplace, mockHistory, mockGetToken, mockClearToken } = vi.hoisted(() => {
  const mockHistoryReplace = vi.fn();
  return {
    mockHistoryReplace,
    mockHistory: {
      location: { pathname: '/console/devices', search: '', hash: '' },
      replace: mockHistoryReplace,
    },
    mockGetToken: vi.fn(),
    mockClearToken: vi.fn(),
  };
});

vi.mock('@umijs/max', () => ({
  getIntl: vi.fn(() => ({
    formatMessage: vi.fn(({ defaultMessage }) => defaultMessage),
  })),
  history: mockHistory,
}));

vi.mock('@/utils/auth', () => ({
  getToken: mockGetToken,
  clearToken: mockClearToken,
}));

describe('requestErrorConfig', () => {
  // biome-ignore lint/style/noNonNullAssertion: config handlers are always defined
  const errorThrower = errorConfig.errorConfig!.errorThrower!;
  // biome-ignore lint/style/noNonNullAssertion: config handlers are always defined
  const errorHandler = errorConfig.errorConfig!.errorHandler!;

  beforeEach(() => {
    vi.clearAllMocks();
    window.history.pushState({}, '', '/console/devices');
  });

  describe('errorThrower', () => {
    it('should throw error when success is false', () => {
      const response = {
        success: false,
        data: null,
        errorCode: 400,
        errorMessage: 'Bad Request',
        showType: 2,
      };

      expect(() => {
        errorThrower(response);
      }).toThrow('Bad Request');
    });

    it('should not throw error when success is true', () => {
      const response = {
        success: true,
        data: { id: 1 },
      };

      expect(() => {
        errorThrower(response);
      }).not.toThrow();
    });

    it('should throw BizError with correct info', () => {
      const response = {
        success: false,
        data: { detail: 'more info' },
        errorCode: 403,
        errorMessage: 'Forbidden',
        showType: 3,
      };

      expect.assertions(5);
      try {
        errorThrower(response);
      } catch (error: any) {
        expect(error.name).toBe('BizError');
        expect(error.info.errorCode).toBe(403);
        expect(error.info.errorMessage).toBe('Forbidden');
        expect(error.info.showType).toBe(3);
        expect(error.info.data).toEqual({ detail: 'more info' });
      }
    });
  });

  describe('errorHandler', () => {
    it('should rethrow error when skipErrorHandler is true', () => {
      const error = new Error('Test error');
      const opts = { skipErrorHandler: true };

      expect(() => {
        errorHandler(error, opts);
      }).toThrow('Test error');
    });

    it('should handle SILENT showType', () => {
      const error: any = new Error('Silent error');
      error.name = 'BizError';
      error.info = {
        errorCode: 1001,
        errorMessage: 'Silent error',
        showType: 0,
      };

      errorHandler(error, {});

      expect(message.warning).not.toHaveBeenCalled();
      expect(message.error).not.toHaveBeenCalled();
      expect(notification.open).not.toHaveBeenCalled();
    });

    it('should handle WARN_MESSAGE showType', () => {
      const error: any = new Error('Warning');
      error.name = 'BizError';
      error.info = {
        errorCode: 1002,
        errorMessage: 'This is a warning',
        showType: 1,
      };

      errorHandler(error, {});

      expect(message.warning).toHaveBeenCalledWith('This is a warning');
    });

    it('should handle ERROR_MESSAGE showType', () => {
      const error: any = new Error('Error message');
      error.name = 'BizError';
      error.info = {
        errorCode: 1003,
        errorMessage: 'This is an error',
        showType: 2,
      };

      errorHandler(error, {});

      expect(message.error).toHaveBeenCalledWith('This is an error');
    });

    it('should handle NOTIFICATION showType', () => {
      const error: any = new Error('Notification');
      error.name = 'BizError';
      error.info = {
        errorCode: 1004,
        errorMessage: 'This is a notification',
        showType: 3,
      };

      errorHandler(error, {});

      expect(notification.open).toHaveBeenCalledWith({
        title: 1004,
        description: 'This is a notification',
      });
    });

    it('should handle REDIRECT showType', () => {
      const error: any = new Error('Redirect');
      error.name = 'BizError';
      error.info = {
        errorCode: 401,
        errorMessage: 'Unauthorized',
        showType: 9,
      };

      errorHandler(error, {});

      // REDIRECT 分支不应触发任何消息/通知提示
      expect(message.warning).not.toHaveBeenCalled();
      expect(message.error).not.toHaveBeenCalled();
      expect(notification.open).not.toHaveBeenCalled();
    });

    it('should handle default case for unknown showType', () => {
      const error: any = new Error('Unknown type');
      error.name = 'BizError';
      error.info = {
        errorCode: 1005,
        errorMessage: 'Unknown error type',
        showType: 99,
      };

      errorHandler(error, {});

      expect(message.error).toHaveBeenCalledWith('Unknown error type');
    });

    it('should handle axios response error', () => {
      const error: any = new Error('Axios error');
      error.response = {
        status: 500,
        data: {},
      };

      errorHandler(error, {});

      expect(message.error).toHaveBeenCalledWith('Response status:500');
    });

    it('should clear the token and redirect to login on a 401 response', () => {
      const error: any = new Error('Unauthorized');
      error.response = { status: 401, data: { error: 'missing bearer token' } };

      errorHandler(error, {});

      expect(mockClearToken).toHaveBeenCalled();
      expect(mockHistoryReplace).toHaveBeenCalledWith(
        `/user/login?redirect=${encodeURIComponent('/console/devices')}`,
      );
      // 401 走专用分支，不应该再弹通用的 "Response status:401" 提示
      expect(message.error).not.toHaveBeenCalled();
    });

    it('should not redirect again on a 401 response while already on the login page', () => {
      window.history.pushState({}, '', '/user/login');
      const error: any = new Error('Unauthorized');
      error.response = { status: 401, data: { error: 'missing bearer token' } };

      errorHandler(error, {});

      expect(mockClearToken).toHaveBeenCalled();
      expect(mockHistoryReplace).not.toHaveBeenCalled();
    });

    it('should handle offline error', () => {
      const error: any = new Error('Network error');
      error.request = {};

      const originalOnLine = navigator.onLine;
      Object.defineProperty(navigator, 'onLine', {
        writable: true,
        value: false,
      });

      try {
        errorHandler(error, {});

        expect(message.error).toHaveBeenCalledWith(
          'Network unavailable. Please check your connection and try again.',
        );
      } finally {
        Object.defineProperty(navigator, 'onLine', {
          writable: true,
          value: originalOnLine,
        });
      }
    });

    it('should handle request error with no response', () => {
      const error: any = new Error('Request error');
      error.request = {};

      errorHandler(error, {});

      expect(message.error).toHaveBeenCalledWith(
        'None response! Please retry.',
      );
    });

    it('should handle generic error', () => {
      const error: any = new Error('Generic error');

      errorHandler(error, {});

      expect(message.error).toHaveBeenCalledWith(
        'Request error, please retry.',
      );
    });
  });

  describe('requestInterceptors', () => {
    // The interceptor is registered as a plain function (not a tuple),
    // so narrow the union type to a callable for the test.
    const interceptor = errorConfig.requestInterceptors?.[0] as (config: {
      url?: string;
      method?: string;
      headers?: Record<string, string>;
    }) => { url?: string; headers?: Record<string, string> };

    it('attaches an Authorization header when a token is stored', () => {
      mockGetToken.mockReturnValue('abc.def.ghi');
      const config = { url: 'https://api.example.com/users', method: 'GET' };

      const result = interceptor(config);

      expect(result.headers?.Authorization).toBe('Bearer abc.def.ghi');
      expect(result.url).toBe('https://api.example.com/users');
    });

    it('omits the Authorization header when no token is stored', () => {
      mockGetToken.mockReturnValue(null);
      const config = { url: 'https://api.example.com/users', method: 'GET' };

      const result = interceptor(config);

      expect(result.headers?.Authorization).toBeUndefined();
    });

    it('preserves existing headers when attaching the token', () => {
      mockGetToken.mockReturnValue('abc.def.ghi');
      const config = { headers: { 'Content-Type': 'application/json' } };

      const result = interceptor(config);

      expect(result.headers).toEqual({
        'Content-Type': 'application/json',
        Authorization: 'Bearer abc.def.ghi',
      });
    });

    it('should handle URL without config', () => {
      mockGetToken.mockReturnValue(null);
      const config = {};

      const result = interceptor(config);

      expect(result.url).toBeUndefined();
    });
  });
});
