param(
  [string]$Ref = "HEAD"
)

$ErrorActionPreference = "Stop"

$files = & git ls-tree -r --name-only $Ref
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

$blockedPaths = @()
foreach ($file in $files) {
  $normalized = $file -replace "\\", "/"
  $lower = $normalized.ToLowerInvariant()

  $isEnvExample = $lower.EndsWith(".env.example") -or $lower.EndsWith("/.env.example")
  $isForbiddenRuntimePath =
    $lower -match "(^|/)(sessions|workspaces|device-dumps|adb-dumps|logs)/" -or
    $lower -match "(^|/)\.flovera/(sessions|workspaces|logs|settings\.json)" -or
    $lower -match "(^|/)(settings|setting|local\.settings)\.json$" -or
    $lower -match "\.local\.json$"
  $isForbiddenSecretPath =
    (-not $isEnvExample -and $lower -match "(^|/)\.env($|[./])") -or
    $lower -match "\.(jks|keystore|p12|pfx|mobileprovision|pem|key)$" -or
    $lower -match "(^|/)(google-services\.json|secrets\.properties)$"
  $isForbiddenBuildOutput =
    $lower -match "\.(apk|ap_|aab)$" -or
    $lower -match "(^|/)(build|\.gradle|\.cxx)/"

  if ($isForbiddenRuntimePath -or $isForbiddenSecretPath -or $isForbiddenBuildOutput) {
    $blockedPaths += $file
  }
}

if ($blockedPaths.Count -gt 0) {
  Write-Error ("Public safety scan failed for {0}. Blocked tracked paths:`n{1}" -f $Ref, ($blockedPaths -join "`n"))
  exit 1
}

$secretPatterns = @(
  "g[h]p_[A-Za-z0-9_]{20,}",
  "g[h]o_[A-Za-z0-9_]{20,}",
  "g[i]thu[b]_pat_[A-Za-z0-9_]{30,}",
  "s[k]-[A-Za-z0-9]{20,}",
  "A[I]za[0-9A-Za-z_-]{20,}",
  "A[K]IA[0-9A-Z]{16}",
  "B[e]arer[ ]+[A-Za-z0-9._~+/=-]{30,}",
  "api[_-]?key[ ]*[:=][ ]*['""]?[A-Za-z0-9._-]{30,}",
  "token[ ]*[:=][ ]*['""]?[A-Za-z0-9._-]{30,}",
  "B[E]GIN .{0,30}PRIVATE KEY"
)

$matches = @()
foreach ($pattern in $secretPatterns) {
  $result = & git grep -I -n -E $pattern $Ref -- . 2>$null
  if ($LASTEXITCODE -eq 0 -and $result) {
    $matches += $result
  } elseif ($LASTEXITCODE -gt 1) {
    exit $LASTEXITCODE
  }
}

if ($matches.Count -gt 0) {
  Write-Error ("Public safety scan failed for {0}. Possible secrets:`n{1}" -f $Ref, (($matches | Select-Object -First 50) -join "`n"))
  exit 1
}

Write-Host "Public safety scan passed for $Ref."
