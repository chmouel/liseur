# 17. Go to page

Status: accepted
GitHub issue: [#115](https://github.com/chmouel/liseur/issues/115)

## Context

The scrubber is the only absolute move Liseur has. It is a fine
instrument for "somewhere in the last third" and a hopeless one for
"page 212": five hundred pages under a thumb's width means every drag
is a hunt and every overshoot is another.

The number is often not the reader's to guess. A citation gives one. A
reading group says where to start. A note on paper from last week says
87. A paper copy of the same book, open on the table, says where its
owner stopped. In each of those the reader knows precisely where they
want to be and has no way to say it.

Everything needed to take them there already exists.
`BookPositions` numbers positions from 1 and hands out
`locatorAt(position)`; `ReaderViewModel` already exposes it as
`locatorAtPosition()` for the scrubber. `chapterAt()` already knows
which chapter a position falls in. `onJump()` already records the way
back, and `JumpBackPill` already offers it. What is missing is the
question.

There is also a second sense of "page" the app currently ignores. An
EPUB may declare a `page-list`: a nav mapping the printed edition's page
numbers onto points in the text, which is exactly the number a citation
quotes and exactly the number written on the paper note. Readium parses
it, and `Publication.pageList` in `readium-shared` 3.3.0 hands it over
as links. Liseur reads past it, and the page number in its footer is
Readium's synthetic position, which is stable, layout-independent, and
unrelated to what the paper book called that page.

## Decision

The page readout already printed under the scrubber, "Page 142 of 517",
becomes tappable, and opens a small dialog asking for a page. Typing
one moves the book there.

Where a book declares a `page-list`, the dialog asks for and accepts the
printed page. Where it does not, it asks for the position the footer is
already showing. The dialog says which of the two it wants, and names
the chapter the typed page falls in before the reader commits.

Fit with Liseur's simplicity: no new control and no new setting. The
readout is already on screen, already carrying the number a reader would
type, drawn in the shape they would type it, in the one surface that is
already about moving through the book. The top bar, which already
carries three icons, gains nothing.

The entry point was the whole question, and three other places were
considered. A row in the Contents screen is findable but two taps away
and behind a tab strip, for an answer the scrubber row is already
displaying. A long-press on the scrubber has no affordance and would go
undiscovered. The footer's own page number is tempting and wrong: footer
taps already cycle the middle slot, a quiet gesture worth leaving alone,
and the footer is the page the reader is trying to read past; putting a
dialog under it makes the text itself a control.

## Design

`ReadingScrubber` in `reader/chrome/ReadingProgressUi.kt` draws the
readout as a `FooterHint` in the row beneath the slider. It gains an
`onGoToPage` callback and `clickableWithoutRipple`, which the file
already defines for exactly this: chrome that must swallow a tap
without turning the page and without a ripple.

This composes with [ADR 7](0007-scrubber-page-peek.md), which stops a
drag from navigating until release. That ADR changes what dragging the
thumb does; this changes what tapping the readout does. Neither takes
the other's gesture. The readout 0007 leans on while the thumb is down
is the same one that opens this dialog when it is not, which is an
argument for the two shipping in either order.

The dialog itself is a `reader/chrome/GoToPageDialog.kt`, an
`AlertDialog` in the shape `NoteDialog` already uses, holding a text
field, the chapter hint, and the range it will accept. `ReaderScreen`
holds a `goToPage: Boolean` beside `showToc` and `searchFor`, and the
confirm path is the one every other jump takes:

```kotlin
onProgressAction.onJump()
navigateLater(locator, NavigatorPositionEvent.LOCAL_JUMP)
```

so the "back to page N" pill appears with no new state at all, and the
speed estimator is already told not to count the jump as reading.

The resolving is pure and lives in `reader/progress/`, beside
`BookPositions`, so it is testable on the JVM without an emulator. It
answers two questions from a `page-list` index built once when the
publication opens, the same moment `BookPositions.of()` is built: what
to ask for, and what a typed answer means.

The index is not an integer range, and the design must not pretend
otherwise. A `page-list` label is a string: roman numerals across front
matter, then arabic, is the ordinary case, and "iv" through "xxii"
followed by 1 through 480 is neither sorted nor contiguous as a number.
Labels can also repeat across volumes and can be absent entirely for
stretches of a book. So the index is a map from label to `Link`, an
ordered list of the labels for range hints, and nothing more. A label
the book does not carry is refused; the field says so, and the dialog
does not move the book, rather than rounded to the nearest thing, which
would silently answer a different question from the one asked.

`publication.locatorFromLink(link)` turns the chosen `page-list` link
into a locator, the same call `ReaderScreen` already makes for a
Contents entry, so a printed page and a chapter arrive by one path.

The chapter hint resolves a `page-list` link through
`BookPositions.resolve()` and then names the position with
`chapterAt()`, the same title the scrubber shows while it is
dragged. Typing a number is blind in a way
dragging is not; the hint is to a typed page what the scrubber's
label is to a drag, and it is what catches a reader who typed 212 into a
book whose printed pages the file never declared.

## Consequences

The scrubber row stops being the only way to make an absolute move, and
becomes the way to *find* the other one. A reader following a citation
into a book that carries its printed pages lands on the page the
citation meant, which is a thing Liseur could not do at all.

Two senses of "page" now exist in the app, and that is a genuine cost.
The footer will keep showing the synthetic position while the dialog
asks for the printed one, on the same book, in the same session. It is
the lesser of the two costs: the alternative is to keep asking for a
number nobody outside the app has ever seen. The dialog naming what it
wants, and the chapter hint confirming where that lands, is what carries
the difference. Renumbering the footer to the printed page is a much
larger change: the scrubber, time-left, sync progressions and every
stored annotation position hang off the synthetic ones, and it is
deliberately not proposed here.

Books with a `page-list` are a minority, and the ones that have it are
disproportionately the scholarly editions where a citation is most
likely to send someone. The fallback costs nothing: a book without one
behaves exactly as the footer already reads.

No new preference, no row in the typography or Advanced sheets, one new
dialog, one new pure file with its test, and a handful of strings in
`app/src/main/res/values/strings.xml`.

*Where:* `reader/chrome/ReadingProgressUi.kt`,
`reader/chrome/GoToPageDialog.kt` (new), `reader/progress/` (new
resolver beside `BookPositions.kt`), `reader/ReaderViewModel.kt`,
`reader/ReaderScreen.kt`, `res/values/strings.xml`.
