# 7. Scrubber page peek

Status: proposed

## Context

Dragging the scrubber moves the book immediately: every position the
thumb passes through is navigated to, and letting go in the wrong place
means hunting for where you were. Checking how far the chapter runs, or
glancing back at a map three chapters ago, should not gamble the
reading position.

## Decision

While the thumb is down, the scrubber only shows — a small label above
it with the chapter title and page — and the book does not move until
release. After a release that moved the book, a single "Back to page N"
chip offers the way home, and turning a page dismisses it.

Fit with Liseur's simplicity: this is a change to how the existing
scrubber behaves, not a new control. No setting; there is no reader who
wants navigation to fire on every passing position.

## Design

`ReadingScrubber` in `ReadingProgressUi.kt` already knows the position
list and chapter mapping (`BookPositions`); the drag callback stops
calling the navigator and feeds the label instead, and only the release
callback navigates. The label content is the same title/page pairing
the footer already computes.

The return chip is the jump-back flow that link-following already uses
in `ReaderViewModel`: record the pre-drag locator when a drag starts,
surface the existing jump-back affordance if release landed elsewhere.
No new state shape, one more caller of a mechanism that exists.

Thumbnails were considered and dropped: rendering page images for the
whole book costs a background pipeline and storage for a glance the
title-and-page label already serves.

## Consequences

Skimming becomes free: dragging across the whole book is now a lookup,
not a journey. The one behavioural loss is live page flipping under the
thumb, which nobody used on purpose because every flip was a navigation
they would have to undo.

*Where:* `reader/chrome/ReadingProgressUi.kt`,
`reader/progress/BookPositions.kt`, `reader/ReaderViewModel.kt`.
