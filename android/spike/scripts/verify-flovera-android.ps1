param(
  [string]$DeviceSerial = "",
  [switch]$SkipDevice,
  [switch]$SkipRelease
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$DefaultJbr = "C:\Program Files\Android\Android Studio\jbr"
$DefaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"

if (-not $env:JAVA_HOME -and (Test-Path $DefaultJbr)) {
  $env:JAVA_HOME = $DefaultJbr
}
if (-not $env:ANDROID_HOME -and (Test-Path $DefaultSdk)) {
  $env:ANDROID_HOME = $DefaultSdk
}
if (-not $env:ANDROID_HOME) {
  throw "ANDROID_HOME is not set and the default SDK path was not found."
}

$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if ($env:JAVA_HOME) {
  $env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
}
$env:PATH = (Join-Path $env:ANDROID_HOME "platform-tools") + ";" + $env:PATH

$Gradle = Join-Path $ProjectRoot "gradlew.bat"
Push-Location $ProjectRoot
try {
  & $Gradle ":app:assembleDebug" ":app:assembleDebugAndroidTest"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

  if (-not $SkipRelease) {
    & $Gradle ":app:assembleRelease"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  }

  if ($SkipDevice) {
    Write-Host "Device verification skipped."
    exit 0
  }

  $Adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
  if (-not (Test-Path $Adb)) {
    throw "adb.exe was not found under ANDROID_HOME."
  }

  if (-not $DeviceSerial) {
    $DeviceSerial = & $Adb "devices" |
      Select-String -Pattern "^[^\s]+\s+device$" |
      ForEach-Object { ($_ -split "\s+")[0] } |
      Select-Object -First 1
  }
  if (-not $DeviceSerial) {
    throw "No online Android device found. Pass -DeviceSerial or use -SkipDevice."
  }

  $DebugApk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
  $AndroidTestApk = Join-Path $ProjectRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
  & $Adb "-s" $DeviceSerial "install" "-r" "-t" "-d" "-g" $DebugApk
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & $Adb "-s" $DeviceSerial "install" "-r" "-t" "-d" "-g" $AndroidTestApk
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $InstrumentationOutput = & $Adb "-s" $DeviceSerial "shell" "am" "instrument" "-w" "-r" "com.flovera.app.test/androidx.test.runner.AndroidJUnitRunner" 2>&1
  $InstrumentationOutput | ForEach-Object { Write-Host $_ }
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $InstrumentationText = $InstrumentationOutput -join "`n"
  if ($InstrumentationText -match "FAILURES!!!" -or
      $InstrumentationText -match "There were \d+ failures" -or
      $InstrumentationText -match "INSTRUMENTATION_STATUS_CODE: -2" -or
      $InstrumentationText -match "INSTRUMENTATION_RESULT: shortMsg=") {
    exit 1
  }
  if ($InstrumentationText -notmatch "OK \(\d+ tests\)") {
    Write-Error "Instrumentation finished without an OK test summary."
    exit 1
  }
} finally {
  Pop-Location
}
