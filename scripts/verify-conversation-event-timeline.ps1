param(
  [string]$DeviceSerial = "e9512097",
  [string]$PackageName = "com.flovera.app"
)

$ErrorActionPreference = "Stop"

$action = "$PackageName.debug.RUN_CONVERSATION_EVENT_TIMELINE"
$receiver = "$PackageName/com.flovera.app.debug.ConversationTimelineDebugReceiver"
$resultPath = "files/debug/conversation-event-timeline-result.json"

adb -s $DeviceSerial shell am broadcast -a $action -n $receiver | Out-Host

$deadline = (Get-Date).AddMinutes(1)
do {
  Start-Sleep -Seconds 2
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

throw "Timed out waiting for conversation event timeline verification result."
