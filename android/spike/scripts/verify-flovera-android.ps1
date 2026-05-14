param(
  [string]$DeviceSerial = "",
  [string]$InstrumentationClass = "",
  [int]$InstrumentationTimeoutSeconds = 240,
  [switch]$SkipDevice,
  [switch]$SkipRelease,
  [switch]$AllowFreshInstall
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

function Get-InstalledPackageInfo {
  param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$PackageName
  )

  $PackageDump = & $AdbPath "-s" $Serial "shell" "dumpsys" "package" $PackageName 2>&1
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $PackageText = $PackageDump -join "`n"
  if ($PackageText -notmatch "Package \[$([regex]::Escape($PackageName))\]") {
    return $null
  }

  $FirstInstall = ($PackageDump | Select-String -Pattern "firstInstallTime=" | Select-Object -First 1).Line.Trim()
  $LastUpdate = ($PackageDump | Select-String -Pattern "lastUpdateTime=" | Select-Object -First 1).Line.Trim()
  return [pscustomobject]@{
    FirstInstallTime = $FirstInstall
    LastUpdateTime = $LastUpdate
  }
}

function Install-PackageUpdate {
  param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$PackageName,
    [string]$ApkPath,
    [string[]]$InstallArgs,
    [switch]$AllowFreshInstall
  )

  $Before = Get-InstalledPackageInfo -AdbPath $AdbPath -Serial $Serial -PackageName $PackageName
  if (-not $Before -and -not $AllowFreshInstall) {
    throw "$PackageName is not installed on $Serial. Refusing fresh install because device verification must preserve app permissions and data. Install it manually once or pass -AllowFreshInstall deliberately."
  }

  & $AdbPath "-s" $Serial "install" @InstallArgs $ApkPath
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

  $After = Get-InstalledPackageInfo -AdbPath $AdbPath -Serial $Serial -PackageName $PackageName
  if (-not $After) {
    throw "$PackageName was not found after install."
  }
  if ($Before -and $Before.FirstInstallTime -and $After.FirstInstallTime -ne $Before.FirstInstallTime) {
    throw "$PackageName firstInstallTime changed during verification. This indicates a reinstall, not an update."
  }
}

function Find-Aapt {
  $BuildToolsRoot = Join-Path $env:ANDROID_HOME "build-tools"
  $Aapt = Get-ChildItem -Path $BuildToolsRoot -Recurse -Filter "aapt.exe" |
    Sort-Object FullName -Descending |
    Select-Object -First 1
  if (-not $Aapt) {
    throw "aapt.exe was not found under ANDROID_HOME build-tools."
  }
  return $Aapt.FullName
}

function Assert-ApkLabel {
  param(
    [string]$AaptPath,
    [string]$ApkPath,
    [string]$ExpectedLabel
  )

  $Badging = & $AaptPath "dump" "badging" $ApkPath 2>&1
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $BadgingText = $Badging -join "`n"
  if ($BadgingText -notmatch "application-label:'$([regex]::Escape($ExpectedLabel))'") {
    Write-Error "Unexpected APK label for $ApkPath. Expected '$ExpectedLabel'."
    exit 1
  }
  Write-Host "$ApkPath label=$ExpectedLabel"
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

function Invoke-AdbInstrumentation {
  param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$Runner,
    [string]$ClassFilter,
    [int]$TimeoutSeconds
  )

  $OutputFile = Join-Path ([System.IO.Path]::GetTempPath()) "flovera-instrumentation-$([System.Guid]::NewGuid().ToString('N')).out"
  $ExitFile = Join-Path ([System.IO.Path]::GetTempPath()) "flovera-instrumentation-$([System.Guid]::NewGuid().ToString('N')).exit"
  $Arguments = @("-s", $Serial, "shell", "am", "instrument", "-w", "-r")
  if ($ClassFilter) {
    $Arguments += @("-e", "class", $ClassFilter)
  }
  $Arguments += $Runner

  Write-Host "Running instrumentation on $Serial with timeout ${TimeoutSeconds}s"
  if ($ClassFilter) {
    Write-Host "Instrumentation class filter: $ClassFilter"
  }

  $Job = Start-Job -ScriptBlock {
    param(
      [string]$InnerAdbPath,
      [string[]]$InnerArguments,
      [string]$InnerOutputFile,
      [string]$InnerExitFile
    )
    & $InnerAdbPath @InnerArguments *> $InnerOutputFile
    Set-Content -LiteralPath $InnerExitFile -Value $LASTEXITCODE
  } -ArgumentList $AdbPath, $Arguments, $OutputFile, $ExitFile

  $Completed = Wait-Job -Job $Job -Timeout $TimeoutSeconds
  if (-not $Completed) {
    Stop-Job -Job $Job
    Remove-Job -Job $Job -Force
    $Output = if (Test-Path $OutputFile) { Get-Content $OutputFile } else { @() }
    $Output | Select-Object -Last 80 | ForEach-Object { Write-Host $_ }
    throw "Instrumentation did not finish within ${TimeoutSeconds}s. Last output was printed above."
  }
  Receive-Job -Job $Job | Out-Null
  Remove-Job -Job $Job

  $Output = if (Test-Path $OutputFile) { Get-Content $OutputFile } else { @() }
  $Output | ForEach-Object { Write-Host $_ }

  Remove-Item -LiteralPath $OutputFile -Force -ErrorAction SilentlyContinue
  $ExitCode = if (Test-Path $ExitFile) { [int](Get-Content $ExitFile | Select-Object -First 1) } else { 1 }
  Remove-Item -LiteralPath $ExitFile -Force -ErrorAction SilentlyContinue

  if ($ExitCode -ne 0) { exit $ExitCode }
  return $Output -join "`n"
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

  $FloveraDebugApk = Join-Path $ProjectRoot "app\build\outputs\apk\flovera\debug\app-flovera-debug.apk"
  $LegacyDebugApk = Join-Path $ProjectRoot "app\build\outputs\apk\legacy\debug\app-legacy-debug.apk"
  $FloveraReleaseApk = Join-Path $ProjectRoot "app\build\outputs\apk\flovera\release\app-flovera-release-unsigned.apk"
  $LegacyReleaseApk = Join-Path $ProjectRoot "app\build\outputs\apk\legacy\release\app-legacy-release-unsigned.apk"
  $AndroidTestApk = Join-Path $ProjectRoot "app\build\outputs\apk\androidTest\flovera\debug\app-flovera-debug-androidTest.apk"
  $Aapt = Find-Aapt
  Assert-ApkLabel -AaptPath $Aapt -ApkPath $FloveraDebugApk -ExpectedLabel "Flovera"
  Assert-ApkLabel -AaptPath $Aapt -ApkPath $LegacyDebugApk -ExpectedLabel "Flovera legacy"
  if (-not $SkipRelease) {
    Assert-ApkLabel -AaptPath $Aapt -ApkPath $FloveraReleaseApk -ExpectedLabel "Flovera"
    Assert-ApkLabel -AaptPath $Aapt -ApkPath $LegacyReleaseApk -ExpectedLabel "Flovera legacy"
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

  Install-PackageUpdate -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.flovera.app" -ApkPath $FloveraDebugApk -InstallArgs @("-r", "-t", "-d") -AllowFreshInstall:$AllowFreshInstall
  Install-PackageUpdate -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.example.ailinuxvmspike" -ApkPath $LegacyDebugApk -InstallArgs @("-r", "-t", "-d") -AllowFreshInstall:$AllowFreshInstall
  & $Adb "-s" $DeviceSerial "install" "-r" "-t" "-d" $AndroidTestApk
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  Assert-InstalledPackage -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.flovera.app"
  Assert-InstalledPackage -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.example.ailinuxvmspike"
  Assert-LaunchesMainActivity -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.flovera.app"
  Assert-LaunchesMainActivity -AdbPath $Adb -Serial $DeviceSerial -PackageName "com.example.ailinuxvmspike"

  $InstrumentationText = Invoke-AdbInstrumentation `
    -AdbPath $Adb `
    -Serial $DeviceSerial `
    -Runner "com.flovera.app.test/androidx.test.runner.AndroidJUnitRunner" `
    -ClassFilter $InstrumentationClass `
    -TimeoutSeconds $InstrumentationTimeoutSeconds
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
