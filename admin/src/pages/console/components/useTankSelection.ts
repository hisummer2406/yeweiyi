import { useEffect, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';

/**
 * 记录页选中的罐号：属于某台设备，换设备即回到「全部油罐」（旧设备的 1 号罐与新设备的 1 号罐不是同一个对象）。
 * 云端对调用者不能查看的罐答 403 tank_not_authorized（不是空列表、也不是登录失效）：调用方把请求错误交给
 * onError，这里回到全部油罐并记下被拒的罐号，供页面提示；用户再选罐时提示消失。
 */
export function useTankSelection(device: string, error: unknown) {
  const [sel, setSel] = useState<{
    device: string;
    tank?: number;
    refused?: number;
  }>({ device });
  const own = sel.device === device;
  const tank = own ? sel.tank : undefined;
  const refusedNow =
    error instanceof ApiError && error.code === 'tank_not_authorized';

  useEffect(() => {
    if (refusedNow && tank !== undefined) setSel({ device, refused: tank });
  }, [refusedNow, tank, device]);

  return {
    tank,
    refused: own ? sel.refused : undefined,
    setTank: (next?: number) => setSel({ device, tank: next }),
  };
}
