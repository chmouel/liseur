# 69. Warm night reading themes

Status: accepted

Supersedes [5. Warm light](0005-warm-light.md).
GitHub issues: [#427](https://github.com/chmouel/liseur/issues/427),
[#43](https://github.com/chmouel/liseur/issues/43)

## Context

Dark and Black still put a cool grey ink on the page, and some readers
want less blue light at night. ADR 5 proposed an amber overlay over the
page with a warmth slider. Tried on a phone, it never looked gentle: the
scrim dims and muddies the whole page, tints pictures, and lands
differently on every panel.

## Decision

Three more reading themes, set in warm paper and ink rather than drawn
over: Amber (`#18120C` / `#D4A373`), Night red (`#000000` / `#C8605A`)
and Charcoal (`#211E1A` / `#C9BEAD`). They are ordinary entries of
`ReaderTheme`, so the page, the chrome painted on it
([ADR 31](0031-the-readers-chrome-is-painted-on-the-page.md)) and
Readium all take them the way they take Dark. No slider, no custom
colour picker.

The theme row keeps its five everyday choices and ends with one "Low
light" swatch. Tapping it opens a card for each warm theme under the
row, with the theme's name and a short passage set in its colours, so
the choice is made by reading a sample. Once one is picked, the swatch
takes its colours and shows as selected. On e-ink the swatch is not
drawn.

Night red is a muted red. Red light has little luminance, so a red ink
bright enough to meet the contrast every other theme meets would turn
orange. The ink sits at about 5.3:1 on black, and on that page the
reader chrome uses the full ink for secondary text and outlines where
other pages use a softened step of it (`ReaderTheme.isDim`). That keeps
`ReadingColorSchemeTest` passing on every raised surface.

## Consequences

Electronic paper has no hue to show, so the three are not offered when
e-ink mode is on, and a book already set in one is drawn in Black there
(`ReaderThemeChoice.resolve`). That includes colour e-paper, whose
colour layer is too faint to make a dark page look warm.

Pictures keep their colours, because nothing is laid over them.

*Where:* `data/settings/ReaderPrefs.kt`,
`ui/theme/ReadingColorScheme.kt`, `ui/reading/ReadingAppearanceControls.kt`.
