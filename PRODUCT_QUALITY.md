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
- `window.Flovera.toast(...)` is supported.
- `window.Flovera.notify(JSON.stringify(...))` is supported.
- `window.Flovera.postEvent(JSON.stringify(...))` rejects unsupported event types explicitly.
- Agent rules mention available controlled app events.

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
- Network tools are off: agent does not see or call network tools.
- Network tools are on: agent sees `fetch_url` and `download_file`.
- Project is opened publicly: source does not contain user secrets.

Acceptance criteria:

- `.env`, `setting.json`, API keys, and local paths are not hardcoded into source.
- Settings persist across app restarts.
- Provider settings are validated and normalized.
- Network tools default to disabled.

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
policy polish.

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


### Interleaved Model Conversation Streaming

Status: L2 implemented for persisted transcript order. Flovera has a
session-level `transcriptEvents` stream where the `AgentRunEventAccumulator`
chrono buffer preserves MODEL_TEXT_DELTA segments, user guidance events, and
completed tool events in chronological order. Text-before-tool,
text-between-tools, and text-after-tools ordering is correct in
`transcriptEvents`; real-device debug verification also covers guidance inserted
between model text and a tool call. Remaining work is richer tool lifecycle
coverage and handling provider-specific streaming finish anomalies without
losing the partial transcript.

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
inputs as guidance, and status notifications exist for active runs. Guidance
sent while a run is active is visible in the conversation as a user bubble in
the active run transcript, followed by a lightweight queued status. Interrupts
persist the active draft transcript plus a lightweight `run_interrupted` status
instead of a full assistant bubble. Remaining work is clearer UI separation
between system rules and workspace rules, stronger cancellation coverage for
active provider/tool work, and more explicit background lifecycle diagnostics.

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
  Android lifecycle limits.
- Show ongoing background status in the Android notification shade, including
  current run state, interruption action, and failure/success outcome.
- Background execution must preserve existing settings, session persistence,
  error logs, and notification permission boundaries.

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
`fetch_url`, and `download_file` tools exist behind explicit network/search
settings. Remaining work is the proposal and approval flow for additional
restricted tools and MCP integrations.

- Add Brave Search API support as the first non-provider web search path.
- Expose web search as an agent tool behind an explicit permission setting.
- Let the agent propose additional restricted tools and MCP integrations.
- Add a user approval flow before proposed tools or MCP entries become active.
- Keep tool availability visible to the agent only when the corresponding user
  permission is enabled.

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

Status: Backlog. Conversation rendering is usable for plain text and basic
Markdown, but real dogfood still exposes malformed Markdown, mojibake-like
garbling, and edge cases where generated text does not render as the model
intended. Treat this as conversation product quality, not a cosmetic renderer
detail, because unclear output can change user decisions during agent work.

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

Status: Partially implemented. Flovera seeds a visible portable workspace demo
and now exposes an agent-facing `artifact_diagnose` tool that reports whether a
workspace app manifest was discovered, registered, or rejected with validation
diagnostics. Remaining work is an app-owned hidden reference demo the agent can
inspect as a concrete comparison without exposing the reference project to
ordinary users.

- Keep a known-good reference demo aligned with the original workspace shape.
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
content probe, and reports likely invisible-content causes. Remaining work is a
user-facing artifact validator or `artifact_inspect` successor with richer DOM
diagnostics.

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

Status: Deferred behind permission product design. Existing permissions cover
current app needs, but no new high-impact Android capability has been designed,
gated, or exposed as an agent tool. Do not add broad permissions speculatively;
each new permission must start from a concrete user workflow, in-app grant UI,
denial fallback, narrow agent tool schema, and audit record.

- Inventory additional Android permissions and app capabilities that could make
  Flovera more useful, then group them by user value, privacy risk, and Android
  version behavior before implementation.
- Candidate permission surfaces include scoped media/document access,
  notifications, camera, microphone, location, contacts, calendar, nearby
  devices, clipboard-related flows, accessibility integrations, and background
  execution limits where Android allows them.
- Each permission must have a concrete product use case, an in-app explanation,
  a runtime request path, a denial fallback, and an audit trail when the agent
  can influence behavior through that permission.
- Do not expose newly granted Android capabilities directly to the agent until
  they are represented as narrow app-owned tools with explicit schemas,
  permission gates, timeouts, output limits, and event records.
- Keep high-risk permissions opt-in and reversible, with settings that show
  current grant state and what agent/tool features depend on the grant.
- Treat accessibility, contacts, calendar, microphone, camera, and precise
  location as high-impact capabilities that require separate design approval
  before implementation.

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
