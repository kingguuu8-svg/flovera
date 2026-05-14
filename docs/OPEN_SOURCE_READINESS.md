# Open Source Readiness

This checklist tracks the work required before publishing Flovera as an open
source project.

## Publishability Gates

| Area | Required state | Current status |
| --- | --- | --- |
| License | Repository has an explicit license file | Done: MIT |
| Secret hygiene | No committed API keys, tokens, private keys, local server IPs, or personal paths | Initial scan found no obvious real API key; repeat before publishing |
| Runtime config | API keys, model providers, local paths, and settings stay outside source | Partially done; verify Android settings docs and examples |
| README | Public README describes current Flovera direction, not legacy QEMU/VPS route | Done: root `README.md` |
| Build docs | Fresh contributor can build the Android app from documented commands | Done: root `README.md` and `CONTRIBUTING.md` |
| Verification docs | Public verification path is documented without depending on private devices | Done: root `README.md` and `CONTRIBUTING.md` |
| Contribution docs | Contribution and issue-reporting rules are documented | Done: `CONTRIBUTING.md` |
| Security docs | Vulnerability/security reporting and secret-handling policy are documented | Done: `SECURITY.md` |
| Third-party notices | Major dependencies and licenses are documented | Done: `THIRD_PARTY_NOTICES.md`; run final dependency license audit before binary release |
| Legacy material | Old QEMU/VPS route remains archived and clearly separated from the current product | Done: legacy docs and runtime files live under `docs/archive/legacy-qemu-vps/` |
| Release hygiene | Generated APKs, build outputs, local properties, and signing files are ignored | Partially done; repeat `git status --ignored` audit |

## Pre-Publish Audit Commands

Run these before making a public repository or release:

```powershell
git status --short
git ls-files
rg -n --hidden --glob '!**/.git/**' --glob '!**/build/**' --glob '!**/.gradle/**' "(sk-[A-Za-z0-9_-]{10,}|api[_-]?key|apikey|secret|token|password|C:\\Users|E:\\main|vps\.pem)"
```

For Android verification, use the protected verifier that updates an existing
install instead of uninstalling the app:

```powershell
cd android/spike
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-flovera-android.ps1 -SkipRelease
```

## Open Source Positioning

Flovera should be presented as an Android-local workspace agent app:

- Android app with persistent agent sessions.
- Scoped local workspace.
- WebView as the primary generated-web preview surface.
- Configurable model providers without hardcoded secrets.
- Explicit tool permissions.
- Product direction documented in `PRODUCT_QUALITY.md`.

Legacy QEMU/VPS material should remain available only as archived research, not
as the main project promise.
