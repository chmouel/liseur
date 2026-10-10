# 47. The selection bar keeps one colour and a More

Status: accepted

## Context

ADR-0026 cut the selection bar to three colours so it would fit beside
Note, Define, Search and Share. Read aloud and Translate then joined it,
and on a phone the bar was full width again, with its actions scrolling
behind the chips.

## Decision

The bar offers one colour, yellow, until the reader ticks more in
Reading appearance. Readers who already chose a set keep it, and the
synced setting still says "never chose" for those who did not, so no
device pushes the new default onto another.

Read aloud and Translate move behind a More button at the end of the
row. More swaps the row for theirs, with a back arrow, at the same
height. Note, Define, Search, Share and Delete stay in the main row:
the first four are what a selection is mostly for, and a destructive
action should not hide.

We did not use a dropdown menu. The bar is a `Popup`, and a menu is a
second window whose taps the bar can take for a touch outside it,
dismissing the selection before the item runs (#257 has the same cause).
We did not let the bar grow into a list either, which is how the
platform's own selection toolbar overflows: the bar is placed from a
fixed height so it never covers the selected words, and a taller bar
would.

## Consequences

Read aloud and Translate take two taps from a selection instead of one.
The overflow row's labels stay on one line and scroll when a language
makes them long, so its height never changes. The open row resets to
the main one whenever the selection changes. More is not drawn when
neither action is available, for instance while a page is shown
translated and read aloud is not set up.
