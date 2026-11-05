## Purpose

明确串口硬件访问的边界：哪个类是真正的硬件抽象层（HAL），哪个类是协议编排层，UI/业务层不得绕过编排层直接触达底层收发。

## ADDED Requirements

### Requirement: UI 层不得直接引用硬件收发的实现细节
`com.sansen.yeweiyi.manager.SerialPortManager`（协议编排层）SHALL NOT 直接 `import` 或引用 `com.sansen.yeweiyi.base.BaseActivity` 或其他 UI 层类；数据刷新信号 SHALL 通过回调接口传递。

#### Scenario: 探头数据到达后通知 UI 刷新
- **WHEN** 编排层解析出一帧新的探头数据
- **THEN** 它通过注册的回调接口通知 UI 层，而不是直接构造/持有 `BaseActivity` 的消息常量

### Requirement: 底层字节收发只能经由 serialport.manager.SerialPortManager
业务/编排层需要与串口硬件做字节级收发时，SHALL 只能经由 `serialport.manager.SerialPortManager`（JNI 层），不得绕过它直接调用 `serialport.jni.SerialPortJNI`。

#### Scenario: 编排层发送一条查询指令
- **WHEN** 协议编排层需要向探头发送一条查询指令
- **THEN** 该指令经由 `serialport.manager.SerialPortManager` 发出，编排层代码中不出现对 `SerialPortJNI` 的直接调用
