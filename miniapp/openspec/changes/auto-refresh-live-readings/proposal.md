## Why

现场反馈（2026-11-18，ZCT000008）：液位变化后，设备屏幕上很快就变了，手机上要半分钟以上才变。

原因有两个：
1. 设备每 30 秒才上报一次快照。安卓端的配套变更 `android/openspec/changes/report-snapshot-on-level-change` 改为"液位变化 ≥ 1 mm 立即上报，最短间隔 5 秒，平时 30 秒保底"。
2. 小程序首页和设备详情的实时数据只在打开页面或下拉时读一次 `telemetry/latest`，停在页面上不会自动更新。设备报得再快，用户不下拉也看不到。

只改设备端，第 2 点仍然存在，所以两端要一起改。

## What Changes

- 首页（`pages/devices`）在页面可见时，每 5 秒静默重新读一次各设备的 `telemetry/latest`。
- 设备详情页（`pages/device-detail`）停在"实时数据"（`snapshot`）tab 上时，同样每 5 秒静默重读。
- 静默刷新不显示加载状态；失败时保留当前读数，沿用现有的"可能不是最新"提示，不清空页面。
- 页面隐藏、切到后台、离开页面，或切到其他 tab 时停止刷新；回来时立即读一次再恢复。
- 上一次请求还没返回时，不发下一次。

## Impact

- 受影响页面：`miniprogram/pages/devices/devices.ts`、`miniprogram/pages/device-detail/device-detail.ts`
- 接口：只用现有的 `GET /api/devices/:device/telemetry/latest`（依据 yeweiyi-cloud `internal/api/telemetry.go`），不改接口形状。
- 云端负载：每个停在页面上的用户、每台设备每 5 秒一次请求，云端每次直接查 ThingsBoard。按现在的用户量可以忽略；用户量上来后可以改成云端通过 WebSocket 推送（见 design）。
- 端到端延迟：设备变化后约 1 秒上报，小程序最多再等 5 秒，手机上 1–6 秒内看到变化。
- admin（`/console/*`）有同样的实时数据页面，是否同步自动刷新另行评估，本变更不改。
