import { roleFromToken } from '../../utils/auth';
import { describeError } from '../../utils/errors';
import { formatLocalTime } from '../../utils/format';
import { currentLocale, setNavTitle, t, texts } from '../../utils/i18n';
import { commandError, commandPhase, echoState, type Echo } from '../../utils/remoteConfig';
import { getCommand, getTankCalibration, getTankSettings, listCommands } from '../../utils/remoteConfigApi';
import { tableEcho } from '../../utils/tankTable';
import { ApiError } from '../../utils/request';
import { isReadOnlyRole } from '../../utils/roles';
import { loadRecordsContext } from '../../utils/stationPref';
import { getToken } from '../../utils/storage';
import type { ControlCommand } from '../../types/api';

// 操作记录（#6；原「发送控制命令」通用 JSON 表单已移除）：本次命令的进度与回显 + 该设备最近的命令。
// 只读，不发命令。打开方式：储罐设置/参数修正下发后 ?device=&commandId=&tank=；或从设置页进入（首页当前油站的设备）。
// - 是否继续轮询只看云端 terminal；received 不是完成。
// - succeeded 后读回设备镜像：回报时间不早于下发且字段相等才算"已一致"。
// - rejected / failed：显示设备原因或错误码（#42：旧固件的拒绝经 cmdResult 到达，云端已结束为 rejected）。
// - expired / 交付未确认：结果无法确认，设备可能已执行；不显示 ACK 的“已受理”。
// - 页面隐藏（onHide/onUnload）停止所有轮询，回来（onShow）继续。员工看不到命令历史（云端也不给）。

const POLL_MS = 2000;
const ECHO_MS = 5000;
const ECHO_WAIT_MS = 3 * 60 * 1000;

type Row = { id: string; time: string; method: string; target: string; args: string; status: string; phase: string; error: string; operator: string };

function methodLabel(m: string): string {
  if (m === 'config.setTankSettings') return t('cmdLog.method.tank');
  if (m === 'config.setDeviceSettings') return t('cmdLog.method.device');
  if (m === 'config.setTankTable') return t('cmdLog.method.tankTable');
  return m;
}

function fieldLabel(k: string): string {
  const key = `cmdLog.field.${k}`;
  const s = t(key);
  return s === key ? k : s;
}

function toRow(c: ControlCommand): Row {
  const tank = /^tank:(\d+)$/.exec(c.configTarget || '');
  const args = Object.keys(c.args || {})
    .filter((k) => k !== 'tank')
    .map((k) => `${fieldLabel(k)}=${String((c.args as Record<string, unknown>)[k])}`)
    .join(t('common.listSep'));
  const phase = commandPhase(c.status);
  const err = commandError(c.status, c.errorCode, c.errorReason);
  return {
    id: c.commandId,
    time: c.issuedAt ? formatLocalTime(c.issuedAt, currentLocale()) : '—',
    method: methodLabel(c.method),
    target: tank ? t('tank.no', { n: Number(tank[1]) }) : c.configTarget === 'device' ? t('cmdLog.wholeDevice') : '—',
    args: args || '—',
    status: t(`cmdLog.phase.${phase}`),
    phase,
    error: !err ? '' : 'key' in err ? t(err.key) : err.text,
    operator: c.operator,
  };
}

Page({
  data: {
    text: texts('cmdLog'),
    device: '',
    readOnly: false,
    error: '',
    // 本次命令
    tracked: null as null | { row: Row; message: string; tone: 'info' | 'ok' | 'warn' | 'bad'; terminal: boolean },
    rows: [] as Row[],
    listError: '',
  },

  commandId: '',
  tank: 0,
  cmd: undefined as ControlCommand | undefined,
  echo: undefined as Echo | undefined,
  echoStarted: 0,
  timer: 0 as number,
  seq: 0,

  onLoad(query: Record<string, string>) {
    this.commandId = query.commandId ? decodeURIComponent(query.commandId) : '';
    this.tank = Number(query.tank) || 0;
    if (query.device) this.setData({ device: decodeURIComponent(query.device) });
  },

  onShow() {
    setNavTitle('cmdLog.navTitle');
    const token = getToken();
    const readOnly = !token || isReadOnlyRole(roleFromToken(token));
    this.setData({ text: texts('cmdLog'), readOnly });
    if (readOnly) return;
    const seq = ++this.seq;
    const start = this.data.device
      ? Promise.resolve(this.data.device as string)
      : loadRecordsContext().then((ctx) => (ctx.kind === 'ok' ? ctx.device.device_name : ''));
    start
      .then((device) => {
        if (seq !== this.seq) return;
        if (!device) {
          this.setData({ error: t('records.noStation') });
          return;
        }
        this.setData({ device, error: '' });
        this.loadList();
        if (this.commandId) this.tick(seq);
      })
      .catch((err) => {
        if (seq === this.seq) this.setData({ error: describeError(err) });
      });
  },

  onHide() {
    this.stop();
  },
  onUnload() {
    this.stop();
  },

  stop() {
    this.seq++;
    if (this.timer) clearTimeout(this.timer);
    this.timer = 0;
  },

  later(seq: number, ms: number) {
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.tick(seq), ms) as unknown as number;
  },

  // One step of tracking: the command until terminal, then (if succeeded) the device's report.
  tick(seq: number) {
    if (seq !== this.seq) return;
    const device = this.data.device as string;
    if (!this.cmd || !this.cmd.terminal) {
      getCommand(device, this.commandId)
        .then((cmd) => {
          if (seq !== this.seq) return;
          this.cmd = cmd;
          const m = /^tank:(\d+)$/.exec(cmd.configTarget || '');
          if (m) this.tank = Number(m[1]);
          this.show();
          if (!cmd.terminal) this.later(seq, POLL_MS);
          else {
            this.loadList();
            if (cmd.status === 'succeeded') {
              this.echoStarted = Date.now();
              this.later(seq, 0);
            }
          }
        })
        .catch((err) => {
          if (seq !== this.seq) return;
          if (err instanceof ApiError && err.status === 404) {
            this.setData({ error: t('cmdLog.notFound') });
            return;
          }
          this.later(seq, POLL_MS); // keep the last state, try again
        });
      return;
    }
    if (this.cmd.status !== 'succeeded' || !this.tank) return;
    // 罐表（#49）：核对标定镜像，而不是罐设置镜像。
    if (this.cmd.method === 'config.setTankTable') {
      getTankCalibration(device, this.tank)
        .then((cal) => {
          if (seq !== this.seq || !this.cmd) return;
          this.echo = tableEcho(cal, this.cmd.resultDetail, this.cmd.finishedAt);
          this.show();
          if (this.echo === 'waiting' && Date.now() - this.echoStarted < ECHO_WAIT_MS) this.later(seq, ECHO_MS);
        })
        .catch(() => {
          if (seq === this.seq && Date.now() - this.echoStarted < ECHO_WAIT_MS) this.later(seq, ECHO_MS);
        });
      return;
    }
    getTankSettings(device)
      .then((tanks) => {
        if (seq !== this.seq || !this.cmd) return;
        const mirror = tanks.find((x) => x.tank === this.tank);
        this.echo = echoState(mirror as unknown as Record<string, unknown>, this.cmd.args || {}, this.cmd.finishedAt);
        this.show();
        if (this.echo === 'waiting' && Date.now() - this.echoStarted < ECHO_WAIT_MS) this.later(seq, ECHO_MS);
      })
      .catch(() => {
        if (seq === this.seq && Date.now() - this.echoStarted < ECHO_WAIT_MS) this.later(seq, ECHO_MS);
      });
  },

  show() {
    const cmd = this.cmd;
    if (!cmd) return;
    const phase = commandPhase(cmd.status);
    let message = t(`cmdLog.progress.${phase}`);
    let tone: 'info' | 'ok' | 'warn' | 'bad' = 'info';
    if (phase === 'succeeded') {
      if (this.echo === 'matched') {
        message = t('cmdLog.echo.matched');
        tone = 'ok';
      } else if (this.echo === 'mismatch') {
        message = t('cmdLog.echo.mismatch');
        tone = 'warn';
      } else {
        const timedOut = Date.now() - this.echoStarted >= ECHO_WAIT_MS;
        message = t(timedOut ? 'cmdLog.echo.timedOut' : 'cmdLog.echo.waiting');
        tone = timedOut ? 'warn' : 'info';
      }
    } else if (phase === 'rejected' || phase === 'failed') tone = 'bad';
    else if (phase === 'expired') tone = 'warn';
    this.setData({ tracked: { row: toRow(cmd), message, tone, terminal: cmd.terminal } });
  },

  loadList() {
    const device = this.data.device as string;
    listCommands(device, 20)
      .then((cmds) => this.setData({ rows: cmds.map(toRow), listError: '' }))
      .catch((err) => this.setData({ listError: describeError(err) }));
  },

  onRefreshEcho() {
    this.echoStarted = Date.now();
    this.later(this.seq, 0);
  },

  onPullDownRefresh() {
    this.loadList();
    wx.stopPullDownRefresh();
  },
});

export {};
