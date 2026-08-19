## Why

`docs/小程序与PC端集成设计.md` §5 设计了小程序/PC 修改数据后如何同步回设备，但那套设计目前在设备这边**没有接收端**：全部现有 HTTP 接口都是只读（`/upload` 是文件上传，不是数据写入）；`BackService` 订阅的 `requestTankTable`/`requestTankSet`/`requestProbeSet` 三个 MQTT 主题收到消息后直接被忽略（`messageArrived()` 里没有对应分支）；设备端也从未实现过"根据 id 修改一条已有 `OilInOut`/`CommitWork` 记录"的操作——现有代码只会新增。没有这些，PC/小程序 的写回设计只是空中楼阁。

## What Changes

- **PC 路径（LAN，同步直连）**：新增设备本机 HTTP 写接口——油罐/探针配置更新、`OilInOut`/`CommitWork` 按 id 修正。请求-响应同步返回结果，不需要队列。
- **小程序路径（云端，异步）**：给 `requestTankTable`/`requestTankSet`/`requestProbeSet` 三个已订阅但从未处理的 MQTT 主题补上真实的处理分支；新增两个记录修正主题（`OilInOut`/`CommitWork` 目前没有对应的 service 主题，需要新增）。
- **新增"操作确认应答"消息语义**：现有 `BackService` 里所有 `thing/service/*` 的应答都是"查询结果"（分页数据），这次要新增一种带命令 id 的"已应用/失败"确认应答，供云端队列（`docs/小程序与PC端集成设计.md` §5.2）匹配状态。
- **新增按 id 修正历史记录的持久化操作**：`OilInOut`/`CommitWork` 目前的写操作都是 insert-only，这次要补 update-by-id。

## Capabilities

### New Capabilities
- `sync/oilcan-config-write`：油罐/探针配置的写入入口（LAN 直连 + MQTT 两条路径）
- `sync/record-correction`：`OilInOut`/`CommitWork` 按 id 修正的写入入口（LAN 直连 + MQTT 两条路径）
- `sync/command-acknowledgement`：MQTT 异步命令的确认应答语义

### Modified Capabilities
（无——这些是全新能力，设备目前没有任何对应实现）

## Impact

- **受影响代码**：`web/handler/`（新增写接口类，风格照抄 `OilDischarge.java`/`AlarmHandler.java` 的现有模式）、`service/BackService.java`（`subscribeService()`/`messageArrived()` 补处理分支、新增确认应答发布逻辑）、持久化层（新增按 id 更新的方法——若 `layer-data-and-hardware-boundaries` 变更已经落地对应领域的 Repository，新方法应加在 `StockRepository`/`ShiftRepository` 里；若尚未落地，暂时加在 `DBDataManager`，待该变更迁移时一并搬走，不要为了赶本变更提前对 `DBDataManager` 做额外的结构调整）。
- **建议的实施顺序**：本变更依赖的持久化层最好等 `layer-data-and-hardware-boundaries` 至少完成"交班""出入库"两个任务组（`ShiftRepository`/`StockRepository` 落地）之后再做，这样新的按 id 更新方法可以直接加在正确的位置，不用先加进 `DBDataManager` 再迁移一次。**不是硬阻塞**——如果业务上小程序/PC 项目更急，也可以先加进 `DBDataManager`，接受一次后续迁移的返工成本。
- **不含云端后端本身**：云端的待同步队列、离线补推调度逻辑（`docs/小程序与PC端集成设计.md` §5.2）是新建的云端服务职责，不在本仓库范围内，本变更只负责设备侧"能接收、能处理、能确认"这三步。
- **鉴权是前置风险，不在本变更范围**：新增的写接口如果部署到现在这套零鉴权的 LAN HTTP 服务上，等于让"改配置""改历史记录"这种操作也变成任何局域网内的人都能直接调用。`docs/小程序与PC端集成设计.md` §3.3/§6.4 已经把 LAN 接口鉴权列为待决策项——**建议在实现本变更的写接口之前，先决定鉴权方案，不要让写接口成为第一批暴露在零鉴权服务上的高风险操作**。
- **对三条硬约束的态度**：respects 约束1（新增能力不改动现有读路径，不做大范围重写）；respects 约束2（新增的按 id 更新操作、MQTT 处理分支都应该先补测试再接入现网，尤其是"命令幂等"这条规则容易在补测试时才发现遗漏场景）；约束3 部分适用（这不是硬件协议层改动，不需要真机验证探头数据，但涉及本地数据库写入和 MQTT 收发，仍需要在真实网络环境下验证离线补推的实际时序）。
