# Changelog

Flovera is pre-release. This changelog starts from the Android-local workspace
agent product direction.

## v0.2.0 - 2026-06-11

### Added

- Added editable workspace Skills with a bilingual registry, visibility
  controls, built-in creation guidance, and prompt-time discovery.
- Added a unified workspace command gateway with Git, Python, Groovy/JVM,
  Maven dependency, and Android system capability profiles.
- Added lightweight Office document pipelines plus Android-compatible Apache
  POI and docx4j runtime support.
- Added Android permission, location, accessibility, OCR, and recoverable
  cross-app automation foundations, including reusable automation scripts.
- Added user-managed workspace secrets and clearer capability/settings
  surfaces.

### Improved

- Preserved interrupted-run context and introduced layered append-only prompt
  history with selective tool-context retention.
- Improved chronological tool/text rendering, live streaming, Markdown
  rendering, conversation scrolling, guidance states, and compression flow.
- Improved workspace artifact registration, document previews, mobile WebView
  layout guidance, and backend failure isolation.
- Improved provider route normalization, network defaults, background run
  feedback, and explicit first-open API configuration guidance.

### Fixed

- Prevented malformed auto-restored workspace backends from crashing Flovera
  and locking the user out of the app.
- Fixed multiple JVM build stability, resource packaging, provider routing,
  Android location, and conversation interaction regressions.

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
