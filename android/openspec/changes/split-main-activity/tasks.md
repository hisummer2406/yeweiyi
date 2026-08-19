## 1. 前置：onClick 转换为编译期可见绑定（不可跳过、不可与拆分混在一起提交）

> **2026-08-13 复核补充**：34 个绑定点的**分布**比数量更危险，见 1.1。

- [ ] 1.1 列出全部 18 个不同方法名、34 个 XML 属性引用的完整清单（`grep -rn 'android:onClick=' app/src/main/res/layout`）。**已复核的分布**：`activity_main.xml` 14 处、**8 个 `fragment_*.xml` 共 19 处**、`activity_login.xml` 1 处。Fragment 布局里的 `android:onClick` **不解析到 Fragment，解析到宿主 Activity**——已抽查确认 `bar_but_alarm`/`but_startJiaoBan`/`btn_jieJing`/`btn_kuCunRecord`/`main_but_set` 全部定义在 `MainActivity`。只扫 `activity_main.xml` 会漏掉一多半，且漏掉的那些崩在交班、出入库、报警、库存等业务页，比主界面更难在自测中覆盖到
- [ ] 1.2 逐个方法：在对应布局的 View 上改用 `setOnClickListener`，移除 `android:onClick` 属性。Fragment 布局对应的绑定应挂在该 Fragment 上，顺带消除「XML 在 Fragment、方法在 Activity」的跨类隐式引用
- [ ] 1.3 处理 `activity_login.xml`：该布局未被任何 Java 代码与 `AndroidManifest.xml` 引用，且其 `android:onClick="login"` 指向的方法全项目不存在——确认后直接删除该布局文件（删除前用 `git grep -F activity_login` 与 `git grep -F "\"login\""` 复核，**不要用正则**，见 `CLAUDE.md`）
- [ ] 1.4 跑 `./gradlew :app:assembleDebug`，确认编译通过
- [ ] 1.5 真机逐个点击回归**全部 34 个绑定点**（不是 18 个方法名——同一方法名可能绑在多个布局的多个 View 上），且必须覆盖到那 8 个 fragment 页面，确认行为与转换前一致
- [ ] 1.6 单独提交本组改动，不与后续任何拆分工作合并

## 2. 导航器（MainNavigator）

- [ ] 2.1 新建导航器类，承接 Fragment 切换与返回栈逻辑
- [ ] 2.2 搬移相关方法，`MainActivity` 改为持有导航器实例并委托
- [ ] 2.3 跑 `assembleDebug` + 现有单测
- [ ] 2.4 真机回归：走一遍全部主要导航路径（各 Fragment 入口 + 返回）

## 3. 轮询器（PollingCoordinator）

- [ ] 3.1 新建轮询器类，承接定时任务与数据刷新触发逻辑（对接 `MyTimeTask`）
- [ ] 3.2 搬移相关方法，确保 `MainActivity` 生命周期结束时轮询器正确停止（对应 spec 的"生命周期绑定正确"要求）
- [ ] 3.3 跑 `assembleDebug` + 现有单测
- [ ] 3.4 真机回归：观察定时刷新是否按原有频率正常触发，Activity 退出重进无异常

## 4. 业务动作（MainActions）

- [ ] 4.1 新建业务动作类，承接出入库业务动作与密码校验逻辑
- [ ] 4.2 搬移相关方法（若 `layer-data-and-hardware-boundaries` 的 `OilTankRepository`/`StockRepository` 已就绪，直接对接；否则暂时保留对 `DBDataManager` 的依赖并加 TODO）
- [ ] 4.3 跑 `assembleDebug` + 现有单测
- [ ] 4.4 真机回归：执行一次完整出入库动作、一次密码校验失败场景

## 5. 报警协调器（AlarmCoordinator）—— 放最后，可能依赖阶段2产出

- [ ] 5.1 检查 `layer-data-and-hardware-boundaries` 的 `AlarmRepository` 是否已落地
- [ ] 5.2 新建报警协调器类，承接报警响应 UI 逻辑；按 5.1 的结果对接 `AlarmRepository` 或临时依赖 `DBDataManager`（后者需加清晰 TODO 标注，见 spec 的"数据依赖显式声明"要求）
- [ ] 5.3 跑 `assembleDebug` + 现有单测
- [ ] 5.4 真机回归：触发一次真实报警场景，确认 UI 响应与拆分前一致

## 6. 收尾

- [ ] 6.1 确认 `MainActivity` 只保留 Activity 生命周期方法与四个协作类的组装代码，不再直接实现四类职责的业务逻辑
- [ ] 6.2 更新 `docs/重构方案.md` 阶段3状态为已完成，记录实测结果（编译/单测/真机回归）
- [ ] 6.3 更新 `CLAUDE.md`："Known landmines" 中 `android:onClick` 反射绑定的条目改为记录"已消除"，避免误导后来者以为地雷还在
