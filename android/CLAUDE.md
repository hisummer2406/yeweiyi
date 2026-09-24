# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

**安卓液位仪 (Android Liquid Level Meter)** — a native Android app, package `com.sansen.yeweiyi`, that runs on a dedicated landscape-orientation hardware terminal for monitoring oil storage tanks (油罐). It talks to physical liquid-level probes over RS-232/485 serial and/or TCP socket, decodes vendor-specific binary protocols, persists readings locally, and exposes an embedded HTTP server so other devices on the LAN (e.g. a PC) can query the data.

This was previously scaffolded from the `goldze/MVVMHabit` template (Java, MVVM + DataBinding + RxJava2), but as of the current code that template has been **entirely superseded** — the `mvvmhabit` module is gone, DataBinding/RxJava-first architecture is gone, and this is now a self-contained, older-style Android app (compileSdk 27, minSdk 21) built around singleton managers rather than MVVM.

### Modules (`settings.gradle`)

- `app/` — the application, package `com.sansen.yeweiyi`. All real business logic lives here.
- `xutilslibrary/` — a vendored copy of the old **xUtils** framework (`com.lidroid.xutils`: ORM `DbUtils`, `HttpUtils`, view-injection). Used as the local persistence/HTTP layer instead of GreenDAO or Retrofit-first code.

### Orphaned directories — do not edit

The stray root-level `java/` and `src/` duplicate source trees were removed (commit `8f8373c`). If they reappear, they are not referenced by `settings.gradle` or any build.gradle — always edit the real module paths under `app/src/main/java/...` and `xutilslibrary/src/main/java/...`.

Within `app/src/main/java` itself there are also several non-namespaced top-level packages that predate the `com.sansen.yeweiyi` package (`asynmsg`, `bean`, `recycle`, `storage`, `rxjava`, `log`, `base`, `serialport`, `atg`, `com.example.x6.gpioctl`). These *are* compiled and in use (e.g. `log.LogCatHelper`, `serialport.manager.SerialPortManager` are referenced from `MyApplication`), but note there are two different, unrelated `BaseFragment` classes — `base.BaseFragment`/`base.BaseVM` (root package, appears to be dead/unused leftover) vs. `com.sansen.yeweiyi.base.BaseFragment` (the one actually extended by real screens). Check imports carefully before assuming which one a file uses.

## Build

`gradlew`/`gradlew.bat` are present, targeting Gradle 6.7.1 via the Tencent Cloud mirror in `gradle/wrapper/gradle-wrapper.properties`.

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run the JVM unit tests (no device needed)
./gradlew :app:testDebugUnitTest
```

### Toolchain is present on this machine — but still verify before claiming a build passed

As of 2026-08-07 this macOS checkout builds: JDK 11 (Temurin) plus an Android SDK at `~/Library/Android/sdk`, with `local.properties` pointing at it. `./gradlew :app:assembleDebug` and `:app:testDebugUnitTest` both succeed (38 tests, 0 failures).

Two caveats that have already bitten:
- Commits `6ae4e3f` through `ada562e` (阶段 0 and 阶段 1) were written *before* the toolchain existed and were merged unverified. Later commits are compile-verified.
- `testDebugUnitTest` reports `UP-TO-DATE` and prints nothing when inputs haven't changed — a stale pass looks identical to a fresh one. Run `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`, or read `app/build/test-results/testDebugUnitTest/*.xml`, before claiming tests passed.

A green build here proves compilation only. **Nothing about serial/probe hardware behavior can be verified on this machine** — protocol changes still need a real device.

### Line endings are LF, pinned by `.gitattributes`

The repo was CRLF (Windows origin) until 2026-08-07, when commit `5c6e6a3` normalized all 410 text files to LF and added `.gitattributes` (`* text=auto eol=lf`; `*.bat` stays CRLF; binaries explicitly marked). Don't reintroduce CRLF, and don't let a bulk-edit script rewrite whole files — that is what produced the mixed state in the first place (a one-line `catch` fix in `MD5utils.java` showed up as `32+/31-`).

`git blame` on any file older than that commit needs `-w`, or one-time `git config blame.ignoreRevsFile .git-blame-ignore-revs`.

### Secrets are injected at build time — not hardcoded

`app/build.gradle` reads `local.properties` and injects six credential values into `BuildConfig` — `PRODUCT_KEY`, `PRODUCT_SECRET`, `REQUEST_ENCRYPT_KEY`, `BASE_URL`, plus `MQTT_USERNAME` / `MQTT_PASSWORD` (added 2026-08-14, previously hardcoded in `BackService`); `ApiConfig` just re-exports them. `local.properties` is untracked — copy `local.properties.template` and fill it in. Missing keys log a `[配置缺失]` warning at build time and compile to empty strings (so the app builds but MQTT upload / request encryption silently fail — `BackService.initMqttClient` logs a warning rather than falling back to any default account).

There is also one non-secret **flag** on the same channel, injected via `flagField` instead of `secretField` (missing = `false`, no warning): `PROBE_RECORDING` (`sansen.probeRecording`) turns on `manager/ProbeTrafficRecorder`, which writes probe traffic to `/sdcard/yeweiyi-probe-record/` for golden-master capture. Keep it off in any build that ships.

Credentials hardcoded before commit `6ae4e3f` are in git history and must be treated as leaked.

Key `app/build.gradle` facts:
- `compileSdkVersion 27`, `minSdkVersion 21`, `targetSdkVersion 27`, landscape-only (see manifest).
- `multiDexEnabled true`, native build via `externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt" } }`.
- versioning is vendor-specific (`versionName "NEW.SS.A.Y1.00"`), and the APK output filename is auto-suffixed with version + date + build type.
- The root `build.gradle` still declares a `greendao-gradle-plugin` classpath dependency, but no module applies the `org.greenrobot.greendao` plugin or uses GreenDAO anymore — it's vestigial from the old template and can be ignored (or removed if cleaning up).
- Dependencies are a large pile of local jars in `app/libs/` (Baidu Maps/TTS/Push SDKs, Apache POI 3.10 for Excel export, Tencent TBS X5 webview kernel, MQTT client, AndServer, Apache httpclient/mina, `sun.misc.BASE64Decoder`, an internal `xzjh_interface_*.jar`). Treat these as fixed/vendored artifacts — most have no current Maven-hosted release, so don't try to "upgrade" them without checking hardware/vendor SDK compatibility first.
- Repositories are mirrored through Aliyun (`build.gradle`) for mainland China connectivity — don't remove without checking.

There is no real unit/instrumented test suite.

## Architecture

### App entry & global setup

`com.sansen.yeweiyi.application.MyApplication` (declared in `AndroidManifest.xml`) initializes, in `onCreate()`: crash handling (`CrashApphandler`), file logging (`log.LogCatHelper`), `CRCheck`, the serial port manager (`serialport.manager.SerialPortManager`), the local DB (`DaoHelp`, an xUtils `DbUtils` wrapper), sound alerts (`SoundManager`), and several data managers (`DBDataManager`, `OilcanDataManager`, `SaveOilManager`). It also loads the persisted `LevelmeterInfo` device config and sets up locale/language switching. `MainActivity` (`com.sansen.yeweiyi.activity.MainActivity`) is the sole real Activity and hosts everything else as Fragments (see manifest — landscape-locked).

### Data flow: hardware → app → UI / LAN

- `manager/` — singleton managers: `SerialPortManager` (JNI serial comms with the probe hardware), `SocketManager`/`TCPClient` (network comms with the probe or other devices), `AlarmManager`/`AlarmDetection`/`SoundLightManager` (alarm state + audible/visual alerts), `OilcanDataManager`/`SaveOilManager` (business data, now read/write through `repository/` for their domain data — see below), `DBDataManager` (persistence access for everything *not* yet moved to `repository/` — see below), `MyTimeTask` (polling/scheduling), `InOilManager` (出/入库 event detection glue — see below).
- `repository/` — the persistence entry point for four domains, each an interface + `XxxRepositoryImpl` singleton (`getInstance()`) calling `DaoHelp`/`DbUtils` directly: `OilTankRepository` (`OilCanSet`/`OilCanTable`/`ProbeToOil`/`Sideleakage`/`OilCanMapper`/`OilExploration`), `StockRepository` (`OilInOut`/`OutStock`/`StockSave`/`DetailInoutRecord`), `AlarmRepository` (`Alarm`), `ShiftRepository` (`CommitWork`). New code touching these six entities should go through the matching Repository, not `DBDataManager`. See `openspec/changes/layer-data-and-hardware-boundaries` for how these were split out of `DBDataManager` (strangler-fig: old methods marked `@Deprecated`, not deleted) and for the one method-per-domain quirks that were deliberately preserved rather than fixed during the migration.
- `logic/inout/` — pure Java (no `Context`/Android singletons) state machine that decides whether a run of liquid-level readings constitutes a stock-in/stock-out event: `TankLevelTracker` (per-tank state machine — dead-band debounce, then requires both a time and a height threshold before firing, keeps waiting instead of discarding when only one threshold is met), `MultiTankInOutDetector` (per-tank tracker registry, replaces a former 16-slot fixed array), `InOutEvent`/`InOutDirection`/`InOutThresholds` (value types). `manager/InOilManager` is the glue layer: assembles `InOutThresholds` from `Singleton`, feeds readings in, and on a produced `InOutEvent` fills in live `OilcanDataManager` fields and persists to `OilInOut`/`OutStock` — direction is decided once inside `TankLevelTracker` and never re-derived downstream. Covered by JVM unit tests (`logic/inout/*Test.java`) since it has no Android dependency. See `openspec/changes/rewrite-stock-inout-detection` for the rationale (the previous implementation zeroed out its tracked state on the first threshold check that didn't pass, instead of continuing to wait — the main cause of missing 出库/入库 records).
- `factory/` — per-vendor protocol parsers for different probe hardware brands (`LDCompany`, `LD2Company`, `VR350Factory`, `ZhongChengCompany`), all implementing shared interfaces (`ICreateTable`, `IParseDataListener`). This is the extension point for adding support for a new probe vendor/protocol.
- `structbean/` — byte-level struct definitions (`*In.java`) for the serial/socket protocol frames exchanged with probe hardware.
- `db/` — plain POJO entities (`OilCanSet`, `OilInOut`, `Alarm`, `User`, `Authorization`, etc.) persisted via xUtils `DbUtils` (see `db/help/DaoHelp.java`), not GreenDAO. Six of these entities (see `repository/` above) are read/written through a domain Repository; the rest still go through `DBDataManager` directly.
- `fragment/` — the actual UI screens hosted inside `MainActivity` (`MainFragment`, `OilCanFragment`, `InOilFragment`, `OutStockFragment`, `AlarmFragment`, `SetFragment` + sub-settings fragments, `BaoBiaoFragment` for reports, etc.).
- `web/handler/` — request handlers for an embedded AndServer HTTP server (`LoginHandler`, `AlarmHandler`, `OilDischarge`, `UploadHandler`, `FileHandler`, `ServerManager`) so external clients on the LAN can pull data from the device.
- `okGoUntils/` — an OkGo-based networking helper, used alongside (not instead of) xUtils `HttpUtils` — check which a given class actually uses before assuming.

### `DBDataManager`: permanently partial, not slated for deletion

As of 2026-08-19, `layer-data-and-hardware-boundaries` §3–6 moved four domains (see `repository/` above) out of `DBDataManager` into dedicated Repositories. §7 then tried to delete the class entirely and found that isn't achievable without expanding scope well beyond what that change ever proposed: `DBDataManager` is still the sole persistence entry point for three domains that were never part of that change —

- **User/Authorization** (`isUserExist`/`chekIsExists`/`checkObjectNotNull`/`findAuthorization`/`removeObject`/`findUser`/`findAdminUser`) — used by `logic/LoginUser.java` (the whole login/register flow) and `fragment/SetBaseFragment.java`.
- **OilTest** (`findTestObject`, plus generic `findAllObject`/`findObjectByID`/`UpdateObject`/`saveObject` called with `OilTest.class`) — used by `fragment/OilTestFragment.java`, `activity/MainActivity.java`.
- **OilSaveInfo** (`findLast`/`findLimitRecord`/the `saveSaveOilRecord(String,String)` overload — a different method from the `saveSaveOilRecord(StockSave)` overload that *did* move to `StockRepository`) — used by `logic/PrintRunable.java`, `service/BackService.java`, `manager/SaveOilManager.java`.

— plus a set of methods that are deliberately cross-domain and were never meant to be split (Gson helpers `stringToArray`/`getListToStringByGson`/`getStringToListByGson`/`parseObjectWithGson`, and generic CRUD `saveObject`/`findAllObject(Class)`/`findObjectByID`/`findLast`/`UpdateObject`/`delteRecord`/`delteAllInOilRecord`/`findLimitRecord`/`findLimit`/`findObject`/`Sort`/`DeleteTable`/`findFirst`/`ResolveInt`/`findBetweenTimeOilInOut`/`findInOilByPos`).

That's not "a few uncategorized leftovers" — it's over half the class and three complete business domains. Renaming it to signal "not for new code" was considered and rejected: it would touch ~15 call sites' type declarations for zero structural change, the same "no-benefit rename" reasoning `design.md` already applied to *not* merging/renaming the two `SerialPortManager` classes. **Decision: `DBDataManager` stays, under its current name, permanently — not a to-be-deleted transitional class.** New code for `OilCanSet`/`OilCanTable`/`ProbeToOil`/`Sideleakage`/`OilCanMapper`/`OilExploration`/`OilInOut`/`OutStock`/`StockSave`/`DetailInoutRecord`/`Alarm`/`CommitWork` goes through the matching Repository; new code for User/Auth, `OilTest`, or `OilSaveInfo`, or anything needing the generic Gson/CRUD helpers, uses `DBDataManager` directly — there is no Repository for those and none is currently planned. If that ever changes, it's new, separately-scoped work, not a continuation of `layer-data-and-hardware-boundaries`.

### Native code (`app/src/main/cpp`)

- `serialport/` — JNI backing for `serialport.jni.SerialPortJNI` (`System.loadLibrary("serialport")`), the low-level RS-232/485 comms with probe hardware.
- `atg/` — JNI backing for `atg.jni.ATGJNI` (`System.loadLibrary("atg")`).
- `bspatch/` — binary-patch based update support (`Bspatch.java`, `System.loadLibrary("timBsPatch")`).

### Base classes

`com.sansen.yeweiyi.base.BaseActivity`/`BaseFragment` are the real base classes real screens extend — they pull in xUtils `HttpUtils`, Baidu Location SDK, Gson/fastjson directly (not MVVM/DataBinding). Don't confuse with the unrelated, unused `base.BaseFragment` in the root `base` package.

## Testing

`app/src/test/` has real tests as of commit `ada562e` — `CRCheckTest` and `DataUtilsTest` (plus the stock `ExampleUnitTest`). They run on plain JVM; no device or Robolectric needed.

**What is and isn't unit-testable here:** `DataUtils` imports `R`/`MyApplication`/`ATGJNI` but has no static initializer block, so class loading never triggers `System.loadLibrary` — its byte/hex helpers test fine on JVM; only `getLitre()` (ATGJNI) and `getOilType()` (MyApplication) must be avoided. `CRCheck` has no `import` lines, but it is *not* dependency-free: line 1480 calls `DataUtils.shortToByte` from the same package, and same-package references need no import. The `factory/` vendor-protocol classes **cannot** be tested on plain JVM — `LDCompany` and friends import `android.content.Context` and `SparseArray`, so testing them needs Robolectric or an extracted pure-parsing layer.

> Grepping for `^import` does **not** establish that a class is self-contained — same-package references are invisible to it. Verify with the compiler, not with grep.

### ⚠️ `git grep` regex silently fails here — use `-F` for deletion safety checks

This environment's `git grep` is ugrep-backed, and two common constructs **match nothing and exit 0** instead of erroring:

- `\|` alternation — `git grep "R\.string\.foo\|@string/foo"` finds nothing, ever
- `\b` word boundary — `git grep -nE "R\.string\.foo\b"` finds nothing, even though `-E` without `\b` finds it

Both produce a *silent false negative*, which is the worst possible failure mode for "is this safe to delete?" checks. A resource deletion in this repo was made on such a bad check and broke the build (`R.string.apkurl` in `LoginHelper.java:67`).

**Before deleting anything, verify references with `git grep -F` (fixed string), one term per invocation.** Reserve regex for exploration, never for a safety gate. `grep -rlw` (system grep, no alternation) has been reliable for whole-word class-name scans.

Also note resources can only be reached by name via `getIdentifier()` — this project has none, so `R.` / `@string/` references are exhaustive.

## Refactor planning uses OpenSpec

`docs/重构方案.md` records the original 5-phase plan and what's been measured/verified so far. As of 2026-08-07, the two not-yet-started phases (阶段2 分层, 阶段3 拆 MainActivity) have been converted into OpenSpec changes under `openspec/changes/` — each has `proposal.md` (why/what/capabilities), `design.md` (decisions/risks), delta `specs/` (behavior contracts), and `tasks.md` (the actual execution checklist; treat this as the source of truth over the prose in 重构方案.md for those two phases). Run `npx @fission-ai/openspec@latest status --change <name>` to check progress, or `openspec list` for an overview. New phases of work on this refactor should go through `openspec change new` rather than free-form doc edits.

## Known landmines

Traps that compile cleanly and fail at runtime — check these before refactoring:

- **`android:onClick` reflection binding — eliminated as of 2026-08-19 (`split-main-activity` §1, PR #22, unmerged as of this writing).** All 34 `res/layout` bindings that used to reference `MainActivity` methods by string name are now `setOnClickListener` calls, compile-time visible. Enumerating them surfaced more than the original plan expected: a dead side-menu (`menu_right` in `activity_main.xml`, always `visibility="gone"`, never toggled from Java, 4 of its 7 bindings pointed at methods that didn't exist at all) was deleted outright; two fully orphaned layouts (`activity_login.xml`, `fragment_recode_storage.xml`, zero references anywhere) were deleted; 6 bindings turned out to already be shadowed by a real `setOnClickListener` set later in the owning Fragment's `findView()` (Android lets the later call win over the XML-declared one), so they were never actually reachable — deleting the dead XML attribute was enough, no new listener needed. Only 18 of the original 34 needed a real conversion. If this file reappears in a landmine-hunting context, treat the pattern (not this specific instance) as the reusable lesson: before assuming a `android:onClick`-bound method is a live crash risk, check whether the same View already has a programmatic listener set elsewhere — it may be inert.
- **Duplicate method pairs.** `DataUtils.HexString2Bytes` / `hexString2Bytes` and `byte2Short` / `byteToShort` are byte-identical duplicates. `CRCheck.crc16` / `crc162` differ *only* in returned byte order (`"4B37"` vs `"374B"`) — picking the wrong one silently produces a bad checksum.
- **Three `bytesToHexString` overloads with different formats.** With `size` → uppercase, space-separated; without → lowercase, no separator; single-byte → lowercase and *not zero-padded* (`0x0A` → `"a"`). `CRCheck.getModBusCRC` is likewise unpadded lowercase.
- **`DataUtils.hexToFloat` throws on any negative float.** It uses `Integer.parseInt(hex, 16)`, which overflows once the IEEE-754 sign bit is set (`-5.0f` = `0xC0A00000`). Currently unreferenced, but this device reads negative temperatures — fix with `Integer.parseUnsignedInt` before using it.
- **`DataUtils.SumCheck` is not a sum.** It XORs all bytes then subtracts `0x80`.
- **Two same-named `SerialPortManager` classes.** `com.sansen.yeweiyi.manager.SerialPortManager` (protocol orchestration: polling, `factory/` vendor dispatch, alarm linkage, UI callback) is not the HAL. `serialport.manager.SerialPortManager` (263 lines, almost no callers) is the real HAL — raw JNI byte send/receive. UI/business code must always go through the `manager/` one; direct calls to the `serialport.manager` one bypass the orchestration layer entirely. As of 2026-08-19 the orchestration one no longer `import`s `BaseActivity` — it calls back through `manager/DataRefreshCallback`, implemented by `BaseActivity` (see `openspec/changes/layer-data-and-hardware-boundaries` §8); the callback re-posts onto `BaseActivity`'s own `Handler` to preserve the original background-thread → main-thread hop.
- **"A listener is set" ≠ "the callback actually runs" — 2026-09-21, found while auditing Spanish (`values-es`) coverage.** Translating a string is wasted effort if the code path that displays it can't be reached, and this codebase has a lot more unreachable code than the `android:onClick` sweep above found. Checking "is a listener registered" is not enough — the listener's *method body* has to be read. The concrete trigger: `InOutAdapter.onBindViewHolder()` sets a real `setOnClickListener`, but its `onClick(View v)` body is empty (`{}`) — short-tapping a 进出库 list row does nothing; only long-press (a different dialog) fires. That made `dialog_detail_inout.xml` and its inflation site (`InOilFragment.onClick(int)`) fully dead despite looking wired end-to-end. A follow-up audit (2 rounds of parallel agents, then manual re-verification of every "reachable" claim against its actual listener/callback body, then a further round the user prompted by naming specific suspects) found 107 more string keys unreachable across 25 locations this way — empty adapter-listener bodies, `Fragment.onHiddenChanged()` that can never fire because the `Fragment` is always freshly `new`'d instead of cached-and-shown, a `switch` branch (`ActivityDialog` `value==7`) nobody ever passes, a `Service` (`BackService`) declared in the manifest but never `startService()`'d, a `BroadcastReceiver` (`UsbStateReceiver`) only ever registered statically in the manifest for `ACTION_MEDIA_MOUNTED`/`ACTION_MEDIA_EJECT` — broadcasts the platform has refused to deliver to manifest-registered receivers since API 24, well under this app's `targetSdkVersion 27` — a data field (`MainFragment.gasExplor`) that's declared but never assigned so the adapter's `getCount()` is permanently 0, and one that looked like the biggest single class but turned out to be a cautionary tale about stopping the consumer-side check too early: `OilMessage.getState()` (called correctly, `setType()` sites genuinely reachable) was first found to have two dead consumers (`AlarmCoordinator`'s target `TextView` sits inside a `visibility="gone"` container that's never flipped visible — same container `activity_main.xml`'s `onLoading` already implicated — and its fallback path forwards into the dead `BackService`) and got marked fully unreachable on that basis — but there was a *third* consumer, `AlarmRepositoryImpl.saveAlarm()`, which stores `oilMessage.toString()` into the `Alarm.info` column that `AlarmAdapter` displays on the alarm-records screen, and it's very much alive (2026-09-23, the user caught this by just asking why the spreadsheet listed those keys as "used" — checking two consumers and stopping is exactly the same mistake as checking zero). Net effect after the correction: 7 of `OilMessage`'s 15 `case`s are reachable this way (`HEIGHT_OIL_ALARM`/`HHEIGHT_OIL_ALARM`/`LOW_OIL_ALARM`/`LLOW_OIL_ALARM`/`HEIGHT_WATER`/`HEIGHT_TEMPER` all flow into `saveAlarm`; `RELAY_DISCONNECT` reaches the DB through a separate direct call in `AsyManager2`'s error listener, gated by a runtime-toggleable preference, not a hardcoded-off branch) — see the corrected, per-`case` reasoning in `OilMessage.java` itself, not this file, before trusting any summary of which ones are live. Every unreachable one is still marked in place with a `[不可达-YYYY-MM-DD]` comment explaining the specific reason (`grep -rn "不可达-202"` to enumerate all of them regardless of date — a further round on 2026-09-23, prompted by the user naming a couple more suspects from the design draft directly, added `item_commit_work.xml`'s 5 keys, marked `[不可达-2026-09-23]` and attributed to the user's product-side confirmation rather than a code trace since the click path there is in fact wired, and confirmed via code that `MinOilCanView`'s `content[0]`/`oil_type_language` is computed but never drawn — the `i==0` loop iteration draws a tank-number badge instead and `continue`s past the generic label/value drawing); before spending translation (or any other) effort on a string, check whether its call site already carries one of these comments, and whether a later `[已更正-YYYY-MM-DD]` comment nearby supersedes an earlier one. If this file reappears in a landmine-hunting or i18n context: re-verifying "reachable" always means opening the class that owns the click/adapter and reading the method body, following the value all the way to wherever it's actually displayed or sent — and when a value has more than one call site, checking *all* of them before concluding "unreachable", not stopping once a couple come back dead. **2026-09-24 addendum, same root mistake in a new shape: a `<string>`'s Java-side `R.string.KEY` references being all-dead does not mean the key itself is dead — it can also be referenced declaratively via `android:text="@string/KEY"` in a layout XML, which is a completely separate consumer invisible to a Java-only grep.** `data_save_success` was deleted from all four `strings.xml` and its `custom_toast.xml` attribute stripped because both of its `R.string.data_save_success` Java call sites (`OilTankRepositoryImpl`'s and `DBDataManager`'s dead export handlers) were correctly dead — but nobody re-checked `custom_toast.xml`'s own inflation site before concluding the *layout* was dead too. It isn't: `BaseFragment.initWindowDialog()` inflates it unconditionally (called from `BaseFragment`'s own init path, so every real screen picks it up) and `showWindDialog()`/`showWindDialog(int)` display it from five live fragments (`BuLuFragment`, `OilCanSetFragment`, `OilTestFragment`, `SetBaseFragment`, `SetPawFragment`) as a generic "operation succeeded" popup — the user caught this by name. The general lesson: when a string is referenced from both Java and a layout's `android:text/hint/title`, its reachability is the *union* of both paths, not just whichever one you happened to grep for first — check `R.string.KEY` in `.java` **and** `@string/KEY` in `.xml` as two independent searches, then verify every layout that turns up by finding *that layout's own* inflate/`setContentView`/`<include>` site, not by assuming "the string's other references are dead so the layout must be too."

Each of these is pinned by an assertion in `CRCheckTest`/`DataUtilsTest`, so changing the behavior turns a test red rather than shipping silently.

## Signing

Release builds are signed via `app/build.gradle`'s `signingConfigs.release`, wired up 2026-08-16. The keystore file itself (`app/keystore/release.jks`) is untracked (`.gitignore`) and generated locally with `tools/generate-release-keystore.sh`; its store/key passwords and alias go through the same `local.properties`-injection channel as the other secrets (`sansen.releaseStorePassword`, `sansen.releaseKeyAlias`, `sansen.releaseKeyPassword` — see `local.properties.template`). If any of the three properties or the keystore file itself is missing, `assembleRelease` still succeeds (unsigned APK, same as before this existed) but logs a `[配置缺失]` warning — it does not hard-fail, matching the project's existing secret-handling convention.

Treat `release.jks` as the app's permanent production identity once generated: Android requires the *same* signing key for in-place upgrades of a given `applicationId`, so losing the file or its passwords means already-deployed devices can only be wiped and reinstalled, never upgraded. Back the keystore and passwords up somewhere outside this checkout (the generation script prints this reminder too).
