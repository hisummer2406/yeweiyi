# 客户品牌 Logo 契约（#86）

平台管理员在网页后台为客户配置小程序 Logo；客户管理员和员工登录小程序后看到自己客户的 Logo，
未配置时显示中成电气默认 Logo。默认 Logo 打包在客户端里，云端只存客户自定义的那一张。

## 1. 存储

- 表 `customer_brand_logos`（迁移 000044）：每个客户至多一行，没有行 = 默认 Logo。
- 图片原始字节存在业务库（`bytea`），随业务库备份；客户删除时级联删除，不阻止删除空客户。
- `version` = 图片内容 SHA-256 的前 16 位十六进制。同一张图重复上传版本不变，换图版本必变。

## 2. 图片要求

云端按**实际内容**判定，不看扩展名和客户端声明的类型：

| 项 | 要求 |
| --- | --- |
| 格式 | PNG，整张图必须能解码（改扩展名的 JPEG、截断文件都拒绝） |
| 大小 | ≤ 256 KiB（原始字节） |
| 尺寸 | 宽、高各 64–1024 像素；不要求正方形，客户端按 `aspectFit` 显示，建议正方形透明底 |

不合格返回 `400 brand_logo_invalid`（请求体超限为 `413 brand_logo_invalid`），`error` 写明原因。

## 3. 响应形状

```json
{
  "customerId": 12,
  "configured": true,
  "version": "9f2c1a0b5d3e4f61",
  "contentType": "image/png",
  "width": 256,
  "height": 256,
  "size": 18342,
  "updatedAt": "2026-11-26T08:00:00Z",
  "data": "<base64>"
}
```

- 未配置：`{"customerId": 12, "configured": false}`，其余字段省略。
- 平台管理员调 `/api/me/brand-logo`：`{"customerId": null, "configured": false}`。
- 带 `?known=<version>` 且与当前版本相同：`data` 省略，`notModified: true`，其余字段照常。
  版本不同或已恢复默认时照常返回（恢复默认时 `configured: false`）。

图片以 base64 放在 JSON 里，而不是给图片地址：小程序 `<image>` 不能带登录令牌，公开地址又会让任何人
读到任意客户的 Logo。走现有业务请求域名，不需要另配图片下载域名。

## 4. 接口

| 接口 | 谁可调 | 说明 |
| --- | --- | --- |
| `GET /api/customers/:id/brand-logo[?known=]` | 平台管理员 | 读指定客户的配置；客户不存在 `404 customer_not_found` |
| `PUT /api/customers/:id/brand-logo` | 平台管理员 | 请求体 `{"data": "<base64 PNG>"}`，保存或替换，返回 §3 |
| `DELETE /api/customers/:id/brand-logo` | 平台管理员 | 恢复默认，幂等，返回 `200 {"customerId":…, "configured": false}` |
| `GET /api/me/brand-logo[?known=]` | 任何已登录账号 | 客户由调用者在库里的当前行决定，不接受客户 ID 参数 |

- 客户管理员、员工调前三个接口一律 `403 {"error":"not authorized","code":"role_not_allowed"}`（在读取 ID 和请求体之前，写 `denied` 审计）。
- `/api/me/brand-logo` 受认证中间件约束：账号停用 403、客户配置中 `403 customer_not_onboarded`。换客户后立即读到新客户的配置。
- 保存与恢复各写一条审计：`customer.brand_logo.set`（detail 含 `customerId`、`version`、`size`、`width`、`height`）、
  `customer.brand_logo.reset`（detail 含 `customerId`、`removed`）。与其他客户级操作一样，审计行不挂在客户名下
  （否则会让客户无法删除），客户 ID 记在 detail 里。

## 5. 客户端规则

- 默认 Logo 在客户端：登录前、未配置、请求失败、断网、图片加载失败一律显示默认 Logo；Logo 请求失败不得影响页面其他数据。
- 缓存按**账号**隔离，以 `version` 判断是否过期（`?known=`）。响应回来时若登录账号已变（退出、切换账号），丢弃该响应。
- 退出登录清掉上一账号的 Logo 展示状态。
- 换标后客户端在下次打开或刷新时读到新配置；云端不推送。
