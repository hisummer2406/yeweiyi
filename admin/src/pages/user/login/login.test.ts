import { describe, expect, it } from 'vitest';
import { classifyLoginError } from '@/utils/auth';

// 页面本身依赖 Umi 运行时（useModel / TestBrowser），无法在 vitest 里渲染；
// 这里覆盖登录页的错误分流逻辑，登录成功路径由 app.test.tsx / auth.test.ts 与联调覆盖。
describe('classifyLoginError', () => {
  it('401 means wrong credentials (also covers disabled accounts)', () => {
    expect(classifyLoginError({ response: { status: 401 } })).toBe(
      'credentials',
    );
  });

  it.each([400, 500, 503])('%i is a generic failure', (status) => {
    expect(classifyLoginError({ response: { status } })).toBe('failure');
  });

  it('network errors (no response) are a generic failure', () => {
    expect(classifyLoginError({ request: {} })).toBe('failure');
    expect(classifyLoginError(undefined)).toBe('failure');
  });
});
