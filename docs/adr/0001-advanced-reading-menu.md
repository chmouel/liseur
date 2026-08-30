# 1. One Advanced entry, and nothing else

Status: accepted
GitHub issue: [#40](https://github.com/chmouel/liseur/issues/40)

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

It exists as of auto-scroll ([ADR 6](0006-auto-scroll.md)), which was
the first of the five to be built and so brought the sheet with it. The
other four arrive with their own issues.

The typography sheet did grow anyway, one reasonable row at a time,
until it carried eleven controls and the Advanced row held one. Six of
them have since moved behind Advanced — line height, page margins,
columns, the footer mode, the page-turn animation and the
just-this-book toggle — leaving the first sheet the five answers a
reader changes often: the theme, the size, the light, the face, and
whether the book is read by scrolling or by turning pages. Keeping the
screen awake stays there too, being a thing a reader reaches for
mid-chapter.

The Advanced row is no longer conditional. It was hidden in a paginated
book while auto-scroll was all that lived behind it, because the way in
to an empty sheet is worse than no way in; with six more rows there, it
cannot be empty. The rows that do not apply still hide themselves —
auto-scroll only in a scrolled book, the page-turn animation only in a
paginated one, columns only when there is width for two.

Settings → Reading appearance shows five of the six without a book —
the just-this-book toggle needs a book to set apart, and there is none
here — and not behind a collapsed section: that screen has nothing else
competing for room, so there is no empty-sheet problem to avoid, and a
reader who came looking for the margins should not have to open
anything to find them. The typography sheet still collapses them, being
the surface that stays Kindle-simple; the settings screen just lists
them.

New reading settings default to Advanced. `AGENTS.md` carries that as a
convention, so the next reasonable row has to argue its way onto the
first sheet rather than simply land there.

*Where:* `reader/chrome/TypographySheet.kt`,
`reader/chrome/AdvancedSheet.kt`,
`ui/settings/ReadingAppearanceScreen.kt`.
