# Flovera Stability Maintenance Audit - 2026-06-21

## Scope

Release-prep maintenance audit covering the Android app shell, conversation UI,
agent run loop, workspace runtime, provider/skills prompt boundary, JVM/Python
execution surfaces, permissions, UI performance, and public-release safety.

This pass intentionally fixed only P0/P1 findings. P2/P3 items are recorded
instead of expanding the work into new feature development.

## Findings

### P0

No P0 was confirmed in this pass.

### P1 fixed

1. Conversation voice input depended on an external speech-input activity.
   - Impact: on devices without a compatible recognizer activity, the mic entry
     looked present but was effectively unusable.
   - Fix: conversation voice input now uses app-owned Android
     `SpeechRecognizer`, requests `RECORD_AUDIO` at the point of use, displays
     listening/recognizing state, accepts partial results, appends recognized
     text to the composer, and reports localized recognition errors.
   - Regression: `conversationComposerExposesAttachmentAndVoiceInputs` and
     `voiceRecognitionErrorMessagesAreLocalized`.

2. Workspace WebView renderer failure could leave the user trapped in a bad
   auto-restored preview.
   - Impact: a generated app that crashes or kills the Android WebView renderer
     could make Flovera hard to re-enter because the selected preview is restored
     on startup.
   - Fix: `onRenderProcessGone` is handled as recoverable, selected preview state
     is cleared, and WebView startup/load errors expose a close-preview recovery
     action.
   - Regression: `clearWorkspacePreviewResetsPersistedSelectedHtml`.

## P2/P3 backlog

- `lintFloveraDebug` exceeded a 180 second audit timeout. Treat lint as a
  separate CI/nightly gate or give it a longer bounded timeout; do not mix it
  into APK update or device verification.
- No-daemon Android compile/assemble can exceed 180 seconds on cold runs. Use
  360 second timeouts for compile/assemble in maintenance verification, while
  keeping APK update scripts at 60-90 seconds.
- The WebView renderer-exit path is covered by controller state regression and
  compile-time override validation, but not by a forced renderer crash
  instrumentation test. Add one only if it stays deterministic on real devices.
- This audit ran a representative workspace/runtime device subset rather than
  the full `WorkspaceFileTreeInstrumentedTest` class. Keep the full class as a
  heavier release-candidate or nightly gate.

## Verification

- `:app:compileFloveraDebugKotlin`
- `:app:compileFloveraDebugAndroidTestKotlin`
- `:app:testFloveraDebugUnitTest`
- `:app:assembleFloveraDebug`
- `:app:assembleFloveraDebugAndroidTest`
- Main APK update-only: package `com.flovera.app`, device `e9512097`,
  `firstInstallTime` preserved.
- Test APK update-only: package `com.flovera.app.test`, device `e9512097`,
  `firstInstallTime` preserved.
- `AgentScreenInteractionInstrumentedTest#conversationComposerExposesAttachmentAndVoiceInputs`
- `AgentScreenInteractionInstrumentedTest#voiceRecognitionErrorMessagesAreLocalized`
- `AgentScreenInteractionInstrumentedTest#clearWorkspacePreviewResetsPersistedSelectedHtml`
- Full `AgentRunControllerInstrumentedTest`
- Full `AgentPromptBuilderInstrumentedTest`
- Workspace representative set:
  `seedWorkspaceMigratesLegacyAgentRulesToAgentsMd`,
  `controllerContainsAutoStartedPythonHttpFailureWithoutCrashingApp`,
  `workspaceCommandRunExecutesPythonScriptsWithArgv`,
  `workspaceCommandRunInjectsAllowedSecretsIntoPythonEnvironment`,
  `workspaceCommandRunRejectsUnsupportedCommands`,
  `workspaceImportsSharedFilesToRootWithUniqueNames`,
  `workspaceFloveraMetadataExposesCapabilitiesAndSettingsProposals`.
- Public safety scan: `scripts/check-public-md-allowlist.ps1 -Ref HEAD`.
- Working-tree secret spot checks found no exact Z.AI sample key, `sk-...`
  tokens, GitHub PAT pattern, or private-key blocks outside ignored caches.
