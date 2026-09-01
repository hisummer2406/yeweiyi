## Context

See proposal.md - Why. Single-package dead-code removal, not a cross-cutting or architectural change; this design doc exists only because the workflow schema requires it as a dependency of tasks.md, not because the change is complex.

## Goals / Non-Goals

**Goals:**
- Remove the verified-dead `okGoUntils` package and the two dead imports it left in `CrashAppLog.java`.
- Leave every live use of the OkGo library itself (7 Fragments' `runOnUiThread` static import, `CrashAppLog`'s `Progress` import) and xUtils `HttpUtils`/`RequestCallBack` byte-for-byte unchanged.

**Non-Goals:**
- Removing or downgrading the OkGo Gradle dependency (`com.lzy.net:okgo:3.0.4`) — it has real, live callers outside `okGoUntils`; this change only removes the unused wrapper layer around it.
- Cleaning up the commented-out `OkgoNet.getInstance()` blocks inside `BaseActivity.java`/`AlarmDetection.java` — those are stale comments referencing the class being deleted, not code paths, and tidying commented-out code in otherwise-unrelated files is out of scope for a dead-code-package deletion.
- Deciding whether the project should standardize on OkGo or xUtils `HttpUtils` for future networking — that's a forward-looking architecture question, not something this deletion needs to answer.

## Decisions

- **Deletion, not deprecation.** Same rationale as `simplify-export-excel`: zero callers anywhere in the codebase (verified via `git grep -F`, one term per invocation, per CLAUDE.md's documented `\|`/`\b` silent-failure trap), so there is nothing to migrate off of — `@Deprecated` would be theater.
- **Scope stops at the `okGoUntils` package + its two dead imports, not the OkGo library dependency.** Verified via `git grep -F "com.lzy.okgo"` that 7 fragments and `CrashAppLog` import directly from the library package (`com.lzy.okgo.utils.HttpUtils`, `com.lzy.okgo.model.Progress`), independent of the `com.sansen.yeweiyi.okGoUntils` wrapper being deleted. Removing the Gradle dependency would break those live call sites, so it stays.
- **Commented-out `OkgoNet` call sites in `BaseActivity.java`/`AlarmDetection.java` are left alone.** They're already inert (compiled out), and touching them means editing two files whose primary purpose is unrelated to this dead-code removal — mixing that in would make the diff harder to review and blur the change's scope. A future pass through those specific files can decide whether to delete or keep the historical comments.

## Risks / Trade-offs

- [Risk: a caller reaches `okGoUntils` classes through a path `git grep -F` can't see, e.g. reflection or a build variant not checked] → Mitigation: confirmed no `Class.forName`/reflection reference to `okGoUntils`, no XML/manifest reference, no `getIdentifier()`-style dynamic resolution exists in this project (per CLAUDE.md). `assembleDebug` after deletion will fail to compile if any surviving Java source still references a deleted symbol.
- [Risk: deleting the wrapper package is mistaken for also removing the OkGo dependency, breaking the 7 fragments that use it directly] → Mitigation: proposal.md and this doc both explicitly call out the dependency stays; tasks.md will include a step confirming `build.gradle` is untouched and the 7 live `com.lzy.okgo` importers still compile.

## Migration Plan

Single commit: delete the 4 `okGoUntils` files + the 2 dead imports in `CrashAppLog.java`, then `./gradlew :app:assembleDebug` and `:app:cleanTestDebugUnitTest :app:testDebugUnitTest` to confirm no missed reference and no regression. No phased rollout needed — there's no runtime behavior to migrate away from, and no real-device verification is required (not in `serialport/`/`factory/`/`structbean/`).
