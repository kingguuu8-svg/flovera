# Flovera Design Verification

This document defines the Android verification lane for Flovera frontend design work.

## Goal

Keep design iteration separate from the main Flovera app and test package.

| Lane | Main package | Test package | Launcher label |
|---|---|---|---|
| Main | `com.flovera.app` | `com.flovera.app.test` | `Flovera` |
| Design | `com.flovera.design` | `com.flovera.design.test` | `Flovera Design` |

The design lane exists for mobile UI and design validation without replacing the user's main Flovera install.

## Script

Use:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File D:\main\flovera\android\spike\scripts\verify-flovera-design-android.ps1
```

The script builds:

- `:app:assembleDesignDebug`
- `:app:assembleDesignDebugAndroidTest`

It then verifies:

- main APK package is `com.flovera.design`
- main APK label is `Flovera Design`
- test APK package is `com.flovera.design.test`
- device update preserves `firstInstallTime`
- `com.flovera.design/com.flovera.app.MainActivity` launches
- optional instrumentation runs through `adb shell am instrument`

## Bootstrap Rule

The normal path is update-only and follows the `android-apk-update-only` workflow.

The one-time bootstrap install is allowed only when the design packages are missing:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File D:\main\flovera\android\spike\scripts\verify-flovera-design-android.ps1 -BootstrapInitialInstall -SkipInstrumentation
```

After bootstrap, do not use `-BootstrapInitialInstall` again unless a human explicitly approves a new initial install for a missing design package.

## Update-Only Smoke

Use this after bootstrap for fast design validation:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File D:\main\flovera\android\spike\scripts\verify-flovera-design-android.ps1 -SkipInstrumentation
```

This updates both design APKs through the guarded update-only script and launches the design app.

## Instrumentation Smoke

Use a narrow class filter while iterating:

```powershell
rtk powershell -NoProfile -ExecutionPolicy Bypass -File D:\main\flovera\android\spike\scripts\verify-flovera-design-android.ps1 -InstrumentationClass com.flovera.app.AgentPromptBuilderInstrumentedTest -InstrumentationTimeoutSeconds 180
```

Full instrumentation can be run by omitting `-InstrumentationClass`, but it should be reserved for larger checkpoints because it is slower.

## Rules

- Do not use Gradle `install*` tasks.
- Do not use Gradle `connected*AndroidTest` tasks.
- Do not run raw `adb install` except through the script's explicit one-time `-BootstrapInitialInstall` path.
- Do not uninstall the main Flovera app or reset its permissions.
- Prefer command, semantic, and instrumentation checks over visual clicking.

## Current Verification Evidence

Completed on 2026-05-29:

- `verify-flovera-design-android.ps1 -SkipDevice` passed.
- One-time bootstrap installed `com.flovera.design` and `com.flovera.design.test`.
- Default update-only smoke passed and preserved `firstInstallTime`.
- Launch check passed for `com.flovera.design/com.flovera.app.MainActivity`.
- `AgentPromptBuilderInstrumentedTest` passed: `OK (5 tests)`.
