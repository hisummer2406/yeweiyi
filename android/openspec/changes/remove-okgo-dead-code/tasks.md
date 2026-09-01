## 1. Verify dead-code claim before touching anything

- [x] 1.1 Re-confirm `OkgoNet.getInstance()` has zero non-comment callers via `git grep -F "OkgoNet.getInstance()"` (one term, no `\|`/`\b` — see CLAUDE.md ugrep note); confirm the only hits are commented-out lines in `BaseActivity.java` and `AlarmDetection.java`
- [x] 1.2 Re-confirm `MacUtils` has zero references outside its own file via `git grep -F "MacUtils"`
- [x] 1.3 Re-confirm `ServerModel` is only referenced inside `OkgoNet.java` via `git grep -F "ServerModel"`
- [x] 1.4 Re-confirm `com.sansen.yeweiyi.okGoUntils.RequestCallBack` (not the unrelated `com.lidroid.xutils.http.callback.RequestCallBack`) has no callers outside `OkgoNet.java` and `CrashAppLog.java`'s dead import
- [x] 1.5 Re-confirm `com.lzy.okgo` (the OkGo library itself, distinct from the `okGoUntils` wrapper) has live callers in the 7 fragments + `CrashAppLog.java` via `git grep -F "com.lzy.okgo"` — this confirms the Gradle dependency must stay

## 2. Delete the dead okGoUntils package

- [x] 2.1 `git rm` the 4 files: `OkgoNet.java`, `RequestCallBack.java`, `MacUtils.java`, `ServerModel.java` under `app/src/main/java/com/sansen/yeweiyi/okGoUntils/`
- [x] 2.2 Remove the 2 dead imports (`OkgoNet`, `RequestCallBack`) from `CrashAppLog.java:14-15` — confirm the file's live `com.lzy.okgo.model.Progress` import is untouched
- [x] 2.3 Confirm `build.gradle`'s `com.lzy.net:okgo:3.0.4` dependency line is untouched (per design.md Non-Goals — the library itself stays)

## 3. Verify

- [x] 3.1 `./gradlew :app:assembleDebug` — confirms no surviving code referenced a deleted symbol
- [x] 3.2 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`, then read `app/build/test-results/testDebugUnitTest/*.xml` to confirm a fresh pass (not a stale UP-TO-DATE no-op) with no new failures
- [x] 3.3 Confirm via `git diff --stat` that only `CrashAppLog.java` was modified (2-line removal) and the 4 `okGoUntils` files were deleted — no other file touched, especially none of the 7 fragments using `com.lzy.okgo` directly
