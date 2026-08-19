# 4. User-imported fonts

Status: proposed

## Context

Four bundled families cover most tastes, but not the reader who has
paid for a font they love, needs OpenDyslexic, or reads a script the
bundled four render badly. The bundle cannot grow to meet them all —
every family is APK weight everyone carries.

## Decision

Let the reader import a font file. Picked through the system file
picker, copied into the app's own storage, offered in the font row like
the bundled four, applied when the next book opens.

Fit with Liseur's simplicity: one "Add a font…" entry at the end of the
existing font list in the Advanced sheet. Imported fonts appear in the
same list with a remove affordance; no manager screen.

## Design

Import is `ACTION_OPEN_DOCUMENT` for `font/ttf` and `font/otf`, the
bytes copied to `filesDir/fonts/` — copied, not referenced, so the
choice survives the source file moving or the SAF grant lapsing. The
family name is read from the font's name table; a file that has none
falls back to its filename.

`ReaderFont` stops being the whole story: the enum keeps the bundled
families and `PUBLISHER`, and the font preference becomes an id that is
either one of those or a user font's filename. `ReaderFont.fromId`
already tolerates unknown ids by falling back to the default, which is
exactly what happens if the file behind a chosen font is gone.

Declaration goes through the same `addFontFamilyDeclaration` path the
bundled fonts use in `epubNavigatorConfiguration`, with the served-asset
scope widened to cover the app's font directory. That configuration is
read when a book opens, so an import lands on the next book — the same
behaviour column mode already has, documented in the same place.

## Consequences

One imported file is one face: no italic, no bold axis unless the file
is variable. The page falls back to synthesised styles, which is what
every other reader does with a single-face import.

Licensing stays the reader's own business, as with any file they put on
their device; nothing is redistributed.

*Where:* `data/settings/ReaderPrefs.kt`,
`reader/ReaderPreferencesMapper.kt`, `reader/chrome/TypographySheet.kt`,
new `data/settings/UserFontRepository.kt`.
