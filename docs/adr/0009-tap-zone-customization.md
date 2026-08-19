# 9. Tap-zone presets

Status: proposed
GitHub issue: [#46](https://github.com/chmouel/liseur/issues/46)

## Context

The tap zones are fixed: left edge back, right edge forward, middle for
chrome. A left-handed reader holding a phone in one hand has the
forward turn under the wrong thumb every single page. The fix most
readers actually want is not a zone editor, it is "both edges turn
forward" or "swap the edges".

## Decision

One three-way preference: **Standard** (today's layout), **Both edges
forward** (going back stays on the volume keys and the scrubber), and
**Swapped** (left forward, right back).

Fit with Liseur's simplicity: one row with three choices in the
Advanced sheet. No draggable zone editor, no per-zone action picker —
that is a settings hobby, and the three presets cover the readers who
exist.

## Design

`ReaderTapZones` already resolves a tap position to an action in one
place; the preset is an enum on `ReaderPrefs` consulted in that
resolution. The volume keys are untouched — they already give a
physical back/forward pair whatever the thumbs do.

Scroll mode keeps its own scroll-aware tap behaviour; the preset only
reinterprets the paginated edges.

## Consequences

Three presets instead of a matrix means someone will ask for a fourth.
The bar for adding one is the same as for these: a hand position that
actually occurs, not a preference for novelty.

*Where:* `data/settings/ReaderPrefs.kt`,
`reader/chrome/ReaderTapZones.kt`, `reader/chrome/TypographySheet.kt`.
