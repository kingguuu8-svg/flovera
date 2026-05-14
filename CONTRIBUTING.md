# Contributing

Flovera is pre-release. Contributions should keep the project focused on the
Android-local workspace agent direction.

## Development Setup

The active app lives in `android/spike`.

Use JDK 17 and the Android SDK. On Windows, Android Studio JBR is recommended:

```powershell
cd android/spike
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
.\gradlew.bat :app:assembleFloveraDebug
```

## Verification

For Android changes, prefer the protected verifier:

```powershell
cd android/spike
.\scripts\verify-flovera-android.ps1 -DeviceSerial <adb-serial> -SkipRelease
```

Use `-SkipDevice` for build-only checks.

Do not run verification in a way that unexpectedly uninstalls a contributor's
main Flovera app or resets app permissions.

## Commit Rules

- Keep commits small and purposeful.
- Use clear commit messages such as `android: add session snapshot restore`.
- Do not mix unrelated implementation, formatting, and documentation changes.
- Do not revert another contributor's work unless the issue and decision are
  explicit.

## Product Rules

Before changing UX or agent behavior, read `PRODUCT_QUALITY.md`.

The current product path is:

- Android-local agent runtime.
- Persistent sessions.
- Scoped workspace.
- WebView preview for generated HTML/web artifacts.
- Configurable providers without hardcoded secrets.
- Explicit tool permissions.

Legacy QEMU/VPS material under `docs/archive/legacy-qemu-vps/` is historical
research, not the current product promise.

## Secret And Artifact Hygiene

Never commit:

- API keys or provider tokens.
- Signing keys or keystores.
- `.env`, `settings.json`, `setting.json`, or local Android `local.properties`.
- Generated APKs, build outputs, logs, or workspace data.
- Private local paths that reveal a contributor's machine layout.

If a secret is committed by mistake, rotate it immediately and report it as a
security issue.
