# 6. Auto-scroll

Status: proposed

## Context

Scroll mode exists, but the thumb still has to move the page. Reading
over lunch, on a treadmill, or with one hand in a strap on the train,
a page that carries itself at reading pace is the difference between
reading and not. Paginated mode has no equivalent need — the tap zones
and volume keys already turn pages without reaching.

## Decision

Auto-scroll for scroll mode only: a start row, a speed slider, and any
touch on the page pauses it. Turning it on in a paginated book is not
possible because the row only appears when the book is scrolled.

Fit with Liseur's simplicity: one row in the Advanced sheet, visible
only in scroll mode. While scrolling, the reader chrome is the pause
control it already is — a tap shows the chrome and stops the movement.

## Design

A coroutine in `ReaderViewModel`, alive only while reading, ticks a
small `scrollBy` into the navigator's web view on a frame cadence
derived from the speed setting. Speed is stored as a `Float` DataStore
key; the slider maps to a range slow enough for dense prose and fast
enough for skimming.

Pause is the existing tap handling in `ReaderTapZones`: any tap that
reveals the chrome also cancels the coroutine. Reaching the end of a
resource behaves like a manual scroll reaching it — the existing
`ScrollEdgeTurner` carries into the next one.

Position saving needs nothing new: the navigator already reports
locator changes as the page moves, and auto-scroll moves the page.

## Consequences

The reading-speed estimator will see unusually steady progress; that is
genuine reading and needs no special-casing. The screen must not sleep
mid-scroll, so starting auto-scroll implies keep-screen-on for the
session regardless of the toggle.

*Where:* `reader/ReaderViewModel.kt`, `reader/chrome/ReaderTapZones.kt`,
`reader/chrome/TypographySheet.kt`.
