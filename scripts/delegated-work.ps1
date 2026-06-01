param(
  [Parameter(Mandatory = $true, Position = 0)]
  [ValidateSet("new", "brief", "submit", "list", "review", "backlog")]
  [string]$Action,

  [string]$RepoPath = ".",
  [string]$TaskId = "",
  [string]$Title = "",
  [string]$BaseRef = "HEAD",
  [string]$BranchName = "",
  [string]$BranchPrefix = "delegate",
  [string]$CommitSubjectRegex = "",
  [string[]]$RequiredPaths = @(),
  [string[]]$AllowedPaths = @(),
  [string[]]$ForbiddenPaths = @(),
  [string]$Instructions = "",
  [ValidateSet("simple", "standard", "detailed", "strict")]
  [string]$Granularity = "",
  [string[]]$PlanSteps = @(),
  [string[]]$AcceptanceCriteria = @(),
  [string[]]$VerificationCommands = @(),
  [string[]]$ReportRequirements = @(),
  [ValidateSet("accept", "reject", "codex_followup")]
  [string]$Decision = "codex_followup",
  [string]$ReviewerNote = ""
)

$ErrorActionPreference = "Stop"

function Fail($Message) {
  Write-Error $Message
  exit 1
}

function Invoke-Git {
  param(
    [string]$Repo,
    [string[]]$GitArgs
  )
  $Output = & git -C $Repo @GitArgs 2>&1
  if ($LASTEXITCODE -ne 0) {
    $Text = ($Output | Out-String).Trim()
    Fail "git $($GitArgs -join ' ') failed. $Text"
  }
  return $Output
}

function Resolve-Repo {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) {
    Fail "RepoPath does not exist: $Path"
  }
  $Resolved = (Resolve-Path -LiteralPath $Path).Path
  Invoke-Git -Repo $Resolved -GitArgs @("rev-parse", "--show-toplevel") | Out-Null
  return (Invoke-Git -Repo $Resolved -GitArgs @("rev-parse", "--show-toplevel") | Select-Object -First 1).Trim()
}

function ConvertTo-Slug {
  param([string]$Value)
  $Slug = $Value.ToLowerInvariant() -replace "[^a-z0-9]+", "-"
  $Slug = $Slug.Trim("-")
  if (-not $Slug) { Fail "TaskId must contain at least one letter or digit." }
  return $Slug
}

function Get-StateRoot {
  param([string]$Repo)
  return Join-Path $Repo ".codex\delegated-work"
}

function Ensure-StateDirs {
  param([string]$Repo)
  $Root = Get-StateRoot -Repo $Repo
  New-Item -ItemType Directory -Force -Path (Join-Path $Root "tasks") | Out-Null
  New-Item -ItemType Directory -Force -Path (Join-Path $Root "handoffs") | Out-Null
  New-Item -ItemType Directory -Force -Path (Join-Path $Root "reviews") | Out-Null
  return $Root
}

function Read-JsonFile {
  param(
    [string]$Path,
    $Default
  )
  if (-not (Test-Path -LiteralPath $Path)) { return $Default }
  $Content = Get-Content -LiteralPath $Path -Raw
  if (-not $Content.Trim()) { return $Default }
  return $Content | ConvertFrom-Json
}

function Write-JsonFile {
  param(
    [string]$Path,
    $Value
  )
  $Json = $Value | ConvertTo-Json -Depth 20
  Set-Content -LiteralPath $Path -Value ($Json + "`n") -Encoding UTF8
}

function Set-JsonObjectProperty {
  param(
    [object]$Object,
    [string]$Name,
    $Value
  )
  if ($Object.PSObject.Properties.Name -contains $Name) {
    $Object.$Name = $Value
  } else {
    $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
  }
}

function Get-TaskPath {
  param(
    [string]$Repo,
    [string]$ResolvedTaskId
  )
  return Join-Path (Join-Path (Get-StateRoot -Repo $Repo) "tasks") "$ResolvedTaskId.json"
}

function Get-HandoffPath {
  param(
    [string]$Repo,
    [string]$ResolvedTaskId
  )
  return Join-Path (Join-Path (Get-StateRoot -Repo $Repo) "handoffs") "$ResolvedTaskId.md"
}

function Load-Task {
  param(
    [string]$Repo,
    [string]$ResolvedTaskId
  )
  $Path = Get-TaskPath -Repo $Repo -ResolvedTaskId $ResolvedTaskId
  if (-not (Test-Path -LiteralPath $Path)) {
    Fail "Delegated task does not exist: $ResolvedTaskId"
  }
  return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Format-ListOrNone {
  param([object[]]$Values)
  $List = @($Values | Where-Object { $_ -and "$_".Trim() -ne "" })
  if ($List.Count -eq 0) { return "- (none)" }
  return ($List | ForEach-Object { "- $_" }) -join "`n"
}

function Get-ObjectStringList {
  param($Value)
  if ($null -eq $Value) { return @() }
  return @($Value | Where-Object { $_ -and "$_".Trim() -ne "" } | ForEach-Object { "$_".Trim() })
}

function Default-PlanSteps {
  param([string]$Level)
  switch ($Level) {
    "simple" {
      return @(
        "Inspect the requested file or area.",
        "Make the smallest focused change.",
        "Run the listed verification or explain why it was not run."
      )
    }
    "standard" {
      return @(
        "Read the relevant existing code and tests before editing.",
        "Identify the smallest implementation path that satisfies the direct task.",
        "Make focused changes within the allowed scope.",
        "Run targeted verification and record the result."
      )
    }
    "detailed" {
      return @(
        "Map the current behavior and affected files before editing.",
        "List hidden invariants and authoritative durable outputs before choosing a fix.",
        "State the implementation approach in the branch notes or final report before broad changes.",
        "Make changes in small reviewable commits whose subjects match the regex.",
        "Update nearby tests, docs, prompts, or metadata when the task changes those contracts.",
        "Run targeted verification against the authoritative state, file, event sequence, or durable output; include failure details if anything cannot be verified.",
        "If the toolchain appears blocked, run the minimal toolchain probes before declaring it broken."
      )
    }
    default {
      return @(
        "Map the current behavior, affected contracts, and likely regression surfaces before editing.",
        "Write the hidden invariants, expected durable state shape, and known bad sequence before editing.",
        "Write down the intended implementation sequence before changing high-risk code.",
        "Make small commits, each with a subject matching the regex and one coherent reason.",
        "Preserve existing behavior outside the direct task; do not broaden scope without asking.",
        "Add or update tests for deterministic state, file, event, or error boundaries touched by the task.",
        "Run all listed verification commands and include exact pass/fail/skipped evidence from the authoritative state, not only visible UI.",
        "For runtime, streaming, persistence, Android lifecycle, provider, or device behavior, include real-path verification or a precise skipped reason.",
        "If the toolchain appears blocked, prove the blocker with minimal probes before claiming SDK/toolchain failure.",
        "Call out residual risks and any follow-up work separately from completed work."
      )
    }
  }
}

function Default-AcceptanceCriteria {
  param([string]$Level)
  switch ($Level) {
    "simple" {
      return @(
        "The direct task is implemented.",
        "No unrelated files are changed.",
        "The branch can be reviewed from the required base commit."
      )
    }
    "standard" {
      return @(
        "The direct task is implemented with focused changes.",
        "Changed files stay within the declared scope.",
        "Commit subjects match the required regex.",
        "Verification evidence is included in the handoff response."
      )
    }
    "detailed" {
      return @(
        "The implementation follows existing project patterns.",
        "All affected contracts are updated together.",
        "Hidden invariants and durable-output expectations are explicitly addressed.",
        "Regression-sensitive behavior has targeted verification against durable state or outputs.",
        "Stateful bugs are not fixed by UI-only changes unless the durable state is already proven correct.",
        "Toolchain blockers are reported with exact command, cwd, exit code, timeout status, and minimal probe evidence.",
        "The report lists changed files, behavior changes, verification, and remaining risk."
      )
    }
    default {
      return @(
        "The branch starts exactly from the required base commit.",
        "Every commit is focused and matches the commit subject regex.",
        "Implementation, tests, docs, prompts, and metadata stay consistent where affected.",
        "No forbidden or out-of-scope files are changed.",
        "Hidden invariants and expected event/state sequences are proven or explicitly marked unresolved.",
        "Verification covers deterministic durable behavior before any manual UI claim.",
        "Real-path behavior is verified for runtime, streaming, persistence, Android lifecycle, provider, and device-sensitive tasks.",
        "UI-visible behavior does not count as sufficient evidence for persistence, runtime, queueing, or ordering bugs.",
        "SDK/toolchain failure is not used as a skipped reason unless minimal probe evidence is included.",
        "Residual risk is explicitly documented."
      )
    }
  }
}

function Default-ReportRequirements {
  param([string]$Level)
  switch ($Level) {
    "simple" {
      return @(
        "Changed files.",
        "Verification result."
      )
    }
    "standard" {
      return @(
        "Summary of behavior changed.",
        "Changed files.",
        "Verification commands and outcomes.",
        "Any skipped verification."
      )
    }
    "detailed" {
      return @(
        "Implementation approach.",
        "Hidden invariants considered and how the branch satisfies each one.",
        "Changed files and why each area changed.",
        "Tests or verification run with outcomes and durable evidence.",
        "Toolchain blocker evidence when any verification could not run.",
        "Known residual risks.",
        "Anything intentionally left out of scope."
      )
    }
    default {
      return @(
        "Implementation approach and why it is the narrowest viable path.",
        "Hidden invariants, expected durable state/event sequence, and known bad sequence.",
        "Commit list with one-line purpose for each commit.",
        "Changed files grouped by contract or subsystem.",
        "Verification matrix: command, result, evidence, and skipped reason.",
        "Toolchain probe matrix when SDK, Gradle, adb, emulator/device, or other tooling is claimed blocked.",
        "Regression risks and how the branch reduces them.",
        "Open questions requiring requester/Codex decision."
      )
    }
  }
}

function ToolchainDisciplineSection {
  return @(
    "## Toolchain And Verification Discipline",
    "",
    "- Do not stop at `"SDK/toolchain is broken`" without proving it with a minimal reproducible probe.",
    "- For every failed or skipped verification, report exact command, working directory, setup command, exit code, timeout status, and the shortest useful error excerpt.",
    "- If a command times out, check whether the underlying process is still running and whether expected outputs were produced before declaring failure.",
    '- For Android/Flovera work, source `. D:\main\flovera-android-env.ps1` before Android commands and use the repository-required `rtk` prefix when applicable.',
    '- Prefer Gradle assemble tasks for build checks, such as `.\gradlew.bat :app:assembleFloveraDebug --no-daemon`; do not use Gradle install tasks as verification.',
    '- Do not run raw `adb install`, `adb uninstall`, install androidTest APKs, reinstall test packages, or otherwise change install state unless the requester explicitly approved that exact operation.',
    '- For main-app device updates, use the guarded `$android-apk-update-only` workflow against an already installed package.',
    '- Existing installed instrumentation tests may be run with `adb shell am instrument` only when the test APK is already installed and compatible.',
    "- For runtime, streaming, persistence, Android lifecycle, provider, or device behavior, fake-runtime tests are not enough by themselves; include real-path evidence or a precise skipped reason.",
    "- For conversation/session bugs, inspect persisted session JSON or another durable output. Do not rely only on transient UI text.",
    "- When ordering matters, report the exact expected and observed sequence, for example: assistant_text -> user_guidance -> guidance -> tool_call -> assistant_text.",
    "- Do not submit UI-only fixes for state, persistence, queueing, interruption, compression, or runtime-ordering bugs unless durable state was already proven correct."
  ) -join "`n"
}

function Write-HandoffBrief {
  param(
    [string]$Repo,
    [object]$Task
  )

  $ResolvedTaskId = [string]$Task.taskId
  $HandoffPath = Get-HandoffPath -Repo $Repo -ResolvedTaskId $ResolvedTaskId
  $TaskPath = Get-TaskPath -Repo $Repo -ResolvedTaskId $ResolvedTaskId
  $Required = Format-ListOrNone -Values @($Task.requiredPaths)
  $Allowed = Format-ListOrNone -Values @($Task.allowedPaths)
  $Forbidden = Format-ListOrNone -Values @($Task.forbiddenPaths)
  $Plan = Format-ListOrNone -Values (Get-ObjectStringList -Value $Task.planSteps)
  $Criteria = Format-ListOrNone -Values (Get-ObjectStringList -Value $Task.acceptanceCriteria)
  $Verification = Format-ListOrNone -Values (Get-ObjectStringList -Value $Task.verificationCommands)
  $Report = Format-ListOrNone -Values (Get-ObjectStringList -Value $Task.reportRequirements)
  $Content = @(
    "# Delegated Task: $($Task.title)",
    "",
    "Granularity: $($Task.granularity)",
    "",
    "## Direct Task",
    "",
    "$($Task.instructions)",
    "",
    "## Mandatory Git Contract",
    "",
    "- Task id: $($Task.taskId)",
    "- Required branch: $($Task.branchName)",
    "- Required base commit: $($Task.baseCommit)",
    "- Commit subject regex: $($Task.commitSubjectRegex)",
    "",
    "Create the branch exactly from the required base commit:",
    "",
    '```powershell',
    "git switch --detach $($Task.baseCommit)",
    "git switch -c $($Task.branchName)",
    '```',
    "",
    "Every commit on this branch after the base commit must have a subject matching the commit subject regex.",
    "",
    "## Execution Plan",
    "",
    $Plan,
    "",
    "## Acceptance Criteria",
    "",
    $Criteria,
    "",
    "## Verification",
    "",
    $Verification,
    "",
    (ToolchainDisciplineSection),
    "",
    "## Change Scope",
    "",
    "Required changed paths:",
    "",
    $Required,
    "",
    "Allowed changed paths:",
    "",
    $Allowed,
    "",
    "Forbidden changed paths:",
    "",
    $Forbidden,
    "",
    "If allowed paths are listed, do not change files outside them. If forbidden paths are listed, do not touch them.",
    "",
    "## Submission Rules",
    "",
    "- Do only the direct task above.",
    "- Keep changes focused and reviewable.",
    "- Include implementation and verification evidence in the branch.",
    "- Do not merge, rebase onto another base, or modify the main working branch.",
    "- Tell the requester when the branch is ready for review.",
    "",
    "## Required Response When Ready For Review",
    "",
    $Report,
    "",
    "The requester will register your branch with:",
    "",
    '```powershell',
    "delegated-work.ps1 submit -RepoPath <repo> -TaskId $($Task.taskId) -BranchName $($Task.branchName)",
    '```',
    "",
    "Reference task file:",
    "",
    '```text',
    $TaskPath,
    '```'
  ) -join "`n"
  Set-Content -LiteralPath $HandoffPath -Value ($Content.TrimEnd() + "`n") -Encoding UTF8
  return $HandoffPath
}

function Normalize-PathList {
  param([string[]]$Paths)
  return @($Paths | Where-Object { $_ -and $_.Trim() -ne "" } | ForEach-Object {
    $_.Trim().Replace("\", "/").Trim("/")
  })
}

function Test-PathPrefix {
  param(
    [string]$Path,
    [string]$Prefix
  )
  $P = $Path.Replace("\", "/").Trim("/")
  $X = $Prefix.Replace("\", "/").Trim("/")
  return $P -eq $X -or $P.StartsWith("$X/")
}

function Assert-ChangedPathRules {
  param(
    [string[]]$ChangedFiles,
    [string[]]$Required,
    [string[]]$Allowed,
    [string[]]$Forbidden
  )

  foreach ($RequiredPath in $Required) {
    $Hit = $ChangedFiles | Where-Object { Test-PathPrefix -Path $_ -Prefix $RequiredPath } | Select-Object -First 1
    if (-not $Hit) {
      Fail "Required changed path was not touched: $RequiredPath"
    }
  }

  foreach ($ForbiddenPath in $Forbidden) {
    $Hit = $ChangedFiles | Where-Object { Test-PathPrefix -Path $_ -Prefix $ForbiddenPath } | Select-Object -First 1
    if ($Hit) {
      Fail "Forbidden path was changed: $Hit matches $ForbiddenPath"
    }
  }

  if ($Allowed.Count -gt 0) {
    foreach ($Changed in $ChangedFiles) {
      $AllowedHit = $Allowed | Where-Object { Test-PathPrefix -Path $Changed -Prefix $_ } | Select-Object -First 1
      if (-not $AllowedHit) {
        Fail "Changed path is outside allowed scope: $Changed"
      }
    }
  }
}

function Assert-CommitSubjects {
  param(
    [string]$Repo,
    [string]$BaseCommit,
    [string]$Branch,
    [string]$Regex
  )

  if (-not $Regex) { return }
  $Subjects = @(Invoke-Git -Repo $Repo -GitArgs @("log", "--format=%s", "$BaseCommit..$Branch"))
  if ($Subjects.Count -eq 0) {
    Fail "Branch has no commits after base commit."
  }
  foreach ($Subject in $Subjects) {
    if ($Subject -notmatch $Regex) {
      Fail "Commit subject does not match regex '$Regex': $Subject"
    }
  }
}

function Get-QueuePath {
  param([string]$Repo)
  return Join-Path (Get-StateRoot -Repo $Repo) "review-queue.json"
}

function Read-Queue {
  param([string]$Repo)
  $Queue = Read-JsonFile -Path (Get-QueuePath -Repo $Repo) -Default @()
  if ($null -eq $Queue) { return @() }
  if ($Queue -is [array]) { return @($Queue) }
  return @($Queue)
}

function Write-Queue {
  param(
    [string]$Repo,
    [object[]]$Queue
  )
  Write-JsonFile -Path (Get-QueuePath -Repo $Repo) -Value @($Queue)
}

function New-DelegatedTask {
  param([string]$Repo)

  if (-not $TaskId) { Fail "TaskId is required for new." }
  if (-not $Title) { Fail "Title is required for new." }
  if (-not $Instructions) { Fail "Instructions are required for new." }
  if (-not $Granularity) { Fail "Granularity is required for new. Codex must choose one of: simple, standard, detailed, strict." }

  $ResolvedTaskId = ConvertTo-Slug -Value $TaskId
  $BaseCommit = (Invoke-Git -Repo $Repo -GitArgs @("rev-parse", "$BaseRef^{commit}") | Select-Object -First 1).Trim()
  $RequestedBranch = if ($BranchName) { $BranchName } else { "$BranchPrefix/$ResolvedTaskId" }
  $Regex = if ($CommitSubjectRegex) { $CommitSubjectRegex } else { "^$([regex]::Escape($ResolvedTaskId)): .+" }
  $NormalizedRequired = @(Normalize-PathList -Paths $RequiredPaths)
  $NormalizedAllowed = @(Normalize-PathList -Paths $AllowedPaths)
  $NormalizedForbidden = @(Normalize-PathList -Paths $ForbiddenPaths)
  $ResolvedGranularity = $Granularity
  $ResolvedPlanSteps = @(Get-ObjectStringList -Value $PlanSteps)
  if ($ResolvedPlanSteps.Count -eq 0) {
    $ResolvedPlanSteps = @(Default-PlanSteps -Level $ResolvedGranularity)
  }
  $ResolvedAcceptanceCriteria = @(Get-ObjectStringList -Value $AcceptanceCriteria)
  if ($ResolvedAcceptanceCriteria.Count -eq 0) {
    $ResolvedAcceptanceCriteria = @(Default-AcceptanceCriteria -Level $ResolvedGranularity)
  }
  $ResolvedVerificationCommands = @(Get-ObjectStringList -Value $VerificationCommands)
  if ($ResolvedVerificationCommands.Count -eq 0) {
    $ResolvedVerificationCommands = @("Run the narrowest relevant verification. If no verification is practical, explain why and describe the inspection performed.")
  }
  $ResolvedReportRequirements = @(Get-ObjectStringList -Value $ReportRequirements)
  if ($ResolvedReportRequirements.Count -eq 0) {
    $ResolvedReportRequirements = @(Default-ReportRequirements -Level $ResolvedGranularity)
  }

  Ensure-StateDirs -Repo $Repo | Out-Null
  $Path = Get-TaskPath -Repo $Repo -ResolvedTaskId $ResolvedTaskId
  if (Test-Path -LiteralPath $Path) {
    Fail "Delegated task already exists: $ResolvedTaskId"
  }

  $Task = [ordered]@{
    schemaVersion = 1
    taskId = $ResolvedTaskId
    title = $Title
    status = "assigned"
    createdAt = (Get-Date).ToString("o")
    baseRef = $BaseRef
    baseCommit = $BaseCommit
    branchName = $RequestedBranch
    commitSubjectRegex = $Regex
    granularity = $ResolvedGranularity
    requestedGranularity = $Granularity
    requiredPaths = $NormalizedRequired
    allowedPaths = $NormalizedAllowed
    forbiddenPaths = $NormalizedForbidden
    instructions = $Instructions
    planSteps = $ResolvedPlanSteps
    acceptanceCriteria = $ResolvedAcceptanceCriteria
    verificationCommands = $ResolvedVerificationCommands
    reportRequirements = $ResolvedReportRequirements
  }
  $BriefPath = Write-HandoffBrief -Repo $Repo -Task $Task
  $Task["handoffBriefPath"] = $BriefPath
  Write-JsonFile -Path $Path -Value $Task

  Write-Host "Delegated task created: $ResolvedTaskId"
  Write-Host "baseCommit=$BaseCommit"
  Write-Host "branchName=$RequestedBranch"
  Write-Host "commitSubjectRegex=$Regex"
  Write-Host "granularity=$ResolvedGranularity"
  Write-Host "Task file: $Path"
  Write-Host "Handoff brief: $BriefPath"
}

function Write-DelegatedBrief {
  param([string]$Repo)

  if (-not $TaskId) { Fail "TaskId is required for brief." }
  $ResolvedTaskId = ConvertTo-Slug -Value $TaskId
  Ensure-StateDirs -Repo $Repo | Out-Null
  $Task = Load-Task -Repo $Repo -ResolvedTaskId $ResolvedTaskId
  $BriefPath = Write-HandoffBrief -Repo $Repo -Task $Task
  Set-JsonObjectProperty -Object $Task -Name "handoffBriefPath" -Value $BriefPath
  Write-JsonFile -Path (Get-TaskPath -Repo $Repo -ResolvedTaskId $ResolvedTaskId) -Value $Task
  Write-Host "Handoff brief written: $BriefPath"
  Write-Host ""
  Get-Content -LiteralPath $BriefPath
}

function Submit-DelegatedTask {
  param([string]$Repo)

  if (-not $TaskId) { Fail "TaskId is required for submit." }
  $ResolvedTaskId = ConvertTo-Slug -Value $TaskId
  Ensure-StateDirs -Repo $Repo | Out-Null
  $Task = Load-Task -Repo $Repo -ResolvedTaskId $ResolvedTaskId
  $ExpectedBranch = [string]$Task.branchName
  $SubmittedBranch = if ($BranchName) { $BranchName } else { $ExpectedBranch }
  if ($SubmittedBranch -ne $ExpectedBranch) {
    Fail "Submitted branch '$SubmittedBranch' does not match required branch '$ExpectedBranch'."
  }

  Invoke-Git -Repo $Repo -GitArgs @("rev-parse", "--verify", "$SubmittedBranch^{commit}") | Out-Null
  $HeadCommit = (Invoke-Git -Repo $Repo -GitArgs @("rev-parse", "$SubmittedBranch^{commit}") | Select-Object -First 1).Trim()
  $BaseCommit = [string]$Task.baseCommit
  $MergeBase = (Invoke-Git -Repo $Repo -GitArgs @("merge-base", $BaseCommit, $SubmittedBranch) | Select-Object -First 1).Trim()
  if ($MergeBase -ne $BaseCommit) {
    Fail "Branch '$SubmittedBranch' does not start from required base $BaseCommit. merge-base=$MergeBase"
  }
  if ($HeadCommit -eq $BaseCommit) {
    Fail "Branch '$SubmittedBranch' has no commits beyond required base."
  }

  $DiffCheck = & git -C $Repo diff --check "$BaseCommit..$SubmittedBranch" 2>&1
  if ($LASTEXITCODE -ne 0) {
    Fail "git diff --check failed. $($DiffCheck | Out-String)"
  }

  $ChangedFiles = @(Invoke-Git -Repo $Repo -GitArgs @("diff", "--name-only", "$BaseCommit..$SubmittedBranch") | ForEach-Object {
    $_.Trim().Replace("\", "/")
  } | Where-Object { $_ })
  if ($ChangedFiles.Count -eq 0) {
    Fail "Branch '$SubmittedBranch' has no changed files."
  }

  Assert-ChangedPathRules `
    -ChangedFiles $ChangedFiles `
    -Required @($Task.requiredPaths) `
    -Allowed @($Task.allowedPaths) `
    -Forbidden @($Task.forbiddenPaths)

  Assert-CommitSubjects `
    -Repo $Repo `
    -BaseCommit $BaseCommit `
    -Branch $SubmittedBranch `
    -Regex ([string]$Task.commitSubjectRegex)

  $Queue = @(Read-Queue -Repo $Repo | Where-Object {
    -not ($_.taskId -eq $ResolvedTaskId -and $_.branchName -eq $SubmittedBranch)
  })
  $Queue += [ordered]@{
    schemaVersion = 1
    taskId = $ResolvedTaskId
    title = [string]$Task.title
    branchName = $SubmittedBranch
    baseCommit = $BaseCommit
    headCommit = $HeadCommit
    status = "pending_review"
    submittedAt = (Get-Date).ToString("o")
    changedFiles = @($ChangedFiles)
  }
  Write-Queue -Repo $Repo -Queue $Queue

  Set-JsonObjectProperty -Object $Task -Name "status" -Value "pending_review"
  Set-JsonObjectProperty -Object $Task -Name "submittedAt" -Value (Get-Date).ToString("o")
  Set-JsonObjectProperty -Object $Task -Name "headCommit" -Value $HeadCommit
  Write-JsonFile -Path (Get-TaskPath -Repo $Repo -ResolvedTaskId $ResolvedTaskId) -Value $Task

  Write-Host "Submission accepted into pending review queue."
  Write-Host "taskId=$ResolvedTaskId"
  Write-Host "branchName=$SubmittedBranch"
  Write-Host "headCommit=$HeadCommit"
  Write-Host "changedFiles=$($ChangedFiles.Count)"
}

function List-DelegatedReviews {
  param([string]$Repo)
  Ensure-StateDirs -Repo $Repo | Out-Null
  $Queue = @(Read-Queue -Repo $Repo)
  if ($Queue.Count -eq 0) {
    Write-Host "No pending delegated reviews."
    return
  }
  $Queue | ForEach-Object {
    Write-Host "$($_.taskId) [$($_.status)] $($_.branchName) $($_.headCommit) - $($_.title)"
  }
}

function Review-DelegatedTask {
  param([string]$Repo)

  if (-not $TaskId) { Fail "TaskId is required for review." }
  if (-not $ReviewerNote) { Fail "ReviewerNote is required for review." }
  $ResolvedTaskId = ConvertTo-Slug -Value $TaskId
  Ensure-StateDirs -Repo $Repo | Out-Null
  $Queue = @(Read-Queue -Repo $Repo)
  $Matches = @($Queue | Where-Object {
    $_.taskId -eq $ResolvedTaskId -and ((-not $BranchName) -or $_.branchName -eq $BranchName)
  })
  if ($Matches.Count -eq 0) {
    Fail "No pending review entry found for task '$ResolvedTaskId'."
  }
  if ($Matches.Count -gt 1) {
    Fail "Multiple pending review entries found for task '$ResolvedTaskId'. Pass -BranchName."
  }
  $Entry = $Matches[0]
  $ReviewId = "$ResolvedTaskId-$($Entry.headCommit.Substring(0, 12))-$Decision"
  $ReviewPath = Join-Path (Join-Path (Get-StateRoot -Repo $Repo) "reviews") "$ReviewId.json"
  $Review = [ordered]@{
    schemaVersion = 1
    taskId = $ResolvedTaskId
    title = $Entry.title
    branchName = $Entry.branchName
    baseCommit = $Entry.baseCommit
    headCommit = $Entry.headCommit
    decision = $Decision
    reviewerNote = $ReviewerNote
    reviewedAt = (Get-Date).ToString("o")
  }
  Write-JsonFile -Path $ReviewPath -Value $Review

  $Remaining = @($Queue | Where-Object {
    -not ($_.taskId -eq $ResolvedTaskId -and $_.branchName -eq $Entry.branchName)
  })
  Write-Queue -Repo $Repo -Queue $Remaining

  $Task = Load-Task -Repo $Repo -ResolvedTaskId $ResolvedTaskId
  Set-JsonObjectProperty -Object $Task -Name "status" -Value "reviewed_$Decision"
  Set-JsonObjectProperty -Object $Task -Name "reviewedAt" -Value (Get-Date).ToString("o")
  Set-JsonObjectProperty -Object $Task -Name "reviewDecision" -Value $Decision
  Set-JsonObjectProperty -Object $Task -Name "reviewPath" -Value $ReviewPath
  Write-JsonFile -Path (Get-TaskPath -Repo $Repo -ResolvedTaskId $ResolvedTaskId) -Value $Task

  Write-Host "Review recorded and queue entry removed."
  Write-Host "taskId=$ResolvedTaskId"
  Write-Host "decision=$Decision"
  Write-Host "reviewPath=$ReviewPath"
}

function Move-DelegatedTaskToBacklog {
  param([string]$Repo)

  if (-not $TaskId) { Fail "TaskId is required for backlog." }
  $ResolvedTaskId = ConvertTo-Slug -Value $TaskId
  Ensure-StateDirs -Repo $Repo | Out-Null
  $Task = Load-Task -Repo $Repo -ResolvedTaskId $ResolvedTaskId

  $Queue = @(Read-Queue -Repo $Repo)
  $Remaining = @($Queue | Where-Object {
    -not ($_.taskId -eq $ResolvedTaskId -and ((-not $BranchName) -or $_.branchName -eq $BranchName))
  })
  if ($Remaining.Count -ne $Queue.Count) {
    Write-Queue -Repo $Repo -Queue $Remaining
  }

  Set-JsonObjectProperty -Object $Task -Name "status" -Value "backlog"
  Set-JsonObjectProperty -Object $Task -Name "backlogAt" -Value (Get-Date).ToString("o")
  if ($ReviewerNote) {
    Set-JsonObjectProperty -Object $Task -Name "backlogReason" -Value $ReviewerNote
  }
  Write-JsonFile -Path (Get-TaskPath -Repo $Repo -ResolvedTaskId $ResolvedTaskId) -Value $Task

  Write-Host "Delegated task moved to backlog."
  Write-Host "taskId=$ResolvedTaskId"
  Write-Host "status=backlog"
}

$Repo = Resolve-Repo -Path $RepoPath
switch ($Action) {
  "new" { New-DelegatedTask -Repo $Repo }
  "brief" { Write-DelegatedBrief -Repo $Repo }
  "submit" { Submit-DelegatedTask -Repo $Repo }
  "list" { List-DelegatedReviews -Repo $Repo }
  "review" { Review-DelegatedTask -Repo $Repo }
  "backlog" { Move-DelegatedTaskToBacklog -Repo $Repo }
}
