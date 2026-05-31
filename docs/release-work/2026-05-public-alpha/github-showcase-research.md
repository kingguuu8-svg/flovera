# GitHub Showcase Research

## Sources Checked

- GitHub README docs: https://docs.github.com/articles/about-readmes/
- GitHub community docs: https://docs.github.com/communities
- Open Source Guides, community: https://opensource.guide/building-community/
- Vercel v0 docs: https://vercel.com/docs/v0
- Vercel v0.app launch blog: https://vercel.com/blog/v0-app
- Bolt introduction: https://support.bolt.new/building
- Lovable introduction: https://docs.lovable.dev/introduction/welcome
- Replit Agent mobile announcement: https://blog.replit.com/try-agent
- Replit Agent announcement: https://blog.replit.com/introducing-replit-agent

## What GitHub README Must Do

GitHub's README guidance says the README should explain what the project does,
why it is useful, how to get started, where to get help, and who maintains it.
For Flovera, this means the GitHub page should not be a pure marketing page. It
needs a fast product explanation plus install/build limits.

Recommended top-level sections:

1. One-sentence positioning.
2. Short animated GIF or screenshot slot.
3. "What you can build" examples.
4. Current capabilities.
5. How it works.
6. Alpha limits.
7. Build and verification.
8. Contributing/security/license.

## Similar Product Page Patterns

### v0

v0 frames itself as a natural-language builder and pair programmer that can go
from idea to deployed app. The strong pattern is "one prompt -> real software,"
with examples spanning landing pages, full-stack apps, dashboards, and content.

Reusable for Flovera:

- Lead with a concrete workflow, not implementation.
- Show categories of outputs.
- Keep "real software" / "working artifact" as proof language.

Avoid:

- "Deployed app" promise. Flovera is Android-local, not cloud deployment-first.

### Bolt

Bolt's intro is direct: type your idea, click send, get a working product in
minutes. It also names audience breadth: non-coders and developers.

Reusable for Flovera:

- Use simple verb chain: ask, generate, preview, iterate.
- Mention both lightweight creators and developers.

Avoid:

- "Web, mobile, anything" breadth. Flovera is strongest when scoped to local
  demos, files, and WebView artifacts.

### Lovable

Lovable emphasizes full-stack app iteration from natural language, real code,
security, and governance.

Reusable for Flovera:

- "Real files" and "real code" matter.
- Iteration is as important as first generation.

Avoid:

- Enterprise governance framing for the first alpha.

### Replit Agent

Replit's mobile announcement is the closest comparison: build and deploy apps
from iOS/Android. Its key advantage is mobile software creation.

Reusable for Flovera:

- The mobile-native angle is valuable and rare.
- Demonstrate that the phone is not only a chat surface; it is the workbench.

Avoid:

- Replit owns cloud runtime/deployment. Flovera should not imply that.

## Flovera Differentiation

Flovera should not try to look like a v0/Lovable clone. Its strongest story is:

- Local Android workspace.
- Session, files, preview, and runtime in one app.
- WebView is the output surface.
- Bounded Python enables real artifact generation, especially docs/spreadsheets.
- Generated apps are portable projects with `flovera.app.json` as adapter.
- The product is honest about Android background and WebView limits.

## Recommended GitHub First Screen

Headline:

> Flovera is an Android-local workspace agent.

Subheading:

> Ask for a small app, game, spreadsheet, or local web demo. Flovera writes it
> into a scoped phone workspace, verifies it, and opens it in WebView so you can
> keep iterating on Android.

Primary proof block:

- GIF/screenshot placeholder: conversation -> files -> WebView demo.
- Three bullets: "local workspace", "WebView artifacts", "bounded Python".

## README Tone

Use "alpha, local, scoped, visible, inspectable." Avoid "magic, autonomous,
always-on, full-stack in one prompt, replaces your computer."

