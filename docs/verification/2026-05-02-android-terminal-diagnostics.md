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
| Device preview | NOT RUN | No Android device was online when this verification was performed. |

## Scope Boundary

This round improves the first-stage terminal presentation but does not implement a real PTY, interactive shell, terminal resizing, cursor handling, or persistent SSH session.
