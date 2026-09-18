# 36. The footer counts screens

Date: 2026-10-02

Status: accepted

Amends [29. What a page is in the footer](0029-what-a-page-is-in-the-footer.md)

## Context

ADR-29 settled that a page in the footer is a Readium position, and gave
good reasons: one unit everywhere, a figure that does not move when the
reader changes the font, and arithmetic on numbers the app already
holds.

Reading with it is a different experience from arguing about it. A
Readium position is about a thousand characters of the stored file, and
at a comfortable size on a phone a screenful is rather less than that.
So the footer stands at `13 of 212` for two turns, three turns,
sometimes four, and then moves by one. The chapter countdown does the
same thing: nine pages left, nine pages left, nine pages left, eight.
The reader is turning pages and the page number is not changing. That
was reported as a bug, and as a bug it is right. The page number of a
paper book changes when the page does.

The web reader in liseur-sync counts the same positions and has the same
behaviour, which is why the two agreed and why neither was obviously
wrong.

## Decision

In a reflowable book being read in pages, both of the footer's numbers
count screenfuls.

The middle slot, when it is set to the chapter countdown, says how many
screens are left in the resource on screen. Ten, then nine, then eight,
one per turn, and "Last page in chapter" when there are none left.

The right edge says which page of the whole book this is, as `137/892`.
That figure is measured rather than derived, and the word measured is
doing some work: read on.

Both keep the word page. The reader asked for a page number that moves
when the page moves, not for a new vocabulary, and the liseur-sync web
reader says page in both places already. Nothing is renamed.

### How a whole-book number can be measured

Only the resource on screen can be counted. Its width is one property
read from the laid-out document, and dividing it by the width of a turn
is the whole calculation. The rest of the book has not been drawn, and
at this font, at these margins, at this width, its length is not in the
file. ADR-29 made that case and it still holds.

What the file does give is how much of the book each resource is:
counting Readium's positions says that a resource runs from 12.4% to
13.1%, and that share does not move when the typography does. It is
counted rather than read off the interpolated coordinate, so every
resource holding a position gets a share of its own and consecutive
resources tile the book exactly, including a last resource holding only
one position. So a resource measured at
eighteen screens across 0.7% of the book is a measurement of the book's
density, and the book is about eighteen divided by 0.007 screens long.

Every resource the reader passes through adds a sample, and the estimate
is taken over all of them at once rather than from the last one. So it
refines as the book is read instead of lurching about whenever a short
chapter is crossed. Within a resource nothing new is recorded, so the
total stands still and the page walks up by exactly one per turn, which
is the thing that was asked for. When the page is rebuilt — a new type size, a
new margin, a rotation, a resized window — every sample taken at the old
shape is about a book with different pages, so they are all thrown away
and the estimate begins again. The shape is named, and a measurement
that was already in flight when it changed is refused on the way back
rather than filed against the new one.

### Why the page cannot run backwards

The total is an estimate and it moves. The page number is not allowed
to. A resource of verse laid out at twice the density of the prose
before it shortens the whole book when its sample joins, and if the page
were simply the total scaled by how far in the reader is, that shortening
would drag the page back: twenty of two hundred, then sixteen of a
hundred and fifty, on a forward turn.

So the screens behind the reader are counted rather than scaled. Every
resource already measured contributes the screens it really laid out
into, and only the stretches never visited are guessed at the running
density. Reading a book from its beginning, everything behind the reader
has been measured, so the page ahead of them is the page behind plus
one, whatever the sample did to the total.

Counting is not quite enough on its own, because a book opened in the
middle has an unmeasured stretch ahead of where the reader started, and
that stretch is guessed. Re-guessing it at a revised density moves it,
and moving it moves everything after it, including pages the reader has
already been shown. So a resource's origin — the screens lying ahead of
it — is settled once, when it is first measured, and afterwards only
ever pushed further along, never pulled back. A book can grow shorter
under a reader who is going forwards. It cannot grow shorter behind
them.

### What that costs

The total moves when a resource is measured for the first time, so the
denominator can step at a chapter boundary while the numerator steps by
one. It is an estimate and it says so: the screen reader is told "About
page 137 of 892", and the slash in `137/892` is a different figure at a
glance from the `137 of 892` the scrubber prints for stable positions.

A fixed-layout book is exact instead. Readium gives such a book one
position per resource and a resource is a page, so the stable count is
already the page count, the measurement is not run, and the screen
reader is told "Page 137 of 892" without the hedge.

### Why the countdown is the file, not the chapter

A chapter in this app is a run of resources, folded together so that a
chapter split across several files reads as one. Screens can only be
counted in the file on screen, so in a chapter split in two the
countdown reaches nought at the halfway point and starts again. Every
other rule ADR-29 wrote for that slot is kept: it is hidden where the
chapter is unknown or where the remaining distance is meaningless, and
only the unit under it changed.

### A book read by scrolling

A scrolled page has no screens to turn. It also has no footer: the page
runs under the corner the footer would occupy, so the figures live in
the chrome, one tap away. Nothing changes there.

## Consequences

The numbers move with the typography. Make the text bigger and a book of
892 pages becomes a book of 1,340. This is the objection ADR-29 raised
and the answer is that a book reset in larger type has more pages. That
is what larger type does.

The count is measured, so there is a moment after a resource arrives or
a reflow starts when there is no answer yet. The footer is empty for
that moment rather than showing the previous section's number or a
stable position under a screen label. Two readings of the document have
to agree before either is used, which is what stops a width measured
halfway through a reflow reaching the footer.

A turn held under the thumb has already moved the navigator while the
reader is still looking at a photograph of the page they are leaving, so
the count freezes for the length of the drag and is taken again when the
turn is made or put back.

Android and the liseur-sync web reader now print different numbers in
the same corner of the same book. The web reader counts positions, which
it can do without a browser measurement because it derives them from the
stored file. Position sync is unaffected: locators, progressions and
everything on the wire are unchanged, and the screen count is never
stored, never sent, and never counted as reading. The stable positions
still carry the scrubber, the go-to dialog, the pills, annotation labels
and the sync dialog, and an annotation's stored number still says what
the scrubber says.

`FooterMode.PAGES_LEFT_CHAPTER` keeps its name, its stored value and its
label, because it is a synced setting and it still counts pages left in
the chapter. Only what a page is changed.

## Alternatives considered

**Lay out the whole book.** Opening and rendering every resource before
the first page can be numbered, and doing it again on every font change.
ADR-29's objection, undiminished.

**Count screens of the current file and print `4/12`.** Honest, exact,
and not a page number. A reader glancing at the corner of a book wants
to know where they are in the book, and `4/12` restarting at every file
answers a question nobody asked.

**Estimate from the last resource alone.** One short chapter of dense
verse would rewrite the length of the book. Averaging over everything
seen makes the figure settle instead of chase.

**Scale the page from the total as well as the book.** Simpler by a
dozen lines, and it lets a forward turn print a smaller number than the
turn before it. A page number that goes backwards is the bug this ADR
exists to fix, in a new costume.

**Count the screens behind the reader but re-guess the rest each time.**
Enough for a book read from its first page, and not for one resumed in
the middle, where the unmeasured beginning is part of every page number
printed afterwards. Settling each origin the first time costs one stored
integer per resource and holds in both cases.

**Change liseur-sync to match.** The web reader would need the same
measurement, which is cheaper there than here, but the request was about
the phone, and two clients disagreeing about a displayed number costs
less than a second implementation of a thing neither reader asked for.
Worth revisiting if the disagreement is noticed.

**Leave it and explain positions in the interface.** The reader who
filed this was not confused about what the number meant. They wanted the
page number to move when they turned the page.
