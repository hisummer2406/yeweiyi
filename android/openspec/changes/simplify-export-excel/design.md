## Context

See proposal.md - Why. This is a single-file dead-code removal, not a cross-cutting or architectural change; this design doc exists only because the workflow schema requires it as a dependency of tasks.md, not because the change is complex.

## Goals / Non-Goals

**Goals:**
- Remove verified-dead code from `ExportExcel.java` and delete the orphaned `test/TestExcel.java`.
- Leave every live call path (9 call sites across 6 fragments + `DBDataManager`) byte-for-byte behaviorally unchanged.

**Non-Goals:**
- Splitting the surviving ~450 lines of `ExportExcel<T>` into per-domain classes. After dead-code removal it is no longer oversized, and the project's "职责搬移，不做设计升级" constraint rules out a speculative redesign with no forcing function.
- Touching `DBDataManager`'s dependency on `ExportExcel` (layering direction) — out of scope, no behavior change needed here.

## Decisions

- **Deletion, not deprecation.** Unlike the Repository strangler-fig pattern used in `layer-data-and-hardware-boundaries` (where `DBDataManager` methods were marked `@Deprecated` because external callers still existed), these methods have zero callers anywhere in the codebase (verified via `git grep -F`, one term per invocation, per CLAUDE.md's documented `\|`/`\b` silent-failure trap in this repo's ugrep-backed `git grep`). There is nothing to migrate off of, so `@Deprecated` would be theater — straight deletion is correct.
- **Verification method: exhaustive single-term `git grep -F`, cross-checked against reflection.** Confirmed no dynamic dispatch reaches these method names — the only `getMethod(...)` reflection call inside `ExportExcel.java` resolves bean-property getters on the generic `T` (for the live generic export path), not `ExportExcel`'s own methods by name. So "zero grep hits" is not undermined by a hidden reflective caller.
- **`test/TestExcel.java` is deleted outright, not moved to `app/src/androidTest`.** It predates real test infrastructure, extends the deprecated `android.test.AndroidTestCase`, and its method `TestExcel()` doesn't match any JUnit/AndroidJUnitRunner test-method naming convention — it has never been executed by any test runner, in this source location or any other. Reviving it as a real test is out of scope; it's dead weight shipping into the production APK today (`app/src/main/java`, not a test source set).
- **`getValue(HSSFCell)` deleted as cascading dead code.** Its only call sites are inside the 6 methods being deleted; once those are gone it has no callers. `getCellDoubleValue(Cell)` is kept — it's used by the live `readExcelOilCanTableContent`.

## Risks / Trade-offs

- [Risk: a caller reaches a "dead" method through a path `git grep -F` can't see, e.g. JNI, resource-driven reflection, or a build variant not checked] → Mitigation: this project has no `getIdentifier()`-style dynamic resource resolution (per CLAUDE.md), and the reflection check above rules out method-name-string dispatch. `assembleDebug` after deletion will fail to compile if any surviving Java source still references a deleted symbol, which is a stronger guarantee than grep alone for compile-time callers.
- [Risk: POI-related dead code removal accidentally strips a shared helper used transitively] → Mitigation: `getCellDoubleValue` explicitly kept; `HSSFCellStyle`/`HSSFFont`/etc. formatting helpers inside the surviving `exportExcel`/`exportOilCanTable` methods are untouched — only whole dead *methods* are removed, no shared statement-level code is touched.

## Migration Plan

Single commit: delete the 6 dead read methods + 2 dead export methods + `getValue` + `test/TestExcel.java`, then `./gradlew :app:assembleDebug` and `:app:cleanTestDebugUnitTest :app:testDebugUnitTest` to confirm no missed reference and no regression. No phased rollout needed — there's no runtime behavior to migrate away from, and no real-device verification is required (see proposal.md Impact).
