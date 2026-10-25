## ADDED Requirements

### Requirement: Home shows tank cards for the selected station
首页 SHALL 直接展示当前油站下所有设备的罐卡片，用户无需进入设备详情即可看到每个罐的实时数据。每张罐卡片 MUST 展示：罐图、「N号 油品」标签、液位、容积、水高、温度、质量。数据 MUST 来自各设备的实时遥测快照，不得使用写死或本地造的数据。卡片 MUST NOT 展示设备的 `is_online`。

#### Scenario: Station with devices that have reported
- **WHEN** 用户打开首页且当前油站下的设备都已上报过遥测
- **THEN** 页面按设备分组、按罐号顺序展示全部罐卡片，每张卡片的五项数值与该罐最新快照一致

#### Scenario: Station has several devices
- **WHEN** 当前油站下有多于一台设备
- **THEN** 每台设备的罐卡片前 MUST 有一个以设备名为标题的分组头；只有一台设备时可以省略分组头

#### Scenario: Device has never reported
- **WHEN** 某台设备的遥测接口返回"已授权但从未上报"
- **THEN** 该设备分组下显示"该设备还没有上报过数据"，这不是错误状态，不弹错误提示，也不影响其他设备的卡片

#### Scenario: One device fails to load
- **WHEN** 某台设备的遥测请求失败（网络错误、5xx、403）
- **THEN** 只有该设备分组显示失败原因与"重试"，其他设备的卡片照常展示；页面 MUST NOT 因一台设备失败而整体变空

#### Scenario: Refresh
- **WHEN** 用户下拉刷新，或从其他页面返回首页
- **THEN** 页面重新读取当前油站下所有设备的遥测快照；首页 MUST NOT 把快照当作实时推送流来展示，并 MUST 显示该快照的上报时间

### Requirement: Tank illustration follows the tank's shape
罐图 SHALL 按设备配置里的罐形状（卧罐/立罐）选择。云端尚未提供罐形状之前，页面 MUST 使用卧罐图，且 MUST NOT 根据油品、罐号等字段猜测形状。

#### Scenario: Shape not available
- **WHEN** 云端没有提供该罐的形状
- **THEN** 该罐卡片使用卧罐图

#### Scenario: Shape provided
- **WHEN** 云端提供了该罐的形状为立罐
- **THEN** 该罐卡片使用立罐图，同一设备的其他罐不受影响

### Requirement: Station dropdown switches the current station
首页顶部 SHALL 显示当前油站名称和下拉标记，点击进入油站选择页。一个客户可以有多个油站；选中的油站 MUST 被记在本机，下次打开首页时恢复。

#### Scenario: Switch station
- **WHEN** 用户在油站选择页点选另一个油站
- **THEN** 返回首页，顶部显示新油站名称，罐卡片刷新为新油站下的设备

#### Scenario: Remembered station no longer available
- **WHEN** 本机记住的油站不在当前账号可见的油站列表中
- **THEN** 首页回退到列表的第一个油站，不报错

#### Scenario: Search in station select page
- **WHEN** 用户在油站选择页的搜索框输入文字
- **THEN** 列表只保留名称或地址包含该文字的油站

#### Scenario: Station select page lists names and addresses
- **WHEN** 用户进入油站选择页
- **THEN** 每个油站展示名称和地址；云端没有返回地址时只展示名称

### Requirement: Stations shown are those of the user's bound devices
油站下拉与油站选择页 SHALL 只列出用户已绑定设备所属的油站，并且选中油站后只展示该油站下用户已绑定的设备。页面 MUST NOT 提示用户"该油站还有其他设备"，也 MUST NOT 展示用户没有绑定的设备。

#### Scenario: Station has devices the user did not bind
- **WHEN** 某油站有 3 台设备，用户只绑定了其中 1 台
- **THEN** 该油站出现在列表中，选中后只展示那 1 台设备的罐卡片，没有任何关于另外 2 台的提示

#### Scenario: User has bound nothing in a station
- **WHEN** 用户没有绑定某油站下的任何设备
- **THEN** 该油站不出现在下拉与选择页中

### Requirement: Device configuration entries are for station administrators only
设备配置类入口（参数修正、储罐设置、罐表设置、记录设置、报表设置、补录记录）SHALL 只对该油站的管理员显示。云端未提供"当前用户是否为油站管理员"之前，页面 MUST NOT 自行用 `customer_admin` 或其他已有角色推断，现有这些页面保持现状（不接后端，保存时明确提示未开放）。

#### Scenario: Cloud does not provide the administrator indication
- **WHEN** 云端没有提供油站管理员信息
- **THEN** 设置页保持现状，保存类操作提示"暂未开放"，不发送任何配置请求

#### Scenario: User is not an administrator of the station
- **WHEN** 云端明确告知当前用户不是当前油站的管理员
- **THEN** 设置入口不显示，且直接访问设置页面时提示无权限

### Requirement: Home degrades when stations are not provided
云端未提供油站数据时，首页 SHALL 不显示油站下拉，直接展示该账号全部已绑定设备的罐卡片。页面 MUST NOT 用本地写死的油站名或用客户名冒充油站。

#### Scenario: Cloud has no station data
- **WHEN** 设备列表接口没有返回设备所属油站
- **THEN** 顶部只显示品牌栏和"+"，没有油站下拉，所有已绑定设备按设备分组展示

#### Scenario: Device without a station while others have one
- **WHEN** 部分设备没有所属油站
- **THEN** 这些设备归入"未分组"油站，该项排在油站列表末尾

### Requirement: Empty and first-run states
首页 SHALL 区分"还没有绑定任何设备"和"当前油站下没有设备"。

#### Scenario: No bound devices
- **WHEN** 账号没有任何已绑定设备
- **THEN** 展示空状态和"扫码绑定设备"按钮，不显示油站下拉

#### Scenario: Station has no devices
- **WHEN** 当前油站下没有设备
- **THEN** 展示"该油站下暂无设备"，下拉仍可用

### Requirement: Add menu offers device binding and a placeholder
首页右上角"+" SHALL 弹出两项：添加设备、油站管理。添加设备 MUST 进入现有扫码绑定流程。油站管理本次只保留入口，不得提交任何请求，并 MUST 明确告知用户该功能暂未开放。菜单 MUST NOT 提供邀请加入；小程序内 MUST NOT 提供注册、邀请或员工管理，账号只能由后台创建并配置密码。

#### Scenario: Add device
- **WHEN** 用户选择"添加设备"
- **THEN** 进入设备添加页，点击"扫一扫"走现有扫码绑定流程

#### Scenario: Station management not yet available
- **WHEN** 用户选择"油站管理"
- **THEN** 提示该功能暂未开放，不发送请求

#### Scenario: No invite entry
- **WHEN** 用户打开"+"菜单
- **THEN** 菜单中没有"邀请加入"，也没有任何微信邀请或手机号邀请入口

### Requirement: Tank card opens device detail
点击罐卡片 SHALL 进入该罐所属设备的设备详情页并选中这个罐（单罐详情，见变更 `records-by-station-and-single-tank-detail` 的 `device-detail-single-tank`）。出入库记录不在详情页，从「我的记录」进入。

#### Scenario: Open detail from a card
- **WHEN** 用户点击某设备下的罐卡片
- **THEN** 进入该设备的设备详情页，选中被点的罐
