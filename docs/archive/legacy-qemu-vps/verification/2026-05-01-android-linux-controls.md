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

## Real Device Follow-up

Date: 2026-05-02

Target device:

- Serial: `e9512097`
- Model: `RMX3841`
- Android SDK: `36`
- ABI: `arm64-v8a,armeabi-v7a,armeabi`
- Power: USB powered
- Battery: 14-16%, explicitly overridden by the user with `--allow-low-battery`

Commands:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug

bash scripts/verify-android-spike-device.sh `
  --device e9512097 `
  --apk android/spike/app/build/outputs/apk/debug/app-debug.apk `
  --allow-low-battery

android layout --device=e9512097 --pretty
android screen capture --output=artifacts/android-spike/2026-05-01-device-controls-after-stop.png
```

Results:

| Check | Result | Evidence |
|---|---|---|
| Device preflight | PASS | Device was online, SDK 36, arm64, USB powered, `run-as` available. |
| Low battery override | PASS with explicit user approval | The script was run with `--allow-low-battery` after the user said to ignore battery. |
| APK install and private input staging | PASS | `verify-android-spike-device.sh` installed the APK and staged `QEMU_EFI.fd`, `vmlinuz-virt`, `ai-linux-aarch64.cpio.gz`, and `id_ed25519` into the app private input directory. |
| UI layout | PASS | `android layout --device=e9512097 --pretty` showed `Prepare Linux`, `Start Linux`, `Pause`, `Resume`, `Shutdown`, `Terminal command`, and `Run Command`. |
| Start Linux | PASS | UI log showed `Linux process started.` and guest kernel boot output. |
| Terminal command | PASS | `Run Command` waited for SSH readiness, then `echo ready` logged `stdout: ready` and `Ready probe succeeded.` |
| Pause | PASS | QMP returned a `STOP` event and UI status changed to `paused`. |
| Resume | PASS | QMP returned a `RESUME` event and UI status changed to `running`. |
| Shutdown | PASS after fix | Shutdown used QMP `quit`, logged `SHUTDOWN`, `Linux process exited. exitCode=0`, and `Linux stopped. exitCode=0`. |
| Foreground state after shutdown | PASS | `mCurrentFocus` and `mFocusedApp` stayed on `com.example.ailinuxvmspike/.MainActivity`. |
| Residual QEMU process check | PASS | `adb shell ps -A` showed only the app process, with no matching QEMU/VM child process. |

Fixes made during real-device verification:

- Shutdown now keeps the process reference until cleanup finishes, prefers QMP `quit`, waits for process exit, and always writes `Stopped` plus a terminal log line even on fallback paths.
- Terminal command execution now waits and retries for SSH readiness instead of assuming dropbear is ready immediately after `Linux process started.`

Evidence files were captured under `artifacts/android-spike/` and remain ignored by git.

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

The emulator remains blocked by the local AVD environment. Real-device verification is the current reliable Android behavior gate for this spike.
