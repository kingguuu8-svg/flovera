param(
  [string]$Ref = "HEAD"
)

$ErrorActionPreference = "Stop"

$allowed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
@(
  "README.md",
  "CHANGELOG.md",
  "CONTRIBUTING.md",
  "SECURITY.md",
  "THIRD_PARTY_NOTICES.md"
) | ForEach-Object { [void]$allowed.Add($_) }

$files = & git ls-tree -r --name-only $Ref
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

$blocked = @(
  $files |
    Where-Object { $_.EndsWith(".md", [System.StringComparison]::OrdinalIgnoreCase) } |
    Where-Object { -not $allowed.Contains($_) }
)

if ($blocked.Count -gt 0) {
  Write-Error ("Public markdown allowlist failed for {0}. Blocked files:`n{1}" -f $Ref, ($blocked -join "`n"))
  exit 1
}

Write-Host "Public markdown allowlist passed for $Ref."
