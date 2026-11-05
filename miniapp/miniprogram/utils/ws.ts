import { WS_BASE_URL } from '../config/env';
import { getToken } from './storage';
import type { AlarmPushMessage } from '../types/api';

export type { AlarmPushMessage };

// 'connecting' = a socket is being opened (the first time, or a retry); 'open' = connected; 'down' = it
// dropped or could not be opened and a retry is scheduled. The page uses it to say that live alarms
// are not coming in: silence must never be mistaken for "no alarms".
export type SocketState = 'connecting' | 'open' | 'down';

// Backoff, not a fixed delay: the server closes the socket on "token expired"/"access revoked", and
// reconnecting every 2s forever with a token that will never be accepted again just hammers it.
const RECONNECT_MIN_MS = 2000;
const RECONNECT_MAX_MS = 60000;

// GET /ws/alarms sends no ping/keepalive frames of its own yet, so the client is responsible for
// noticing a drop and reconnecting on its own. See yeweiyi-cloud internal/api/ws.go: the fan-out is
// scoped server-side to one `device`, so this socket only ever needs to filter by nothing further.
export class AlarmSocket {
  private device: string;
  private onMessage: (msg: AlarmPushMessage) => void;
  private task: WechatMiniprogram.SocketTask | null = null;
  private closedByCaller = false;
  private reconnectDelayMs = RECONNECT_MIN_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private state: SocketState = 'connecting';
  private onState: (state: SocketState) => void;

  constructor(
    device: string,
    onMessage: (msg: AlarmPushMessage) => void,
    onState: (state: SocketState) => void = () => {},
  ) {
    this.device = device;
    this.onMessage = onMessage;
    this.onState = onState;
  }

  private setState(state: SocketState): void {
    if (this.state === state) return;
    this.state = state;
    this.onState(state);
  }

  // The app came back to the foreground or the network came back: do not wait out the backoff. Does
  // nothing unless the socket is down (a connection still being opened is left alone) or after close(). Later failures go back to the normal backoff, so this can
  // never turn into a reconnect loop on its own — it only runs when the user or the phone changed
  // something.
  reconnectNow(): void {
    if (this.closedByCaller || !this.device || this.state !== 'down') return;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.task) {
      const stale = this.task;
      this.task = null;
      stale.close({});
    }
    this.reconnectDelayMs = RECONNECT_MIN_MS;
    this.connect();
  }

  connect(): void {
    // No device, no socket: `?device=` would be refused and retried with backoff forever.
    if (!this.device) return;
    this.closedByCaller = false;
    this.setState('connecting');
    const token = getToken();
    const url = `${WS_BASE_URL}/ws/alarms?device=${encodeURIComponent(this.device)}&token=${encodeURIComponent(token)}`;
    const task = wx.connectSocket({ url });
    this.task = task;

    task.onOpen(() => {
      if (task !== this.task) return;
      this.reconnectDelayMs = RECONNECT_MIN_MS;
      this.setState('open');
    });

    task.onMessage((res) => {
      try {
        const data = JSON.parse(res.data as string) as AlarmPushMessage;
        this.onMessage(data);
      } catch {
        // ignore malformed frames
      }
    });

    // A failed connection fires onError and then onClose; both land here, and only the first may
    // schedule a reconnect or one drop opens two sockets. The `task !== this.task` check also drops
    // events from a socket that has already been replaced.
    task.onClose(() => this.handleDrop(task));
    task.onError(() => this.handleDrop(task));
  }

  private handleDrop(task: WechatMiniprogram.SocketTask): void {
    if (this.closedByCaller || task !== this.task || this.reconnectTimer) {
      return;
    }
    this.task = null;
    this.setState('down');
    const delay = this.reconnectDelayMs;
    this.reconnectDelayMs = Math.min(delay * 2, RECONNECT_MAX_MS);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (!this.closedByCaller) {
        this.connect();
      }
    }, delay);
  }

  close(): void {
    this.closedByCaller = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.task?.close({});
    this.task = null;
  }
}
