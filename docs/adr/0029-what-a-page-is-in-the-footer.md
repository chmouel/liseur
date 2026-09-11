# 29. What a page is in the footer

Date: 2026-09-11

## Status

Accepted

## Context

The reading footer's middle slot counted time and only time: time left
in the chapter, time left in the book, the chapter's name, or nothing.
Every one of those either waits for a reading pace to be measured or
says nothing about distance at all. A reader deciding whether to start
the next chapter before putting the phone down has no figure to act on.

Libby answers that question outright, with pages remaining until the
next chapter. It is available the moment the book opens, and it does not
move when the reader speeds up or slows down. #172 asked for the same.

Before any of it could be written, "a page" had to mean something, and
there are two candidates that disagree.

A **Readium position** is a fixed slice of a resource, roughly a
thousand characters, computed once per book and unchanged by anything
the reader does afterwards.

A **screen** is what a turn actually consumes: however much text the
current font size, margins and column count leave room for. It is what
the reader's thumb counts.

They are not close to each other. A book at a large font size has
several screens to a position; at a small one, the other way about.

## Decision

A page in the footer is a Readium position.

### One unit per row

The footer already draws `42 of 300` on its right edge, and that 300 is
`BookPositions.totalPositions` — positions. So is the page the go-to
dialog asks for when a book declares no printed page list, the page the
scrubber previews, and the page the jump-back and catch-up pills name.
The app has one notion of a page and it is this one.

Counting screens in the middle of the footer would put two different
meanings of "page" a hand's width apart on the same line of text, moving
at different rates, with nothing to tell the reader they are different.
That is not a better number; it is a number that makes the one beside it
untrustworthy.

### A figure that stays still

A count of screens changes when the font size changes, when the margins
change, when the device is rotated, and when a tablet switches to two
columns. Every one of those is a thing a reader does while reading. A
number that says 8 and then says 14 because the text got bigger is read
as a bug before it is read as arithmetic.

Positions do not move. The count answers the reader's question — how
much of this chapter is left — in a unit that means the same thing at
the start of the chapter and at the end of it.

### What it costs to compute

A position count is subtraction on numbers `ReaderViewModel.progressAt()`
already holds: the chapter's last position and the current one. No
JavaScript, nothing to measure, nothing to re-measure.

A screen count would need `scrollWidth / innerWidth` from the web view
per resource, re-run on every typography change and every rotation, with
the answer arriving asynchronously after the turn that prompted it. And
it would still be incomplete: a chapter in this app can span several
resources — `BookPositions` folds an untitled resource into the chapter
before it, because split chapters are the ordinary case in EPUB — and
the screens of a resource Readium has not laid out yet cannot be
counted. Those would have to be estimated from positions anyway, so the
"exact" count would be exact only for the part of the chapter already on
screen.

## Consequences

A turn does not always consume exactly one of the pages being counted.
Read at a large font size, the number will come down more slowly than
the turns. This is not new: it is already true of the page number on the
footer's right edge, and of the page the go-to dialog accepts. The
change inherits the discrepancy rather than introducing it.

`ReaderProgress` carries `positionsLeftInChapter` as a nullable, and
`pagesLeftInChapter()` decides when it is null. The time estimates fall
back to the end of the book when no chapter resolves, which is fair for
a figure that is a guess either way; a count printed under the words "in
chapter" cannot do the same without quietly counting something else.

A **nameless chapter counts as no chapter**. `BookPositions.of()` gives
every book at least one, by opening a chapter on the first resource
whether or not anything names it and folding each untitled resource
after it into the one before — which is what keeps a chapter split
across several files whole. A book with no table of contents comes out
of that as a single untitled chapter spanning the whole text, so
counting to its end would be counting to the end of the book with the
word "chapter" on it. The end of an untitled run is not a boundary the
book declared, so the slot stays empty, as it already does for the
chapter-title mode.

Zero left is the chapter's last page, and the figure survives as zero
into the footer so it can be worded there — "Last page in chapter" —
rather than being dropped and blanking the slot on the one page of the
chapter with something plain to say.

Readium's resource lookup and the stable position can disagree about
which chapter the reader is in. This can happen when the reading order
contains the same href more than once: the href can resolve to one
resource's chapter while the stable position belongs to another. In
that case the stable position wins, and the footer counts against the
chapter containing it. The count is absent only when no final named
chapter contains the position. Zero is reserved for a position actually
equal to that chapter's `lastPosition`.

`FooterMode.SMART` is untouched. It promises time and degrades to the
chapter's name, which is not a number at all; degrading to a different
number would be the second meaning of "page" arriving by another door.

## Known limitation

A chapter here is a run of reading-order resources, so several table-of-
contents entries pointing at fragments of one XHTML file collapse into
one chapter, and the count runs to the end of the file rather than to
the next heading. That is the chapter model the whole app already uses
— the time left in the chapter, the scrubber's ticks and the chapter
title read the same ranges — and this figure inherits it rather than
introducing it. Chapter boundaries resolved from the contents entries'
own locators would fix all four at once, and would be a change to
`BookPositions`, not to the footer.

## Alternatives considered

**Pages left in the book.** The existing time-left-in-book mode already
covers that shape of question, and it is further from what was asked
for.

**A hybrid: screens for the loaded resource, positions scaled for the
rest of the chapter.** Exact where it can be and estimated where it
cannot, which means the number changes character partway through a
chapter without saying so, and still moves with the font size. All of
the cost of screens and most of the imprecision of positions.
