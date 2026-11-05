// What a failed device-data read or command submission means for the user. Pure (no `wx`), checked in
// Node by `npm run check`. Returns i18n keys; the page renders them with `t()`.
//
// yeweiyi-cloud docs/error-codes-contract.md (#79): a 404 from telemetry/latest is one of three states,
// told apart by `code`, and only one of them is "never reported". An older cloud sends no code; its 404
// keeps meaning "never reported".

export type TelemetryEmpty =
  // A real, empty state — not a failure. `lastReportedAt` (ISO) only for `expired`.
  | { kind: 'empty'; key: string; lastReportedAt?: string }
  // A failure: the page shows its usual error/stale handling.
  | { kind: 'error' };

function bodyField(body: unknown, field: string): string | undefined {
  const v = (body as Record<string, unknown> | undefined)?.[field];
  return typeof v === 'string' ? v : undefined;
}

export function telemetryFailure(status: number, body: unknown): TelemetryEmpty {
  if (status !== 404) return { kind: 'error' };
  switch (bodyField(body, 'code')) {
    case undefined:
    case 'telemetry_not_reported':
      return { kind: 'empty', key: 'telemetry.neverReported' };
    case 'telemetry_expired':
      return { kind: 'empty', key: 'telemetry.expired', lastReportedAt: bodyField(body, 'lastReportedAt') };
    case 'device_not_provisioned':
      return { kind: 'empty', key: 'telemetry.notProvisioned' };
    default:
      // Another 404 (an unknown code) is not something to present as "no data".
      return { kind: 'error' };
  }
}

// A command submission whose outcome is unknown: the cloud said it could not confirm the hand-off
// (`command_delivery_unconfirmed`), or no answer came back at all (status 0: timeout, dropped
// connection — the request may well have reached the cloud and been sent). Either way the device may be
// acting on it (yeweiyi-cloud invariant 2), so the user is sent to the command list, never told to retry.
export function commandOutcomeUnknown(status: number, body: unknown): boolean {
  return status === 0 || bodyField(body, 'code') === 'command_delivery_unconfirmed';
}
