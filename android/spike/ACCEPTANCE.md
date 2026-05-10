# Acceptance Log

Current Android direction: flovera agent app with persistent session, workspace, provider config, file tree, WebView preview, and true-device instrumentation coverage.

## Current Gates

- Build: `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`
- True-device tests: install debug APK and androidTest APK, then run `androidx.test.runner.AndroidJUnitRunner`
- Live agent loop: pass provider API key through instrumentation arguments when intentionally testing real model calls

## Archived Route

Historical QEMU/VPS verification notes were moved out of the active app path. Use `docs/archive/legacy-qemu-vps/` and git history for that route.
