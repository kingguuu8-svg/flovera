# Flovera Product Quality

This document turns product intuition into executable review rules for Flovera.
It is not a static specification. Each time a real usage problem is found, add
the failed pattern to the anti-pattern library and convert it into an experience
model or verification gate.

## Operating Principle

Flovera should be reviewed as a product, not only as code. Before implementing
or reviewing a feature, run the feature through five questions:

1. What is the user's primary intent at this screen or control?
2. Is the primary intent completed by the most direct action?
3. Are secondary actions available without crowding the primary path?
4. Does the app create, retain, interrupt, or ask for anything the user did not request?
5. Does the design remain tolerable after repeated daily use, long histories, and messy workspaces?

If a feature passes automated tests but fails these questions, treat it as a
product defect.

## Product Review Loop

For each focused product change:

1. Define the main path: what the user is trying to do.
2. Define the counter-paths: cancel, empty state, wrong item, long list, failure, retry.
3. Compare with mature agent tools such as Codex, Claude Code, and small
   Claude-Code-like agents: where does Flovera require extra thought or clicks?
4. Implement the smallest change that removes the friction.
5. For new or changed Flovera capabilities, update the app-owned system prompt
   and the settings JSON surface when affected: settings persistence,
   `.flovera/settings-view.json`, and `.flovera/capabilities.json`.
6. Update the Product Backlog status when implementation changes the boundary:
   mark what is implemented, what remains, and what verification proves it.
7. Apply the layered verification strategy in
   `docs/03-flovera-testing-strategy.md`: prove deterministic protocol, state,
   file, event, and error boundaries before relying on manual dogfood.
8. Add or update an instrumented/user-journey test when the behavior can regress.
9. Run the standard Android verifier on a real device when the change affects UI,
   app lifecycle, sessions, workspace files, WebView, permissions, or release output.
10. Commit the change independently.

## Anti-Pattern Library

These are known patterns that should trigger redesign or at least explicit
justification.

| Area | Anti-pattern | Why it is bad | Preferred behavior |
| --- | --- | --- | --- |
| Session | Creating a persisted session before the first user message | Leaves empty garbage and makes "New" feel expensive | New session starts as a draft and persists only after the first message |
| Session | Empty sessions remain visible | Pollutes the list and makes recovery harder | Zero-message sessions are removed or never created |
| Session | Session row click opens a management menu | Click usually means open | Row click opens the session; management lives in a secondary menu |
| Session | Session title is a generic timestamp | User cannot identify work by intent | Title derives from the first user prompt, capped to a readable length |
| Session | List order ignores recent work | User cannot resume flow | Pinned sessions first, then recently changed sessions |
| Session | Archive is a destructive-looking main action | Increases accidental cleanup risk and visual noise | Archive sits inside the secondary menu |
| File | File row click does not open the file | Violates the expected file-browser interaction | Row click opens with the app default; secondary actions stay in a menu |
| File | Open, share, rename, and delete are all inline | Makes the list noisy and hard to scan | Inline surface is for identity; actions live in a compact menu |
| File | Workspace only supports a flat list | Real projects quickly become nested | Show a simple tree with stable expansion and file actions |
| Conversation | Opening a conversation starts at the top | User usually wants the latest state | Default to the latest message while preserving scroll ability |
| Conversation | Rendering every message eagerly | Long histories become slow | Use lazy rendering and collapse low-value content |
| Conversation | Tool calls take the same visual weight as user/assistant text | Tool noise hides the actual conversation | Tool calls are summarized and expandable |
| Conversation | Closing the conversation kills a running loop | Hiding UI should not cancel work | Agent loop continues unless the user explicitly stops it |
| Conversation | Revert keeps the selected message | "Revert to here" usually means go back before the chosen point | Revert removes the selected message and everything after it |
| WebView | Main surface shows app chrome instead of the workspace | Flovera's main output is the user's generated web surface | Display selected HTML full screen, with controls hidden behind app UI |
| WebView | Main surface exposes multiple unrelated control entries | Scattered entry points make the app feel like a debug panel instead of a workspace | Main surface keeps one Agent entry; secondary controls live inside the conversation |
| WebView | Empty web surface is bright or unrelated to app tone | Looks like a broken browser rather than product empty state | Dark neutral empty state matching Flovera visual language |
| WebView | Workspace HTML can trigger app behavior without a named interface | Creates hidden capabilities and security confusion | Expose controlled, documented `window.Flovera` events |
| Permission | App requests permissions at cold start before a user action | Creates distrust and startup friction | Ask only at the point of use, with clear product context |
| Config | API keys, provider choices, or workspace paths are hardcoded | Blocks sharing, open source, and user control | Store runtime config outside source and keep secrets out of git |
| Verification | Manual visual clicking is the only way to test a path | Slow and brittle for iterative development | Prefer instrumentation, adb, semantics, and scriptable journeys |
| Verification | Device verification freshly installs the user's main app | Resets the real usage state and may require permissions or install approval again | Update the already-installed package only; refuse fresh installs unless explicitly requested |
| Verification | Real provider dogfood is treated as the main regression suite | Too slow, costly, and unstable for a personal developer | Cover protocol families with fake providers, then use limited live smoke and fixed manual dogfood scenarios |

## Experience Models

Experience models make product intuition executable. Each model should describe
the main path, counter-paths, and acceptance criteria.

### Session Management

Main path:

1. User opens conversation.
2. User taps New.
3. A draft conversation opens immediately.
4. User sends the first message.
5. The session is created, named from that first prompt, and becomes recoverable.
6. User returns later and opens it by tapping the row.

Counter-paths:

- User opens New and closes without sending: no session is created.
- User has many sessions: pinned sessions appear first, others sort by recent change time.
- User wants organization: rename, duplicate, archive, restore, and pin are in a secondary menu.
- User archives the current session: app switches to the next usable non-empty session.
- User reverts history: the selected message and all later messages are removed.

Acceptance criteria:

- Zero-message sessions do not appear in the list.
- First user prompt becomes the default title, capped to 30 characters.
- Row click opens the session.
- Secondary menu contains rename, duplicate, archive/restore, and pin/unpin.
- Pinned sessions sort before unpinned sessions.
- Unpinned sessions sort by latest update time.
- Revert confirmation text matches the destructive behavior.

### Conversation

Main path:

1. User opens the conversation from the main web surface.
2. Latest messages are visible without manual scrolling.
3. User sends a prompt.
4. Assistant streams or updates without freezing the screen.
5. Tool activity is visible but does not dominate the dialogue.

Counter-paths:

- Conversation is long: rendering stays lazy and recent messages remain fast to reach.
- Tool output is large: show a concise summary first and reveal details on demand.
- User needs to copy text: long press or selection mode should not break layout.
- User closes the conversation while agent runs: loop continues unless explicitly stopped.
- Error occurs: message explains provider/tool failure and preserves session state.

Acceptance criteria:

- Default open position is the latest message.
- While the user stays at the bottom, streaming drafts keep the list pinned to
  the newest output by scrolling to a dedicated bottom anchor rather than the
  top of the last message; manual scroll-away disables that auto-follow.
- A completed, idle session never jumps back to the top when the user reaches
  the bottom.
- Streaming output remains scrollable while it grows; if the user is already at
  the bottom, the list continues to follow the newest output.
- Path chips shown at the bottom of a conversation message open the referenced
  workspace file on the main surface instead of only entering text selection.
- Message list uses lazy rendering.
- Tool events are collapsed by default when they are not the main answer.
- Every message has a timestamp.
- Running state disables only actions that would corrupt the active loop.

Human retest archive, 2026-05-24:

- Confirmed the conversation bottom-follow behavior is materially improved
  after switching from last-message scrolling to a dedicated bottom anchor.
- Fixed issues from this retest must stay regression cases: no bottom bounce to
  top in idle sessions, no lost bottom-follow during final-answer streaming, no
  hidden running state while the agent is waiting on model or tool progress.
- Follow-up work should treat conversation UX regressions as loop-product
  regressions, not cosmetic polish, because they determine whether users can
  trust a long-running agent task.

### Workspace Files

Main path:

1. User opens Conversation from the main workspace surface.
2. User opens Files from the conversation secondary menu.
3. Workspace appears as a tree.
4. User taps a file to open it.
5. User opens the row menu only for management actions.

Counter-paths:

- Workspace has nested folders: tree expansion is stable and readable.
- File is HTML: default open action can display it in Flovera WebView.
- File is not HTML: user can open with another Android app.
- User shares a file out: app grants temporary read access.
- User shares a file into Flovera: file is copied to workspace root with a unique name.
- Rename conflicts or path escapes: operation returns an explicit error and does not alter data.

Acceptance criteria:

- File row click opens the file.
- Folder row click expands or collapses.
- Secondary menu contains open with, share, rename, and future destructive actions.
- Shared-in files land in the root with stable conflict naming.
- FileProvider authorities are scoped by application id.
- All file paths stay inside the active workspace.

### WebView Workspace Surface

Main path:

1. User opens the app.
2. The main screen is a full-screen workspace preview.
3. User opens the conversation secondary menu.
4. User selects an HTML file.
5. The selected HTML loads without browser chrome.
6. HTML can intentionally call controlled app events through `window.Flovera`.

Counter-paths:

- No HTML selected: show the Flovera dark empty state.
- Selected HTML disappears: clear the selected path and return to empty state.
- HTML wants app integration: it checks `window.Flovera` before calling.
- HTML wants notification behavior: permission is requested only at the point of use.
- External sites render partially because of remote CSP, anti-embed, login, or WebView support:
  treat this as a web compatibility issue, not necessarily a Flovera rendering bug.

Acceptance criteria:

- Main surface has no visible URL bar.
- Empty state text is minimal and visually consistent with Flovera.
- The `flovera` flavor uses the promoted design front-end: the primary display
  remains the workspace preview, the bottom command bar carries display state,
  and Conversation/Preview are opened from compact semantic controls instead of
  a debug-style multi-entry chrome.
- `window.Flovera.toast(...)` is supported.
- `window.Flovera.notify(JSON.stringify(...))` is supported.
- `window.Flovera.postEvent(JSON.stringify(...))` rejects unsupported event types explicitly.
- Agent rules mention available controlled app events.

Implementation note, 2026-05-31:

- Promoted the design-lane bottom command bar and visual system into the
  `flovera` flavor by enabling `design_frontend_style_enabled` for the main app.
- Aligned the `flovera` launcher resources with the promoted design lane so the
  production package no longer presents the older launcher identity.
- First-open configuration should remain visibly clean: no active session, no
  selected display target, no auto-opened seed HTML, and the empty main display
  remains the first surface even though seed files are present in the workspace.
- Verification gate: `BottomCommandBarInstrumentedTest`,
  `AgentScreenInteractionInstrumentedTest#mainSurfaceExposesAgentAndHtmlQuickPickerWhileConversationOwnsSecondaryEntries`,
  `AgentScreenInteractionInstrumentedTest#htmlQuickPickerOpensWorkspaceHtmlFromMainSurface`,
  and `AgentScreenInteractionInstrumentedTest#firstOpenConfigurationStartsEmptyAndUnselected`
  must pass on an update-only real-device APK.

### Provider And Configuration

Main path:

1. User opens Conversation from the main workspace surface.
2. User opens settings from the conversation secondary menu.
3. User selects provider and model.
4. User enters API key.
5. Agent loop uses that configuration without hardcoded secrets.

Counter-paths:

- User switches provider: API keys are scoped by provider.
- Key is missing: agent reports configuration error without starting a broken loop.
- Network tools are on by default: agent sees `fetch_url` and `download_file`.
- Network tools are explicitly off: agent does not see or call network tools.
- Project is opened publicly: source does not contain user secrets.

Acceptance criteria:

- `.env`, `setting.json`, API keys, and local paths are not hardcoded into source.
- Settings persist across app restarts.
- Provider settings are validated and normalized.
- Network tools default to enabled, remain inspectable in Settings, and preserve
  an explicit user-disabled choice.

## Product Backlog

These items are not committed implementation requirements yet. They are queued
so future work can be planned without losing the product direction.

Backlog maintenance rules:

- Every backlog item should state its current implementation status before
  listing future work.
- Status is evidence-based: only mark a capability implemented when source,
  tests, or device verification prove the behavior.
- When code changes complete or materially change a backlog item, update this
  document in the same change set unless the item is intentionally deferred.
- "Baseline implemented" means the user-facing path exists and is tested, but
  hardening, polish, or broader coverage still remains.

### Context Records And Compression

Status: Baseline implemented for context usage visibility and compression
markers. The app records context usage, displays a compact context ring and
details dialog, includes request-overhead/tool-catalog estimate components, and
shows compression dividers in conversation history. Remaining work is provider
reported/tokenizer-backed accuracy, auditability, and automatic compression
policy polish, including proactive compression that is not limited to threshold
or provider-error recovery.

- Track context usage for each agent run and session.
- Show how much context has been used, what was compressed, and what summary is
  currently active.
- Improve the context estimate so the compact ring does not create false
  confidence. The estimate should break down system prompt, workspace rules,
  recent history, current input, workspace listing, tool schema/catalog overhead,
  and provider/request-wrapper overhead where Flovera can reasonably estimate
  them.
- Label the number as estimated unless it comes from provider-reported usage or
  a tokenizer that matches the active model family. Do not present a precise
  percentage for unknown context windows or unverified model metadata.
- Add regression tests with long histories, large workspace listings, low
  context-window overrides, and tool-heavy configurations to prove the displayed
  percentage rises and compression thresholds trigger when expected.
- For known models, show context usage as a percentage of the configured model
  context window. For unknown models, show only absolute usage and the estimate
  source.
- Add a compact ring indicator for the active model context budget, starting
  with DeepSeek-specific model metadata before general provider discovery is
  mature.
- Tie automatic compression thresholds to the active model context window and
  the current estimated request size.
- When context is close to full, run a session handoff/compression skill,
  continue the conversation from the compressed state, and insert a visible
  conversation divider after the compressed summary.
- Add proactive compression as an explicit user/app action. The user should be
  able to request compression before the next run, and Flovera may suggest or
  trigger it when session history is large even if the provider has not failed
  yet. The compressed handoff must be visible in conversation history,
  reversible through existing session/workspace recovery paths where possible,
  and must not silently discard recent interrupted-run transcript or tool
  history.
- Persist compression records so the user can understand why an old detail was
  retained, summarized, or dropped.
- Treat compression as part of session history, not as invisible runtime state.

### Tool Progress Narration

Status: Baseline implemented. During a running agent loop, Flovera turns
completed tool events into compact deterministic assistant draft progress lines,
for example listing, reading, editing, Python, package, web, and inspection
events. The progress narration remains transient UI copy, while the final
persisted message stores bounded tool events and run events. Remaining
work is polish around longer runs and a future audit view if users need deeper
history.

- Add lightweight app-generated progress narration between tool calls so the
  user can see what the agent is doing before the final assistant answer.
- Keep this narration deterministic and derived from tool events, for example
  "Listed agent-app", "Read backend/agent.py", or "Edited frontend/app.js".
- Treat progress narration as transient run state by default, not permanent
  assistant content, unless a future audit view explicitly stores it.
- Do not ask the model to produce these lines; the point is low-latency,
  provider-independent visibility into current tool activity.
- Keep the copy compact so it improves observability without crowding the
  conversation or pretending to be model reasoning.

### Agent Run Events

Status: L2 implemented with an AgentRunEvent bus and a persisted conversation
transcript compatibility layer. Session messages still keep `content`,
`toolEvents`, and `runEvents` for runtime compatibility, and now also persist
`transcriptEvents` so the conversation can render status/tool/final-answer
entries as one chronological stream, with an `AgentRunEventAccumulator` chrono
buffer that preserves runtime event order (MODEL_TEXT_DELTA segments and
newly completed tool events are appended in arrival order, not grouped by type).
The first covered event sources are
thinking/compression status, completed tool calls, failure/interruption, and
final-answer streaming. Running-run guidance is now recorded as a
`user_guidance` transcript bubble followed by a lightweight `guidance` status,
so steering text stays visible at the time it was sent without becoming a fake
assistant message. Failure events include a bounded error category
(`provider`, `network`, `tool`, `permission`, `context`, or `unknown`) in the
session run events, checkpoint, workspace error log, and user-visible error
message, and the generated `.flovera/logs/...` and `.flovera/runs/...`
paths are conversation links that open the underlying log/checkpoint preview.
The conversation UI uses `transcriptEvents` when present, and falls back to
legacy `runEvents + content` for old sessions. Thinking rows include bounded
status text so a run never appears idle while it is waiting on the model/runtime.
This is observability for the run loop, not hidden reasoning.

- Represent each agent run as a sequence of user-visible runtime events:
  context checkpoint, optional compression, thinking/status, completed tools,
  interruption or final response.
- Persist run events with the session message so interruption and restore do
  not erase what happened during the run.
- Persist transcript events with the session message so new conversation UI can
  render one time-ordered stream without losing old session compatibility.
- Keep event details bounded and deterministic; do not expose private model
  reasoning as run-event content.
- Keep lifecycle event types stable: `run_started`, `run_completed`,
  `run_failed`, and `run_interrupted`.
- Use run events as the future checkpoint boundary for more precise
  compression and context accounting.
- Route runtime state through `AgentRunEvent` so UI drafts, run events, session
  persistence, notifications, and future compression checks consume the same
  run-state stream.
- Remaining work: add tool-start/tool-running events from tool entry points.
  The chrono buffer in `AgentRunEventAccumulator` now correctly interleaves
  MODEL_TEXT_DELTA segments, user guidance transcript events, guidance status,
  and tool-completion events in timestamp order, with adjacent text deltas
  coalesced only within the same contiguous text segment, never across tool or
  guidance boundaries.

### Runtime Context Retention

Status: Baseline implemented for persisted tool history. Tool events now carry
bounded retention metadata (`success`, `resultKind`, `outputChars`,
`outputTruncated`, `retentionPriority`, and `retentionReason`) at record time.
`RuntimeSessionHistory` no longer treats every persisted tool result as the
same kind of prompt history: it uses `ToolContextRetentionPolicy` to emit
`tool_context` slices where failed tools remain active-critical, recent command,
read, search, and network outputs stay full only while fresh, successful
artifact/file-write validation becomes structured memory, generic successes
become summaries, and status-only outputs are UI-only. Current Koog run-loop
tool results are still delivered directly inside the active run; this policy is
for cross-run prompt reconstruction and future compression/skills retention.

- Keep full session logs and conversation transcript available for UI/debug
  without forcing every tool result back into the next provider request.
- Preserve failed tool output with higher priority so retries and recovery do
  not lose the actionable error tail.
- Downgrade older successful tool results into summaries or structured facts
  instead of replaying large stdout/stderr blocks.
- Treat skill reads as future tool events that can reuse the same retention
  policy: active task gets high-priority context, compacted history keeps
  activation metadata and a path back to the skill body.
- Skill registry baseline is implemented with the standard editable
  `.flovera/skills/<skill-id>/SKILL.md` directory shape and
  `.flovera/skills/manifest.json` registration. Compact English descriptors
  for enabled skills are injected into the request from the current workspace
  registry; when a skill is relevant the agent reads the registered `SKILL.md`
  through normal `read_file`, and that read is retained as active-critical tool
  context. The settings console lists registered skills with bilingual
  descriptions and an enable switch. Disabling a skill only removes its English
  descriptor from the next request; the user and agent may still inspect or edit
  `.flovera/skills` directly. Built-in skills are seed files only and may be
  edited or replaced by the user/agent to match the local environment. The
  seeded Python workspace command skill documents the command-first Python
  path, while `flovera-skill-creator` documents the standard folder shape,
  `SKILL.md` frontmatter requirements, bilingual registration fields, enable
  state, and validation checks so the agent can create or update skills through
  ordinary workspace file tools.
- Remaining work: expand the extractor from generic summaries into richer
  structured file/artifact facts and polish the console skill management UI if
  large user registries need grouping or search.


### Interleaved Model Conversation Streaming

Status: L2 implemented for persisted transcript order. Flovera has a
session-level `transcriptEvents` stream where the `AgentRunEventAccumulator`
chrono buffer preserves MODEL_TEXT_DELTA segments, user guidance events, and
completed tool events in chronological order. Text-before-tool,
text-between-tools, and text-after-tools ordering is correct in
`transcriptEvents`; real-device debug verification also covers guidance inserted
between model text and a tool call. Rapid model text deltas are coalesced into
contiguous chrono text segments and draft UI updates are throttled so streaming
output does not rebuild the full transcript on every token. Remaining work is
richer tool lifecycle coverage and handling provider-specific streaming finish
anomalies without losing the partial transcript.

- Add a separate streaming conversation track where the model can emit
  assistant text before, between, and after tool calls.
- This is distinct from app-generated tool progress narration: the text comes
  from the model/runtime event stream and can carry planning, observations, and
  intermediate explanations.
- Investigate whether Koog exposes a stable event, trace, or streaming API for
  assistant deltas around tool calls before replacing the current `AIAgent.run`
  flow.
- Keep Koog as the main route if fake and real-provider evidence shows
  assistant text before a tool call, tool-call frames, and assistant text after
  tool results all flow through stable Koog events.
- If Koog cannot expose the needed events, use a controlled OpenAI-compatible
  tool loop owned by Flovera only for providers that need interleaved assistant
  messages.
- Current evidence favors a Koog-first path: Koog exposes typed streaming frames
  and event handlers, and Flovera's Koog streaming strategy can compile a fake
  interleaving case with text before a tool call and text after the tool result.
  Real-device debug verification shows those text deltas reach
  `AgentRunEvent`, and a live DeepSeek run produced the expected
  `assistant_text -> tool_call -> assistant_text -> tool_call` transcript shape
  even though the provider stream ended without a finish reason. Flovera must
  therefore preserve interleaved model text through `MODEL_TEXT_DELTA`
  transcript events, not by relying on final output alone.
- Streaming frames must be forwarded to `AgentRunEvent` as the provider flow is
  collected. Do not call `toList()` before forwarding frames, because that
  makes the UI receive text only after the provider stream has already ended.
- Persist only user-meaningful assistant text in session history; keep raw
  trace details expandable and bounded so long tool runs do not flood the
  conversation.

### Final Assistant Response Streaming

Status: L1 implemented. `KoogAgentRuntime.runStreaming` now uses a
Flovera-owned Koog streaming strategy that keeps the single-run workspace tool
loop but routes provider `StreamFrame.TextDelta` events through
`AgentRunEvent(MODEL_TEXT_DELTA)`. Providers without streaming support fall
back to the original non-streaming `run()` path before any final delta is
emitted, and the final session still persists one complete assistant message.

- Stream the final assistant answer into the conversation instead of waiting for
  `AIAgent.run` to return a complete string.
- This is distinct from tool progress narration: model text deltas are optional
  real provider output and may appear before, between, or after tool calls
  depending on provider/model behavior.
- Update the running assistant draft incrementally so long responses start
  reading immediately and can show provider latency or stalls clearly.
- Keep the persisted session message as one final assistant message after the
  stream completes; partial deltas should remain transient unless the run fails.
- Prefer runtime/provider streaming APIs when they preserve existing tool-call
  behavior. Current coverage preserves the Koog single-run tool loop for text
  and tool-call `StreamFrame` responses; richer interleaved model narration is
  still a later loop-product milestone.

### Workspace Search Performance

Status: Baseline implemented. `workspace_search` exists as a local grep-like
agent tool with path scoping, literal/regex modes, context lines, globs, ignore
handling, bounded output, and debug timing/candidate diagnostics. Remaining
work is incremental indexing, cancellation, and paged/streamed result delivery.

- Improve workspace search around measured latency, result quality, and
  responsiveness before expanding it into heavier retrieval features.
- Build or reuse an incremental workspace file index for searchable text,
  metadata, file type, modified time, and lightweight content summaries.
- Exclude ignored files, generated outputs, binary blobs, oversized files, and
  app-private metadata unless the user explicitly includes them.
- Debounce repeated search input, cancel stale searches, and stream or page large
  result sets so the UI stays responsive on big workspaces.
- Prefer stable ranking signals such as exact path matches, filename matches,
  recent edits, pinned files, and workspace-open context before fuzzy matches.
- Record search timing, candidate counts, skipped-file reasons, and top-result
  quality signals so performance regressions are visible.
- Keep search local to the workspace unless a future permission explicitly
  allows broader device or connector search.

### Targeted Cache Hit Rate Improvements

Status: Deferred behind cache diagnostics design. Any existing caching is
incidental to specific UI/runtime paths, not a measured product capability. The
next safe step is a read-only diagnostics layer that reports cacheable surfaces,
candidate keys, and invalidation inputs before introducing persistent caches.

- Improve cache hit rate through specific cache keys and invalidation rules, not
  by broadening stale-cache tolerance.
- Identify cacheable surfaces separately: workspace file metadata, search index
  shards, rendered previews, provider/model capability metadata, tool manifests,
  and stable prompt fragments.
- Use content hashes, file mtimes, settings versions, tool versions, and model
  capability versions as invalidation inputs so correctness remains explainable.
- Record hit, miss, bypass, and invalidation reasons in debug logs or lightweight
  diagnostics.
- Make cache scope explicit: app-global, workspace-local, session-local, or
  conversation-local.
- Add low-risk warmup paths for common workspace views and recently used files
  without blocking app startup.
- Provide a user-visible cache clear path for corrupted or privacy-sensitive
  local cache state.

### Agent-Controlled App Settings

Status: Partially implemented. Flovera exposes `.flovera/settings-view.json`,
`.flovera/capabilities.json`, settings proposals, Safe/Assisted/Full Authority
mode, automatic snapshots before Full Authority settings application, and an
audit log. Remaining work is broader high-impact settings coverage and better
user-facing inspection of applied changes.

- Treat workspace snapshots and restore as the safety floor before broad agent
  authority is enabled.
- Add a workspace-scoped `.flovera/` directory for Flovera metadata and
  agent-visible app configuration.
- Expose selected settings to the agent through `.flovera`, including provider,
  API configuration, active model, tool permissions, theme mode, theme color,
  custom request body templates, and app capability flags.
- Add an Agent Authority Mode setting:
  - Safe: agent can read app capabilities and selected settings, but cannot
    modify app behavior.
  - Assisted: agent can propose setting changes and the user confirms before
    they take effect.
  - Full Authority: agent can directly modify broad app settings after an
    automatic restore point is created.
- Current implementation supports Safe, Assisted, and Full Authority for
  workspace files plus low/mid-risk app settings. Full Authority still uses the
  same settings proposal schema, but applies proposals automatically after a
  workspace snapshot and writes an audit record under
  `.flovera/logs/full-authority.jsonl`.
- Support a high-trust mode where the user can hand broad app-control authority
  to the agent, with every change logged, inspectable, and reversible.
- Let the agent change low-risk settings first, such as theme mode, theme color,
  language, selected HTML, HTML pins, active provider, active model, network
  switch, tool permissions, and max iterations.
- Later expand authority to high-impact settings such as custom request bodies,
  URL routes, tool manifests, MCP manifests, and provider-specific request
  options.
- Keep secrets and source-separated config rules intact: no API key or private
  local path should be committed into source.
- Expose secret existence through references instead of plaintext, such as
  `apiKeyRef: deepseek.default`, so the agent can select a key slot without
  reading or copying the secret value.

### Workspace Secret Manager

Status: Baseline implemented. Flovera now has a user-managed Secrets panel at
the same surface level as Preview, Snapshots, Skills, AGENT.md, and Settings.
Secrets are stored in the app settings store, which is encrypted by the existing
settings persistence layer. Agent-visible entries enter the request only as
environment-variable refs and are synchronized into `.flovera/settings-view.json`
with a suffix preview; plaintext values are not written to workspace metadata or
prompt text. Workspace Python commands receive allowed secrets through
system-assigned stable environment variable refs. If a user directly pastes an API key into chat, Flovera's
policy is notify-only: do not block, mask, or rewrite the user's content.

Remaining scope:
- Add a Groovy/JVM-safe runtime accessor if JVM scripts need direct secret use;
  do not claim `System.getenv` support until that path is verified.
- Consider per-secret usage audit only if it stays lightweight and does not
  make ordinary API-backed skills harder to create.

### Custom URL Routing And Request Model

Status: Partially implemented for provider routing metadata. Provider profiles,
transport metadata, request hooks, field omission/addition, and app-owned
OpenAI-compatible routes exist. The new workspace-owned `python_http` path means
user-created AI apps can now use ordinary HTTP/SSE without requiring app-level
custom URL routes. Remaining work is a general app settings UI/schema for
advanced request templates and auditable route changes.

- Add a configurable URL routing model for workspace HTML and app-controlled
  internal routes.
- Let advanced settings define request body templates and per-route behavior
  when the app calls provider APIs or app tools.
- Treat new model onboarding as part of the request model: each provider/model
  entry should declare context window, token usage source, tool support,
  thinking/reasoning replay requirements, streaming support, and compression
  thresholds.
- Add DeepSeek-specific thinking intensity controls when supported by the active
  DeepSeek model, and expose the chosen intensity through settings-view and
  auditable settings proposals.
- Support model capabilities from built-in catalog entries, provider discovery
  when available, and user overrides when the provider cannot reliably expose
  the metadata.
- Never present context percentages as exact unless the model context window and
  usage source are known; otherwise label them as estimates.
- Record route and request-template changes as auditable settings changes.

### Agent Rules And Runtime Control

Status: Partially implemented. Workspace `AGENT.md` rules are injected into the
agent prompt, users can interrupt runs, queue follow-up inputs, mark queued
inputs as guidance, and active runs use an Android foreground service with
status notifications. Guidance sent while a run is active is visible in the
conversation as a user bubble in the active run transcript after the next
completed tool result, with a lightweight waiting status while it is pending.
Interrupts persist the active draft transcript plus a lightweight
`run_interrupted` status instead of a full assistant bubble, and notification
copy now says partial transcript/tool history was saved. Settings include an
explicit opt-in background keep-alive mode that keeps Flovera foreground-service
visible for long workspace work, requests notification permission, opens the
Android battery-optimization exception flow, and restores from the persisted
setting after a service restart. Remaining work is clearer UI separation
between system rules and workspace rules, stronger cancellation coverage for
active provider/tool work, notification actions, OEM-specific autostart
diagnostics, overlay/floating-window research, and more explicit background
lifecycle diagnostics.

- Separate system rules from user/workspace rules:
  - System rules are app-owned product and safety constraints.
  - User rules are workspace-owned instructions, stored in `AGENT.md` or a
    future `.flovera/rules/` structure.
  - The UI should make the boundary visible so users know which rules they can
    edit and which rules are product constraints.
- Allow users to forcibly interrupt a running agent loop. Interruption should
  cancel active model/tool work when possible, mark the run as interrupted, and
  leave the session in a coherent state.
- Support sending information while the agent is already working:
  - Queue mode: the new message waits for the current run to finish, then starts
    the next run automatically.
  - Guided-thinking mode: the new message is treated as steering/context for
    the current run if the runtime can accept it safely.
- Keep queued or steering messages visible in the conversation so the user can
  see what will happen next and cancel pending input before it runs.
- Let the agent continue background work when Flovera is not focused, within
  Android lifecycle limits. Baseline implemented through
  `AgentRunForegroundService` for active runs.
- Show ongoing background status in the Android notification shade, including
  current run state and failure/success outcome. Remaining work: notification
  actions such as interrupt/resume.
- Offer a stronger opt-in keep-alive mode for long-running local work. Baseline
  implemented as a settings-controlled foreground-service notification plus
  Android notification/battery-optimization permission prompts. Remaining work:
  evaluate whether a small overlay/floating-window affordance can lawfully and
  usefully keep Flovera foreground-adjacent on Android. This must be gated by
  explicit user permission, clear visible state, and a fallback path when the
  overlay permission is denied or the OEM background policy still stops work.
- Background execution must preserve existing settings, session persistence,
  error logs, and notification permission boundaries.

### Slash Commands And Input Modes

Status: Deferred. Flovera does not currently parse slash commands in the
conversation composer. Input modes are exposed through normal send, queue,
guide, and stop controls. The backlog target is lightweight command shortcuts
that improve repeat workflows without turning the composer into a hidden shell.

- Add slash commands for common conversation modes, starting with commands such
  as `/plan` and `/compose` when their semantics are stable.
- Slash commands should map to app-owned modes or prompt wrappers with visible
  state, not to arbitrary hidden instructions that users cannot inspect.
- Commands must be discoverable from the composer or settings and should degrade
  to ordinary text when not recognized.
- Keep command output in the normal conversation transcript so mode changes are
  visible and reversible.
- Acceptance criteria before implementation:
  - `/plan` and `/compose` have precise product semantics and tests;
  - unknown slash commands do not silently change agent behavior;
  - localized Chinese UI and prompt behavior remain natural when the command is
    used in a Chinese conversation.

### System Prompt Optimization

Status: Partially implemented. The app-owned prompt is split into stable
sections, embeds stable Flovera runtime boundaries, discourages repeated
`.flovera` rediscovery, and warns against treating mocked files or JSON handoff
protocols as proof of interactive artifact completion. Remaining work is golden
prompt snapshots, token-cost diagnostics, and regression checks for high-risk
capability claims.

- Audit the app-owned system prompt for duplicated rules, stale project history,
  and instructions that can be represented as structured capability metadata.
- Split prompt construction into stable system constraints, app capability
  manifests, workspace rules, conversation state, and task-specific context.
- Keep permission, privacy, restore, and source-separation boundaries as
  non-optional system constraints; prompt shortening must not weaken them.
- Use deterministic prompt templates so changes can be reviewed with diffs and
  tested with golden prompt snapshots.
- Measure prompt token cost by section and show the major contributors in
  diagnostics.
- Prefer compact structured summaries for app state, settings, tools, and
  workspace context instead of repeating prose instructions.
- Stable Flovera runtime boundaries should be embedded in the app-owned system
  prompt instead of rediscovered by repeatedly reading `.flovera` metadata:
  bounded Python runs, workspace files, WebView preview, documented bridge
  methods, app-owned provider credentials, and Android lifecycle limits.
- The prompt must explicitly prevent agents from treating project-specific JSON
  handoff protocols, mocked outputs, or syntax checks as proof of a real
  interactive artifact loop.
- Add regression checks for high-risk behaviors such as secret exposure,
  permission bypass, destructive edits, and tool availability mismatches.

### Web Search And Tool Expansion

Status: Partially implemented for web search. Brave-backed `web_search`,
`fetch_url`, and `download_file` tools exist behind Settings controls. Network
tools default to enabled with a user override, and web search defaults on when a
Brave Search API key is present. Remaining work is the proposal and approval
flow for additional restricted tools and MCP integrations.

- Add Brave Search API support as the first non-provider web search path.
- Expose web search as an agent tool behind an explicit permission setting.
- Keep the default network posture release-friendly: network-enabled workflows
  are available by default, Brave search is available by default when the key is
  configured, and both switches stay inspectable in Settings.
- Let the agent propose additional restricted tools and MCP integrations.
- Add a user approval flow before proposed tools or MCP entries become active.
- Keep tool availability visible to the agent only when the corresponding user
  permission is enabled.

### MCP Endpoint Tool Registry

Status: Deferred with a concrete first implementation path. Flovera should
integrate MCP as a controlled client for approved HTTP/SSE or Streamable HTTP
endpoints, not as an Android-local npm, npx, stdio, or subprocess runtime.
Koog's MCP support can be used to turn discovered MCP tools into Koog tools at
agent-run creation time, but Flovera must own endpoint approval, permission
gates, output limits, credentials, auditing, failure handling, and user-visible
capability state.

- First phase: support only externally running or app-owned HTTP MCP endpoints.
  Do not install npm packages, run `npx`, start arbitrary subprocesses, or
  expose stdio MCP servers from the Android app.
- Store MCP configuration as approved app/workspace capability state, created
  through proposal and approval. A proposed MCP entry should include name,
  endpoint URL, transport, requested capabilities, permission class, optional
  credential references, and user-facing risk notes.
- At the start of each agent run, load enabled MCP endpoints, discover their
  tool schemas, convert approved tools into the Koog `ToolRegistry`, and merge
  them with Flovera's built-in workspace tools. Runtime discovery affects the
  next run boundary, not an already-running agent loop.
- Apply per-endpoint and per-tool controls: allowlist/denylist, timeout,
  bounded input/output, network permission checks, audit events, and clear
  fallback status if discovery or invocation fails.
- Handle credentials as references such as `apiKeyRef`, never as plaintext
  values visible to the agent or workspace files. Prefer app-owned request
  mediation when a tool needs a Flovera-managed secret.
- Treat npm MCP packages as external-server instructions only. Flovera may
  record that a package such as `@scope/server-name` can be run outside Android
  and connected by endpoint, but it must not claim on-device npm support.
- Acceptance criteria before implementation:
  - an approved HTTP MCP endpoint contributes at least one callable tool to a
    new agent run;
  - disabled or failed endpoints do not break built-in tools or the agent run;
  - tool invocation is persisted as bounded run/tool events;
  - settings and `.flovera/capabilities.json` expose which MCP endpoints and
    tools are enabled;
  - unapproved MCP proposals remain inert and cannot be called by the agent.

### Flovera MCP Adapter Skill

Status: Backlog, not implemented in the runtime. The current Groovy/JVM
workspace command substrate is strong enough for Flovera to rewrite selected MCP
servers as workspace-local JVM/Groovy adapters, but this should first land as a
skill and scaffold process rather than as a large built-in MCP runtime.

The intent is to let Flovera analyze an MCP repository, README, package
metadata, or tool schema as a source specification, then generate and test a
workspace adapter under a stable shape such as:

```text
.flovera/mcp/adapters/<name>/
  manifest.json
  maven.json
  adapter.groovy
  tests.json
  adapter-report.md
```

- The skill should classify the source MCP server by dependency shape:
  filesystem, HTTP/REST, Markdown/HTML, SQLite, Git/JGit, SaaS API, browser,
  shell, Docker, native binary, or unsupported desktop/runtime dependency.
- The generated adapter should expose a small JVM/Groovy contract for listing
  tools/resources and calling them, while keeping all file access inside the
  workspace and all dependency declarations inside the adapter-local
  `maven.json`.
- Adapter smoke tests must run through `workspace_command_run groovy` with
  `FLOVERA_JVM_MAVEN_CONFIG` pointing at the adapter-local Maven config so
  stale workspace dependencies do not pollute the run.
- The skill should produce an `adapter-report.md` that records implemented
  tools, unsupported tools, permissions, Maven dependencies, verification
  results, and Android/JVM incompatibilities found during the run.
- The first useful target classes are filesystem, fetch/HTTP, Markdown/HTML,
  SQLite, REST API, and later JGit/GitHub-style adapters. Playwright, Docker,
  arbitrary shell daemons, npm/npx execution, and native binary servers should
  be classified as unsupported or external-endpoint-only.
- This backlog item is complete only when the skill can take at least one simple
  MCP-style source package/spec, generate a JVM/Groovy adapter, run smoke tests,
  and leave a report without modifying Flovera's built-in tool registry. A later
  product step may promote successful adapters into an app-owned registry or
  `mcp_call` gateway.

### Workspace Shell And JGit

Status: Baseline command runtime started. Flovera now exposes one default
execution entry, `workspace_command_run`, with argv-shaped execution, workspace
cwd, timeouts, output limits, snapshots, and audit/tool events. The first
supported runtimes are `python`/`python3`, embedded local `git`/JGit,
app-owned `android` permission/status commands, and an experimental
Full-Authority-only `groovy` adapter, routed through a command gateway that
classifies risk, checks authority mode, writes `.flovera/logs/workspace-command.jsonl`,
and dispatches to approved runtime adapters. Python reuses the existing
Flovera-owned Python runtime for workspace scripts and `python -c` code. Groovy
compiles workspace scripts to JVM class files, converts them with D8, then runs
the generated dex through `DexClassLoader`; direct `GroovyShell` loading is not
used because Android cannot load JVM class files directly. Groovy also scans
workspace `libs/**/*.jar`, compiles scripts against those jars, converts the
jars to dex, and caches script/library dex under `.flovera/runtime/jvm-artifacts`.
The same JVM artifact layer can resolve direct Maven coordinates declared in
`libs/maven.json` or `.flovera/jvm/maven.json`, downloading POM/JAR files into
the workspace runtime cache, parsing common compile/runtime transitive
dependencies, and then feeding those jars into the same D8/classloader path.
Groovy JVM preparation and execution are delegated to an Android service running
in the isolated `:jvmworker` app process, so D8/Groovy peaks and worker death are
separated from the UI process.
Heavy JVM preparation is now guarded by a serialized throttled scheduler:
Maven resolution, artifact download, library D8 conversion, Groovy compilation,
and script D8 conversion write progress to `.flovera/logs/jvm-build.jsonl`.
Library jars are converted one jar at a time into resource-preserving dex jars
and then loaded as a multi-dex classpath. This keeps non-class jar resources
such as `META-INF/services`, properties, schemas, and templates alongside
`classes.dex`, which expands the usable JVM substrate for libraries that rely on
classpath resources while still lowering D8 peak memory for heavy document
stacks such as POI and PDFBox. The scheduler reuses checkpointed cache outputs
and inserts adaptive cool-down windows so repeated document-library runs slow
down instead of saturating the Android app process. The current phase writes
`.flovera/runtime/jvm-artifacts/build-state.json`, checks
`.flovera/runtime/jvm-artifacts/cancel.flag` at stage boundaries, consumes that
cancel marker after the first observed cancellation so the next JVM run can
continue from checkpointed caches, lowers Groovy worker thread priority, and
classifies Maven/D8/class-loading/Groovy/runtime failures in tool stderr.
Single Groovy runs can also pass `FLOVERA_JVM_MAVEN_CONFIG` to use one
workspace-relative Maven config file for that run instead of merging the default
`libs/maven.json` and `.flovera/jvm/maven.json`, which keeps one-off
compatibility tests from inheriting stale heavy dependency sets. Process crashes
and Android historical exit reasons are mirrored to `.flovera/logs/app-crash.jsonl` on the next app start so
whole-process failures are diagnosable even when no session message is written.
This supports pure JVM jars and direct Maven coordinates as reusable library
sources, including jar resources that survive dex packaging. Full Maven/Gradle
builds, BOMs, exclusions, classifiers, Android-missing Java SE APIs, and advanced
conflict mediation remain deferred. The older direct evaluator tool
`python_run` is disabled by default to keep the request tool schema smaller and
reduce routing ambiguity; it can be restored as a fallback through
`pythonRunToolFallbackEnabled` in app settings/settings proposals. This is not
Android shell access and does not enable `sh`, `bash`, `npm`, daemons,
shell operators, remotes, push, or arbitrary OS commands. Git is local-only
through embedded JGit and supports `init`, `status`, `diff`, `log`, `show`,
`branch`, `add`, and `commit`. Android command support currently exposes app
info, permission status, and system permission page intents; it does not grant
permissions without user action or expose Android shell.

- Done: expose a workspace-scoped command surface for selected command
  runtimes, starting with Python when shell-style execution is more natural than
  direct `python_run` code.
- Done: keep `python_run` out of default tool registration; expose it only as a
  settings-controlled fallback for direct evaluator/session-global workflows.
- Done: centralize command risk classification, authority checks, and command
  audit logging in the command gateway so Groovy/Git/JGit can reuse the same
  execution boundary.
- Done: add a Groovy runtime spike under Full Authority using the same workspace
  cwd, timeout, output, snapshot, and audit boundary.
- Done: add the first JVM artifact layer for Groovy: workspace `libs/**/*.jar`
  classpath discovery, D8 conversion, dex caching, and runtime class loading.
- Done: add direct Maven coordinate resolution through `libs/maven.json` and
  `.flovera/jvm/maven.json`, with Maven Central defaults, runtime cache, and
  basic compile/runtime transitive dependency parsing.
- Done: add serialized, throttled JVM build scheduling with progress logs and
  cache-hit observability to reduce repeated Maven/D8/Groovy compile pressure
  on Android devices without disabling heavy document-library tasks.
- Done: split JVM library D8 into per-jar conversions and enable app-level
  crash/exit logging so low-memory kills and uncaught crashes leave a workspace
  diagnostic trail.
- Done: add JVM build state, cancel-flag checks, worker background thread
  priority, and user-visible failure classification for Maven, D8, class
  loading, Groovy compile, cancellation, and runtime failures.
- Done: move Groovy JVM preparation and execution behind a bound service running
  in the isolated `:jvmworker` process. Tool output includes the worker process
  marker, and worker IPC failures are reported separately from script failures.
- Done: add local embedded JGit as a `workspace_command_run` profile with
  workspace-bounded `init/status/diff/log/show/branch/add/commit`, bounded
  output, unsupported-command errors, and the same audit log boundary.
- Done: add an app-owned Android command profile for `android app info`,
  `android permission status`, and permission/system settings intents, plus a
  user-facing Permissions panel whose Grant all flow batches missing runtime
  permissions and then opens each missing special Android authorization page
  in sequence.
- Next hardening target: promote the worker to a foreground service during very
  long JVM builds if Android background process pressure still kills the worker
  before checkpointed work can resume.
- Next target: keep expanding command runtimes only when they can reuse the same
  execution boundary and have clear Android/workspace permission behavior.
- Keep ordinary file operations as app-owned tools (`read`, `edit`, `search`,
  artifact diagnostics) because those are safer, more inspectable, and easier
  for the UI to connect to workspace state.
- Treat Git as a CLI-like workspace capability from the agent's point of view,
  not as a long list of narrow UI buttons. The implemented subset is local-only
  JGit and intentionally excludes push/remote credential flows.
- Do not add repository mutation such as checkout, reset, clean, rebase, or
  force operations until restore/snapshot behavior and user confirmation
  boundaries are explicit.
- Verification gate:
  - instrumented tests cover JGit init/status/add/commit/diff/log through
    `workspace_command_run`;
  - large Git output is bounded by the command gateway output limit;
  - mutation commands are recorded in `.flovera/logs/workspace-command.jsonl`;
  - unsupported Git behavior fails with a precise explanation rather than a
    misleading partial result.

### Agent Capability Expansion

Status: Ongoing holding backlog. Initial production surfaces now include
workspace files, workspace search, bounded Python, artifact inspection,
WebView/local HTTP previews, artifact jobs, and workspace-owned `python_http`
HTTP/SSE backends. The remaining items here should be pulled into focused
backlog entries only when they have a concrete user workflow and verification
path.

- Treat Python and HTML as the first two production surfaces, not as the whole
  capability boundary.
- Treat generated interactive work as portable workspace artifacts first, with
  Flovera metadata only as an enhancement layer. The design is captured in
  `docs/01-interactive-workspace-artifact-runtime.md`.
- Keep future expansion organized around what lets the agent sense, execute,
  verify, and connect work inside the workspace:
  - file format engines for office documents, PDF, images, SQLite, archives,
    and structured data;
  - render and inspect tools for validating generated artifacts before the user
    opens them;
  - local native tools for work that does not fit inside Python, such as PDF
    rendering, screenshots, OCR, and media processing;
  - retrieval and index layers for workspace text, file metadata, structured
    document content, and later embeddings;
  - external connectors such as Git, browser, cloud drive, mail, calendar, and
    provider APIs, each behind explicit permission and audit boundaries;
  - task orchestration primitives such as snapshots, artifact records,
    resumable plans, and permission-scoped tool manifests.
- Do not expand this backlog by adding another unconstrained runtime. Prefer
  small app-owned tools with schemas, timeouts, output limits, event records,
  snapshots, and tests.
- Keep this backlog item as the holding area for capability-boundary ideas while
  the current implementation focus remains the controlled Python runtime.

### Interactive Workspace Artifact Runtime

Status: Baseline implemented. Flovera discovers `flovera.app.json`, validates
schema v1, opens WebView/local HTTP previews, runs declared `python_job`
actions, persists bounded job state under `.flovera/jobs/`, exposes legacy
`window.Flovera.runAction/getJob/cancelJob`, seeds a portable workspace chat
demo, and now supports workspace-owned `python_http` backends with standard
HTTP/SSE routes and user-provided API keys. The runtime also exposes baseline
server lifecycle status, reuse, stop, and restart controls through the artifact
picker. Local HTTP previews with a declared `python_http` backend must not
silently fall back to static HTML when startup fails. App startup and backend
startup are separate phases: Flovera opens the shell first, then starts the
selected backend asynchronously with a visible loading state and stale-result
guard for rapid HTML switching or pinning. Startup waits through real-device
Python cold-start latency and then reports a server status error.
Remaining work is richer artifact validation and broader UX polish.

- Goal: let the agent create a portable project that can be opened, run, edited,
  and iterated inside Flovera without inventing a project-specific JSON handoff
  protocol.
- Product boundary:
  - artifacts are ordinary projects first;
  - `flovera.app.json` is an optional adapter, not a private project format;
  - Flovera owns lifecycle, permissions, timeout, job status, and recovery;
  - Python, HTML, local HTTP, and future renderers are runtime adapters, not the
    product goal.
- Implementation sequence:
  1. Done: define and validate `flovera.app.json` schema v1.
  2. Done: discover manifests in the workspace and expose artifact entries.
  3. Done: open manifest preview entrypoints with WebView/local HTTP paths.
  4. Done: add manifest actions, starting with `python_job` on top of the
     controlled Python runtime.
  5. Done: persist bounded job state under `.flovera/jobs/` and mark stale
     running jobs as `interrupted` after restart.
  6. Done: expose a narrow legacy preview bridge such as
     `window.Flovera.runAction(id, input)` for declared actions.
  7. Done: rebuild the current `agent-app` as a portable workspace chat demo.
  8. Done: add workspace-owned `python_http` local HTTP/SSE backends so generated
     projects can connect frontend and backend through ordinary web protocols.
  9. Done: add python_http lifecycle controls, reuse semantics, stop/restart
     behavior, and status diagnostics in the artifact picker.
  10. Done: remove silent static fallback for failed python_http preview startup
      and extend startup tolerance for real-device Python cold starts.
  11. Done: move python_http startup off app initialization into an asynchronous
      preview-loading phase with stale-result protection.
  12. Remaining: add render-level validation beyond the current WebView
      visibility probe and make artifact diagnostics more user-facing.
- Acceptance criteria:
  - generated artifacts remain understandable and runnable outside Flovera with
    README and standard commands;
  - inside Flovera, a user can open the artifact, start a declared action, see
    stdout/stderr/result, inspect changed files, and ask the agent to iterate;
  - an interrupted job preserves status and output instead of pretending to be
    complete;
  - a declared local_http/python_http preview either opens through the backend
    URL or reports a backend startup error, never a misleading static fallback;
  - opening Flovera, pinning HTML, or switching HTML does not synchronously wait
    for Python startup;
  - the main flow does not rely on each project inventing its own
    `input.json`/`output.json` protocol.

### Conversation Rendering And Markdown Fidelity

Status: Baseline implemented for finalized conversation messages. Flovera now
uses Markwon/CommonMark rendering for finalized Markdown message bodies,
including richer list, code, link, quote, and table handling through the Android
TextView/Spannable path. The display layer still normalizes unsafe control
characters/newlines and repairs common UTF-8 mojibake when it is clearly safer
than the original text. Streaming draft messages deliberately use a lightweight
plain-text path that renders each runtime-throttled draft immediately while the
runtime coalesces adjacent text deltas, so token-by-token output stays visible
without blocking conversation scrolling; finalized messages then re-render with
the full Markdown renderer.
Remaining work is inline workspace-path links inside the rendered Markdown
surface, richer code-block styling, math/scientific formula rendering, and
regression examples from real malformed provider output.

- Done: add a low-risk display normalization layer for control characters,
  mixed newlines, BOM characters, and common UTF-8 mojibake.
- Done: render finalized Markdown through Markwon instead of Flovera's small
  hand-rolled Markdown parser.
- Done: keep streaming drafts on a lightweight plain-text path and throttle
  runtime draft updates to preserve scroll responsiveness during provider token
  output.
- Improve Markdown parsing/rendering for mixed Chinese/English, code fences,
  lists, inline paths, tables, escaped characters, and streaming updates.
- Add regression examples from real broken conversation output instead of only
  synthetic Markdown snippets.
- Preserve text selection and file path links while improving Markdown
  rendering.
- Acceptance criteria:
  - common Markdown generated by provider output renders without mojibake,
    broken code fences, or layout-corrupting spans;
  - streaming partial Markdown does not permanently corrupt the completed
    message after finalization;
  - malformed provider output degrades to readable plain text instead of
    unreadable glyph noise.

### Hidden Reference Demo And Artifact Registration Diagnostics

Status: Baseline implemented. Flovera seeds a visible portable workspace demo,
exposes an agent-facing `artifact_diagnose` tool that reports whether a
workspace app manifest was discovered, registered, or rejected with validation
diagnostics, and can include an app-owned hidden reference demo shape for
comparison without exposing a second demo in the user's normal workspace.
Remaining work is richer registration UI diagnostics and broader artifact
validation.

- Done: keep a known-good reference demo aligned with the original workspace shape.
  It should be invisible in normal user browsing, but available to Flovera
  internals and agent guidance as a concrete comparison for manifest shape,
  frontend/backend connection, local HTTP/SSE routes, actions, outputs, and
  registration expectations.
- Done: expose an app registration diagnostic tool that reports whether a
  generated app was discovered, whether `flovera.app.json` parsed, which fields
  were accepted/rejected, which preview/backend entrypoints resolved, and why
  an app is absent from the picker.
- Update artifact-generation guidance so HTML is explicitly designed for
  Android/mobile WebView first: responsive viewport, tap targets, safe bottom
  area, no first-load autofocus, no zero-height roots, and no desktop-only
  assumptions.
- Acceptance criteria:
  - the agent can compare a generated app against the hidden reference without
    copying it into the user's visible workspace;
  - a user or agent can run one diagnostic and tell whether app registration
    succeeded, failed validation, failed discovery, or failed preview/backend
    startup;
  - generated HTML artifacts default to mobile-friendly layout and controls
    before desktop polish.

### WebView Artifact Runtime Hardening

Status: Baseline implemented after a real failure. A generated artifact
previously opened as a black screen on Android WebView. Flovera now injects a
viewport helper into workspace WebView pages, publishes
`--flovera-viewport-height`, `--flovera-viewport-width`,
`--flovera-safe-bottom`, and `window.FloveraViewport`, runs a first-load visible
content probe after dynamic-page startup has a short grace period, and reports
specific likely invisible-content causes such as missing body, zero viewport,
empty body, or no visible candidates. Remaining work is a user-facing artifact
validator or `artifact_inspect` successor with richer DOM diagnostics.

- Treat Android WebView layout differences as a Flovera runtime responsibility,
  not something every generated artifact must rediscover.
- Add a stable viewport contract for workspace web artifacts, including usable
  height, safe-area offsets, keyboard/resize behavior, and bottom app chrome
  avoidance.
- Prefer an app-owned injected helper or CSS variable, such as a Flovera viewport
  height value, so generated pages do not depend on fragile
  `html, body { height: 100%; overflow: hidden; }` assumptions.
- Update the app-owned system prompt and artifact generation rules to tell the
  agent:
  - avoid mobile WebView black-screen layout patterns;
  - do not default to `autofocus` on first-load inputs;
  - keep first-screen content visible in Android WebView;
  - use Flovera-provided viewport helpers when available.
- Extend artifact validation beyond file existence and manifest checks:
  - load the selected artifact in real WebView or an equivalent inspection path;
  - verify key DOM elements have non-zero size;
  - verify the primary content is inside the visible viewport;
  - report likely causes such as zero-height `html/body`, offscreen roots,
    covered controls, missing local HTTP routes, or blocked resources.
- Acceptance criteria:
  - a generated chat-style web artifact opens visibly on a real Android device;
  - `artifact_inspect` or its successor can distinguish "HTML parses" from
    "WebView actually renders usable content";
  - prompt constraints reduce repeated bad output, but runtime adaptation and
    validation remain the primary safety net.

### Android App Permission Expansion

Status: Permission entry and command API mapping implemented. Flovera declares a broad
development-stage permission set and exposes a top-level Permissions panel
beside preview/snapshots/skills/secrets. Its Grant all action requests all
missing runtime permissions together and then automatically walks the user
through the Android system pages for battery optimization, overlay, all-files,
unknown-app install, and exact-alarm access. Individual permission entries
remain available as recovery paths for vendor-specific settings behavior.
The agent can inspect permission state through `workspace_command_run`
`["android","permission","status"]` and open a specific system page through
`["android","permission","open","<permission-id>"]`. The same command profile
now exposes permission-gated Android system APIs:

- notifications: post and cancel;
- camera and microphone: capture/record directly into workspace files;
- location: use a fresh system cache when available, otherwise query enabled
  fused/network/GPS/passive providers concurrently, with an explicit
  last-known fallback. Results expose source, age, accuracy, and enabled
  providers so the agent does not mistake a missing GPS fix for total location
  failure;
- contacts: list, search, create, and delete;
- calendar: list calendars/events, create events, and delete events;
- media: list images/video/audio and import selected MediaStore items into the
  workspace;
- Bluetooth: list paired devices and run bounded discovery;
- overlay: show and hide bounded app-owned overlay messages;
- shared storage: list and import files when all-files access is granted;
- package installer: open Android's installer for a workspace APK;
- exact alarms: schedule and cancel notification reminders;
- network: bounded HTTP GET while the Flovera Network setting is enabled;
- foreground service: start, stop, and inspect Flovera's foreground keep-alive;
- intents: permission pages, URLs/maps, share sheets, and dialer.
- desktop operation: an Accessibility-backed `android ui` profile can inspect
  the active semantic tree, filter inspection by text/description/resource-id,
  expand a subtree by node id, capture a workspace screenshot, launch apps,
  click/set text by text/description/resource-id/node-id, tap/swipe/use global
  actions, swipe until target text appears, and wait for expected text or
  package changes. Mutating actions require stable action IDs, persist the last
  confirmed action, update app-owned runtime feedback so the user can see that
  Flovera is operating the phone, and verify the resulting semantic state
  before success. Login, CAPTCHA, biometric, payment, protected-dialog,
  lock-screen, and unverified states become explicit user-intervention
  checkpoints rather than guessed continuation.

Desktop operation currently uses semantic accessibility data for agent
reasoning. Screenshots are captured as workspace PNG diagnostics, but the
provider request layer remains text-only; pixel-level model vision is not yet
claimed.

Every action checks the corresponding Android permission before execution.
Binary results and imports use explicit workspace-relative output paths.
Installer, share, dial, URL, and permission actions intentionally open Android
system UI and do not pretend user confirmation has already happened. This is
still an app-owned adapter, not Android shell access.

- Keep inventory and status generation centralized in the app permission
  capability list so UI and agent metadata do not drift.
- Candidate permission surfaces include scoped media/document access,
  notifications, camera, microphone, location, contacts, calendar, nearby
  devices, clipboard-related flows, accessibility integrations, and background
  execution limits where Android allows them.
- Done: expose the declared permission-backed capabilities through the existing
  single `workspace_command_run` tool as the `android` command profile, keeping
  explicit argv schemas, permission checks, output limits, workspace path
  validation, and command audit events.
- Keep future Android permissions and actions in the same centralized
  permission-to-command mapping. Adding a manifest permission without a
  corresponding status entry and command API is incomplete.
- Keep high-risk permissions opt-in and reversible, with settings that show
  current grant state and what agent/tool features depend on the grant.
- Accessibility desktop operation is implemented as an explicit, reversible
  permission-gated command surface. It must stop at lock screens and password
  fields, persist an intervention checkpoint, diagnose disconnected/denied
  Accessibility state without pretending Android allows silent re-enable, and
  require an explicit continue/resume plus current-screen re-identification
  before any further mutating action.

### Controlled Python Runtime

Status: Baseline implemented. The conversation-bound, blocking Python runtime is
  implemented as a workspace-scoped agent tool, with stdout/stderr/exit code,
  duration reporting, timeout/cancellation, workspace file boundaries, tool
  manifest support, artifact inspection, and a small production package layer
  for document, spreadsheet, PDF, Markdown, and templating workflows.
- Workspace-owned `python_http` artifact backends are implemented as a separate
  local HTTP/SSE adapter for interactive artifacts. This does not change the
  core `python_run` rule: conversation Python remains bounded and blocking.
- Keep the product boundary intact: Kotlin/Android owns permissions, secrets,
  WebView, notifications, lifecycle, timeout, and restore; Python only runs
  inside the controlled runtime.
- Treat native Python dependencies such as `lxml` and Pillow as APK/runtime
  decisions, not dynamic pure-Python catalog entries.
- Remaining backlog is runtime polish, not the initial Python enablement:
  - maintain and expand the Flovera pure-Python wheel catalog for dynamic
    installs. Each entry must include package name, version, wheel URL, sha256,
    top-level imports, dependencies, and whether the wheel is `py3-none-any`;
  - add lightweight pure-Python utilities such as `pyyaml`, `faker`, and
    `sympy` only when a concrete user task needs them;
  - improve package provenance, dependency closure checks, storage location,
    rollback behavior, and diagnostics before full PyPI resolver behavior is
    considered;
  - keep Python execution blocking and conversation-bound unless a future
    Android lifecycle design explicitly expands that boundary.

### Rendering Coverage

Status: Baseline implemented for common workspace outputs. Flovera can preview
HTML, Markdown, plain text, code with line numbers, JSON, CSV, images, and the
first page of PDFs, while `artifact_inspect` can inspect JSON, HTML, Office
documents, PDFs, images, and text. Unsupported formats show a clear built-in
preview fallback instead of pretending to render. Remaining work is richer
Office, multi-page PDF, archives, SQLite, and media rendering.

- Extend workspace rendering beyond HTML and Markdown.
- Candidate formats: plain text, images, PDF, JSON, CSV, office documents, and
  code previews.
- Prefer lightweight native or WebView-based renderers before adding heavy
  dependencies.
- Each renderer needs clear fallback behavior when Android cannot render the
  format locally.

### Workspace Snapshots

Status: Baseline implemented. Manual and automatic workspace snapshots exist,
restore/delete are wired through the controller, snapshots cover workspace
files and `.flovera` metadata, and a regression test verifies file counts after
restore. Remaining work is stronger UX around destructive restore confirmation
and clearer session-level restore context.

- Add workspace snapshot save and restore.
- Snapshot scope should cover workspace files, `.flovera` metadata, selected
  HTML state, and enough session metadata to make restore understandable.
- Support user-named snapshots for archival use.
- Create automatic restore points before the agent changes high-impact settings,
  custom routes, request templates, tool manifests, MCP manifests, or broad
  authority settings.
- Make restore an app-owned capability, not something that depends on the agent
  repairing its own mistake.
- Restore should be explicit and confirm destructive overwrites.

### Main Surface HTML Quick Picker

Status: Baseline implemented. The main surface exposes an `HTML` quick picker
beside the Agent entry, opens workspace apps and HTML files without browser
chrome, sorts pinned/recent paths first, and preserves artifact-aware
`local_http`/`python_http` opening semantics. Remaining work is visual polish for
very large workspaces and denser artifact metadata.

- Add a quick HTML selector beside the main Agent entry.
- The button opens a popup list of HTML files in the workspace.
- The list supports pinning so frequently used HTML surfaces stay at the top.
- Sorting should favor pinned files first, then recently opened or recently
  changed files.
- Selecting an item opens it directly in the main WebView without exposing URL
  chrome.

### Conversation File Path Links

Status: Baseline implemented. Conversation messages conservatively detect
existing workspace-relative file paths and expose clickable bottom entries that
close the conversation and open HTML in the main WebView or other previewable
formats in the native preview surface. Agent failure messages can also open
generated `.flovera/logs/...` and `.flovera/runs/...` diagnostics, so run
failures are inspectable without manual file browsing. The parent bubble must
not own a tap/long-press gesture that steals those bottom path clicks. Remaining
work is inline text-link polish, text-selection polish, and stale-path status
copy.

- Detect workspace-relative file paths in user, assistant, and error messages.
- Make detected paths clickable without breaking text selection or Markdown
  rendering.
- On click, open HTML files in the main WebView and other previewable formats in
  the existing native preview surface.
- If the path no longer exists, report a concise status instead of navigating to
  a blank preview.
- Keep path detection conservative so normal prose and URLs are not treated as
  workspace files.

## Review Checklist

Use this checklist before calling a feature product-ready:

- Main click semantics match user intent.
- Main surface exposes a single Agent entry; secondary management actions live under Conversation.
- Empty state creates no persistent junk.
- Repeated use does not create management debt.
- Secondary actions are discoverable but not visually dominant.
- Long histories and large workspaces stay navigable.
- Running agent work is not interrupted by hiding UI.
- Permissions are requested only when the user reaches the relevant action.
- Error states preserve data and explain the failed layer.
- The agent is told about capabilities it can actually use.
- Non-human verification covers deterministic protocol, state, file, event, and error boundaries before manual dogfood.
- A real-device verification path exists for the behavior, or the gap is documented.
- Real-device verification preserves the existing Flovera install, app data, and permission state.

## How To Extend This Document

When a new design problem is discovered:

1. Add the concrete failure to the anti-pattern library.
2. Add or update the corresponding experience model.
3. Add an acceptance criterion that would have caught the problem earlier.
4. If practical, add an instrumented test or verifier check.
5. Commit the documentation and implementation separately unless the doc change is
   directly required to explain the implementation.
