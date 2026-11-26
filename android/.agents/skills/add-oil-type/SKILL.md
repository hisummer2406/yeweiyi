---
name: add-oil-type
description: 给安卓液位仪新增介质（油品类型，罐设置页"介质"下拉框）：改 6 个语言的 oiltypeArray、同步远程配置 oilTypeIndex 上限/单测/字段清单文档、登记多语言文案表。用户说"增加介质""新增油品""加几个介质"时用。
metadata:
  version: "1.0"
---

# 新增介质

介质列表就是 `R.array.oiltypeArray`，库里 `OilCan.oilType` 存的是**下标**，不存名称。所有显示都按下标取：`Const.getOilType(int)` → `DataUtils.getOilType()`。往例：`f9c3694`、`c37e0ab`。

## 数组结构（改之前先搞清楚）

```
[0] 未设置   [1..N] 内置介质   [N+1] 空白（最后一项）
```

`DataUtils.getOilType()` 会把"空白"那一位替换成 8 个自定义介质（`SharePrefUtil.CN_OIlTYPE`/`EN_OIlTYPE`，目前没有写入入口），所以对外的有效下标是 `0 ~ 条数-2+8`。

## 步骤

1. **查重**：先 `grep -n -F '<名称>' app/src/main/res/values*/array.xml`，列表里已有的不重复加（往例：甲醇、乙醇）。
2. **改 6 个 `array.xml` 的 `oiltypeArray`**：新项插在"空白"**前面**、最后一个内置介质后面，不要动"空白"之前已有项的顺序（改了等于把已部署罐的介质换掉）。
   - `values/`、`values-zh/`：中文原文。
   - `values-en/`、`values-es/`、`values-ru/`、`values-uz/`：译文。没有设计稿/文案表给出的就 AI 翻译，前面加一行 `<!-- 以下 N 项 YYYY-MM-DD AI 翻译，待 review -->`。下拉框较窄，参照邻近项用缩写。uz 里的撇号要转义成 `\'`。
   - `values-ru/` 里有两个"Пусто"（另一个属于别的数组），以最后一个内置介质为锚点插入，不要用"空白"文本定位。
   - 改完核对 6 个文件的 `oiltypeArray` 条数完全一致。
3. **远程配置上限**：`logic/thingsboard/rpc/handler/SetTankSettingsHandler.java` 的 `MAX_OIL_TYPE_INDEX` 加上新增条数（= 条数 − 2 + 8）；`SetTankSettingsHandlerTest` 里越界用例的 `oilTypeIndex` 改为新上限 + 1。
4. **文档**：`docs/远程配置下发-字段清单.md` 中 `oilTypeIndex` 一行的取值范围、内置/自定义下标区间。
5. **多语言文案表**：在线腾讯文档 https://docs.qq.com/sheet/DS2trbXdua0JPRWF1 的「数组文案对照」sheet 登记新项（经 tencent-docs MCP，写之前先问用户；做法同 `add-language` skill）。
6. **验证**：`./gradlew -Dhttp.proxyHost= -Dhttps.proxyHost= :app:assembleDebug :app:cleanTestDebugUnitTest :app:testDebugUnitTest`，读 `app/build/test-results/testDebugUnitTest/*.xml` 确认 0 failures。
7. **停在工作区等用户验证**，用户确认后再提交，只带上面这些文件。提交信息格式：

```
feat(oiltype): 新增介质 A、B、C

- oiltypeArray 在"空白"前插入 N 项（序号 x~y），"空白"移到 z；
  已设成"空白"的罐升级后会显示"A"，需现场重选
- en/es/ru/uz 为 AI 翻译，待 review；多语言文案表「数组文案对照」已登记/尚未登记
- 远程配置 oilTypeIndex 上限 旧 → 新，同步单测和字段清单文档
```

## 必须告诉用户的副作用

"空白"下标后移：已经设成"空白"的罐升级后会显示为新加的第一项，需要现场重选。自定义介质位置也随之后移（目前无写入入口，名称恒为空，实际无影响）。
