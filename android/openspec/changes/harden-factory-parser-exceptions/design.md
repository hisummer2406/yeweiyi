## Context

See proposal.md - Why. Two call sites reach the four `factory/` parsers in production: `SerialPortManager`'s probe-type switch (serial polling loop, `case 0`/`1`/`2`) and `VR350Factory`'s own `HandlerThread` (driven by `PCPortThread`, not the switch — see `ProbeTrafficRecorder`'s comment confirming VR350 doesn't go through `hexDataParseFinish`). `case 0`/`case 1` already catch and log; `case 2` and the VR350 handler don't.

## Goals / Non-Goals

**Goals:**
- Every production call site that invokes a `factory/` parser catches and logs parser exceptions instead of letting them propagate.
- The fix for `case 2` is byte-for-byte the same shape as the existing `case 0`/`case 1` handling, so a reviewer can diff three near-identical blocks instead of learning a new pattern.

**Non-Goals:**
- Adding exception handling *inside* the parser classes themselves (`LDCompany`/`LD2Company`/`ZhongChengCompany`/`VR350Factory`). Catching at the call site (matching `case 0`/`1`'s established pattern) keeps the diff small and keeps the oil-can-position/raw-frame log context available, which the parsers themselves don't have direct access to.
- Fixing `LD2Company`'s missing CRC check or its unchecked `bytes[2]/2` indexing — that's the underlying cause of *why* it throws, tracked separately by the already-shipped `fix-ld-probe-dispatch-fallthrough` change's documentation (see its comment in `SerialPortManager.java`). This change only stops the throw from crashing the thread; it doesn't make the parse itself more correct.
- Any change to `AbstractCompany`/`ICreateTable`/`IParseDataListener` shared interfaces.

## Decisions

- **Catch at the call site, not inside the parser.** Alternative considered: add a try/catch inside each parser's `probeInfoPase`/`initData`. Rejected because (a) it would touch 4 files instead of 2, all of which are hardware-protocol code requiring real-device verification: better to touch the minimum surface; (b) the call site already has the contextual data (`mOilCan.getOilCanPos()`, raw `data`) needed for a useful log line, which `probeInfoPase` doesn't receive as a parameter for `LDCompany`/`LD2Company`/`ZhongChengCompany` (only `IParseDataListener`); (c) it matches the existing, already-reviewed `case 0`/`1` pattern exactly, so this reads as "closing a gap" rather than "introducing a new pattern".
- **VR350Factory: wrap the `initData` call inside `handleMessage`, not `initData` itself.** Same rationale — the catch belongs at the boundary between "thread that must stay alive" and "code that might throw", which is the `Handler.handleMessage` override, not `initData`'s internals.
- **Log level: `Log.e` (error), matching `case 0`/`1`'s existing choice**, not `Log.w` — a parser exception is not the routine "0 result" path (`default:` branch, unknown probe type), it's an unexpected fault worth error-level visibility during field diagnosis.

## Risks / Trade-offs

- [Risk: catching broadly (`Exception`) at these two sites could mask a bug that should actually crash loudly during development] → Mitigation: this mirrors the exact catch width already in production at `case 0`/`1` for over a year with no reported issue; consistency with the established pattern is valued over introducing a narrower-but-different exception type here.
- [Risk: can't be verified on this dev machine — no real probe/PC-protocol hardware] → Mitigation: `assembleDebug` confirms compilation; proposal.md's Impact section flags real-device verification as required before shipping (triggering source is a malformed frame, which can be simulated on-device if needed, but not from this checkout).

## Migration Plan

Single commit: add the `case 2` try/catch in `SerialPortManager.java` (mirroring `case 0`/`1`), add the `handleMessage` try/catch in `VR350Factory.java`, then `./gradlew :app:assembleDebug` and `:app:cleanTestDebugUnitTest :app:testDebugUnitTest` to confirm no regression. No phased rollout — this is a pure addition of a catch boundary, not a behavior change for the non-error path.
