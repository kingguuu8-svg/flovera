# Android Linux Controls Verification

Date: 2026-05-01

## Purpose

Verify the Android spike change that exposes the first-stage Linux computer controls:

- `Prepare Linux`
- `Start Linux`
- `Pause`
- `Resume`
- `Shutdown`
- terminal command input
- `Run Command`
- log panel

This round validates the Android app surface and control wiring. It does not validate the full Ubuntu cloud guest on Android.

## Commands

```powershell
android info
android describe --project_dir=android/spike
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
android emulator start --cold small_phone
android emulator start --cold Medium_Phone_API_36.1
android run --apks android/spike/app/build/outputs/apk/debug/app-debug.apk --activity com.example.ailinuxvmspike/.MainActivity
```

## Results

| Check | Result | Evidence |
|---|---|---|
| Android CLI available | PASS | `android info` returned SDK path and CLI version. |
| Debug APK build | PASS | `gradlew.bat assembleDebug` exited 0. |
| Unit test task | PASS | `gradlew.bat testDebugUnitTest` exited 0; current task is `NO-SOURCE`. |
| Android CLI describe | FAIL, known tool issue | Windows launcher tries to execute extensionless `gradlew` and returns `CreateProcess error=193`. |
| Emulator UI install/layout | BLOCKED | ADB had no online device after `small_phone`; `Medium_Phone_API_36.1` emulator process died during startup. |

## Emulator Root Cause

The failure is outside the APK install path. The emulator log for `Medium_Phone_API_36.1` reports hanging QEMU threads:

```text
detected a hanging thread 'QEMU2 CPU0 thread'
detected a hanging thread 'QEMU2 CPU1 thread'
detected a hanging thread 'QEMU2 CPU2 thread'
detected a hanging thread 'QEMU2 CPU3 thread'
detected a hanging thread 'QEMU2 CPU4 thread'
detected a hanging thread 'QEMU2 CPU5 thread'
detected a hanging thread 'QEMU2 main loop'
```

## Scope Boundary

The UI and controller compile. Runtime UI layout verification remains blocked by the local emulator environment until an AVD can reach an online ADB state or a real device is connected.
