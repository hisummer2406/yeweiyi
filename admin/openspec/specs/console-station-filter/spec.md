# console-station-filter Specification

## Purpose
监控页（设备总览、报警/入库/出库的设备选择）按油站筛选与分段：选项来自 GET /api/stations（静默请求），筛选值同步在 ?station=，以及云端不支持油站、没有油站或设备行没有 station_id 时的退化。

## Requirements

### Requirement: 油站选项的获取与退化
监控页 SHALL 通过 `GET /api/stations` 取得调用者可见的油站作为筛选选项（平台管理员全部、客户管理员本客户全部、客户用户仅自己设备所在的油站），该请求 SHALL 为静默请求：
失败（含旧版云端的 404）SHALL NOT 弹出全局错误提示，SHALL 退化为不显示油站筛选与分组，页面其余行为与未引入油站时一致；401 与账号失效的 403 SHALL 仍转交全局鉴权处理。
油站列表为空时同样不显示筛选。设备行没有 `station_id` 字段（旧版云端）时 SHALL NOT 显示筛选与分组。

#### Scenario: 云端没有油站接口
- **WHEN** `GET /api/stations` 返回 404
- **THEN** 不弹错误提示，设备总览与记录页按未引入油站时的样子显示，没有油站筛选与分段

#### Scenario: 没有油站
- **WHEN** 调用者可见的油站为空
- **THEN** 不显示油站筛选

#### Scenario: 设备行没有 station_id
- **WHEN** `GET /api/devices` 的行都没有 `station_id` 字段
- **THEN** 不显示筛选与分段，不把这些设备当作「未分组」

#### Scenario: 账号失效仍回登录页
- **WHEN** 油站请求返回 401 或账号失效的 403
- **THEN** 清 token 并回登录页，不被静默吞掉

### Requirement: 设备总览按油站筛选与分段
设备总览 SHALL 在有油站数据时提供油站筛选，选项为「全部油站」「未分组」及各油站，选中值同步到 URL 的 `?station=`（省略表示全部，`none` 表示未分组，数字为油站 id）。
选择「全部油站」时设备卡片 SHALL 按油站分段显示，段标题为油站名，「未分组」段在最后；选择某个油站或「未分组」时 SHALL 只显示该范围的设备且不再分段。
`station_id` 为 `null` 的设备 SHALL 归入「未分组」；`station_id` 对应的油站不在可见列表中时，该设备 SHALL 以 `#id` 作为段标题而不是丢失。
平台管理员下选项标签 SHALL 追加所属客户名以区分重名油站（客户名取不到时只显示油站名）。
筛选与分段 SHALL NOT 改变遥测取数与 30 秒轮询行为，也 SHALL NOT 触发额外的遥测请求。

#### Scenario: 全部油站按段显示
- **WHEN** 客户管理员打开设备总览且设备分属两个油站并有一台未分组
- **THEN** 页面出现两个油站段和末尾的「未分组」段，每段下是该范围的设备卡片

#### Scenario: 筛选单个油站
- **WHEN** 用户选择某个油站
- **THEN** 只显示该油站的设备，不再分段，URL 为 `?station=<id>`

#### Scenario: 选择未分组
- **WHEN** 用户选择「未分组」
- **THEN** 只显示 `station_id` 为 `null` 的设备，URL 为 `?station=none`

#### Scenario: URL 直达
- **WHEN** 访问 `/console/devices?station=<id>`
- **THEN** 筛选控件显示对应油站并只显示该油站的设备；`id` 不在可见列表时回退为「全部油站」

#### Scenario: 筛选不触发额外遥测请求
- **WHEN** 用户切换油站筛选
- **THEN** 不新增遥测请求，已取到的数据直接按筛选重新渲染

### Requirement: 记录页设备选择按油站缩小候选
报警、入库、出库页的设备选择器 SHALL 在有油站数据时提供同样的油站筛选（同一 `?station=` 约定），选中油站后设备下拉 SHALL 只列出该范围的设备。
筛选 SHALL 只缩小候选，SHALL NOT 清除或改变已选设备：URL 中的 `?device=` 仍按**完整**设备列表判断是否可见，已选设备不在当前候选内时下拉仍显示已选设备。
记录查询 SHALL 仍必须带 `device`，系统 SHALL NOT 提供按油站聚合的跨设备记录查询（云端没有该能力）。

#### Scenario: 缩小候选
- **WHEN** 用户在报警页选择某个油站
- **THEN** 设备下拉只列出该油站的设备，未选设备前不发起记录请求

#### Scenario: 换油站不清掉已选设备
- **WHEN** 已选设备 A 属于油站 X，用户把筛选改成油站 Y
- **THEN** 设备 A 仍保持选中并继续显示其记录，页面不提示「不可见」，下拉候选变为油站 Y 的设备

#### Scenario: 没有按油站的汇总查询
- **WHEN** 选择了油站但没有选设备
- **THEN** 页面提示先选择设备，不发起任何记录请求
