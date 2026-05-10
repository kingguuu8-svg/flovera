# flovera Android App

Android app prototype for the local flovera agent product path.

## Scope

- Native Android shell built with Kotlin and Jetpack Compose.
- Koog-backed agent loop with workspace file tools and optional network tools.
- Persistent sessions, conversation history, provider settings, and selected workspace HTML display.
- WebView as the first-class workspace preview surface.

Legacy QEMU/VPS work is archived under `docs/archive/legacy-qemu-vps/` and is no longer part of this Android app source set.

## Build

Use Android Studio JBR on Windows:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
.\gradlew.bat :app:assembleFloveraDebug :app:assembleFloveraDebugAndroidTest :app:assembleLegacyDebug
```

## True-Device Verification

Run the standard verification gate from this directory:

```powershell
.\scripts\verify-flovera-android.ps1 -DeviceSerial <adb-serial>
```

Use `-SkipDevice` for build-only verification, or `-SkipRelease` when iterating on debug-only UI/test changes.

The verifier installs both Android package slots:

- `com.flovera.app`, launcher label `Flovera`
- `com.example.ailinuxvmspike`, launcher label `Flovera legacy`, for devices that still have the pre-rename flovera install.
