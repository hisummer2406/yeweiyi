## ADDED Requirements

### Requirement: Tank row opens detail on that tank
首页的每个罐行 SHALL 携带完整设备码与该罐的实际罐号（协议罐号，不是列表下标）以及当前油站进入设备详情；详情 SHALL 默认选中这个罐。本条取代 `home-by-station` 变更中「Tank card opens device detail」的「详情保留出入库记录」表述。

#### Scenario: Two tanks on one device
- **WHEN** 某设备有 1 号、3 号两个可见罐，用户在首页点击 3 号罐
- **THEN** 详情打开该设备并选中 3 号罐，页面明确显示「3号罐」及其油品

#### Scenario: Different tank rows of the same device
- **WHEN** 用户先点 1 号罐进入详情、返回后再点 3 号罐
- **THEN** 第二次进入的详情选中 3 号罐，不沿用上一次的 1 号罐

### Requirement: Entry without a tank number picks the first visible tank
仅携带设备码的入口（扫码、小程序码 `scene`、旧链接）SHALL 在实时快照加载后默认选中第一个可见罐（按罐号升序）。指定的罐号不在当前可见罐中时，SHALL 提示「该罐不可见」并改选第一个可见罐；没有任何可见罐时 SHALL 显示无可见罐的说明，不显示任何罐的旧数据。

#### Scenario: Scan opens a device
- **WHEN** 用户扫码打开一台有 2、5 号可见罐的设备
- **THEN** 详情选中 2 号罐

#### Scenario: Requested tank no longer visible
- **WHEN** 链接指定 4 号罐，但当前账号对该设备只可见 2 号罐（授权被撤销或罐已不存在）
- **THEN** 页面提示 4 号罐不可见，并选中 2 号罐，不显示 4 号罐的任何缓存数据

#### Scenario: Device authorized but never reported
- **WHEN** 实时快照返回 404（已授权、从未上报）
- **THEN** 页面显示「设备尚未上报数据」，罐切换不出现，报警页签仍可按整机查看且注明尚无罐信息

### Requirement: Tank switcher lists only visible tanks
详情 SHALL 在页签上方显示当前罐（罐号 + 油品），并提供只含当前账号可见罐的切换；只有一个可见罐时 SHALL 只显示罐名、不显示切换控件。员工（customer_user）SHALL NOT 看到未授权的罐。

#### Scenario: Staff with one granted tank
- **WHEN** customer_user 只被授权某设备的 1 号罐
- **THEN** 详情只显示「1号罐」，没有切换控件，也不出现其他罐号

#### Scenario: Admin switches tank
- **WHEN** customer_admin 在 1 号罐详情中切换到 2 号罐
- **THEN** 实时数据与报警记录都换成 2 号罐，当前页签不变

### Requirement: Snapshot, alarms and live banner follow the current tank
实时数据页签 SHALL 只显示当前罐。报警记录页签 SHALL 只列当前罐的报警，并且只有在整机记录已翻到云端声明的末尾仍没有该罐记录时，才显示「该罐暂无报警」；SHALL NOT 仅凭整机第一页过滤结果判定为空。实时报警横幅 SHALL 只显示当前罐的推送。切换罐 SHALL 重置报警列表、分页与加载状态，保留已选时间范围。

#### Scenario: Other tank fills the first page
- **WHEN** 所选时间内整机前 50 条报警都属于 1 号罐，2 号罐的报警在更早的记录中
- **THEN** 选中 2 号罐时页面继续向后取，直到找到 2 号罐的报警或整机记录取完，期间显示加载中而不是「暂无报警」

#### Scenario: Live alarm for another tank
- **WHEN** 当前是 1 号罐，实时连接推来 2 号罐的报警
- **THEN** 横幅不显示该报警；切到 2 号罐后报警列表重新加载，包含它

#### Scenario: Switch keeps range
- **WHEN** 用户在报警页签选了「近7天」后切换罐
- **THEN** 新罐的报警按「近7天」重新查询，列表从第一页开始

### Requirement: Late responses never write into another tank or device
切换罐、切换设备（换机跟随）或时间范围后，SHALL 丢弃为旧罐/旧设备/旧范围发出且尚未返回的响应。换机后「旧设备 + 1 号罐」与「新设备 + 1 号罐」SHALL 视为不同对象：重新计算可见罐，按新设备默认规则选罐，不接续旧设备的列表。

#### Scenario: Fast tank switching
- **WHEN** 用户快速从 1 号切到 2 号再切到 3 号，1、2 号的报警请求随后才返回
- **THEN** 列表只显示 3 号罐的结果

#### Scenario: Station device replaced while open
- **WHEN** 详情跟随的油站在后台把设备 A 换成设备 B
- **THEN** A 的数据、罐选择、报警列表全部清掉，按 B 的可见罐重新选罐并加载

### Requirement: Stock in/out tab removed from device detail
设备详情 SHALL NOT 提供出入库记录页签。带 `tab=oilInOut` 的旧链接 SHALL 在能确认「该设备仍是所带油站的当前设备，且该油站可见」时转到该油站的入库或出库记录页（按 `direction`，缺省为入库），否则留在详情的实时数据页签并提示「出入库记录已移到『我的记录』」。

#### Scenario: Old link with a valid station
- **WHEN** 打开 `device-detail?device=A&station=7&tab=oilInOut&direction=OUT`，A 仍是 7 号站的当前设备
- **THEN** 转到 7 号站的出库记录页

#### Scenario: Old link without verifiable station
- **WHEN** 旧链接不带油站，或所带油站的当前设备已不是 A
- **THEN** 停在 A 的实时数据页签并提示出入库已移到「我的记录」，不打开其他油站或设备的记录

### Requirement: Session and authorization errors keep their meaning
单罐化 SHALL NOT 改变既有错误语义：401 与 403 `not authorized` 结束会话回登录；403 `not authorized for this device` 显示无权查看该设备、不结束会话；403 `tank_not_authorized` 只表示该罐无权查看，页面移除该罐并改选其他可见罐。控制命令的目标与权限 SHALL 保持设备级不变。

#### Scenario: Tank grant revoked mid-session
- **WHEN** 员工正在看 2 号罐，刷新时该罐的请求返回 403 `tank_not_authorized`
- **THEN** 页面提示 2 号罐已无权查看，改选仍可见的罐；没有其他可见罐时显示无可见罐说明，会话不结束

#### Scenario: Device-level refusal
- **WHEN** 请求返回 403 `not authorized for this device`
- **THEN** 页面显示无权查看该设备，不清 token、不跳登录
