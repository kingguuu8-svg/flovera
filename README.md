# Flovera

Flovera is an Android-local workspace agent app.

The current product direction is not a phone-hosted Linux VM. Flovera runs an
agent inside an Android app, gives it a scoped local workspace, persists
sessions, and uses WebView as the primary surface for generated HTML/web
artifacts.

## Current Status

This repository is still pre-release. The Android app already supports:

- Persistent agent sessions and conversation history.
- Workspace file read/write tools.
- Workspace file browser with HTML preview in the app WebView.
- Configurable model provider settings stored outside source code.
- Optional network tools behind a user setting.
- Markdown conversation rendering and collapsed tool output.
- Product quality and backlog rules in `PRODUCT_QUALITY.md`.

Planned work includes workspace snapshots, broader agent-controlled settings,
controlled Python runtime support, Brave Search integration, and more workspace
renderers.

## Repository Layout

```text
.
|-- android/spike/                 Android app source
|-- docs/                          Current docs and archived research
|-- docs/archive/legacy-qemu-vps/  Historical QEMU/VPS route
|-- PRODUCT_QUALITY.md             Product quality model and backlog
|-- docs/OPEN_SOURCE_READINESS.md  Open-source readiness checklist
|-- LICENSE                        MIT license
`-- .env.example                   Local environment template
```

The old QEMU/VPS work is kept as archived research. It is not the active product
path.

## Android Build

Requirements:

- Android Studio or Android SDK.
- JDK 17. Android Studio JBR is recommended on Windows.

From `android/spike`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

.\gradlew.bat :app:assembleFloveraDebug :app:assembleFloveraDebugAndroidTest :app:assembleLegacyDebug
```

## Device Verification

Use the protected verifier instead of running raw Gradle connected tests. The
script updates existing app installs and refuses unexpected fresh installs by
default.

From `android/spike`:

```powershell
.\scripts\verify-flovera-android.ps1 -DeviceSerial <adb-serial> -SkipRelease
```

Use `-SkipDevice` for build-only verification.

The app currently has two Android package slots:

- `com.flovera.app` with launcher label `Flovera`
- `com.example.ailinuxvmspike` with launcher label `Flovera legacy`

The legacy slot exists so existing test devices can update an old install
without losing app data.

## Configuration And Secrets

Do not commit API keys, signing files, local paths, generated settings, APKs, or
workspace data.

Runtime provider settings are stored by the Android app, not hardcoded in
source. `.env.example` is only a template for local development.

Before publishing or cutting a release, run the checks in
`docs/OPEN_SOURCE_READINESS.md`.

## License

Flovera is licensed under the MIT License. See `LICENSE`.
