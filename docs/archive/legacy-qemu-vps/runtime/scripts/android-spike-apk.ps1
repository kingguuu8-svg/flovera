param(
  [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
  [string]$JavaHome = $env:JAVA_HOME
)

$spikeDir = Join-Path $ProjectRoot 'android\spike'
$gradlew = Join-Path $spikeDir 'gradlew.bat'
$metadataPath = Join-Path $spikeDir 'app\build\outputs\apk\debug\output-metadata.json'
$fallbackApk = Join-Path (Split-Path $metadataPath -Parent) 'app-debug.apk'

if (-not (Test-Path $gradlew)) {
  throw "Missing Gradle wrapper: $gradlew"
}

if (-not $JavaHome) {
  $JavaHome = 'C:\Program Files\Android\Android Studio\jbr'
}

if (-not (Test-Path $JavaHome)) {
  throw "Missing Java home: $JavaHome"
}

$env:JAVA_HOME = $JavaHome
$env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH

& $gradlew -p $spikeDir assembleDebug
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

if (Test-Path $metadataPath) {
  $metadata = Get-Content $metadataPath -Raw | ConvertFrom-Json
  $apkName = $metadata.elements[0].outputFile
  if ($apkName) {
    Join-Path (Split-Path $metadataPath -Parent) $apkName
    exit 0
  }
}

if (Test-Path $fallbackApk) {
  $fallbackApk
  exit 0
}

throw "APK metadata was not produced at $metadataPath"
