# Android Terminal Diagnostics Split Verification

Date: 2026-05-02

## Purpose

Verify the Android UI change that separates the user-facing terminal from backend diagnostics.

The problem fixed in this round: QEMU launch details, kernel output, SSH/JSch negotiation, lifecycle events, and user command output were all appended to one log panel. That made the App feel like a debug console instead of a VPS-like Linux terminal.

## Commands

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
git diff --check
```

## Results

| Check | Result | Evidence |
|---|---|---|
| Debug APK build | PASS | `gradlew.bat assembleDebug` exited 0. |
| Unit task | PASS | `gradlew.bat testDebugUnitTest` exited 0; current local unit task has `NO-SOURCE`. |
| Whitespace check | PASS | `git diff --check` exited 0. |
| Terminal/diagnostics split | PASS by static inspection | `VmUiState` now has `terminalText` and `diagnosticsText` instead of one `logText`. |
| User command output path | PASS by static inspection | `Run Command` appends prompt, stdout/stderr, exit code, and `Ready probe succeeded.` to Terminal. |
| Backend log path | PASS by static inspection | QEMU command details, kernel stdout/stderr, JSch logs, SSH readiness, and QMP responses append to Diagnostics. |
| Device preview | PASS after follow-up | RMX3841 / Android SDK 36 / arm64 real-device preview passed after keeping lifecycle messages out of Terminal. |

## Real Device Follow-up

Target device:

- Serial: `e9512097`
- Model: `RMX3841`
- Android SDK: `36`
- ABI: `arm64-v8a,armeabi-v7a,armeabi`
- Power: USB powered
- Battery: 54-55%

Commands:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest

bash scripts/verify-android-spike-device.sh `
  --device e9512097 `
  --apk android/spike/app/build/outputs/apk/debug/app-debug.apk

android layout --device=e9512097 --pretty
```

Real-device results:

| Check | Result | Evidence |
|---|---|---|
| APK install and private input staging | PASS | `verify-android-spike-device.sh` installed the debug APK and staged the existing QEMU/firmware/kernel/initramfs/key inputs. |
| Static UI split | PASS | `android layout` showed `Terminal`, `Diagnostics`, `root@ai-linux:~#`, and `Diagnostics ready.` as separate visible regions. |
| Start Linux | PASS | UI status reached `Linux status: running`; Diagnostics showed `Linux process started.` |
| Terminal command output | PASS | Terminal showed `root@ai-linux:~# echo ready`, `ready`, `[exit 0]`, and `Ready probe succeeded.` |
| Terminal lifecycle noise | PASS after fix | Terminal no longer showed `[system] Starting Linux` or `[system] Linux started`. |
| Diagnostics retention | PASS | Diagnostics still contained kernel/QEMU diagnostic output. |
| Shutdown cleanup | PASS | `Shutdown` returned to `Linux status: stopped`; `adb shell ps -A` showed only the App process and no QEMU child process. |

Evidence screenshots were captured under `artifacts/android-spike/` and remain ignored by git.

## Scope Boundary

This round improves the first-stage terminal presentation but does not implement a real PTY, interactive shell, terminal resizing, cursor handling, or persistent SSH session.
