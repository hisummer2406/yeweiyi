# main-navigator Specification

## Purpose

承担 `MainActivity` 内 Fragment 切换与返回栈管理职责，使这部分逻辑不再和轮询、报警、业务动作混在同一个类里。

## Requirements

### Requirement: onClick 绑定必须编译期可见
所有原先通过 `android:onClick="方法名"` 字符串反射绑定、且属于导航职责的方法，SHALL 改为在代码中用 `setOnClickListener` 显式绑定，使方法引用关系在编译期可被检查。

#### Scenario: 移动一个导航相关的按钮处理方法
- **WHEN** 开发者把某个导航按钮的处理方法移动到导航器类中或改名
- **THEN** 编译失败（而不是编译成功、点击时才崩溃），直到调用点被相应更新

### Requirement: Fragment 切换行为与拆分前一致
导航器 SHALL 产生与拆分前 `MainActivity` 完全相同的 Fragment 切换与返回栈行为。

#### Scenario: 切换到出入库页面再返回
- **WHEN** 用户点击出入库入口按钮，再点击返回
- **THEN** 显示的 Fragment 序列与拆分前的行为完全一致
