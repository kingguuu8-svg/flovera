# Flovera

<p align="center">
  <img src="docs/assets/flovera-wordmark.svg" alt="Flovera Preview" width="760">
</p>

<p align="center">
  <a href="README.zh-CN.md"><img src="docs/assets/badges/lang-zh-cn.svg" alt="中文 README"></a>
  <a href="LICENSE"><img src="docs/assets/badges/license-mit.svg" alt="MIT License"></a>
  <img src="docs/assets/badges/status-preview.svg" alt="Flovera Preview">
  <img src="docs/assets/badges/platform-android.svg" alt="Android-local">
  <img src="docs/assets/badges/deepseek-tested.svg" alt="Tested with DeepSeek API">
</p>

<p align="center">
  Android-local workspace agent for creating and previewing small demos on your phone.
</p>

Flovera is an Android-local workspace agent app.

It gives an AI agent a scoped workspace on your phone, lets the agent create and
modify files there, and opens generated HTML/web artifacts inside Android
WebView. The first public version is positioned as **Flovera Preview**: a small,
local demo workbench for creating and iterating on phone-readable artifacts.

Flovera is not a VPS replacement, a general phone automation framework, or an
always-on background worker. Its current value is the tight loop between chat,
files, verification, and preview on one Android device.

## What You Can Build

- Mobile-first HTML demos that open directly in Android WebView.
- Small games, dashboards, calculators, reports, and interactive prototypes.
- Local workspace artifacts such as Markdown, JSON, CSV, text, code, images,
  and PDFs.
- Bounded Python-generated outputs when a task needs scripting, calculation, or
  structured file generation.

## How It Works

1. Open Flovera on Android.
2. Ask the agent to create a small artifact.
3. The agent reads, writes, searches, and edits files inside the scoped
   workspace.
4. Flovera validates generated app manifests and preview targets.
5. Open the result in the app WebView or supported file preview.
6. Continue the conversation to revise the artifact.

The intended loop is:

```text
chat -> workspace files -> diagnostics -> WebView preview -> revision
```

## Current Preview Capabilities

- Persistent sessions and conversation history.
- Chronological conversation rendering with compact tool and status events.
- Workspace file read, write, edit, list, and search tools.
- Workspace snapshots for safer iteration.
- HTML/WebView preview and workspace-local HTTP preview.
- `flovera.app.json` manifests for generated workspace apps.
- `artifact_diagnose` checks for generated Flovera app registration.
- Controlled Python runtime for bounded local generation and verification.
- Preview support for HTML, Markdown, JSON, CSV, text, code, images, and PDFs.
- Configurable model providers stored in app settings, not source code.
- Network tools enabled by default, with settings controls.
- Brave Search support when a Brave Search API key is configured.

## Boundaries

- Android background behavior still depends on OS and device vendor policy.
- Generated demos can still require more than one iteration.
- Android WebView behavior can differ from desktop browsers.
- Provider API keys and app permissions belong to Flovera app settings, not the
  workspace source tree.
- Most Preview-stage testing is done with the official DeepSeek API. Other
  provider configuration options exist, but Flovera does not currently guarantee
  that they work correctly.
- MCP, Git, and general shell-style workspace tooling are not part of the first
  Preview boundary.

## Repository Layout

```text
.
|-- android/spike/                 Android app source
|-- docs/                          Project and release notes
|-- examples/                      Example material
|-- scripts/                       Repository scripts
|-- PRODUCT_QUALITY.md             Product quality model and internal backlog
|-- CHANGELOG.md                   User-facing change notes
|-- THIRD_PARTY_NOTICES.md         Dependency notice summary
`-- LICENSE                        MIT license
```

## Build

Requirements:

- Android Studio or Android SDK.
- JDK 17. Android Studio JBR is recommended on Windows.

From `android/spike`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

.\gradlew.bat :app:assembleFloveraDebug :app:assembleFloveraDebugAndroidTest
```

## Device Verification

Use the protected verifier instead of uninstalling the app during verification.
Flovera app data, permissions, provider settings, and sessions are part of the
product state.

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

Runtime provider settings are stored by the Android app. `.env.example` is only
a local development template.

## License

Flovera is licensed under the MIT License. See [LICENSE](LICENSE).

Flovera uses [JetBrains Koog](https://github.com/JetBrains/koog) as the upstream
agent runtime framework. Koog is licensed under the Apache License 2.0. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency notes.
