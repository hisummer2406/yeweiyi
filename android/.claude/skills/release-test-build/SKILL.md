---
name: release-test-build
description: 出测试包：把 versionName 末尾的版本字母加一（如 1.0.5-E → 1.0.5-F），打 release 包并核对签名和构建开关，提交"[chore]测试包X；"，再整理出距上一个测试包的更新内容。用户说"更新版本字母""打 release 包""出测试包""整理更新内容/更新说明"时用。
metadata:
  version: "1.0"
---

# 出测试包

步骤 1～5 要按顺序做，前一步不通过就停下，告诉用户原因。

## 1. 前置检查

- `git status --short` 必须干净。有未提交的改动就停下来问用户：是先提交、不进这个包，还是不打包。不要自己替用户提交业务改动（用户没验证过的改动不提交）。
- 找到上一个测试包：`git log --oneline --grep="测试包" -1`。它的提交信息形如 `[chore]测试包E；`，就是这次的比较基准。
- 基准之后如果没有任何业务提交（只有文档同步或在线表格操作），告诉用户"没有新改动"，问清楚是否仍要出包。

## 2. 改版本字母

- 平时只把 `app/build.gradle` 里 `versionName "x.y.z-字母"` 的末尾字母加一（E→F），`versionCode` 不动。
- 当前已经是 Z 时，同时升 `versionCode` 和 `versionName`，字母从 A 重新开始：`versionCode` 加 1，`versionName` 的末位跟着加 1。例如 `versionCode 5` / `"1.0.5-Z"` → `versionCode 6` / `"1.0.6-A"`。

## 3. 打 release 包

```bash
./gradlew -Dhttp.proxyHost= -Dhttps.proxyHost= :app:assembleRelease
```

- 本机 `~/.gradle/gradle.properties` 配了一个通常没在运行的代理，所以要传空代理参数。
- 产物在 `app/build/outputs/apk/release/app_<versionName>_<日期>_release.apk`。

打完包按下面的清单核对。任何一项不对，就不提交、不交付：

| 检查项 | 怎么查 | 期望 |
|---|---|---|
| 已签名 | `~/Library/Android/sdk/build-tools/<最新>/apksigner verify --print-certs <apk>` | 有 Signer #1。证书 SHA-256 前缀和历次一致（截至 F 包为 `5de1115e…`），不一致说明换了 keystore，已部署的设备将无法升级 |
| 探针抓包关闭 | 生成的 `BuildConfig.java`（`app/build/generated/source/buildConfig/release/...`）里的 `PROBE_RECORDING` | `false` |
| 云平台走 TLS | 同上，`THINGSBOARD_USE_TLS` | `true` |
| 配网密钥已注入 | 同上，`PROVISION_DEVICE_KEY` / `PROVISION_DEVICE_SECRET` | 非空。只报"非空"，不要把值打印到对话里 |
| 版本号 | 同上，`VERSION_NAME` | 新字母 |

构建日志里出现 `[配置缺失]` 时：截至 F 包，`productKey`/`productSecret`/`requestEncryptKey`/`baseUrl` 这 4 项一直缺。它们只给旧的阿里云接口用，和 ThingsBoard 无关，照实告诉用户即可。除此之外缺了其他项，就停下问用户。

## 4. 提交

```bash
git add app/build.gradle
git commit -m "[chore]测试包X；"   # 末尾加当前会话要求的 Co-Authored-By 行
```

只提交 `build.gradle` 这一个文件。不推送，不打 tag，除非用户要求。

## 5. 整理更新内容

- 范围：`git log --oneline <上个测试包提交>..HEAD`，排除测试包提交本身和只改文档的提交。
- 每条都要对照 `git show <hash>` 的实际 diff 来写，不能只看提交信息。提交信息可能写得不全，也可能夸大。
- 写给现场测试和客户看，要简洁：每个功能一条，每条一句话，说清楚用户能看到的变化，不展开原因和实现，不写类名和文件路径。示例：
  ```
  1.0.5-F 更新内容（对比 1.0.5-E）
  1. 乌兹别克语首页标签改用缩写，数据不再被截断。
  2. 云平台连接成功后，设备编号和访问令牌不可再修改。
  ```
- 更新内容之外，单独另起几行告诉用户：哪些改动还没有真机验证过、release 包本身有没有在真机上装过。这些不写进更新内容。

交付时给出：apk 的完整绝对路径、核对结果、新的提交 hash、更新内容。
