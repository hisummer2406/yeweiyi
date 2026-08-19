## 1. SerialPortManager: guard the LD2Company dispatch

- [x] 1.1 In `manager/SerialPortManager.java`, wrap the `case 2` (`LD2Company`) `probeInfoPase` call in a try/catch matching `case 0`/`case 1`'s existing pattern — `Log.e(TAG, ...)` including 油罐位 (`mOilCan.getOilCanPos()`) and the raw frame (`data`), no rethrow.
- [x] 1.2 Confirm `case 0`/`case 1`'s existing try/catch blocks are untouched — this is an additive change to `case 2` only.

## 2. VR350Factory: guard the PC-protocol handler thread

- [x] 2.1 In `factory/VR350Factory.java`, wrap the `initData((String) msg.obj)` call inside `handler`'s `handleMessage` override in a try/catch, `Log.e` logging the exception with the raw message payload.
- [x] 2.2 Confirm the `HandlerThread("VR350Factory")` and its `Handler` construction are otherwise untouched — this only adds a catch boundary around the existing `initData` call.

## 3. Verify

- [x] 3.1 `./gradlew :app:assembleDebug` — confirms compilation.
- [x] 3.2 `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`, then read `app/build/test-results/testDebugUnitTest/*.xml` to confirm a fresh pass (not a stale UP-TO-DATE no-op) with no new failures.
- [x] 3.3 `git diff --stat` — confirm only `SerialPortManager.java` and `VR350Factory.java` were touched, and the diff in each is limited to the new try/catch (no unrelated changes).
- [ ] 3.4 Real-device verification (cannot be done on this dev machine — no physical LD2 probe or PC-protocol link): before shipping, trigger a malformed LD2Company frame and a malformed VR350 PC-protocol frame on a real device/test rig, and confirm the app logs the failure and keeps running instead of crashing or going silent. Mark this task's completion only once someone with real hardware has actually done this — do not check it off from this checkout.
