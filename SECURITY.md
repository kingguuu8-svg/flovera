# Security Policy

Flovera is pre-release software. Treat it as experimental and avoid placing
irreplaceable or highly sensitive data in a workspace until the permission and
snapshot systems are mature.

## Reporting A Vulnerability

If the repository is hosted on GitHub, prefer GitHub Security Advisories.

If a private advisory channel is not available, open an issue with a minimal
description and do not include secrets, tokens, private keys, or exploit details
that would put users at immediate risk.

## Secret Handling

Flovera must not hardcode provider API keys, model credentials, signing keys, or
private local paths in source.

Configuration examples may name variables such as `DEEPSEEK_API_KEY`, but must
not include real values.

Agent-visible settings should use references for secrets, for example
`apiKeyRef: deepseek.default`, instead of exposing plaintext keys to the agent.

## App And Workspace Boundaries

Security-sensitive work should preserve these boundaries:

- Android owns permissions, API key storage, lifecycle, WebView integration,
  notifications, timeout, and restore.
- Workspace tools operate inside the active workspace unless the user grants a
  broader permission.
- Network, file write, share/open, settings control, and future Python runtime
  tools should be permissioned and logged.
- High-impact agent-controlled settings should be protected by workspace
  snapshots and restore points.

## Generated Artifacts

Generated APKs, build outputs, logs, local settings, and workspace files are not
source artifacts and should stay out of git.
