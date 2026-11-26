// Requests of 远程配置 / 补录 (miniapp#6). Contracts: yeweiyi-cloud docs/config-command-contract.md,
// docs/stock-manual-entry-contract.md. Failures reject with ApiError (status 0 = no answer).
import { get, post } from './request';
import type { SendArgs, SendMethod } from './remoteConfig';
import type { ControlCommand, ListCommandsResponse, MediaOptions, TankSettings, TankSettingsResponse } from '../types/api';

const dev = (device: string) => `/api/devices/${encodeURIComponent(device)}`;

export function getTankSettings(device: string): Promise<TankSettings[]> {
  return get<TankSettingsResponse>(`${dev(device)}/tank-settings`).then((r) => (r && Array.isArray(r.tanks) ? r.tanks : []));
}

// What an administrator may pick for this device's tanks (media + whether the firmware takes oilColor).
// Employees are refused (403); the editor never asks for them.
export function getMediaOptions(device: string): Promise<MediaOptions> {
  return get<MediaOptions>(`${dev(device)}/media-options`);
}

export type ConfigSubmission = {
  method: SendMethod;
  args: SendArgs;
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

// Calibration (tank table) mirror, cloud#14: what the device last reported for one tank. 404 = never reported.
export type TankCalibration = { reportedAt: string; pointCount: number; heightMm: number[]; volumeL: number[] };

export function getTankCalibration(device: string, tank: number): Promise<TankCalibration> {
  return get<TankCalibration>(`${dev(device)}/tanks/${tank}/calibration`);
}
