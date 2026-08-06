## 1. MQTT 死主题补处理分支（风险最低，现状是完全不处理，先做）

- [ ] 1.1 在 `BackService.messageArrived()` 里为 `requestTankSet`（油罐配置）补处理分支：解析 payload，更新 `OilCanSet` 对应字段
- [ ] 1.2 为 `requestProbeSet`（探针配置）补处理分支
- [ ] 1.3 为 `requestTankTable`（罐表标定）补处理分支
- [ ] 1.4 三个分支均按"覆盖式语义"实现（设为某值，不做增量运算），呼应 design.md 决策2的幂等假设
- [ ] 1.5 跑 `./gradlew :app:assembleDebug`，确认编译通过

## 2. 按 id 修正的持久化层方法

- [ ] 2.1 检查 `layer-data-and-hardware-boundaries` 变更的完成状态：若 `ShiftRepository`/`StockRepository` 已落地，新方法加在对应 Repository；否则暂加在 `DBDataManager`，并标注 TODO 待后续迁移
- [ ] 2.2 `OilInOut` 按 `(deviceName 概念上对应本机, 本地id)` 定位并更新非主键字段的方法；id 不存在时返回明确失败标识，不新增记录
- [ ] 2.3 `CommitWork` 同上
- [ ] 2.4 跑 `assembleDebug` + 现有单测，确认通过
- [ ] 2.5 补针对性单测：id存在时正确更新、id不存在时明确失败且不新增记录、重复调用同一修正结果幂等

## 3. LAN 写接口（PC 路径，依赖第2步）

- [ ] 3.1 新增油罐/探针配置更新的 HTTP handler（照抄 `web/handler/OilDischarge.java` 的现有代码风格），同步返回应用后的实际生效值
- [ ] 3.2 新增 `OilInOut` 按 id 修正的 HTTP handler，目标不存在时返回明确失败状态码
- [ ] 3.3 新增 `CommitWork` 按 id 修正的 HTTP handler
- [ ] 3.4 三个新接口暂时沿用现有 LAN 接口的鉴权水平（零鉴权），但在代码注释和本任务的提交信息里明确标注"待鉴权方案定案后补"，不能不标注就当作已解决
- [ ] 3.5 跑 `assembleDebug` + 单测，确认通过
- [ ] 3.6 真机验证：用 PC 客户端（或先用 curl/Postman 模拟）实际调用三个新接口，确认设备本地数据库真正被更新

## 4. MQTT 确认应答（依赖第1、2步，需要真实 MQTT 环境验证）

- [ ] 4.1 定义确认应答的消息结构（命令标识 + 处理结果 + 应用后的实际生效值），确保和现有查询类应答结构可区分
- [ ] 4.2 在配置更新（第1组）、记录修正（若同时开放 MQTT 路径）处理完成后调用发布逻辑
- [ ] 4.3 复用 `getPropert()` 的 topic 命名模式新增确认应答 topic（如 `ackTankSet`/`ackProbeSet`/`ackTankTable`）
- [ ] 4.4 跑 `assembleDebug` + 单测，确认通过
- [ ] 4.5 **真实环境验证（不可跳过）**：本机开发环境无法验证真实的云端 MQTT 往返，需要接入真实 broker（或至少一个本地 MQTT broker模拟环境）确认应答消息能被正确发布和解析

## 5. 收尾

- [ ] 5.1 更新 `docs/小程序与PC端集成设计.md` §6 风险清单第6、7条，标注设备侧对应工作已完成
- [ ] 5.2 更新 `CLAUDE.md` `web/handler/` 一节，补充新增的写接口
- [ ] 5.3 明确记录鉴权方案的决策结果（若本变更实现时鉴权仍未定案，需在此列出待办并说明现状风险）
