## 1. Verify dead-code claim before touching anything

- [x] 1.1 Re-confirm zero callers for each of the 6 target read methods with individual `git grep -F "<methodName>"` calls (one term per invocation — this repo's ugrep-backed `git grep` silently returns zero matches on `\|`/`\b`, see CLAUDE.md), excluding hits inside `ExportExcel.java` itself: `readExcelOilCanSetContent`, `readExcelAlarmContent`, `readExcelCommitWorkContent`, `readExcelInOutContent`, `readExcelSaveInfo`, `readExcelProbeToOilCanContent`
- [x] 1.2 Re-confirm zero callers for `exportCommitWork` and `exportOilStroage` the same way
- [x] 1.3 Re-confirm `getValue(HSSFCell)`'s only call sites are inside the methods being deleted in 2.1-2.2 (grep line numbers, cross-check against the ranges being removed)
- [x] 1.4 Re-confirm `test/TestExcel.java` has no callers anywhere else in the codebase and is not referenced from `AndroidManifest.xml` or any test runner config

## 2. Delete dead code in ExportExcel.java

- [x] 2.1 Delete the 6 dead `readExcelXxxContent` methods identified in 1.1
- [x] 2.2 Delete `exportCommitWork` and `exportOilStroage`
- [x] 2.3 Delete `getValue(HSSFCell)` (cascading dead code per 1.3)
- [x] 2.4 Remove now-unused imports left behind by the above deletions (check each import still has a live reference before removing — don't assume from the deleted method's import list alone, since surviving methods may share imports)

## 3. Delete the orphaned TestExcel scaffold

- [x] 3.1 Delete `app/src/main/java/com/sansen/yeweiyi/test/TestExcel.java`
- [x] 3.2 Check whether `com.sansen.yeweiyi.test` package still has other files after this deletion; if empty, no further action needed (don't delete the directory manually — Gradle doesn't care about empty package dirs, and removing it isn't part of this change's scope)

## 4. Verify

- [x] 4.1 `./gradlew :app:assembleDebug` — confirms no surviving code referenced a deleted symbol
- [x] 4.2 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`, then read `app/build/test-results/testDebugUnitTest/*.xml` to confirm a fresh pass (not a stale UP-TO-DATE no-op) with no new failures
- [x] 4.3 Spot-check the 9 live call sites (`AlarmFragment`, `CommitWorkRecortFragment`, `CreateOilTableFragment` ×2, `InOilFragment`, `OutStockFragment`, `StockFragment`, `DBDataManager`) still compile against unchanged method signatures — this is implied by 4.1 passing, but confirm by reading the diff shows no edits to any of these 7 files
