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
5. Add or update an instrumented/user-journey test when the behavior can regress.
6. Run the standard Android verifier on a real device when the change affects UI,
   app lifecycle, sessions, workspace files, WebView, permissions, or release output.
7. Commit the change independently.

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
- Message list uses lazy rendering.
- Tool events are collapsed by default when they are not the main answer.
- Every message has a timestamp.
- Running state disables only actions that would corrupt the active loop.

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

### Context Records And Compression

- Track context usage for each agent run and session.
- Show how much context has been used, what was compressed, and what summary is
  currently active.
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

### Interleaved Model Conversation Streaming

- Add a separate streaming conversation track where the model can emit
  assistant text before, between, and after tool calls.
- This is distinct from app-generated tool progress narration: the text comes
  from the model/runtime event stream and can carry planning, observations, and
  intermediate explanations.
- Investigate whether Koog exposes a stable event, trace, or streaming API for
  assistant deltas around tool calls before replacing the current `AIAgent.run`
  flow.
- If Koog cannot expose the needed events, consider a controlled
  OpenAI-compatible tool loop owned by Flovera for providers that support
  interleaved assistant messages.
- Persist only user-meaningful assistant text in session history; keep raw
  trace details expandable and bounded so long tool runs do not flood the
  conversation.

### Final Assistant Response Streaming

- Stream the final assistant answer into the conversation instead of waiting for
  `AIAgent.run` to return a complete string.
- This is distinct from tool progress narration and interleaved model
  conversation: it only concerns the final natural-language answer after the
  agent has enough information to respond.
- Update the running assistant draft incrementally so long responses start
  reading immediately and can show provider latency or stalls clearly.
- Keep the persisted session message as one final assistant message after the
  stream completes; partial deltas should remain transient unless the run fails.
- Prefer runtime/provider streaming APIs when they preserve existing tool-call
  behavior. If Koog cannot stream final text from the current `AIAgent.run`
  path, evaluate a narrow runtime adapter rather than weakening tool routing.

### Workspace Search Performance

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

- Add Brave Search API support as the first non-provider web search path.
- Expose web search as an agent tool behind an explicit permission setting.
- Let the agent propose additional restricted tools and MCP integrations.
- Add a user approval flow before proposed tools or MCP entries become active.
- Keep tool availability visible to the agent only when the corresponding user
  permission is enabled.

### Agent Capability Expansion

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
  1. define and validate `flovera.app.json` schema v1;
  2. discover manifests in the workspace and show artifact entries;
  3. open manifest preview entrypoints, starting with WebView paths;
  4. add manifest actions, starting with `python_job` on top of the controlled
     Python runtime;
  5. persist bounded job state under `.flovera/jobs/` and mark stale running jobs
     as `interrupted` after restart;
  6. expose a narrow preview bridge such as `window.Flovera.runAction(id, input)`
     for declared actions;
  7. rebuild the current `agent-app` as the first portable validation demo.
- Acceptance criteria:
  - generated artifacts remain understandable and runnable outside Flovera with
    README and standard commands;
  - inside Flovera, a user can open the artifact, start a declared action, see
    stdout/stderr/result, inspect changed files, and ask the agent to iterate;
  - an interrupted job preserves status and output instead of pretending to be
    complete;
  - the main flow does not rely on each project inventing its own
    `input.json`/`output.json` protocol.

### Android App Permission Expansion

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

- Baseline status: the conversation-bound, blocking Python runtime is
  implemented as a workspace-scoped agent tool, with stdout/stderr/exit code,
  duration reporting, timeout/cancellation, workspace file boundaries, tool
  manifest support, artifact inspection, and a small production package layer
  for document, spreadsheet, PDF, Markdown, and templating workflows.
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

- Extend workspace rendering beyond HTML and Markdown.
- Candidate formats: plain text, images, PDF, JSON, CSV, office documents, and
  code previews.
- Prefer lightweight native or WebView-based renderers before adding heavy
  dependencies.
- Each renderer needs clear fallback behavior when Android cannot render the
  format locally.

### Workspace Snapshots

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

- Add a quick HTML selector beside the main Agent entry.
- The button opens a popup list of HTML files in the workspace.
- The list supports pinning so frequently used HTML surfaces stay at the top.
- Sorting should favor pinned files first, then recently opened or recently
  changed files.
- Selecting an item opens it directly in the main WebView without exposing URL
  chrome.

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
