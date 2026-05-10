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

function Assert-InstalledPackage {
  param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$PackageName
  )

  $PackageDump = & $AdbPath "-s" $Serial "shell" "dumpsys" "package" $PackageName 2>&1
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $PackageText = $PackageDump -join "`n"
  if ($PackageText -notmatch "Package \[$([regex]::Escape($PackageName))\]") {
    Write-Error "Package was not installed: $PackageName"
    exit 1
  }

  $Version = ($PackageDump | Select-String -Pattern "versionCode=" | Select-Object -First 1).Line.Trim()
  $LastUpdate = ($PackageDump | Select-String -Pattern "lastUpdateTime=" | Select-Object -First 1).Line.Trim()
  Write-Host "$PackageName $Version $LastUpdate"
}

function Assert-LaunchesMainActivity {
  param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$PackageName
  )

  $Component = "$PackageName/com.flovera.app.MainActivity"
  $LaunchOutput = & $AdbPath "-s" $Serial "shell" "am" "start" "-W" "-n" $Component 2>&1
  $LaunchOutput | ForEach-Object { Write-Host $_ }
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $LaunchText = $LaunchOutput -join "`n"
  if ($LaunchText -notmatch "Status: ok") {
    Write-Error "Failed to launch $Component"
    exit 1
  }
  & $AdbPath "-s" $Serial "shell" "am" "force-stop" $PackageName
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$Gradle = Join-Path $ProjectRoot "gradlew.bat"
Push-Location $ProjectRoot
try {
  & $Gradle ":app:assembleFloveraDebug" ":app:assembleFloveraDebugAndroidTest" ":app:assembleLegacyDebug"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

  if (-not $SkipRelease) {
    & $Gradle ":app:assembleFloveraRelease" ":app:assembleLegacyRelease"
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

  $FloveraDebugApk = Join-Path $ProjectRoot "app\build\outputs\apk\flovera\debug\app-flovera-debug.apk"
  $LegacyDebugApk = Join-Path $ProjectRoot "app\build\outputs\apk\legacy\debug\app-legacy-debug.apk"
  $AndroidTestApk = Join-Path $ProjectRoot "app\build\outputs\apk\androidTest\flovera\debug\app-flovera-debug-androidTest.apk"
  & $Adb "-s" $DeviceSerial "install" "-r" "-t" "-d" "-g" $FloveraDebugApk
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & $Adb "-s" $DeviceSerial "install" "-r" "-t" "-d" "-g" $LegacyDebugApk
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  & $Adb "-s" $DeviceSerial "install" "-r" "-t" "-d" "-g" $AndroidTestApk
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  Assert-InstalledPackage -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.flovera.app"
  Assert-InstalledPackage -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.example.ailinuxvmspike"
  Assert-LaunchesMainActivity -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.flovera.app"
  Assert-LaunchesMainActivity -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.example.ailinuxvmspike"

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
