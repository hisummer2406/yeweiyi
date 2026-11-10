## ADDED Requirements

### Requirement: Shared typography hierarchy
页面和自定义组件 SHALL 使用同一份字号定义：标题与主要读数 36rpx、分组标题 32rpx、正文/表单/按钮 30rpx、标签/单位 28rpx、说明 26rpx、时间/徽标 24rpx。

#### Scenario: Navigate between pages and open a picker
- **WHEN** 用户在登录、记录、设置、参数页面之间切换，并打开选择弹层
- **THEN** 同类文字的字号保持一致，组件隔离不导致字体变量失效

### Requirement: Preserve the confirmed home screen
首页 SHALL 保留已确认的品牌、状态及短屏字号例外与首屏四罐排布，所有罐继续保留自然滚动查看。

#### Scenario: Return to the home screen
- **WHEN** 用户从设置页回到首页
- **THEN** 首页仍一屏恰好四罐，第五罐及以后下滑可见，不因统一字号改变高度

### Requirement: Complete long button and dialog text
按钮和确认弹窗 MUST 容纳中文、英文和西班牙文文案；必要时换行并撑高，不截断可操作文字。

#### Scenario: Long translated action labels
- **WHEN** 按钮使用较长的英文或西班牙文文案，或确认提示换成多行
- **THEN** 所有文字可见，按钮不被弹窗底边遮挡
