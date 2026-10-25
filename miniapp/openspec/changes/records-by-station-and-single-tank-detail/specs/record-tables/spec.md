## ADDED Requirements

### Requirement: Columns follow the display's order
每类记录 SHALL 以表头 + 行列展示，列从左到右按以下顺序（单位写在表头）：

| 类型 | 列顺序 |
| --- | --- |
| 入库 | 罐号、介质、入库时间、前高(mm)、前量(L)、后高(mm)、后量(L)、入库量(L)、入库量(kg) |
| 出库 | 罐号、介质、出库时间、前高(mm)、前量(L)、后高(mm)、后量(L)、出库量(L)、出库量(kg) |
| 报警 | 罐号、介质、报警信息、报警时间 |
| 库存 | 罐号、介质、库存生成时间、液位(mm)、容积(L)、水高(mm)、温度(℃)、质量(kg) |
| 交班 | 罐号、介质、交班时间、接班时间、前高(mm)、前量(L)、后高(mm)、后量(L)、出库量(L)、出库量(kg) |

库存、交班的表头在能力开放后才显示（见 `station-records`）。

#### Scenario: Stock-in header
- **WHEN** 用户打开入库记录
- **THEN** 表头从左到右为 罐号、介质、入库时间、前高(mm)、前量(L)、后高(mm)、后量(L)、入库量(L)、入库量(kg)

### Requirement: Missing values show a dash
云端没有提供的字段 SHALL 显示「—」；SHALL NOT 显示 0、示例数字，或用当前油品、当前密度、前后量差推算历史值。入库量/出库量(L) SHALL 使用云端给出的实际交付量，不用前后容量差代替。

#### Scenario: Alarm medium not provided
- **WHEN** 报警记录没有介质字段
- **THEN** 介质列显示「—」，不填当前实时数据里的油品

#### Scenario: Delivered mass not provided
- **WHEN** 出入库记录没有入库量(kg)的明确字段
- **THEN** 入库量(kg)列显示「—」，不用前后重量差计算

### Requirement: Wide tables scroll horizontally with a fixed tank column
竖屏下表格 SHALL 横向滚动，罐号列固定在左侧，表头与数据列随横滑同步；SHALL NOT 为放下所有列而缩小字号。数值列右对齐、时间按手机本地时区统一格式。过长的报警信息 SHALL 可以查看全文（例如点击该行展开）。

#### Scenario: Scroll a stock-out table
- **WHEN** 用户在出库表上向左滑动
- **THEN** 罐号列保持不动，其余列与表头一起滑动，没有串列

#### Scenario: Long alarm text
- **WHEN** 某条报警信息超出单元格
- **THEN** 单元格显示省略，点击该行可看到完整报警信息、状态和级别

### Requirement: Alarm rows keep status and severity
报警表 SHALL 在四个主列之外保留产生/恢复状态与级别的区分（例如状态标识与行内详情），SHALL NOT 为对齐列而丢失这两项语义。

#### Scenario: Active and cleared alarms
- **WHEN** 同一报警先产生后恢复
- **THEN** 表中两行能区分「报警中」与「已恢复」，并可看到级别

### Requirement: Range, order and paging
记录列表 SHALL 默认按记录时间倒序，提供最近 24 小时 / 近 7 天 / 近 30 天 / 自定义日期的时间范围，并在滚动到底部时加载更早的记录。入库与出库列表在云端无方向筛选时 SHALL 连续翻页过滤，SHALL NOT 因某一页全是另一方向而显示为空或结束。

#### Scenario: Page full of the other direction
- **WHEN** 入库列表请求的某一页全部是出库记录，云端表示还有更多
- **THEN** 页面继续取下一页，而不是显示「暂无入库记录」或「已到底」

#### Scenario: Change range
- **WHEN** 用户把范围从 24 小时改为近 30 天
- **THEN** 列表清空并按新范围从第一页加载，旧范围的在途响应被丢弃

### Requirement: Six list states are distinguishable
每个记录列表 SHALL 区分并分别显示：加载中、真实无记录、首次加载失败（带重试）、刷新失败但保留旧数据（注明可能已过期及上次更新时间）、记录不完整、功能尚未开放。出入库 502 时 SHALL 仍显示其中可用的记录并给出不完整说明（unparsable / legacySkipped / partial / truncated 等）。

#### Scenario: Stock 502 with rows
- **WHEN** 出入库查询返回 502，但带有已解析的记录和 unparsable 计数
- **THEN** 表格显示已解析的记录，上方提示部分记录无法解析，不显示为错误页或空表

#### Scenario: First load fails
- **WHEN** 首次查询网络失败
- **THEN** 显示失败原因和重试按钮，不显示「暂无记录」

#### Scenario: Refresh fails after success
- **WHEN** 下拉刷新失败，之前已有数据
- **THEN** 保留已有表格，并提示可能已过期与上次更新时间

#### Scenario: Truly empty
- **WHEN** 查询成功、记录已取完且没有匹配记录
- **THEN** 显示所选范围内暂无该类记录

#### Scenario: Unauthorized tank
- **WHEN** 查询返回 403 `tank_not_authorized`
- **THEN** 提示该罐无权查看并从筛选项移除，回到「全部」，不显示为空记录、不结束会话
