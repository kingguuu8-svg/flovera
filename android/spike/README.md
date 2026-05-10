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
$env:ANDROID_HOME='C:\Users\中二哲人\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

## True-Device Verification

Install and run the current instrumentation suite on a connected device:

```powershell
adb install -r -t -d -g app\build\outputs\apk\debug\app-debug.apk
adb install -r -t -d -g app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -r com.example.ailinuxvmspike.test/androidx.test.runner.AndroidJUnitRunner
```
