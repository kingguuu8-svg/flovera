---
name: flovera-frontend-design
description: Flovera-specific frontend design and review workflow for Android-local workspace agent UI, Jetpack Compose surfaces, WebView/workspace views, design prototypes under android/spike/design, icon tools, and visual polish tasks. Use when Codex is asked to improve, redesign, audit, or implement Flovera UI without generic AI aesthetics, marketing-page patterns, or changes that conflict with app-owned settings, sessions, workspace files, permissions, and agent entry points.
---

# Flovera Frontend Design

Use this skill to make Flovera interfaces feel like a focused mobile agent app: quiet, operational, trustworthy, and efficient. Treat external design skills as source material only; do not copy their files, branding, or broad style packs into Flovera.

## Product Boundary

Flovera is not a landing page. Do not use oversized hero sections, decorative marketing cards, generic SaaS gradients, floating blobs, testimonial patterns, or product-tour copy unless the user explicitly asks for a public site.

Flovera is also not a generic chat app. Conversation is one of the two primary information planes because it is where the user directs, corrects, and understands the agent. The other primary plane is the main output/display surface where the user sees the AI result. Workspace, files, preview internals, permissions, and run state support these two planes; do not make them the visual center by default.

The Android app owns sessions, workspace files, WebView display, permissions, settings, and the agent entry point. UI changes that affect capabilities or settings must keep the app prompt and settings JSON surfaces consistent when those files are in scope.

Prefer dense but calm mobile agent UI:

- Maximum effective area for the two primary planes: main display and conversation.
- Fast scanning over spectacle.
- Clear hierarchy over decoration.
- Stable controls over animated novelty.
- Result-first language over workspace-internal language.
- Native Android and Compose conventions over web marketing composition.

## Workflow

1. Read the brief and current files before deciding style.
2. State a one-line Design Read for nontrivial UI work: target user, surface type, density, motion level, and risk.
3. Audit existing UI before changing it. Load `references/audit-gates.md` for detailed gates when doing redesign, review, or final polish.
4. Work with the existing stack and file boundaries. Do not migrate frameworks, introduce broad dependencies, or rewrite working UI for style alone.
5. Implement focused improvements, then verify with the narrowest meaningful checks available.

## Design Dials

Set these internally before implementing:

- `VISUAL_DENSITY`: 7 for conversation and main display surfaces, 6-7 for result inspection, 4-5 only for onboarding or empty states.
- `MOTION_INTENSITY`: 2-4 by default. Use motion for state continuity only, not decoration.
- `DESIGN_VARIANCE`: 2-4 for production mobile app UI, 5-6 only for design studies in `android/spike/design`.

If the user asks for a more expressive visual study, keep it isolated inside `android/spike/design` unless they explicitly approve app integration.

## Mobile Minimalism

Minimalism in Flovera means fewer competing controls, not less useful information.

- Make the main display surface and conversation surface occupy the largest share of the viewport.
- Collapse secondary options into clear entry points: overflow menus, bottom sheets, command palettes, contextual toolbars, or detail drawers.
- Prefer one primary action plus one compact secondary entry over rows of equal-weight buttons.
- Keep persistent chrome thin. Navigation, status, and toolbars must earn their pixels.
- Move settings, filters, advanced actions, and mode switches behind integrated entry points unless they are part of the current task loop.
- Keep desktop-platform traces only when they help the user inspect the AI result or steer the conversation. Files, logs, permissions, and run details should appear as contextual evidence, not as a permanent workbench.

## Primary Information Planes

Design around two dominant planes:

- `Main Display`: the AI's product, preview, rendered artifact, diff, page, document, file result, or runnable state. This is the user's main reason to open the app.
- `Conversation`: the user's steering surface for instructions, corrections, clarification, and agent summaries.

Do not assume the user wants to study the agent's workspace like a desktop IDE. On mobile, the user usually needs to see the result and keep enough conversation context to guide the next step.

Workspace objects are secondary:

- Show files when choosing, opening, attaching, or reviewing a result.
- Show run state when it changes user trust, waiting, cancellation, or next action.
- Show permissions when a decision is required.
- Show logs only for diagnosis, failure recovery, or explicit inspection.

## Visual Direction

Use a restrained, app-like system:

- Backgrounds: neutral, low-chroma surfaces with enough contrast for long sessions.
- Accent: one primary Flovera accent, repeated consistently for focus, active state, and progress.
- Typography: clear sans for UI, tabular numerals for counts, paths, timings, and status values.
- Shape: modest radii. Avoid pill-heavy UI unless the control is truly a chip, tag, or segmented option.
- Icons: use established icon libraries when available. Icon-only controls need labels or tooltips.
- Content: write operational labels. Avoid feature-explaining text inside the app unless it removes ambiguity at the decision point.

## Component Rules

Every interactive component needs:

- Default, hover/pressed when applicable, focused, disabled, loading, error, and success states.
- Stable dimensions so dynamic labels, icons, counters, and progress text do not shift layout.
- Keyboard and accessibility semantics where the platform supports them.
- Clear empty states with next action, not marketing copy.

For entry integration:

- Group related options behind a single named entry when exposing them separately would reduce the main display or conversation area.
- Make entry labels semantic: `Result`, `Files`, `Run`, `Permissions`, `Settings`, not generic `More`.
- Keep the current task's primary action visible; hide configuration, not action.
- Use bottom sheets for temporary option clusters and detail panes for persistent context.

For Android Compose:

- Prefer Material 3 and platform navigation patterns unless existing Flovera UI has a stronger local pattern.
- Preserve edge-to-edge handling, touch target sizes, semantic descriptions, and back behavior.
- Do not use visual clicking as the primary verification path when command, test, semantic node, or debug entry verification can prove the behavior.

For WebView or HTML prototypes:

- Avoid nested cards and section-as-card layouts.
- Use fixed-format preview areas with explicit aspect ratios.
- Validate desktop and mobile breakpoints.
- Keep text within containers at all supported widths.

## Anti-Generic Gates

Before finalizing, reject these patterns unless the brief explicitly requires them:

- Purple-blue gradients as the main visual idea.
- Decorative blur blobs, orbs, bokeh, noise-only backgrounds, or random floating shapes.
- Centered hero plus three cards as the default structure.
- Inter/slate-only visual language without a reason.
- Emoji as functional icons.
- Placeholder names, fake testimonials, fake logos, or sample data that looks like production truth.
- Multiple accent colors competing for primary action.
- UI cards inside cards.
- Copy that explains the existence of controls instead of labeling the controls.

## Output Discipline

When proposing or implementing UI changes, say why each major design decision fits Flovera. If the shortest path is a smaller audit/fix instead of a redesign, say so and take the smaller path.

For substantial redesign work, finish with:

- What surface changed.
- What design risk was reduced.
- What verification ran.
- What remains intentionally out of scope.
