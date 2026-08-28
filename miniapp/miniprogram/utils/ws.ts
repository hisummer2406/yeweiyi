import { WS_BASE_URL } from '../config/env';
import { getToken } from './storage';

export interface AlarmPushMessage {
  deviceName: string;
  msg: string;
}

// GET /ws/alarms sends no ping/keepalive frames of its own yet (see CLAUDE.md "Sibling repos"),
// so the client is responsible for noticing a drop and reconnecting on its own.
export class AlarmSocket {
  private device: string;
  private onMessage: (msg: AlarmPushMessage) => void;
  private task: WechatMiniprogram.SocketTask | null = null;
  private closedByCaller = false;
  private reconnectDelayMs = 2000;

  constructor(device: string, onMessage: (msg: AlarmPushMessage) => void) {
    this.device = device;
    this.onMessage = onMessage;
  }

  connect(): void {
    this.closedByCaller = false;
    const token = getToken();
    const url = `${WS_BASE_URL}/ws/alarms?device=${encodeURIComponent(this.device)}&token=${encodeURIComponent(token)}`;
    this.task = wx.connectSocket({ url });

    this.task.onMessage((res) => {
      try {
        const data = JSON.parse(res.data as string) as AlarmPushMessage;
        this.onMessage(data);
      } catch {
        // ignore malformed frames
      }
    });

    this.task.onClose(() => {
      this.scheduleReconnect();
    });

    this.task.onError(() => {
      this.scheduleReconnect();
    });
  }

  private scheduleReconnect(): void {
    if (this.closedByCaller) {
      return;
    }
    setTimeout(() => {
      if (!this.closedByCaller) {
        this.connect();
      }
    }, this.reconnectDelayMs);
  }

  close(): void {
    this.closedByCaller = true;
    this.task?.close({});
    this.task = null;
  }
}
