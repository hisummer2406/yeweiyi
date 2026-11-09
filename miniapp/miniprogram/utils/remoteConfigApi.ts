// Requests of 远程配置 / 补录 (miniapp#6). Contracts: yeweiyi-cloud docs/config-command-contract.md,
// docs/stock-manual-entry-contract.md. Failures reject with ApiError (status 0 = no answer).
import { get, post } from './request';
import type { ControlCommand, ListCommandsResponse, TankSettings, TankSettingsResponse } from '../types/api';

const dev = (device: string) => `/api/devices/${encodeURIComponent(device)}`;

export function getTankSettings(device: string): Promise<TankSettings[]> {
  return get<TankSettingsResponse>(`${dev(device)}/tank-settings`).then((r) => (r && Array.isArray(r.tanks) ? r.tanks : []));
}

export type ConfigSubmission = {
  method: 'config.setTankSettings';
  args: Record<string, number>;
  reason: string;
  requestId: string;
  // The station the page saw the device on; null = on no station. Always sent (cloud contract §1.2).
  expectedStationId: number | null;
};

// 202 accepted or 200 replay of the same requestId — both only say how far it got; the outcome is the
// command's status.
export function issueConfig(device: string, body: ConfigSubmission): Promise<ControlCommand> {
  return post<ControlCommand>(`${dev(device)}/commands`, body as unknown as Record<string, unknown>);
}

export function getCommand(device: string, commandId: string): Promise<ControlCommand> {
  return get<ControlCommand>(`${dev(device)}/commands/${encodeURIComponent(commandId)}`);
}

// The caller's own submission by requestId (list shape, 0 or 1).
export function findByRequestId(device: string, requestId: string): Promise<ControlCommand | undefined> {
  return get<ListCommandsResponse>(`${dev(device)}/commands`, { requestId }).then((r) => (r && r.commands ? r.commands[0] : undefined));
}

export function listCommands(device: string, limit = 20): Promise<ControlCommand[]> {
  return get<ListCommandsResponse>(`${dev(device)}/commands`, { limit }).then((r) => (r && r.commands) || []);
}

export function addStockEvent(device: string, body: Record<string, unknown>): Promise<unknown> {
  return post(`${dev(device)}/stock-events`, body);
}
