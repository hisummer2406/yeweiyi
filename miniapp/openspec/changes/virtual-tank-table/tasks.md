## 1. 实现
- [x] `utils/virtualTable.ts`：查表/插值/边界、派生读数、表校验、权限、key、旧倍率 key
- [x] `utils/virtualTableStore.ts`：本机读写、停用、旧倍率清理
- [x] `utils/telemetry.ts`、首页、罐详情接入虚拟表；删除倍率卡、`utils/displayScale*.ts`
- [x] 罐表设置：「启用为虚拟罐表」、本机虚拟罐表区块与停用
- [x] 参数编辑器数字键盘 + ± 符号（`splitSigned`/`joinSigned`）
- [x] 首页读数与标签同字号、同字重、单行
- [x] 中/英/西文案；#44 方案标注被替代

## 2. 验证
- [x] `npm run typecheck`、`npm run check`（`virtual-table.check.mjs` 15 项：示例 260 点/2599 mm/30086 L、表点/插值/首段、缺失/负数/越界、完整表逐点、不同罐型非等比、派生口径、密度缺失、回退、不叠加、损坏表、存储隔离/员工/无角色/写失败、旧倍率清理；`remote-config` 与 `param-editor` 的符号输入流程；首页离线重算）
- [ ] 微信开发者工具模拟器
- [ ] 微信真机：三个修正字段默认数字键盘、500/100/0/1.5 及 ± 负数、首页长读数单行（尚未验证）
- [ ] Donut（尚未验证）
- [ ] 中/英/西文与窄屏截图核对（尚未验证）
