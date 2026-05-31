param(
  [string]$DeviceSerial = "",
  [string]$InstrumentationClass = "",
  [int]$InstrumentationTimeoutSeconds = 240,
  [switch]$SkipDevice,
  [switch]$SkipInstrumentation,
  [switch]$BootstrapInitialInstall
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$DefaultJbr = "C:\Program Files\Android\Android Studio\jbr"
$DefaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$UpdateOnlyScript = "C:\Users\Administrator\.codex\skills\android-apk-update-only\scripts\update-installed-apk.ps1"
$DesignPackage = "com.flovera.design"
$DesignTestPackage = "com.flovera.design.test"

if (-not $env:JAVA_HOME -and (Test-Path $DefaultJbr)) {
  $env:JAVA_HOME = $DefaultJbr
}
if (-not $env:ANDROID_HOME -and (Test-Path $DefaultSdk)) {
  $env:ANDROID_HOME = $DefaultSdk
}
if (-not $env:ANDROID_HOME) {
  throw "ANDROID_HOME is not set and the default SDK path was not found."
}
if (-not (Test-Path -LiteralPath $UpdateOnlyScript)) {
  throw "APK update-only script was not found: $UpdateOnlyScript"
}

$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
if ($env:JAVA_HOME) {
  $env:PATH = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH
}
$env:PATH = (Join-Path $env:ANDROID_HOME "platform-tools") + ";" + $env:PATH

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

function Get-ApkPackageName {
  param(
    [string]$AaptPath,
    [string]$ApkPath
  )

  $Badging = & $AaptPath "dump" "badging" $ApkPath 2>&1
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $Text = $Badging -join "`n"
  $Match = [regex]::Match($Text, "package: name='([^']+)'")
  if (-not $Match.Success) {
    throw "Could not read package name from APK: $ApkPath"
  }
  return $Match.Groups[1].Value
}

function Assert-ApkPackageAndLabel {
  param(
    [string]$AaptPath,
    [string]$ApkPath,
    [string]$ExpectedPackage,
    [string]$ExpectedLabel
  )

  $Badging = & $AaptPath "dump" "badging" $ApkPath 2>&1
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $Text = $Badging -join "`n"
  if ($Text -notmatch "package: name='$([regex]::Escape($ExpectedPackage))'") {
    throw "Unexpected APK package for $ApkPath. Expected '$ExpectedPackage'."
  }
  if ($Text -notmatch "application-label:'$([regex]::Escape($ExpectedLabel))'") {
    throw "Unexpected APK label for $ApkPath. Expected '$ExpectedLabel'."
  }
  Write-Host "$ApkPath package=$ExpectedPackage label=$ExpectedLabel"
}

function Resolve-DeviceSerial {
  param(
    [string]$AdbPath,
    [string]$ProvidedSerial
  )

  if ($ProvidedSerial) {
    $State = & $AdbPath "-s" $ProvidedSerial "get-state" 2>&1
    if ($LASTEXITCODE -ne 0 -or (($State -join "`n").Trim() -ne "device")) {
      throw "Device is not online: $ProvidedSerial"
    }
    return $ProvidedSerial
  }

  $Devices = & $AdbPath "devices" 2>&1
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $Online = @($Devices |
    Select-String -Pattern "^[^\s]+\s+device$" |
    ForEach-Object { ($_ -split "\s+")[0] })
  if ($Online.Count -eq 0) {
    throw "No online Android device found. Pass -DeviceSerial or use -SkipDevice."
  }
  if ($Online.Count -gt 1) {
    throw "Multiple online Android devices found: $($Online -join ', '). Pass -DeviceSerial."
  }
  return $Online[0]
}

function Start-AdbServerQuietly {
  param([string]$AdbPath)

  $PreviousErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    & $AdbPath "start-server" *> $null
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  } finally {
    $ErrorActionPreference = $PreviousErrorActionPreference
  }
}

function Get-PackageInfo {
  param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$PackageName
  )

  $Dump = & $AdbPath "-s" $Serial "shell" "dumpsys" "package" $PackageName 2>&1
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
  $Text = $Dump -join "`n"
  if ($Text -notmatch "Package \[$([regex]::Escape($PackageName))\]") {
    return $null
  }

  $FirstInstall = ($Dump | Select-String -Pattern "firstInstallTime=" | Select-Object -First 1).Line.Trim()
  $LastUpdate = ($Dump | Select-String -Pattern "lastUpdateTime=" | Select-Object -First 1).Line.Trim()
  $VersionCode = ($Dump | Select-String -Pattern "versionCode=" | Select-Object -First 1).Line.Trim()
  return [pscustomobject]@{
    FirstInstallTime = $FirstInstall
    LastUpdateTime = $LastUpdate
    VersionCode = $VersionCode
  }
}

function Install-OrUpdatePackage {
  param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$ApkPath,
    [string]$PackageName,
    [switch]$IsTestApk
  )

  $Before = Get-PackageInfo -AdbPath $AdbPath -Serial $Serial -PackageName $PackageName
  if ($Before) {
    if ($IsTestApk) {
      & $UpdateOnlyScript `
        -ApkPath $ApkPath `
        -PackageName $PackageName `
        -DeviceSerial $Serial `
        -AdbPath $AdbPath `
        -AllowTestApkUpdateOnly
    } else {
      & $UpdateOnlyScript `
        -ApkPath $ApkPath `
        -PackageName $PackageName `
        -DeviceSerial $Serial `
        -AdbPath $AdbPath
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    return
  }

  if (-not $BootstrapInitialInstall) {
    throw "$PackageName is not installed on $Serial. Default flow is update-only. Re-run once with -BootstrapInitialInstall only for the explicitly approved initial Flovera Design install."
  }

  Write-Host "Bootstrap initial install for isolated Flovera Design package."
  Write-Host "device=$Serial"
  Write-Host "package=$PackageName"
  & $AdbPath "-s" $Serial "install" "-r" "-t" "-d" $ApkPath
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

  $After = Get-PackageInfo -AdbPath $AdbPath -Serial $Serial -PackageName $PackageName
  if (-not $After) {
    throw "$PackageName was not found after bootstrap install."
  }
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
    throw "Failed to launch $Component"
  }
  & $AdbPath "-s" $Serial "shell" "am" "force-stop" $PackageName
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Assert-InstrumentationSucceeded {
  param([string]$InstrumentationText)

  if ($InstrumentationText -match "FAILURES!!!" -or
      $InstrumentationText -match "There were \d+ failures" -or
      $InstrumentationText -match "INSTRUMENTATION_STATUS_CODE: -2" -or
      $InstrumentationText -match "INSTRUMENTATION_RESULT: shortMsg=") {
    exit 1
  }
  if ($InstrumentationText -notmatch "OK \(\d+ tests?\)") {
    throw "Instrumentation finished without an OK test summary."
  }
}

function Invoke-AdbInstrumentation {
  param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$Runner,
    [string]$ClassFilter,
    [int]$TimeoutSeconds
  )

  $OutputFile = Join-Path ([System.IO.Path]::GetTempPath()) "flovera-design-instrumentation-$([System.Guid]::NewGuid().ToString('N')).out"
  $ExitFile = Join-Path ([System.IO.Path]::GetTempPath()) "flovera-design-instrumentation-$([System.Guid]::NewGuid().ToString('N')).exit"
  $Arguments = @("-s", $Serial, "shell", "am", "instrument", "-w", "-r")
  if ($ClassFilter) {
    $Arguments += @("-e", "class", $ClassFilter)
  }
  $Arguments += $Runner

  Write-Host "Running Flovera Design instrumentation on $Serial with timeout ${TimeoutSeconds}s"
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
    & $AdbPath "-s" $Serial "shell" "am" "force-stop" $DesignPackage
    throw "Instrumentation did not finish within ${TimeoutSeconds}s. Last output was printed above."
  }
  Receive-Job -Job $Job | Out-Null
  Remove-Job -Job $Job

  $Output = if (Test-Path $OutputFile) { Get-Content $OutputFile } else { @() }
  $Output | ForEach-Object { Write-Host $_ }
  $OutputText = $Output -join "`n"

  Remove-Item -LiteralPath $OutputFile -Force -ErrorAction SilentlyContinue
  $ExitCode = if (Test-Path $ExitFile) { [int](Get-Content $ExitFile | Select-Object -First 1) } else { 1 }
  Remove-Item -LiteralPath $ExitFile -Force -ErrorAction SilentlyContinue

  if ($ExitCode -ne 0) { exit $ExitCode }
  Assert-InstrumentationSucceeded -InstrumentationText $OutputText
}

function Get-InstrumentationClassFilters {
  param([string]$ProjectRoot)

  $TestRoot = Join-Path $ProjectRoot "app\src\androidTest\java\com\flovera\app"
  $Tests = Get-ChildItem -Path $TestRoot -Filter "*Test.kt" |
    Where-Object { $_.BaseName -ne "WorkspaceLocalHttpDeepSeekSmokeInstrumentedTest" } |
    Sort-Object Name |
    ForEach-Object { "com.flovera.app.$($_.BaseName)" }
  if (-not $Tests) {
    throw "No instrumentation test classes were found under $TestRoot."
  }
  return $Tests
}

$Gradle = Join-Path $ProjectRoot "gradlew.bat"
$DesignDebugApk = Join-Path $ProjectRoot "app\build\outputs\apk\design\debug\app-design-debug.apk"
$DesignAndroidTestApk = Join-Path $ProjectRoot "app\build\outputs\apk\androidTest\design\debug\app-design-debug-androidTest.apk"

Push-Location $ProjectRoot
try {
  & $Gradle ":app:assembleDesignDebug" ":app:assembleDesignDebugAndroidTest" "--no-daemon"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

  $Aapt = Find-Aapt
  Assert-ApkPackageAndLabel -AaptPath $Aapt -ApkPath $DesignDebugApk -ExpectedPackage $DesignPackage -ExpectedLabel "Flovera Design"
  $TestApkPackage = Get-ApkPackageName -AaptPath $Aapt -ApkPath $DesignAndroidTestApk
  if ($TestApkPackage -ne $DesignTestPackage) {
    throw "Unexpected design androidTest package: $TestApkPackage. Expected $DesignTestPackage."
  }
  Write-Host "$DesignAndroidTestApk package=$DesignTestPackage"

  if ($SkipDevice) {
    Write-Host "Device verification skipped."
    exit 0
  }

  $Adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
  if (-not (Test-Path $Adb)) {
    throw "adb.exe was not found under ANDROID_HOME."
  }

  Start-AdbServerQuietly -AdbPath $Adb
  $ResolvedSerial = Resolve-DeviceSerial -AdbPath $Adb -ProvidedSerial $DeviceSerial
  Install-OrUpdatePackage -AdbPath $Adb -Serial $ResolvedSerial -ApkPath $DesignDebugApk -PackageName $DesignPackage
  Install-OrUpdatePackage -AdbPath $Adb -Serial $ResolvedSerial -ApkPath $DesignAndroidTestApk -PackageName $DesignTestPackage -IsTestApk
  Assert-LaunchesMainActivity -AdbPath $Adb -Serial $ResolvedSerial -PackageName $DesignPackage

  if (-not $SkipInstrumentation) {
    $InstrumentationFilters = if ($InstrumentationClass) {
      @($InstrumentationClass)
    } else {
      Get-InstrumentationClassFilters -ProjectRoot $ProjectRoot
    }
    foreach ($ClassFilter in $InstrumentationFilters) {
      & $Adb "-s" $ResolvedSerial "shell" "am" "force-stop" $DesignPackage
      if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
      Invoke-AdbInstrumentation `
        -AdbPath $Adb `
        -Serial $ResolvedSerial `
        -Runner "$DesignTestPackage/androidx.test.runner.AndroidJUnitRunner" `
        -ClassFilter $ClassFilter `
        -TimeoutSeconds $InstrumentationTimeoutSeconds
    }
  }

  Write-Host "Flovera Design verification completed."
} finally {
  Pop-Location
}
