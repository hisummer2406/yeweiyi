## ADDED Requirements

### Requirement: 高级设置提供手动软件更新入口
系统 SHALL 在高级设置提供“软件更新”，展示当前版本、目标版本、发布说明、大小和状态。下载与安装 SHALL 分别由用户触发；收到 TB 分配或 MQTT 通知 MUST NOT 自动下载或安装。页面 SHALL 复用单实例并解绑隐藏页面观察者。

#### Scenario: 收到更新通知
- **WHEN** TB 为设备分配一个更高版本并推送属性
- **THEN** 设备可刷新候选信息，但不产生自动下载或安装动作

#### Scenario: 下载期间切页
- **WHEN** 用户离开软件更新页面后再次进入
- **THEN** 原任务继续且进度可见，不创建第二个下载任务或重复观察者

### Requirement: 使用 TB 原生 HTTPS 检查和 SOFTWARE 下载
设备 SHALL 以自身 token 调用明确配置的 HTTPS TB Device API，主动检查读取完整共享属性新响应。MQTT SHALL 复用既有连接用于通知及回报。HTTP origin SHALL 独立配置，不能由 manifest 或 MQTT 端口推导；MUST NOT 通过跨 origin 重定向发送凭据。

#### Scenario: MQTT 断开但 HTTPS 可达
- **WHEN** 用户手动检查且设备 token、HTTPS 配置有效
- **THEN** 可通过 HTTPS 获取候选，状态回报等待 MQTT 恢复

#### Scenario: HTTPS 不可达
- **WHEN** 属性查询超时或返回错误
- **THEN** 显示检查失败，不显示“没有更新”，也不使用旧缓存直接开始安装

### Requirement: 候选必须满足版本与属性一致性
系统 SHALL 接受 SOFTWARE 的标准 sw_* 属性和 schemaVersion=1 的 appUpdateManifest，且 title、code、size、hash 一致、enabled=true。sw_version SHALL 为实际 Android versionCode 的十进制字符串；只有 code 更大才能更新。文件大小、schema、包名和字段类型 SHALL 有界校验。

#### Scenario: 只有 versionName 后缀变化
- **WHEN** 目标与当前 versionCode 相同，显示版本后缀不同
- **THEN** 不允许当作新版本更新

#### Scenario: TB 标准属性与发布说明尚未同步
- **WHEN** title/code/size/hash 缺失或互不匹配
- **THEN** 显示信息同步中，禁止下载和安装

#### Scenario: 无分配
- **WHEN** 新快照没有可用分配
- **THEN** 显示暂无可用更新，不宣称全平台最新

### Requirement: 新旧分配与设备身份必须隔离
系统 SHALL 将任务绑定到端点、设备身份、凭据/配置代次、releaseId、assignmentRevision 及摘要。下载前、继续前、安装前 SHALL 查询最新完整候选。目标变更、撤销或身份改变 SHALL 使旧任务失效；晚到回调 MUST NOT 更新新任务或新身份的状态。

#### Scenario: 下载中管理员改配
- **WHEN** 设备获知目标 revision 已变化
- **THEN** 取消旧任务，不把旧文件继续拼接为新包；新目标等待用户触发

#### Scenario: 设备配置变化后旧查询返回
- **WHEN** 旧 HTTP 请求在新的平台地址或身份保存后才完成
- **THEN** 丢弃旧结果，不覆盖新页面和候选

### Requirement: 下载支持有界分块和断点恢复
系统 SHALL 用 TB size/chunk 下载完整 APK，单任务串行写应用私有目录。完整块写入并 fsync 后 SHALL 原子记录 checkpoint。继续前 SHALL 重查分配，最终 SHALL 校验完整文件长度和 SHA-256。HTTP 错误、短块、超长块和错误页面 MUST NOT 视为下载成功。

#### Scenario: 块写完但 checkpoint 未提交时掉电
- **WHEN** 重启后的文件长于已提交字节
- **THEN** 截去未提交尾部，并经用户继续、复核目标后从可信块边界下载

#### Scenario: 最后一块小于固定块大小
- **WHEN** 总 size 不能整除块大小
- **THEN** 最后一块严格按剩余字节验收，完成长度与目标一致

#### Scenario: 存储不足或网络重试耗尽
- **WHEN** 下载不可继续
- **THEN** 停止请求并提供明确状态，保留可恢复 checkpoint，不无限重试或阻塞界面

### Requirement: 安装前独立核对 APK 与签名
设备 SHALL 核对实际包名、code、name、SDK/ABI、长度、摘要与签名兼容性。系统安装器 SHALL 执行最终签名与 APK 验证。发布说明中的签名声明 MUST NOT 作为独立信任来源；MUST NOT 通过卸载旧应用规避签名不兼容。

#### Scenario: 摘要正确但包名或签名错误
- **WHEN** 下载包属于其他应用或不兼容签名
- **THEN** 拒绝安装并保留当前应用与数据

#### Scenario: 包文件在就绪后被改动
- **WHEN** 用户点击安装
- **THEN** 重新验证本地包并拒绝不符合原候选的文件

### Requirement: 安装需系统确认并耐久记录意图
首版 SHALL 使用 PackageInstaller 会话和必要安装授权，处理 PENDING_USER_ACTION、取消、明确失败和结果缺失。安装提交前 SHALL 耐久保存 attemptId/sessionId/目标与待上报状态。持久化失败 MUST NOT 提交安装，外部伪造 Intent MUST NOT 被接受为安装结果。

#### Scenario: 用户拒绝未知来源授权或系统安装
- **WHEN** 用户取消确认
- **THEN** 不自动重试安装，保留经验证包供用户再次操作

#### Scenario: session 已提交而旧进程结束
- **WHEN** 应用恢复且尚未取得最终结果
- **THEN** 查询本机版本及会话恢复状态，不因进程结束直接判定成功或失败

### Requirement: 成功必须由目标版本启动确认
系统 SHALL 在实际运行版本匹配 pending 目标且业务库、配置、主界面及采集调度初始化完成后确认 UPDATED。安装器成功、文件下载成功和 Application.onCreate 入口 MUST NOT 单独作为更新成功。系统 SHALL 提供替换后恢复接入并在支持的真实终端验收。

#### Scenario: 安装器报告成功但新进程没有启动
- **WHEN** 服务端读取更新状态
- **THEN** 显示安装后启动尚未确认，不能显示升级成功或自动回滚

#### Scenario: 新版本启动时暂时无网络或探头断开
- **WHEN** 目标版本完成规定的本地启动检查
- **THEN** 本地记录启动确认并等待网络补报，不将外部连通故障判成安装失败

### Requirement: 结果报告须持久化并关联本次目标
系统 SHALL 上报当前实际软件版本、能力和完整 appUpdateReport，并使用标准 sw_state。报告 SHALL 带 revision/attemptId/reportSeq，reportSeq 按身份跨重启递增；关键结果在 MQTT delivery 确认前耐久保留，旧 seq 确认 MUST NOT 清理新状态。恢复时 SHALL 发送最新真实状态，MUST NOT 重放旧阶段覆盖新终态。OTA 发件状态 MUST NOT 挤占报警/出入库耐久队列。

#### Scenario: 更新成功时 MQTT 断线
- **WHEN** 新版本本地启动已确认
- **THEN** 成功待发状态跨重启保留，恢复连接后补报

#### Scenario: 前一分配结果迟到
- **WHEN** 服务端期望 revision 为 3，设备收到/发送 revision 为 2 的历史结果
- **THEN** 结果只用于旧目标诊断，不证明 revision 3 已成功

### Requirement: 更新不得改变既有采集和业务数据语义
下载、校验 SHALL 在独立工作线程执行，采集、报警、遥测和 RPC 继续使用原通道。安装 SHALL 告知监测中断，并保证本地必要写入完成。更新 MUST NOT 主动调用泵阀、清警或远程重启；MUST NOT 清库或重建配置作为默认步骤。

#### Scenario: 下载大包时发生真实报警
- **WHEN** 探头触发既有报警条件
- **THEN** 现有报警、记录和声光路径照常运行，其时序影响纳入真实终端验收

#### Scenario: 同签名覆盖更新
- **WHEN** 从引导版本更新到更高 code
- **THEN** 配置、罐表、业务历史和设备凭据保留，启动后恢复采集
