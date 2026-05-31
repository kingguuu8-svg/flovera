# GitHub Showcase Draft

This is a draft for the public `README.md`. Do not apply directly until a
stable screenshot/GIF and alpha artifact are ready.

---

# Flovera

Flovera is an Android-local workspace agent.

Ask for a small app, game, spreadsheet, report, or local web demo. Flovera writes
it into a scoped Android workspace, verifies the files it creates, and opens
HTML artifacts in WebView so you can keep iterating without leaving the phone.

> Alpha status: Flovera is usable as a local demo workbench, but it is not a
> VPS replacement or guaranteed always-on automation platform.

![Flovera demo placeholder](docs/assets/flovera-demo-placeholder.png)

## What It Is Good For

- Generate mobile-first HTML demos and open them in Android WebView.
- Build small local tools, games, dashboards, and interactive prototypes.
- Create and inspect files in a scoped phone workspace.
- Generate spreadsheets, documents, and structured outputs with bounded Python.
- Iterate on generated artifacts with persistent conversation history.

## How It Works

1. Open Flovera on Android.
2. Ask the agent for a small artifact.
3. Flovera writes files into the local workspace.
4. The agent validates generated Flovera apps with `artifact_diagnose`.
5. Open the result in WebView or preview supported files directly.
6. Keep chatting to modify the artifact.

## Current Alpha Capabilities

- Persistent sessions and conversation transcript.
- Workspace file read/write/search tools.
- Workspace snapshots for safer iteration.
- Markdown conversation rendering with compact tool/status events.
- HTML/WebView preview and workspace local HTTP preview.
- Portable `flovera.app.json` artifact manifests.
- Workspace-owned Python HTTP backends for local demos.
- Bounded Python runtime for local generation and verification.
- Preview support for HTML, Markdown, JSON, CSV, text, code, images, and PDFs.
- Configurable model providers stored outside source.
- Network tools enabled by default, with Settings controls.
- Brave Search when a Brave Search API key is configured.
- Optional foreground-service keep-alive for longer local work.

## Why Android-Local?

Most coding agents feel like they live somewhere else: a cloud VM, web IDE, or
desktop shell. Flovera explores a smaller loop where the phone itself owns the
workspace, preview surface, permissions, and session history.

That makes Flovera useful when the target output is a demo, local artifact, or
phone-readable web surface rather than a production deployment.

## Boundaries

- Flovera does not promise arbitrary background automation.
- Android background behavior still depends on OS and OEM policy.
- Generated demos can still need iteration.
- WebView compatibility differs from desktop browsers.
- MCP, Git, and general shell-style tools are not part of the first alpha
  boundary.
- Provider API keys and app permissions are app-owned settings, not workspace
  source files.

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

## Verification

Use the protected verifier or update-only APK flow. Avoid uninstalling an
existing Flovera app during verification because app data and permissions are
part of the product state.

## License

Flovera is MIT licensed.

---

## Asset TODO

- Replace placeholder with a real 15-30 second GIF:
  conversation -> file generation -> artifact diagnostics -> WebView preview.
- Add one static screenshot for README fallback.
- Add a known limitations badge or alpha label.

