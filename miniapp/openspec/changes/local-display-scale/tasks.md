## 1. 实现
- [x] `utils/displayScale.ts` 纯规则（换算、解析、校验、权限、key）与 `scripts/display-scale.check.mjs`
- [x] `utils/displayScaleStore.ts` 本机读写（按 token 的账号与角色）
- [x] `utils/telemetry.ts` 保留原始值并按倍率生成展示值
- [x] 罐详情「本机显示设置」折叠卡、换算标识与原始值；首页换算标识
- [x] 隐藏 `tank-shape-edit` 的「保存到手机」按钮
- [x] 中/英/西文案

## 2. 验证
- [x] `npm run typecheck`、`npm run check`（含 13 项倍率检查：示例数据、不叠乘、舍入顺序、缺失值、非法输入、存储损坏/失败、多设备同罐号、换账号、员工、无角色）
- [ ] 微信开发者工具模拟器
- [ ] 微信真机（尚未验证）
- [ ] Donut（尚未验证）
- [ ] 中/英/西文与窄屏截图核对（尚未验证）
