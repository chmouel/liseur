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
bottom. Everything that genuinely needs a reading-comfort setting lives
behind it, and nothing in the reader grows a second menu, screen or
piece of chrome for these features.

A feature that turns out not to be about reading comfort is not
smuggled in behind Advanced either: it goes to the settings surface it
actually belongs to, next to the setting it is a sibling of. That is a
row on a screen the app already has, never a new one.

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
- The tap-zone preset ([ADR 9](0009-tap-zone-customization.md)) is a
  chip row in Settings -> Reading, beside the volume-key switch it is
  the thumb's version of. Not a reading-comfort setting after all: it
  is how the device is held, set once when the app is set up, and it
  belongs with the other page-turning hardware rather than with the
  page's appearance.

That leaves the Advanced sheet holding what actually needs a home:
fine typography ([ADR 2](0002-typography-fine-tuning.md)), read-aloud
([ADR 3](0003-read-aloud-tts.md)), user fonts
([ADR 4](0004-user-imported-fonts.md)) and auto-scroll
([ADR 6](0006-auto-scroll.md)).

## Consequences

A feature that cannot state, in one line of its ADR, where it surfaces
and why that adds nothing new to the UI, is not ready to build. Each of
the nine ADRs carries that line in its Decision section.

The Advanced sheet itself is a second bottom sheet reached from the
first, not a navigation destination: dismissing it lands back on the
typography sheet, and dismissing that lands back on the page.

It exists as of auto-scroll ([ADR 6](0006-auto-scroll.md)), which was
the first of the four to be built and so brought the sheet with it. The
other three arrive with their own issues.

The typography sheet did grow anyway, one reasonable row at a time,
until it carried eleven controls and the Advanced row held one. Six of
them have since moved behind Advanced: line height, page margins,
columns, the footer mode, the page-turn animation and the
just-this-book toggle, leaving the first sheet the five answers a
reader changes often: the theme, the size, the light, the face, and
whether the book is read by scrolling or by turning pages. Keeping the
screen awake stays there too, being a thing a reader reaches for
mid-chapter.

The Advanced row is no longer conditional. It was hidden in a paginated
book while auto-scroll was all that lived behind it, because the way in
to an empty sheet is worse than no way in; with six more rows there, it
cannot be empty. The rows that do not apply still hide themselves:
auto-scroll only in a scrolled book, the page-turn animation only in a
paginated one, columns only when there is width for two.

Settings -> Reading appearance shows four of the six without a book,
and behind an Advanced section of its own, closed on arrival. The
just-this-book toggle needs a book to set apart and there is none here;
the page-turn animation is on Reading & navigation, under the Advanced
section there. It listed the four flat at first, on the argument that a
screen with nothing else competing for room has no empty-sheet problem
to avoid; that argument lost. See the second update below.

New reading settings default to Advanced. `AGENTS.md` carries that as a
convention, so the next reasonable row has to argue its way onto the
first sheet rather than simply land there.

*Where:* `reader/chrome/TypographySheet.kt`,
`reader/chrome/AdvancedSheet.kt`,
`ui/settings/ReadingAppearanceScreen.kt`.

## Update

The Settings screen went the way this ADR describes the sheet going.
Its Reading section had grown to eight switches, a chip row and two
rows that only some devices show, so it moved behind a single row into
**Settings -> Reading & navigation**
(`ui/settings/ReadingNavigationScreen.kt`), taking the dictionary
section with it. Reading appearance is unchanged and still holds how
the page looks; the tap-zone chip row named above is now on the new
screen. `AGENTS.md` names both destinations.

## Second update

Reading appearance now collapses its advanced settings too, reversing
the paragraph above that said it should not.

The argument for listing them flat was about room, and room was never
the problem. The problem is that the appearance settings the two
surfaces share were sorted into everyday and rare with a book open, and
not sorted at all without one, so a reader who learned the sheet
learned nothing about the screen. Two ways into the same preferences
that disagree about which of them are everyday teach the reader that
the distinction is arbitrary — and if it is arbitrary, the sheet has no
reason to keep hiding anything.

So the screen takes the sheet's shape: the preview, the theme, the
size, the light and the face at the top, and line spacing, margins,
columns, the fine typography and the footer behind **Advanced** at the
bottom, closed on every arrival. The preview stays above it and goes on
answering to what is inside, so opening Advanced is not a step away
from the only page there is to look at.

Reading & navigation already had such a section, in a rounded card of
`ListItem` rows. Reading appearance is sliders, swatches and dropdowns
down a spaced column, so it gets `SettingsExpandableSection`: the same
header, announced the same way, without the card that would box
controls that are not rows.

The convention that follows is the one `AGENTS.md` now carries: a
setting that appears on both surfaces is everyday on both or advanced
on both. Placing such a row on one of them is placing it on the other.

*Where:* `ui/settings/ReadingAppearanceScreen.kt`,
`ui/settings/SettingsRows.kt`.
