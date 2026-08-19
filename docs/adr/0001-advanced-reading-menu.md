# 1. One Advanced entry, and nothing else

Status: accepted

## Context

Nine reading-comfort features are missing from the reader: finer
typography, read-aloud, user fonts, warm light, auto-scroll, a peek
while scrubbing, highlight styles, tap-zone presets and translation.
Each one, added the obvious way, brings its own switch, its own row,
sometimes its own screen. Nine features added the obvious way turn the
typography sheet into a settings app, and the typography sheet is the
one surface that has stayed Kindle-simple on purpose: theme, font,
size, a few layout choices, done.

## Decision

The typography sheet gains exactly one new row, **Advanced**, at the
bottom. Everything that genuinely needs a setting lives behind it.
Nothing else in the app grows a menu, a screen or a piece of chrome for
these features.

Most of the nine do not even get a row there, because they can be a
gesture or fold into something that already exists:

- Scrubber peek ([ADR 7](0007-scrubber-page-peek.md)) is pure gesture.
  No setting at all.
- Warm light ([ADR 5](0005-warm-light.md)) is a second slider under the
  brightness slider already in the sheet.
- Highlight styles ([ADR 8](0008-highlight-styles.md)) live in the
  selection popup, next to the tints.
- Translation ([ADR 10](0010-translation-on-selection.md)) is one
  action in the selection popup, switched on from the dictionary
  settings that already exist.

That leaves the Advanced sheet holding what actually needs a home:
fine typography ([ADR 2](0002-typography-fine-tuning.md)), read-aloud
([ADR 3](0003-read-aloud-tts.md)), user fonts
([ADR 4](0004-user-imported-fonts.md)), auto-scroll
([ADR 6](0006-auto-scroll.md)) and the tap-zone preset
([ADR 9](0009-tap-zone-customization.md)).

## Consequences

A feature that cannot state, in one line of its ADR, where it surfaces
and why that adds nothing new to the UI, is not ready to build. Each of
the nine ADRs carries that line in its Decision section.

The Advanced sheet itself is a second bottom sheet reached from the
first, not a navigation destination: dismissing it lands back on the
typography sheet, and dismissing that lands back on the page.

*Where:* `reader/chrome/TypographySheet.kt`.
