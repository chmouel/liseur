# 19. Go to a percentage, and open on where you are

Status: accepted
GitHub issue: [#126](https://github.com/chmouel/liseur/issues/126)

## Context

[ADR 17](0017-go-to-page.md) made the page readout under the scrubber a
button. It left the readout beside it, the percentage on the left of
the same row, as a label, and it left both halves of the question it
answers unfinished.

The percentage is the number the rest of the app already speaks. It is
what calibre-web sync exchanges, what the library grid prints under a
cover, what the footer prints on the left of every page, and what a
reader who put a book down a month ago actually remembers: not "page
212" but "about two thirds". Anyone who has that number has to hunt for
it with a thumb, on a scrubber where five hundred pages fit under a
thumb's width, and where ADR 17 said the hunt is the problem.

The percentage is also the only number that always exists. A printed
`page-list` is a minority of books; the synthetic position is stable but
private to the app. The percentage is on every book, in every server,
and on the shelf.

The second gap is in the dialog ADR 17 shipped. It names the endpoints
and not the present: "Enter a page from 1 to 517". A reader typing an
absolute number is very often moving relative to somewhere: back to the
start of the chapter, on thirty pages, to the other side of a scene
break. The number they need for that arithmetic is the one number the
dialog covers up, since it opens over the row that was displaying it.
Getting it back means cancelling, reading, and opening the dialog again.

## Decision

The percentage readout becomes a button of the same shape as the page
readout, opening a dialog that asks for a whole number from 0 to 100,
names the chapter it lands in, and leaves the same "back to page N" pill
behind. Both dialogs open with the reader's current position already in
the field, selected.

**The entry point is the readout, not a mode.** The alternative was one
dialog with a Page/Percent toggle, and it is worse in the place that
matters: the scrubber row is already two numbers, side by side, and
making them two buttons needs no explaining. A segmented control asks
the reader to open a dialog before they can see that the other kind of
answer exists, and it puts a decision in front of a reader who arrived
having already made it. The row also stays honest about what it is: each
number is a way in to changing that number.

**The current position is prefilled, not printed as a label.** Both
carry the same fact; only one is also a starting point. Selected-on-open
means the first digit typed replaces it, so a reader who knows their
number pays nothing for it, and a reader who is moving relative to where
they are gets the arithmetic in front of them and can edit it in place.
The cost is a confirm button enabled before anything is typed, where Go
jumps to where the book already is. That is a no-op with a pill behind
it, not a trap, and it is not worth a special case.

**Whole percentages only.** On a five hundred page book a tenth of a
percent is half a page, so decimals buy precision that is already
available, more legibly, in the page dialog next door. Refusing them
keeps one keyboard, one validation rule, and one obvious shape of
answer.

**Printed-page books prefill a printed label.** A dialog asking for the
printed page must open on the printed page, or it teaches the reader
that the number in the field is not the number in the field. That needs
a second index over the `page-list`: label to position, where ADR 17
only needed label to `Link`.

## Design

`GoToPageResolver.prompt` becomes `promptAt(position)`, and
`GoToPagePrompt` gains a `currentLabel`. The endpoints and the numbering
are still per-book and computed once; only the starting point moves with
the reader.

The printed index is built lazily, on first use. Each `page-list` mark
is resolved through `locatorFromLink` and `BookPositions.resolve()` into
a position; the result is sorted, one label per position. A page list
runs to one mark per printed page, and paying for all of them belongs
to the moment the reader opens the dialog rather than to every book
that is merely opened.

A page list whose marks land nowhere in the reading order is not a
numbering at all, whatever the file declares: every label it offered
would be refused, and the reader would be left with a dialog that cannot
answer. Such a book falls back to the page the footer shows. The
endpoints of a real one are still read off the document rather than the
index, because a page list is not sorted by position; roman numerals
through the front matter is the ordinary case.

Two rules in the index are deliberate. Marks landing on the same position keep
the *earliest* label, because a chapter whose marks all resolve to its
first position should read as the page it starts on, not the page it
ends on. And a reader ahead of no mark at all, in the cover and the
title page before the book prints a number, is told the first number
the book does print, rather than an empty field.

The percentage resolver is a function, not a class: there is no per-book
index to hold, only `BookPositions`.

It maps a percentage to the *first* position the footer would call that
percentage, in integer arithmetic, because that boundary is the whole
point: 7% of a hundred steps is 7.000000000000001 in binary floating
point, and rounding that up steps a page past the page being asked for.
That is the only mapping that keeps the promise the feature is for:
type the number the footer is showing and the footer still shows it.
Rounding the other way, flooring as
`locatorAtOrBeforeProgression()` does for sync, lands a page short, and
since the footer truncates rather than rounds, typing 70 leaves 69% on
screen. A reader can only read that as a refusal, and retype it forever.
It was written that way first and caught on a device, which is the
argument for having built the screenshot into the process.

The same rule makes the prefilled answer safe. Confirming it untouched
goes to where that percentage begins, which is at or a little before
where the reader is, and never past it.

The two dialogs are one file, `reader/chrome/GoToDialogs.kt`, over a
shared private body. They differ in four strings, a keyboard type and a
resolver; keeping them apart would be keeping two copies of a validation
rule that has to stay identical. Both now request focus on open, so the
keyboard is up and the prefilled answer is selected without a tap.

The confirm path is untouched from ADR 17:

```kotlin
onProgressAction.onJump()
navigateLater(destination.locator, NavigatorPositionEvent.LOCAL_JUMP)
```

`GoToPageDestination` is renamed `GoToDestination`, since both dialogs
return one.

## Consequences

Every absolute move the reader can name now has a way in, and the two
they can name sit next to each other in the row that displays them. A
book with no `page-list` and a reader who thinks in percentages (the
common case, twice over) is no longer served worst.

The percentage is coarse on a long book: one percent of a nine hundred
page book is nine pages. That is the honest resolution of the number,
and the page dialog is one tap away for anyone who needs better. Within
that percent the reader lands on its first page, so a jump is repeatable
and reversible: the same number always goes to the same place, and it is
the earliest page it could mean.

Prefilling changes the existing page dialog too, which is the point:
they are one question asked twice and should not behave differently.

The percentage readout keeps showing the settled percentage while the
page readout shows the drag preview. That asymmetry predates this change
and is left alone here.

*Where:* `reader/progress/GoToPage.kt`,
`reader/chrome/GoToDialogs.kt` (renamed from `GoToPageDialog.kt`),
`reader/chrome/ReadingProgressUi.kt`, `reader/ReaderViewModel.kt`,
`reader/ReaderScreen.kt`, `reader/ReaderActivity.kt`,
`res/values/strings.xml`.
