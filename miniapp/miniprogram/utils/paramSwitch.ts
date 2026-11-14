// 切罐前的未下发修改（Issue #43）。罐详情和设置页的参数页面共用：任一编辑组件有修改时让用户选
// 保留草稿并切换 / 放弃修改并切换 / 取消（action sheet 的取消）。草稿是自动暂存的，「保留」只是切换。
import { t } from './i18n';

export type ParamEditorRef = { hasEdits(): boolean; discardDraft(): void };

export function confirmTankSwitch(editors: (ParamEditorRef | null | undefined)[]): Promise<boolean> {
  const dirty = editors.filter((e): e is ParamEditorRef => !!e && e.hasEdits());
  if (dirty.length === 0) return Promise.resolve(true);
  return new Promise((resolve) => {
    wx.showActionSheet({
      itemList: [t('param.switchKeep'), t('param.switchDiscard')],
      success: (r) => {
        if (r.tapIndex === 1) dirty.forEach((e) => e.discardDraft());
        resolve(true);
      },
      fail: () => resolve(false),
    });
  });
}
