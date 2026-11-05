## ADDED Requirements

### Requirement: Records follow the home page's current station
「我的记录」页 SHALL 使用与首页相同的、按账号保存的当前油站，并在顶部显示油站名称；每次显示时 SHALL 重新读取设备列表与油站列表，校验该油站仍可见、并确定其当前唯一设备。页面 SHALL NOT 提供设备选择，也 SHALL NOT 跨油站合并记录。

#### Scenario: Station picked on home
- **WHEN** 用户在首页选了「城东站」后切到「我的记录」
- **THEN** 记录页顶部显示「城东站」，五类入口都指向城东站当前设备，全程没有设备选择

#### Scenario: Station changed on home
- **WHEN** 用户回首页改选「城西站」，再回到「我的记录」或返回到已打开的记录列表
- **THEN** 显示城西站；已打开的列表清掉城东站的记录并按城西站重新加载

#### Scenario: Another account on the same phone
- **WHEN** 账号 A 选过城东站，退出后账号 B 登录
- **THEN** B 的记录页用 B 自己保存的油站（或默认规则），不出现城东站

### Requirement: No guessing when the station has no single current device
有效油站下没有当前账号可见的设备时，记录页 SHALL 说明「该油站暂无可查看的设备」；没有有效油站（未选、已不可见、云端无油站数据、设备未分组）时 SHALL 引导回首页选择油站；同一油站出现多台设备（数据异常）或接口失败时 SHALL 说明原因并提供重试。以上情况 SHALL NOT 猜选一台设备，也 SHALL NOT 弹出设备选择。

#### Scenario: Empty station
- **WHEN** 当前油站存在但没有可见设备
- **THEN** 记录页显示该站暂无可查看的设备，五类入口不可进入

#### Scenario: Old cloud without station data
- **WHEN** 设备列表不带 `station_id`、或 `/api/stations` 失败
- **THEN** 记录页提示请先在首页选择油站，不弹设备选择

#### Scenario: Device list fails
- **WHEN** 读取设备列表失败（网络错误或 5xx）
- **THEN** 记录页显示读取失败与重试按钮，不使用上次的设备

#### Scenario: Session ended
- **WHEN** 读取设备列表返回 401 或 403 `not authorized`
- **THEN** 回到登录页

### Requirement: Five record types have their own entries
记录页 SHALL 提供入库、出库、报警、库存、交班五个独立入口，每个入口打开独立的记录列表页；入库、出库 SHALL 是各自的页面，不借用设备详情页签。

#### Scenario: Open stock-in records
- **WHEN** 用户点击「入库记录」
- **THEN** 打开当前油站设备的入库记录列表页，页面标题与顶部注明油站和「入库记录」

### Requirement: Types without cloud data say so
库存与交班在云端提供对应数据前，其列表页 SHALL 显示「尚未开放」的能力说明，SHALL NOT 发起查询，也 SHALL NOT 显示为空表格或「暂无记录」。

#### Scenario: Open shift records
- **WHEN** 用户点击「交班记录」
- **THEN** 列表页显示交班记录尚未开放的说明，没有网络请求

### Requirement: Tank filter defaults to all visible tanks
记录列表 SHALL 默认查询当前账号可见的全部罐，并提供按实际罐号筛选；筛选项 SHALL 只包含当前账号对该设备可见的罐。切换罐筛选 SHALL 重置分页从第一页开始。在云端提供按罐筛选之前，筛选结果 SHALL 通过对整机记录连续翻页过滤得到，只有整机记录取完仍无匹配才显示为空。

#### Scenario: Staff filter options
- **WHEN** customer_user 只被授权 1、2 号罐
- **THEN** 筛选项只有「全部」「1号罐」「2号罐」

#### Scenario: Filter to one tank
- **WHEN** 用户把筛选从「全部」改为「2号罐」
- **THEN** 列表清空并从第一页重新加载，只显示 2 号罐的记录

#### Scenario: No tank list available
- **WHEN** 设备从未上报（实时快照 404），客户端拿不到罐号
- **THEN** 筛选只有「全部」，列表照常按整机查询

### Requirement: Station device replacement does not mix histories
油站换机后，记录列表 SHALL 跟随该站的新设备：清掉旧设备的记录、罐筛选、分页与在途响应，按新设备重新加载；SHALL NOT 把旧设备的历史拼接进新设备结果。

#### Scenario: Replacement while a list is open
- **WHEN** 用户正在看城东站的报警记录，后台把城东站的设备 A 换成 B，用户下拉刷新或重新进入
- **THEN** 列表只显示 B 的记录，罐筛选按 B 的可见罐重新计算

#### Scenario: Late response from the old device
- **WHEN** 换机后，为 A 发出的请求才返回
- **THEN** 该响应被丢弃，不写入 B 的列表
