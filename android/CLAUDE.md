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

`app/build.gradle` reads `local.properties` and injects four values into `BuildConfig` (`PRODUCT_KEY`, `PRODUCT_SECRET`, `REQUEST_ENCRYPT_KEY`, `BASE_URL`); `ApiConfig` just re-exports them. `local.properties` is untracked — copy `local.properties.template` and fill it in. Missing keys log a `[配置缺失]` warning at build time and compile to empty strings (so the app builds but MQTT upload / request encryption silently fail).

Credentials hardcoded before commit `6ae4e3f` are in git history and must be treated as leaked.

Key `app/build.gradle` facts:
- `compileSdkVersion 27`, `minSdkVersion 21`, `targetSdkVersion 27`, landscape-only (see manifest).
- `multiDexEnabled true`, native build via `externalNativeBuild { cmake { path "src/main/cpp/CMakeLists.txt" } }`.
- versioning is vendor-specific (`versionName "NEW.SS.A.Y11.00"`), and the APK output filename is auto-suffixed with version + date + build type.
- The root `build.gradle` still declares a `greendao-gradle-plugin` classpath dependency, but no module applies the `org.greenrobot.greendao` plugin or uses GreenDAO anymore — it's vestigial from the old template and can be ignored (or removed if cleaning up).
- Dependencies are a large pile of local jars in `app/libs/` (Baidu Maps/TTS/Push SDKs, Apache POI 3.10 for Excel export, Tencent TBS X5 webview kernel, MQTT client, AndServer, Apache httpclient/mina, `sun.misc.BASE64Decoder`, an internal `xzjh_interface_*.jar`). Treat these as fixed/vendored artifacts — most have no current Maven-hosted release, so don't try to "upgrade" them without checking hardware/vendor SDK compatibility first.
- Repositories are mirrored through Aliyun (`build.gradle`) for mainland China connectivity — don't remove without checking.

There is no real unit/instrumented test suite.

## Architecture

### App entry & global setup

`com.sansen.yeweiyi.application.MyApplication` (declared in `AndroidManifest.xml`) initializes, in `onCreate()`: crash handling (`CrashApphandler`), file logging (`log.LogCatHelper`), `CRCheck`, the serial port manager (`serialport.manager.SerialPortManager`), the local DB (`DaoHelp`, an xUtils `DbUtils` wrapper), sound alerts (`SoundManager`), and several data managers (`DBDataManager`, `OilcanDataManager`, `SaveOilManager`). It also loads the persisted `LevelmeterInfo` device config and sets up locale/language switching. `MainActivity` (`com.sansen.yeweiyi.activity.MainActivity`) is the sole real Activity and hosts everything else as Fragments (see manifest — landscape-locked).

### Data flow: hardware → app → UI / LAN

- `manager/` — singleton managers: `SerialPortManager` (JNI serial comms with the probe hardware), `SocketManager`/`TCPClient` (network comms with the probe or other devices), `AlarmManager`/`AlarmDetection`/`SoundLightManager` (alarm state + audible/visual alerts), `OilcanDataManager`/`SaveOilManager`/`DBDataManager` (business data + persistence), `MyTimeTask` (polling/scheduling).
- `factory/` — per-vendor protocol parsers for different probe hardware brands (`LDCompany`, `LD2Company`, `VR350Factory`, `ZhongChengCompany`), all implementing shared interfaces (`ICreateTable`, `IParseDataListener`). This is the extension point for adding support for a new probe vendor/protocol.
- `structbean/` — byte-level struct definitions (`*In.java`) for the serial/socket protocol frames exchanged with probe hardware.
- `db/` — plain POJO entities (`OilCanSet`, `OilInOut`, `Alarm`, `User`, `Authorization`, etc.) persisted via xUtils `DbUtils` (see `db/help/DaoHelp.java`), not GreenDAO.
- `fragment/` — the actual UI screens hosted inside `MainActivity` (`MainFragment`, `OilCanFragment`, `InOilFragment`, `OutStockFragment`, `AlarmFragment`, `SetFragment` + sub-settings fragments, `BaoBiaoFragment` for reports, etc.).
- `web/handler/` — request handlers for an embedded AndServer HTTP server (`LoginHandler`, `AlarmHandler`, `OilDischarge`, `UploadHandler`, `FileHandler`, `ServerManager`) so external clients on the LAN can pull data from the device.
- `okGoUntils/` — an OkGo-based networking helper, used alongside (not instead of) xUtils `HttpUtils` — check which a given class actually uses before assuming.

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

## Known landmines

Traps that compile cleanly and fail at runtime — check these before refactoring:

- **`android:onClick` reflection binding.** 18 bindings in `res/layout` reference `MainActivity` methods by string name. Moving or renaming those methods compiles fine and crashes on tap. Convert them to `setOnClickListener` *before* splitting `MainActivity`, so the references become compile-time visible.
- **Duplicate method pairs.** `DataUtils.HexString2Bytes` / `hexString2Bytes` and `byte2Short` / `byteToShort` are byte-identical duplicates. `CRCheck.crc16` / `crc162` differ *only* in returned byte order (`"4B37"` vs `"374B"`) — picking the wrong one silently produces a bad checksum.
- **Three `bytesToHexString` overloads with different formats.** With `size` → uppercase, space-separated; without → lowercase, no separator; single-byte → lowercase and *not zero-padded* (`0x0A` → `"a"`). `CRCheck.getModBusCRC` is likewise unpadded lowercase.
- **`DataUtils.hexToFloat` throws on any negative float.** It uses `Integer.parseInt(hex, 16)`, which overflows once the IEEE-754 sign bit is set (`-5.0f` = `0xC0A00000`). Currently unreferenced, but this device reads negative temperatures — fix with `Integer.parseUnsignedInt` before using it.
- **`DataUtils.SumCheck` is not a sum.** It XORs all bytes then subtracts `0x80`.

Each of these is pinned by an assertion in `CRCheckTest`/`DataUtilsTest`, so changing the behavior turns a test red rather than shipping silently.

## Signing

No keystore is currently checked into the repo (the previous template's `app/xinchen.jks` is gone). If one is (re)added, treat keystore credentials as sensitive — don't hardcode passwords in committed files.
