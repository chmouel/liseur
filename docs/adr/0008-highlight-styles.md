# 8. Highlight styles

Status: proposed
GitHub issue: [#41](https://github.com/chmouel/liseur/issues/41)

## Context

Highlights come in four tints and one shape: a filled band. A reader
who marks vocabulary one way and arguments another has only colour to
say so, and on the Black theme a filled band is the loudest thing on
the page. An underline is the quiet alternative every margin-writer
reaches for.

## Decision

Two styles, fill and underline, chosen in the selection popup next to
the four tints. A highlight keeps its style when its tint changes.

Fit with Liseur's simplicity: the selection popup gains one small
fill/underline toggle beside the tint dots it already shows. No
setting, no new screen; the contents screen lists both styles together
as it does today.

## Design

One nullable `style` column on the annotation entity, defaulting to
fill, one Room migration. `HighlightTint` stays what it is; a parallel
two-value enum carries the style from the popup through the ViewModel
to the row.

Rendering is a second decoration shape in
`reader/annotations/Annotations.kt`: Readium's decoration API has an
underline style alongside the highlight style, so the change is a
`when` at the single place decorations are built.

The Markdown notebook export does not distinguish styles; a style is a
visual register on the page, not a category worth exporting.

## Consequences

The popup gets slightly busier, which is the entire UI cost. The Room
schema version bumps, joining the migrations that `docs/ROADMAP.md`
already wants tested. Two styles is deliberate — squiggles and boxes
invite a formatting hobby that has nothing to do with reading.

*Where:* `data/db/BookAnnotation.kt`, `data/db/LiseurDatabase.kt`,
`reader/annotations/Annotations.kt`,
`reader/annotations/SelectionPopup.kt`, `reader/ReaderViewModel.kt`.
