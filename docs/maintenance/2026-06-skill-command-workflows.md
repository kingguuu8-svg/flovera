# Skill-Scoped Command Workflows

Flovera should not rely on long prompt instructions for workflows that can be
checked deterministically. The first implementation keeps the command surface
inside `workspace_command_run` and lets activated skills document the exact
argv to use.

Implemented workflows:

- `flovera webview audit-mobile-layout <path>` checks HTML/CSS for the Android
  WebView zero-height root pattern and related mobile viewport hazards.
- `flovera app verify-registration <flovera.app.json>` wraps app discovery and
  registration diagnostics in structured JSON.
- `flovera skill check [skill-id|skill-path|--all]` validates standard
  `.flovera/skills/<skill-id>/SKILL.md` structure, frontmatter, and manifest
  metadata.

These commands are deliberately not separate global tools. The goal is to move
repeatable skill workflows from "read a tutorial and remember it" to "follow the
skill, run the validator, fix `ok=false`", while keeping ordinary file editing
and reading as app-owned tools.

Verification gate:

- Kotlin main and androidTest compilation pass.
- Instrumented tests cover all three workflow commands through
  `WorkspaceCommandRunTool`.
- Seeded skill text points agents at the command workflow where it matters.
- `.flovera/capabilities.json` records that skill-scoped workspace command
  workflows are available.
