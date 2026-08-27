# 2. Typography fine-tuning

Status: proposed
GitHub issue: [#44](https://github.com/chmouel/liseur/issues/44)

## Context

The sheet exposes font, size, line height, margins and columns. Readium
can also justify text, hyphenate it, and adjust letter, word and
paragraph spacing and font weight, and none of that is reachable. For
most readers the defaults are right; for a reader with a dyslexic
child, or one who cannot stand a ragged right edge, the missing knob is
the difference between using the app and not.

## Decision

Expose justification, hyphenation, letter spacing, word spacing,
paragraph spacing and font weight as rows in the Advanced sheet.

Fit with Liseur's simplicity: these controls are why the Advanced sheet
exists ([ADR 1](0001-advanced-reading-menu.md)); the main typography
sheet does not change.

## Design

Each control becomes a nullable field on `ReaderPrefs`, exactly as
`lineHeight` and `pageMargins` already are: null means the publisher's
styles, a value means the reader asked for something. The mapping is a
handful of lines in `toEpubPreferences` — `EpubPreferences` already has
`textAlign`, `hyphens`, `letterSpacing`, `wordSpacing`,
`paragraphSpacing` and `fontWeight`.

The existing `publisherStyles` rule extends to the new fields: any
non-null value turns publisher styles off, no value leaves the page
exactly as Readium would have laid it out. That rule already lives in
one place in the mapper and stays there.

Persistence follows `lineHeight`: one DataStore key per field in
`ReaderPreferencesRepository`, absent when null.

## Consequences

Six new preference keys, one enum for text alignment, no new types
otherwise. The mapper's publisher-styles condition grows from three
clauses to nine, which is the moment to fold it into a small
`overridesPublisherStyles()` on `ReaderPrefs`, testable on the JVM.

Hyphenation only works where Readium's CSS supports the language;
the row does not promise otherwise.

*Where:* `data/settings/ReaderPrefs.kt`,
`data/settings/ReaderPreferencesRepository.kt`,
`reader/ReaderPreferencesMapper.kt`, `reader/chrome/AdvancedSheet.kt`.
