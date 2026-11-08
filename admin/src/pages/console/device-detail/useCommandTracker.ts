import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@/services/ant-design-pro/apiError';
import { getCommand } from '@/services/ant-design-pro/deviceConfig';
import { type EchoState, echoState } from '@/utils/deviceConfig';

export const COMMAND_POLL_MS = 2000;
export const ECHO_POLL_MS = 5000;
/** succeeded 之后最多等这么久的设备回报；之后停止自动读取，提示可手动刷新 */
export const ECHO_WAIT_MS = 3 * 60 * 1000;

type Mirror = (Record<string, unknown> & { reportedAt?: string }) | null;

export type Tracked = {
  command: API.ControlCommand;
  /** 本次提交的字段（含 tank），用于回显比对 */
  args: Record<string, unknown>;
};

export type TrackerState = {
  command?: API.ControlCommand;
  echo?: EchoState;
  /** 回显等待超过 ECHO_WAIT_MS */
  echoTimedOut: boolean;
  /** 最近一次读取失败（只提示，继续读） */
  pollFailing: boolean;
};

const hidden = () =>
  typeof document !== 'undefined' && document.visibilityState === 'hidden';

/**
 * 跟踪一条配置命令直到终态，成功后再读回设备镜像核对（cloud config-command-contract §6）。
 * - 是否继续轮询只看云端的 terminal，不在前端硬编码状态集合。
 * - 页面隐藏时不发请求（到点只是重新排期），回到前台继续。
 * - tracked 换了（新的命令、换设备卸载）旧定时器与迟到响应都作废。
 */
export function useCommandTracker(
  device: string,
  tracked: Tracked | undefined,
  readMirror: () => Promise<Mirror>,
  opts: { pollMs?: number; echoMs?: number; echoWaitMs?: number } = {},
): TrackerState & { refreshEcho: () => void } {
  const pollMs = opts.pollMs ?? COMMAND_POLL_MS;
  const echoMs = opts.echoMs ?? ECHO_POLL_MS;
  const echoWaitMs = opts.echoWaitMs ?? ECHO_WAIT_MS;
  const [state, setState] = useState<TrackerState>({
    echoTimedOut: false,
    pollFailing: false,
  });
  const readRef = useRef(readMirror);
  readRef.current = readMirror;
  const [echoKick, setEchoKick] = useState(0);

  const id = tracked?.command.commandId;

  // 1) 命令状态
  useEffect(() => {
    if (!tracked) {
      setState({ echoTimedOut: false, pollFailing: false });
      return;
    }
    setState({
      command: tracked.command,
      echoTimedOut: false,
      pollFailing: false,
    });
    if (tracked.command.terminal) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      if (cancelled) return;
      if (hidden()) {
        timer = setTimeout(tick, pollMs);
        return;
      }
      try {
        const next = await getCommand(device, tracked.command.commandId);
        if (cancelled) return;
        setState((s) => ({ ...s, command: next, pollFailing: false }));
        if (!next.terminal) timer = setTimeout(tick, pollMs);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.authHandled) return;
        setState((s) => ({ ...s, pollFailing: true }));
        timer = setTimeout(tick, pollMs);
      }
    };
    timer = setTimeout(tick, pollMs);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // 只随命令号/设备重跑：同一命令的状态更新不重启轮询
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [device, id, pollMs]);

  // 2) 成功之后读回镜像
  const succeeded = state.command?.status === 'succeeded';
  useEffect(() => {
    if (!tracked || !succeeded) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const started = Date.now();
    const tick = async () => {
      if (cancelled) return;
      if (hidden()) {
        timer = setTimeout(tick, echoMs);
        return;
      }
      try {
        const mirror = await readRef.current();
        if (cancelled) return;
        const echo = echoState(mirror, tracked.args, state.command?.finishedAt);
        const timedOut =
          echo === 'waiting' && Date.now() - started >= echoWaitMs;
        setState((s) => ({
          ...s,
          echo,
          echoTimedOut: timedOut,
          pollFailing: false,
        }));
        if (echo === 'waiting' && !timedOut) timer = setTimeout(tick, echoMs);
      } catch (err) {
        if (cancelled) return;
        if (err instanceof ApiError && err.authHandled) return;
        setState((s) => ({ ...s, pollFailing: true }));
        if (Date.now() - started < echoWaitMs) timer = setTimeout(tick, echoMs);
      }
    };
    tick();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    device,
    id,
    succeeded,
    state.command?.finishedAt,
    echoMs,
    echoWaitMs,
    echoKick,
  ]);

  return { ...state, refreshEcho: () => setEchoKick((n) => n + 1) };
}
