# AGENTS.md

This file defines repository-local rules for AI collaboration.

## First Principles

- Do not assume the user request is correct by default.
- Clarify ambiguous requirements before implementation.
- If the goal is clear but the path is not the shortest, say so and propose the better path.
- Find root causes instead of hiding problems with temporary patches.
- Every technical decision must be able to answer "why".
- Keep output focused on information that changes decisions.

## Current Project Direction

- The active product is Flovera: an Android-local workspace agent app.
- The Android app owns sessions, workspace files, WebView display, permissions, settings, and the agent entry point.
- Koog is the upstream agent runtime framework.
- Legacy QEMU/VPS material is archived under `docs/archive/legacy-qemu-vps/`.

## Reuse And Editing Rules

- Reuse existing files and directories before creating new ones.
- Use git branches, commits, diffs, and tags for history and rollback.
- Do not delete, rewrite, or revert user changes without explicit instruction.
- Do not use destructive git commands such as `git reset --hard` or `git checkout -- <file>` unless the user explicitly asks.
- If PyTorch is ever introduced, only CUDA builds are allowed.
- When adding or changing Flovera capabilities, update the app-owned system prompt
  and settings JSON surface in the same change set when they are affected. This
  includes `AgentPromptBuilder`, app settings persistence, `.flovera/settings-view.json`,
  and `.flovera/capabilities.json` so the agent, settings UI, and workspace view
  stay consistent.
- When implementation changes the status or boundary of a Product Backlog item,
  update `PRODUCT_QUALITY.md` in the same change set with the implemented
  evidence, remaining scope, and verification gate. Do not leave completed
  capabilities described as future-only backlog.

## Git Rules

- Local commits may be small and frequent.
- Each commit must explain what changed and why.
- Do not use empty commit messages such as `misc update`.
- After each commit, tell the user what changed and why.
- Do not push to a remote unless the user explicitly approves that specific push.
- Pushes should represent larger coherent checkpoints, not every small local change.
- Do not create or update a GitHub Release unless the user explicitly approves it.
- Keep the latest pushed commit and the latest release aligned.

## Public Safety Rules

- This repository is allowed to be public as a product and process showcase.
  Planning docs, backlog notes, design docs, and repository-local collaboration
  rules may be committed and pushed when they are useful.
- Do not create special public-only commit selections just to hide ordinary docs.
  When the user approves a push, push the normal committed branch state unless
  there is a concrete safety issue.
- Use `.gitignore` as the primary boundary for local-only files: secrets,
  signing material, APK/AAB outputs, device dumps, private workspace data,
  session logs, local settings, and generated caches should not become tracked.
- `.gitignore` does not protect files that are already tracked. If a tracked
  file contains a secret or private device/user data, stop and remove the
  sensitive content before pushing.
- Before a public release or whenever safety is uncertain, run
  `scripts/check-public-md-allowlist.ps1 -Ref <ref>`. The script is now a
  lightweight public safety scan despite the legacy name; it must fail only for
  likely secrets, signing files, binaries, or private local/runtime data, not for
  ordinary Markdown documents.

## Device Verification Rules

- Avoid visual clicking during real-device verification unless it is necessary.
- Prefer command, test, semantic node, and debug-entry verification paths.
- Do not use verification paths that uninstall the user's main app or reset permissions.
- Prefer `android/spike/scripts/verify-flovera-android.ps1` for Android verification.

## Command Rules

- Use the `rtk` prefix for shell commands in this environment.
- Do not wait for long commands with `sleep 30s` polling loops.
- Keep build and verification commands reproducible, preferably in scripts or docs.
