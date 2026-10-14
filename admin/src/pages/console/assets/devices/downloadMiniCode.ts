import { message } from 'antd';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { getDeviceMiniCode } from '@/services/ant-design-pro/devices';
import { describeMiniCodeError } from './errors';

/** 按图片类型取扩展名；云端按内容判定 PNG/JPEG。 */
export const miniCodeFilename = (device: string, mime: string): string =>
  `${device}-mini-code.${mime === 'image/jpeg' ? 'jpg' : 'png'}`;

/**
 * 平台管理员下载设备小程序码（设备页行内与油站页「当前大屏」旁共用）。成功触发浏览器下载；
 * 失败弹出归类后的提示（鉴权失败已回登录页则不再提示）。
 */
export async function downloadMiniCode(
  device: string,
  t: (id: string, values?: Record<string, string>) => string,
): Promise<void> {
  try {
    const blob = await getDeviceMiniCode(device);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = miniCodeFilename(device, blob.type);
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  } catch (err) {
    const apiErr = err instanceof ApiError ? err : undefined;
    if (apiErr?.authHandled) return;
    const m = describeMiniCodeError(
      apiErr?.status ?? 0,
      apiErr?.code,
      apiErr?.error ?? (err as Error)?.message,
    );
    message.error(t(m.id, { detail: m.detail ?? '' }));
  }
}
