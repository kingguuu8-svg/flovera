# Flovera UI Audit Gates

Use these gates when reviewing, redesigning, or final-polishing Flovera UI.

## 1. Product Fit

- The screen supports a real local-agent workflow: session, workspace, file, WebView, permission, setting, or agent action.
- The first viewport is usable, not promotional.
- The surface prioritizes the AI result and conversation, not a desktop-style workbench.
- The primary action is visible and named as an action.
- Secondary information is scannable without turning the screen into a card gallery.

## 2. Information Architecture

- One primary task per surface.
- Main display and conversation are visually dominant.
- Navigation, session state, and supporting workspace context are visually secondary.
- Conversation does not swallow the result surface; the result surface does not hide the steering context.
- File paths, status, model/provider, permission state, and execution progress use consistent placement.
- Empty, loading, error, and permission-blocked states tell the user what can happen next.

## 3. Layout

- Layout uses stable rows, toolbars, panes, sheets, tabs, or split views before decorative cards.
- Repeated items align on a grid and support quick comparison.
- Text never overlaps icons, counters, previews, or following sections.
- Fixed-format elements define dimensions with aspect ratio, min/max sizes, or grid tracks.
- Mobile and desktop/tablet breakpoints preserve the same task order.
- On mobile, the combined main display and conversation planes own the largest visual area.
- Persistent chrome is thin and justified by frequent use.
- Desktop-like panes are translated into tabs, sheets, drawers, or progressive disclosure.

## 3A. Entry Integration

- Low-frequency options are grouped behind clear entries instead of shown as scattered controls.
- Each entry has a semantic label tied to a Flovera object or workflow.
- Primary action remains visible while configuration moves behind sheets, menus, or detail panes.
- Integrated entries reduce visible clutter without hiding current state.
- The user can still discover where settings, permissions, files, run controls, and diagnostics live.
- Supporting workspace details appear when they affect the result, trust, or next action.

## 4. Typography

- Display-scale type is reserved for true app-level identity or onboarding.
- Tool panels, sidebars, and cards use compact headings.
- Paths, counters, logs, durations, and code-like values use monospace or tabular numerals.
- Body text lines stay readable and do not exceed practical scan width.

## 5. Color And Surfaces

- One primary accent is used for active state, focus, and progress.
- Background and panel colors are not a one-hue theme by accident.
- Contrast supports long work sessions.
- Status colors are reserved for status: success, warning, destructive, pending.
- Shadows define elevation only where hierarchy needs it.

## 6. Interaction States

- Buttons and controls include disabled, loading, pressed/focused, success, and error states where relevant.
- Dangerous actions use clear confirmation or undo patterns.
- Long-running agent actions expose progress, cancellation, and result state.
- No workflow depends only on hover.
- Touch targets are platform-appropriate.

## 7. Android And Compose

- Material 3 conventions are followed unless Flovera has an existing local component pattern.
- Edge-to-edge and system bars are handled deliberately.
- Back behavior is predictable.
- Semantics and content descriptions exist for meaningful icon controls.
- Lists avoid unnecessary recomposition and layout churn.

## 8. WebView And Design Prototypes

- Preview canvases and SVG/icon studies have explicit dimensions and background contrast.
- HTML tools work at narrow and wide widths.
- Export/copy/download buttons provide visible feedback.
- Generated SVG or code output is readable and does not resize the main layout unexpectedly.

## 9. Verification

- Prefer tests, semantic nodes, debug entries, screenshots, and command verification over visual clicking.
- For Android verification, prefer the repository's guarded Flovera verification script when applicable.
- For HTML prototypes, verify at least one desktop and one narrow viewport.
- Capture remaining risk if a device/browser verification path was not available.

## 10. Final Review Questions

- Does this look like a real mobile agent app, not a landing page or chat clone?
- Are the main result and conversation surfaces larger and clearer than before?
- Are workspace details secondary unless the user needs them?
- Did any visual flourish reduce clarity?
- Did the change preserve the existing code and product boundary?
- Is every changed state represented or intentionally out of scope?
