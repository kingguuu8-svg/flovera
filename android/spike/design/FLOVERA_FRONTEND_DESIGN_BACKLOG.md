# Flovera Frontend Design Backlog

This document records design backlog items for Flovera's mobile frontend direction.

## Design Thesis

Flovera should not become another chat-first AI app. It is a mobile AI work surface where the user primarily needs to see what the agent produced and keep enough conversation context to steer the next step.

The two largest information planes are:

- `Main Display`: AI output, rendered artifact, preview, diff, page, document, file result, or runnable state.
- `Conversation`: user instruction, correction, clarification, and agent summary.

Workspace, files, logs, run state, permissions, and settings are supporting layers. They should appear when they affect result inspection, trust, recovery, or the next action, not as a permanent desktop-style workbench.

## Current Style Assessment

- The current direction has a plain AI-tool tone: calm, low-risk, and broadly acceptable.
- The issue is not that it has AI flavor. Major AI tools use similar quiet, low-chroma, rounded, panel-based language.
- The issue is that the current style still feels under-polished and generic. It lacks a Flovera-specific visual system.
- The next step should not be a louder visual style. It should be stronger product semantics, tighter hierarchy, and more refined interaction states.

## Backlog

## Layout Target V1 - Main Display / Conversation / Supporting Panels

- Main Display is the root surface. It owns artifact inspection and always exposes the current display target when a target exists.
- Conversation is a first-class page. It owns instruction, steering, run status, and transcript, and must provide a one-step return to the current Main Display.
- Supporting panels are contextual overlays. They never become roots: if opened from Main Display they return to Main Display; if opened from Conversation they return to Conversation.
- Preview is for choosing what the Main Display shows. Files is for workspace file inspection and management. Files may open a file into Main Display, but it is not the primary result picker.
- Empty workspace keeps Main Display as a large empty root surface and moves the creation command to a bottom command dock.
- Conversation has three layout states: idle input, running/interrupt, and queued guidance. These states must not all collapse into identical footer chrome.
- Floating Flovera entry remains a single anchor. It should not hide the current display identity, and supporting actions should stay behind the drawer unless they are high frequency.

## Implemented Checkpoint - 2026-05-29

- Design verification lane is in place as `Flovera Design`, package `com.flovera.design`, with a separate test APK `com.flovera.design.test`.
- The main screen no longer exposes competing top-level `HTML` and `Agent` floating entries. A single Flovera icon is the visible entry point.
- Empty workspace now presents direct project creation: Flovera icon, prompt input, send action, and fixed starter prompts for a scientific calculator and a snake game.
- The Flovera icon expands into two drawer bubbles while the original icon remains visible: `Agent` opens conversation and `Preview` opens preview selection.
- When a result or selected preview exists, the Flovera icon moves to the lower-right corner as the persistent entry.
- Conversation now opens as a full-page surface instead of a padded card-style popup. The old title/subtitle chrome is removed, high-frequency `New` is exposed as a compact header button, and the always-visible network settings row is hidden from the conversation footer.
- Supporting panels now return to their entry point. `Preview`, `Files`, `Snapshots`, `AGENT.md`, and `Settings` opened from the main display return to the main display; the same panels opened from Conversation return to Conversation.
- Main Display now exposes the current display target as a compact root-level label when a target exists.
- Empty workspace keeps the center as display space and places project creation in a bottom command dock.
- Conversation now exposes the current display target in its header; tapping it returns to Main Display in one step.
- Conversation footer now has a separate running-state strip instead of making idle input, running, interrupt, and queued guidance all share the same visual state.
- Preview selection now prioritizes `Current Display`, `Generated Apps`, and `HTML Display Files`, clarifying that Preview chooses what Main Display renders while Files remains workspace inspection/management.
- Remaining polish: add motion to the icon drawer, replace the temporary launcher foreground with a purpose-built Flovera character/icon, and tune empty-state density after real project output is available.

## Layout Direction V2 - Bottom Command Bar / Message Overlay

- Replace scattered floating controls with a bottom protection bar reserved outside the WebView/content area.
- The bottom protection bar is part of `Main Display`, not a separate navigation tab. Its purpose is to protect high-frequency controls from WebView overlap while preserving maximum visible result area.
- The bar should contain the current preview/display state, such as `index.html / text/html`.
- The preview state remains actionable inside the bar: tapping it opens preview/display selection. This avoids the semantic duplication of a bottom-left floating preview pill plus a right-side preview drawer action.
- The bar should contain a direct Conversation entry, either the Flovera icon or a compact message icon.
- Add the lightest possible message input to the bar so the user can send short instructions to Flovera without opening the full Conversation page.
- Short bar messages should use the same session semantics as Conversation. Preset and empty-project creation messages create a new session first; ordinary follow-up messages go to the active session unless product rules say otherwise.
- Agent feedback from the lightweight input should appear as fading floating message overlays above the WebView/content plane.
- Floating message overlays are for transient status and short replies only: examples include `正在修改 index.html`, `已切换到科学计算器预览`, `缺少 API key`, or `生成了 2 个可预览页面`.
- Long assistant replies, tool logs, context details, settings, and durable transcript history stay in the full Conversation page.
- The overlay must be dismissible or self-fading, must not block critical WebView interaction for long, and must provide a path into Conversation for details.
- This direction supersedes the V1 right-bottom Flovera drawer if implemented: preview switching, Conversation entry, and lightweight messaging should converge into the bottom protection bar instead of remaining as duplicated floating entry points.

## Implemented Checkpoint - 2026-05-29 Bottom Command Bar V2

- The right-bottom Flovera drawer and separate left-bottom preview floating pill have been replaced by a bottom protection bar.
- The bottom bar is outside the WebView/content area, so the WebView keeps a protected visible result surface instead of having controls layered over its bottom edge.
- The bottom bar now carries the current display state, Conversation entry, lightweight message input, and send/interrupt/settings action.
- The compact bottom bar revision reduces the protected area to a preview/status edge label plus one message input and one contextual action button.
- The action button is compositional: empty input opens Conversation/Flovera, pending text sends, and missing API opens Settings.
- Starter prompts are no longer inside the protected bar; they float just above it so empty and non-empty preview states keep the same bar height.
- Follow-up correction: the main-display action button must not expose interrupt/pause. Its only normal semantics are `empty input -> Agent` and `has input -> send`; interrupt remains inside Conversation.
- Starter prompts disappear immediately after use so they do not remain as stale creation affordances during the newly-started project run.
- Selecting an artifact/file/preview target always returns to Main Display, even when the selection surface was opened from Conversation.
- Main Display transient feedback should mirror Conversation as discrete fading blocks, not as a single collapsed status string.
- The current display state remains actionable from the bar and opens Preview selection. This keeps preview switching in one protected control layer instead of duplicating it across floating corners.
- Lightweight bottom-bar messages use existing agent session semantics: empty-project creation can create a new session, ordinary messages use the active session, and active-run messages use the existing queued-message path.
- Missing API state is exposed in the bar through a settings action instead of leaving the user with an inert input.
- Agent status and short feedback can appear as a transient floating message overlay above the content plane; tapping the overlay opens full Conversation.
- Full Conversation remains the durable transcript and tool-log surface. The overlay is intentionally limited to transient status and short replies.
- Main Display transient feedback now reuses the same Conversation event-block pipeline as the full Conversation page. Text, tool, run-state, and transcript events are not re-parsed into a second summary model.
- Each transient Conversation block owns its own lifecycle: it appears as the shared block type, waits briefly, then fades independently instead of fading the whole transcript as one unit.
- Conversation may keep streaming assistant text inside the full transcript, but Main Display overlay should not mirror streaming text. During a run it can show tool/status blocks; assistant text should appear as a complete single block after it is committed to the session.
- Main Display overlay filters out dynamic/meta transcript events such as omitted-tool summaries, compression rows, streaming-character statistics, and generic thinking updates. Those remain available only in the full Conversation transcript.
- Overlay fade timing is bound to a stable logical block id, not to changing block content. If an eligible block updates while visible, it may update visually, but the original fade timer is not restarted.
- The bottom display status line is always actionable, including `No preview`, so selecting a preview/display target is available before any artifact is shown.
- Main Display carries a compact running indicator in the bottom status line instead of relying on transient overlay events for loop state.
- Conversation keeps loop state in the footer run-state strip only; the header no longer repeats running status when there is no current display target.
- Main Display and Conversation input areas must align to the IME top edge with edge-to-edge insets instead of being covered by or floating far above the keyboard.
- Main Display overlay text blocks truncate oversized content with ellipsis; full text remains available by opening Conversation.
- Main Display overlay is constrained to the lower third of the WebView/content plane. Oversized text truncates to two lines, and overlay blocks are non-clickable so they do not compete with WebView interaction.
- Overlay truncation uses plain Compose text for the two-line display instead of rich Markdown rendering, so ellipsis is visible and the bubble height stays stable.
- IME alignment avoids stacking keyboard and navigation-bar bottom insets; when the keyboard is visible, the main and Conversation input areas use the IME edge as the bottom reference.
- Main Display bottom bar must not apply `imePadding` to the bar Surface itself. That inflates the protected bar and leaves a blank band between the input and keyboard; the main screen should rely on window resize for vertical placement and only apply navigation-bar padding when the IME is hidden.
- Supporting panels keep entry-origin return behavior through the existing panel stack.

## Remaining Scope From P0/P1/P2

The V1 implementation completed only part of the backlog. Bottom Command Bar V2 closes the layout-level items below, but broader visual-system work remains separate from this layout pass.

### P0 Remaining

- Completed for V2: the default mobile model is result-first with bottom command bar plus full Conversation as a secondary first-class page.
- Completed for V2: lightweight messages route through existing session semantics, with empty-project creation creating a new session and active-run follow-ups using the queued-message path.
- Completed for V2: persistent chrome is reduced to display state, Conversation entry, lightweight input, and one contextual action for send/interrupt/settings.
- Completed for V2: the floating Flovera drawer is replaced by the bottom protection bar for now.
- Completed for V2: rare supporting tools stay behind Conversation overflow/supporting panels until usage proves they deserve a bottom-bar slot.

### P1 Remaining

- Completed for V2: bottom command bar has idle, missing API, running interrupt, and queued-message paths through existing controller behavior.
- Completed for V2: fading overlay has status/short-feedback behavior and tap-to-expand into Conversation, with per-event blocks shared with the full Conversation renderer.
- Completed for V2: compact touch targets are implemented for display, Conversation, settings, send, and interrupt.
- Completed for V2 layout scope: motion and visual-system decisions are documented as a later visual-design pass, not part of the current layout backlog.

### P2 Remaining

- Completed for V2: Preview and Settings have direct bottom-bar paths when they affect the next action; Files, snapshots, logs, diagnostics, and `AGENT.md` remain in Conversation overflow/supporting panels.
- Completed for V2: bottom-bar safe area is handled with navigation bar and IME padding, and the WebView is kept outside the bottom protection bar.
- Completed for V2: overlay tap-to-expand provides a route into Conversation for durable detail.
- Completed for V2: dedicated bottom-bar instrumentation now verifies protected-bar semantic entry points and the removal of floating `Agent` / `HTML` dual entries.

### P0 - Product Information Model

- Define the relationship between `Main Display` and `Conversation` on mobile.
- Decide whether the default screen is result-first, conversation-first, or a two-plane hybrid.
- Define when supporting workspace details appear.
- Define the minimum persistent chrome required for navigation, status, and action.
- Optimize the main screen toward a single interaction entry. The current split between `HTML` and `Agent` entries creates two competing starts; evaluate whether they can become one unified entry with mode/context selection behind it.
- Treat conversation expansion as a primary product direction. The current popup model constrains visibility and weakens the conversation plane.
- Define the Flovera icon as the unified interaction anchor. It replaces top-level `HTML` / `Agent` competition while keeping both functions available inside an integrated entry.

### P0 - Mobile Minimalism

- Treat minimalism as maximizing effective visual information area, not adding empty space.
- Expand the visible area for `Main Display` and `Conversation`.
- Collapse secondary options into integrated entries: bottom sheets, drawers, contextual toolbars, command palettes, or overflow menus.
- Avoid copying desktop panes directly into mobile layout.
- Remove or hide non-decision labels in the conversation UI. Current labels such as `对话` and `提示` in the top-left area do not add useful task information.
- Move low-frequency Web settings out of the conversation's always-visible area. This overlaps with the main backlog and should be handled as one settings-surface cleanup, not duplicated as a separate design-only feature.

### P1 - Layout Exploration

- Explore result-dominant layout: main display owns most of the screen, conversation is bottom input/sheet.
- Explore conversation-dominant layout: conversation owns the screen, result opens as a persistent preview plane.
- Explore dual-plane layout: `Display` and `Talk` are first-class modes with fast switching.
- Evaluate each option by visible result area, conversation continuity, one-handed operation, and discoverability of supporting tools.
- Explore replacing the conversation popup with a full conversation page.
- Explore a full-page conversation layout where the input, message stream, and current result reference are visible without modal chrome.
- Compare full-page conversation against bottom-sheet conversation by available text area, keyboard behavior, and return path to the main display.

### P0 - Unified Flovera Entry

- Empty state: replace `选择一个东西来预览` with a direct prompt input and send action.
- Empty-state prompt text: `和 Flovera 对话来创建项目`.
- Empty-state suggestions start with fixed templates such as `做一个科学计算器` and `做一个贪吃蛇小游戏`.
- Suggestion chips can later become personalized by LLM based on workspace history, user preference, or current context.
- Place the existing Flovera icon next to the empty-state input as the conversational anchor.
- When a display result exists, move the Flovera icon to the lower-right corner as a persistent floating entry.
- Tapping the icon opens a drawer-like expansion: the original icon remains visible and two bubble actions slide out from it.
- Bubble actions:
  - `Agent`: message bubble icon. Opens conversation.
  - `预览`: canvas/board icon. Opens or selects the preview/display target.
- Keep `预览` visible even while already on the preview plane, because it can later support choosing what to preview.
- Use the current Flovera icon for the anchor now. Preserve room for future animation or character replacement without requiring the first implementation to solve mascot design.

### P1 - Visual System

- Establish Flovera's primary accent and surface scale.
- Reduce generic AI visual tells: unfocused glow, template gradients, decorative panels, and overly soft card stacks.
- Define compact typography rules for mobile agent UI.
- Define status colors for run state, permission state, errors, and success.
- Make icon language consistent across result, conversation, files, run state, and settings.

### P1 - Component Polish

- Define complete states for primary action, secondary action, text input, attachment entry, result preview, run status, and permission prompts.
- Add visible feedback for copy, export, retry, cancel, and apply actions.
- Keep controls stable when labels, counters, or progress values change.
- Ensure touch targets and spacing remain comfortable without wasting vertical area.
- Slim down conversation header, footer, and configuration controls.
- Keep conversation controls focused on sending, attaching, interrupting/canceling, and switching context. Move explanatory labels and settings controls into secondary entries.

### P2 - Supporting Layers

- Define how files appear only when selecting, attaching, opening, or reviewing AI output.
- Define how logs and diagnostics are accessed without becoming a default surface.
- Define permission flows as decision moments, not permanent warning panels.
- Define settings as a compact entry, not a competing main tab unless user research proves otherwise.

### P2 - Verification

- Use the separate Flovera Design Android lane documented in `FLOVERA_DESIGN_VERIFICATION.md`.
- Create mobile viewport checks for main display area, conversation area, and integrated entry discoverability.
- Verify text does not overflow compact controls.
- Verify supporting sheets/drawers do not hide the primary action unexpectedly.
- Prefer semantic or command-driven verification paths for Android where possible.

## Open Decisions

- Should `Main Display` and `Conversation` be simultaneously visible by default?
- Should result inspection be a mode, a sheet, or the root surface?
- How much conversation history is needed while inspecting output?
- Which supporting entries deserve persistent icons, and which belong under one integrated menu?
- What is the smallest useful status indicator for agent progress on mobile?
- Should the current `HTML` and `Agent` main entries be fully removed from the top level once the Flovera icon anchor exists?
- If conversation becomes a full page, what is the fastest return path to the main display?
- Which conversation controls are truly needed above the fold?
- What exact bubble animation best communicates drawer expansion without wasting time or space?
