param(
  [string]$DeviceSerial = "e9512097",
  [string]$PackageName = "com.flovera.app"
)

$ErrorActionPreference = "Stop"

$action = "$PackageName.debug.RUN_CHRONOLOGICAL_TRANSCRIPT"
$resultPath = "files/debug/chronological-transcript-result.json"

adb -s $DeviceSerial shell am broadcast -a $action -n "$PackageName/com.flovera.app.debug.ChronologicalTranscriptDebugReceiver" | Out-Host

$deadline = (Get-Date).AddMinutes(3)
do {
  Start-Sleep -Seconds 3
  $raw = adb -s $DeviceSerial exec-out run-as $PackageName cat $resultPath 2>$null
  if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($raw) -and $raw.TrimStart().StartsWith("{")) {
    $result = $raw | ConvertFrom-Json
    $raw
    if ($result.status -eq "passed") {
      exit 0
    }
    exit 1
  }
} while ((Get-Date) -lt $deadline)

throw "Timed out waiting for chronological transcript verification result."
