# Video Production Research

This note prepares the Flovera Preview launch video workflow. It focuses on
GitHub skills and practical capture/editing rules before any real-device
recording is available.

## GitHub Skills And Workflows Checked

### `browser-use/video-use`

Source:

- https://github.com/browser-use/video-use
- https://github.com/browser-use/video-use/blob/main/SKILL.md
- https://github.com/browser-use/video-use/blob/main/install.md

This is the strongest relevant skill found. It is a conversation-driven video
editor for agents. Useful patterns:

- Keep source footage untouched.
- Put all derived outputs in an `edit/` directory.
- Maintain a `project.md` memory file for edit decisions.
- Use an EDL (`edl.json`) for cut decisions.
- Cache transcripts separately.
- Generate animations in per-slot directories.
- Produce `preview.mp4` before `final.mp4`.
- Use ffmpeg/ffprobe as baseline tooling.
- Pick animation engine per slot:
  - HyperFrames for browser-native HTML/CSS/GSAP compositions and UI motion.
  - Remotion for React/CSS video compositions.
  - Manim for explanatory/math-like animation.
  - PIL + PNG sequence + ffmpeg for simple overlay cards, counters, typewriter
    text, progress bars, and callouts.

Flovera adaptation:

- Do not install this skill yet. Use it as workflow reference.
- Our first launch asset can use the same structure:
  `release-media/flovera-preview/edit/project.md`,
  `edl.json`, `clips/`, `overlays/`, `preview.mp4`, `final.mp4`.
- For Flovera, HyperFrames or simple PIL overlays are more appropriate than
  Remotion at first. The product itself is Android UI + WebView, so HTML/CSS
  overlay cards match the subject.

### OpenAI Skills Catalog

Source:

- https://github.com/openai/skills
- https://github.com/openai/skills/blob/main/skills/.system/skill-creator/SKILL.md

Relevant pattern:

- Treat video production as a reusable skill-like workflow: instructions,
  scripts, references, assets, validation.
- Do not hide procedural knowledge in one-off prompts.

Flovera adaptation:

- Create a repeatable checklist and ffmpeg command set before recording.
- Keep style decisions separate from raw footage.
- Later, if video work repeats, convert this workflow into a local Flovera
  `release-video` skill.

### Local `frontend-slides`

Source:

- `C:\Users\Administrator\.codex\skills\frontend-slides\SKILL.md`

Relevant pattern:

- Strong visual hierarchy.
- No generic AI aesthetic.
- Use viewport-fitting rules.
- Motion should support the story, not decorate it.

Flovera adaptation:

- If we add animated title cards, keep them short and functional.
- Do not create a glossy SaaS hero video that misrepresents the product.

## Video Best Practices Checked

Sources:

- FFmpeg GIF palette workflow: https://ffmpeg-cookbook.com/en/articles/gif-creation/
- Peek GIF recorder README: https://github.com/phw/peek
- Screenfully mobile demo product notes: https://screenfully.app/
- Demo video best-practice articles:
  - https://demoscope.app/blog/posts/demo-video-best-practices-2026
  - https://demopolish.com/screen-recording-for-demos/
  - https://rekort.app/blog/how-to-make-product-demo-video
- GitHub README GIF guidance:
  - https://rekort.app/blog/gif-for-github-readme
  - https://giftovideo.net/ru/blog/gif-for-github-readme

## Practical Conclusions

### Capture

- Use real-device recording for the main launch asset.
- Record vertical phone footage first. The product is Android-local; fake
  desktop capture weakens the core claim.
- Target 30 FPS. Use 60 FPS only if gameplay or touch motion looks choppy.
- Enable visible touch indicators if possible. Mobile demos need tap feedback.
- Record without audio. Add captions or overlay labels later.
- Keep the raw recording longer than needed; edit down afterward.

### Runtime Targets

- README hero GIF: 12-18 seconds, silent, loopable, under a reasonable size.
- Social short video: 20-35 seconds, MP4, vertical.
- Longer demo: 60-90 seconds only after the first short asset works.

### Storyboard For Flovera Preview

Recommended first video:

1. Open Flovera.
2. Show a prompt such as "做一个手机端贪吃蛇小游戏".
3. Jump to generated result rather than showing the full wait.
4. Show tool/status timeline briefly.
5. Show `artifact_diagnose` success or the artifact entry.
6. Open the WebView preview.
7. Interact with the generated artifact.
8. End on the artifact running inside Android.

This proves the core loop:

> prompt -> workspace files -> validation -> WebView preview -> iteration.

### Editing Style

- Fast, quiet, product-first.
- Minimal title card: "Flovera Preview".
- Use 2-4 overlay labels:
  - "Android-local workspace"
  - "Files generated on device"
  - "Artifact diagnosed"
  - "Opened in WebView"
- Avoid long explanatory subtitles in the README GIF.
- Keep touch/click actions readable with either native touch indicators or small
  overlay callouts.

### Smooth Animation

For the first launch asset, use editing and overlay motion instead of rebuilding
the whole UI as an animation.

Good first-pass effects:

- 150-250ms zoom-in on important UI areas.
- Smooth pan/crop between conversation and preview.
- Short label fade/slide overlays.
- Optional subtle phone frame for social video, not for README GIF if it wastes
  space.

Avoid:

- Heavy intro logos.
- Fake generated output not from the real app.
- Excessive cursor/tap animation.
- Long waits during model generation.

## FFmpeg Preparation

Recommended MP4 trimming:

```powershell
ffmpeg -ss 00:00:03 -to 00:00:28 -i raw.mp4 -c:v libx264 -crf 20 -preset slow -pix_fmt yuv420p -an preview.mp4
```

Recommended high-quality GIF two-pass:

```powershell
ffmpeg -i preview.mp4 -vf "fps=15,scale=720:-1:flags=lanczos,palettegen" palette.png
ffmpeg -i preview.mp4 -i palette.png -lavfi "fps=15,scale=720:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3" flovera-preview.gif
```

If GIF is too large:

```powershell
ffmpeg -i preview.mp4 -vf "fps=12,scale=540:-1:flags=lanczos,palettegen" palette.png
ffmpeg -i preview.mp4 -i palette.png -lavfi "fps=12,scale=540:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3" flovera-preview-small.gif
```

Notes:

- Use palettegen/paletteuse instead of direct MP4-to-GIF.
- GIF is poor for high-resolution full-screen mobile video. Keep it short and
  scaled.
- MP4 should be the master social asset; GIF is mainly for GitHub README.

## Proposed Directory Layout

Do not create media output until raw footage exists. Suggested future layout:

```text
docs/release-work/2026-05-public-alpha/media/
|-- raw/
|   `-- flovera-preview-device-recording.mp4
|-- edit/
|   |-- project.md
|   |-- edl.json
|   |-- palette.png
|   |-- preview.mp4
|   |-- flovera-preview.gif
|   `-- final-social.mp4
`-- README.md
```

## Capture Request For User

Ask for one raw vertical screen recording:

- 20-45 seconds is enough.
- No voice required.
- Start from Flovera main screen or conversation.
- Include one successful generated artifact opening in WebView.
- Prefer the snake game or another visually obvious demo.
- Make touch indicators visible if the device supports it.

## Decision

For the first public Flovera Preview:

- Use real-device footage as the source of truth.
- Produce one README GIF and one social MP4.
- Keep overlay animation light and factual.
- Use the video-use workflow structure if editing becomes nontrivial, but avoid
  installing or adding dependencies until there is raw footage to process.

