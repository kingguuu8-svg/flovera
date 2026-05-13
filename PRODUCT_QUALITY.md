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
| WebView | Empty web surface is bright or unrelated to app tone | Looks like a broken browser rather than product empty state | Dark neutral empty state matching Flovera visual language |
| WebView | Workspace HTML can trigger app behavior without a named interface | Creates hidden capabilities and security confusion | Expose controlled, documented `window.Flovera` events |
| Permission | App requests permissions at cold start before a user action | Creates distrust and startup friction | Ask only at the point of use, with clear product context |
| Config | API keys, provider choices, or workspace paths are hardcoded | Blocks sharing, open source, and user control | Store runtime config outside source and keep secrets out of git |
| Verification | Manual visual clicking is the only way to test a path | Slow and brittle for iterative development | Prefer instrumentation, adb, semantics, and scriptable journeys |

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

1. User opens Files from the main menu.
2. Workspace appears as a tree.
3. User taps a file to open it.
4. User opens the row menu only for management actions.

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
3. User selects an HTML file from the menu.
4. The selected HTML loads without browser chrome.
5. HTML can intentionally call controlled app events through `window.Flovera`.

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

1. User opens settings from the app menu.
2. User selects provider and model.
3. User enters API key.
4. Agent loop uses that configuration without hardcoded secrets.

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

## Review Checklist

Use this checklist before calling a feature product-ready:

- Main click semantics match user intent.
- Empty state creates no persistent junk.
- Repeated use does not create management debt.
- Secondary actions are discoverable but not visually dominant.
- Long histories and large workspaces stay navigable.
- Running agent work is not interrupted by hiding UI.
- Permissions are requested only when the user reaches the relevant action.
- Error states preserve data and explain the failed layer.
- The agent is told about capabilities it can actually use.
- A real-device verification path exists for the behavior, or the gap is documented.

## How To Extend This Document

When a new design problem is discovered:

1. Add the concrete failure to the anti-pattern library.
2. Add or update the corresponding experience model.
3. Add an acceptance criterion that would have caught the problem earlier.
4. If practical, add an instrumented test or verifier check.
5. Commit the documentation and implementation separately unless the doc change is
   directly required to explain the implementation.
