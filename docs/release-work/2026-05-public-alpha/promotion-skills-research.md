# Promotion Skills Research

## Sources Checked

- OpenAI Skills catalog: https://github.com/openai/skills
- OpenAI `skill-creator`: https://github.com/openai/skills/blob/main/skills/.system/skill-creator/SKILL.md
- OpenAI `frontend-skill`: https://github.com/openai/skills/blob/main/skills/.curated/frontend-skill/SKILL.md
- Marketing Skills for AI Agents: https://github.com/coreyhaines31/marketingskills
- Marketing & growth skills: https://github.com/robertbstillwell/marketing-skills
- Local `frontend-slides` skill: `C:\Users\Administrator\.codex\skills\frontend-slides\SKILL.md`
- Local `deep-think` skill: `C:\Users\Administrator\.codex\skills\deep-think\SKILL.md`

## Useful Skill Patterns

1. Skill repos are useful as workflow packaging, not as content truth.
   OpenAI's catalog frames skills as folders with instructions, resources, and
   scripts that make repeated work more reliable. For Flovera launch work, that
   means preserving repeatable launch checklists, channel-specific copy rules,
   and asset requirements rather than generating one-off hype copy.

2. Marketing skill repos tend to chain from positioning to channel output.
   The `marketingskills` repo explicitly treats product marketing as a
   foundation for later campaign/copy/channel work. This maps well to Flovera:
   first define product category and audience, then write GitHub README, then
   write X/HN/Reddit/Chinese social copy.

3. Design skills push against generic AI output.
   OpenAI's `frontend-skill` emphasizes strong hierarchy, restraint, imagery,
   and avoiding generic card-heavy layouts. For GitHub, this translates to:
   use a clear first screen, one sharp positioning line, screenshot/GIF when
   available, and short demo workflows instead of a long feature wall.

4. Local `frontend-slides` is useful later, not now.
   It is good for an animated launch deck or demo page, but this pass is
   research-first. Use it only after the README/social positioning is settled.

## Skills To Reference Later

- Use `deep-think` for launch strategy and positioning reviews.
- Use `frontend-slides` only if creating a launch deck, Product Hunt gallery, or
  animated demo explainer.
- Use `imagegen` only after the visual direction is decided; do not use it to
  invent misleading screenshots.

## Flovera-Specific Rule

Do not adopt generic "AI builds full-stack apps from one prompt" language. It is
crowded, and Flovera's real differentiator is not cloud deployment. The useful
message is:

> A phone-native local workspace where the agent creates, verifies, previews,
> and iterates on small artifacts without leaving Android.

