export const DEBUG_MODE = false; // true = 本地后端（127.0.0.1:8080），false = 线上。改完重新编译即可；发布前必须是 false
//
// 本地非 HTTPS 域名要在开发者工具 详情 → 本地设置 勾选“不校验合法域名...”；
// 真机预览请把 LOCAL 里的 127.0.0.1 换成电脑的局域网 IP。
const LOCAL = { http: 'http://127.0.0.1:8080', ws: 'ws://127.0.0.1:8080' };
const PROD = { http: 'https://api.zhongchengelec.com', ws: 'wss://api.zhongchengelec.com' };

const active = DEBUG_MODE ? LOCAL : PROD;

export const BASE_URL = active.http;
export const WS_BASE_URL = active.ws;
