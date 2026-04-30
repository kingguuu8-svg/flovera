# Android Spike Toolchain

This note records the current Windows toolchain workarounds for the Android spike.

## Build

The default `java.exe` on this host resolves to Java 8. Gradle and the Android
Gradle Plugin require JDK 17+.

Use Android Studio JBR before invoking Gradle:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\android\spike\gradlew.bat -p android\spike :app:assembleDebug --console=plain
.\android\spike\gradlew.bat -p android\spike :app:testDebugUnitTest --console=plain
```

## APK Location

`android describe --project_dir=android/spike` is currently not reliable on this
Windows host. It attempts to execute the Unix wrapper `gradlew` directly and
fails with `CreateProcess error=193`.

Use Gradle output metadata instead:

```powershell
$metadata = Get-Content -Raw -Encoding UTF8 android\spike\app\build\outputs\apk\debug\output-metadata.json | ConvertFrom-Json
$apk = Join-Path 'android\spike\app\build\outputs\apk\debug' $metadata.elements[0].outputFile
$apk
```

Or use the repository helper:

```powershell
.\scripts\android-spike-apk.ps1
```

Expected debug APK:

```text
android\spike\app\build\outputs\apk\debug\app-debug.apk
```

## SDK Paths

Avoid parsing `android info sdk` in PowerShell pipelines on this machine because
the Chinese username can be mojibake in some command output paths.

Prefer:

```powershell
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
```

## Emulator Status

Current emulator attempts with `Medium_Phone_API_36.1` and `PocketCLI_API34`
reach the emulator process but do not reach an online ADB device. Logs show QEMU
hanging threads, for example `QEMU2 main loop` and `QEMU2 CPU* thread`.

Diagnostic commands:

```powershell
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'

& $adb devices -l
& $emulator -list-avds
Get-Content -Tail 120 (Join-Path $env:USERPROFILE '.android\Medium_Phone_API_36.1\emulator.log')
```

If an offline emulator is left behind:

```powershell
& $adb -s emulator-5554 emu kill
& $adb devices -l
```

This is an emulator/host tooling blocker, not evidence that the spike APK has
crashed.

## Android QEMU Runtime

The current temporary QEMU source is the Termux
`qemu-system-aarch64-headless` package line. Stage an app-local runtime with:

```powershell
wsl --cd /mnt/e/main/ai-in-linux bash scripts/stage-termux-qemu-runtime.sh
```

Output:

```text
artifacts/qemu-runtime/app-local/
```

The staged binary is patched to use `$ORIGIN/../lib`, so for the current spike
layout push:

```text
app-local/bin/qemu-system-aarch64 -> files/ai-linux-spike/inputs/qemu-system-aarch64
app-local/lib/                    -> files/ai-linux-spike/lib/
```
