# Changelog

Flovera is pre-release. This changelog starts from the Android-local workspace
agent product direction.

## v0.1.1

### Fixed

- Hardened OpenAI-compatible provider route normalization for Hermes-derived
  provider profiles whose base URLs include vendor path prefixes.
- Added 404-only fallback route candidates for missing or duplicated version
  path segments.
- Added live Z.AI provider route smoke coverage for real-device verification.

## v0.1.0-alpha.1

### Current Capabilities

- Android-local agent app built with Kotlin and Jetpack Compose.
- Persistent sessions and conversation history.
- Workspace file tools and file browser.
- WebView preview for workspace HTML files.
- Configurable provider settings stored outside source code.
- Optional network tools behind a user setting.
- Markdown conversation rendering and collapsed tool output.
- MIT license and initial open-source project documentation.

### Open Source Preparation

- Added MIT license.
- Rewrote README around the current Flovera product direction.
- Added contribution, security, third-party notice, and readiness documents.
- Archived legacy QEMU/VPS runtime material under
  `docs/archive/legacy-qemu-vps/`.

### Planned

- Workspace snapshots and restore.
- Agent Authority Mode.
- Controlled Python runtime.
- Brave Search tool integration.
- More workspace renderers.
