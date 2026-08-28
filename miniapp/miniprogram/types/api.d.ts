// Field names/casing here are copied verbatim from yeweiyi-cloud's handlers and sqlc-generated
// models (internal/api/*.go, internal/store/db/models.go) as of 2026-08-29 — do not "clean up"
// the casing (e.g. beginHeigth/endHeigth/beginVolum/endVolum are the device's own field-name typos,
// preserved on purpose; see internal/api/writeback.go's comments on those structs).

export interface Device {
  device_name: string;
  is_online: boolean;
  last_online_at: string | null;
  last_offline_at: string | null;
}

export interface Alarm {
  id: number;
  device_name: string;
  device_local_id: number;
  time: string;
  info: string;
  guanhao: string;
  jiezhi: string;
}

export interface OilInOut {
  id: number;
  device_name: string;
  device_local_id: number;
  oil_id: number;
  jiezhi: string;
  time: string;
  begin_height: number;
  end_height: number;
  begin_volume: number;
  end_volume: number;
  begin_weight: number;
  end_weight: number;
}

export interface Shift {
  id: number;
  device_name: string;
  device_local_id: number;
  work_id: number;
  time: string;
  end_time: string;
  info: string;
}

// Backend's cached/polled snapshot for one probe, mirroring the device's NetOilCan shape
// (yeweiyi-cloud/internal/mqtt/payloads.go NetOilCanPayload). GET /api/oilcan/latest returns an
// array of these directly (not wrapped in {rows:...}), one per probe/tank on the device.
export interface OilCanSnapshot {
  id: number;
  oiltype: string;
  oilColor: number;
  oilLevel: number;
  waterLevel: number;
  temp: number;
  oilVolume: number;
  waterVolume: number;
  oilWeight: number;
  nullVolume: number;
  oilRadio: number;
  waterRadio: number;
  alarmState: string;
  probeAddress: string;
  connect: boolean;
  probeType: string;
  address: string;
  fatherAllProbe: number;
  deviceName: string;
}

export type WriteBackStatus = 'pending' | 'sent' | 'acked' | 'failed';

export interface WriteBackCommand {
  id: number;
  device_name: string;
  command_type: string;
  payload: unknown;
  status: WriteBackStatus;
  attempts: number;
  created_at: string;
  updated_at: string;
}

export interface OilInOutCorrectionRequest {
  device: string;
  deviceLocalId: number;
  oilId: number;
  jieZhi: string;
  time: string;
  beginHeigth: number;
  endHeigth: number;
  beginVolum: number;
  endVolum: number;
  beginWeight: number;
  endWeight: number;
}

export interface ShiftCorrectionRequest {
  device: string;
  deviceLocalId: number;
  workId: number;
  time: string;
  endTime: string;
  info: string;
}

export interface WriteBackAccepted {
  id: number;
  status: 'pending';
}
