# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

**安卓液位仪 (Android Liquid Level Meter)** — a native Android app, package `com.sansen.yeweiyi`, that runs on a dedicated landscape-orientation hardware terminal for monitoring oil storage tanks (油罐). It talks to physical liquid-level probes over RS-232/485 serial and/or TCP socket, decodes vendor-specific binary protocols, persists readings locally, and exposes an embedded HTTP server so other devices on the LAN (e.g. a PC) can query the data.

This was previously scaffolded from the `goldze/MVVMHabit` template (Java, MVVM + DataBinding + RxJava2), but as of the current code that template has been **entirely superseded** — the `mvvmhabit` module is gone, DataBinding/RxJava-first architecture is gone, and this is now a self-contained, older-style Android app (compileSdk 27, minSdk 21) built around singleton managers rather than MVVM.

### Modules (`settings.gradle`)

- `app/` — the application, package `com.sansen.yeweiyi`. All real business logic lives here.
- `xutilslibrary/` — a vendored copy of the old **xUtils** framework (`com.lidroid.xutils`: ORM `DbUtils`, `HttpUtils`, view-injection). Used as the local persistence/HTTP layer instead of GreenDAO or Retrofit-first code.

### Orphaned directories — do not edit

The repo root also contains loose `java/` and `src/` directories that are **not referenced by `settings.gradle` or any build.gradle** — they're stray duplicate copies of `app/src/main/java` and `xutilslibrary/src/main/java` (contents mostly but not exactly identical to the real sources). They aren't compiled and aren't the source of truth; always edit the real module paths under `app/src/main/java/...` and `xutilslibrary/src/main/java/...`.

Within `app/src/main/java` itself there are also several non-namespaced top-level packages that predate the `com.sansen.yeweiyi` package (`asynmsg`, `bean`, `recycle`, `storage`, `rxjava`, `log`, `base`, `serialport`, `atg`, `com.example.x6.gpioctl`). These *are* compiled and in use (e.g. `log.LogCatHelper`, `serialport.manager.SerialPortManager` are referenced from `MyApplication`), but note there are two different, unrelated `BaseFragment` classes — `base.BaseFragment`/`base.BaseVM` (root package, appears to be dead/unused leftover) vs. `com.sansen.yeweiyi.base.BaseFragment` (the one actually extended by real screens). Check imports carefully before assuming which one a file uses.

## Build

No `gradlew` wrapper was present before; it now is (`gradlew`/`gradlew.bat`, still targeting Gradle 6.7.1 via the Tencent Cloud mirror in `gradle/wrapper/gradle-wrapper.properties`).

```bash
# Build debug APK
./gradlew :app:assembleDebug
```

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

`com.sansen.yeweiyi.base.BaseActivity`/`BaseFragment` are the real base classes real screens extend — they pull in xUtils `HttpUtils`, Baidu Location SDK, Gson/fastjson directly (not MVVM/DataBinding). Don't confuse with the unrelated, apparently-unused `base.BaseFragment`/`base.BaseVM` in the root `base` package (see "Orphaned directories" above).

## Signing

No keystore is currently checked into the repo (the previous template's `app/xinchen.jks` is gone). If one is (re)added, treat keystore credentials as sensitive — don't hardcode passwords in committed files.
