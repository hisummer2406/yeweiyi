// yeweiyi-cloud defaults to LISTEN_ADDR ":8080" (see that repo's CLAUDE.md). Point this at
// wherever that server is actually reachable from a device/simulator during development, and
// swap to the real HTTPS domain (registered in the mp.weixin.qq.com backend's request domain
// allowlist) before releasing. In WeChat DevTools, local http(s) origins not on that allowlist
// only work with "不校验合法域名..." enabled under 详情 -> 本地设置.
export const BASE_URL = 'http://127.0.0.1:8080';
export const WS_BASE_URL = 'ws://127.0.0.1:8080';
