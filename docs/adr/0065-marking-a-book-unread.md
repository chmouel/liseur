# 65. A book put back on the pile is unread everywhere

Status: proposed

## Context

Marking a book unread was added on the shelf, not to the reading state.
The filter honoured the mark; the series piles, the series volume row
and the book's own page still read the saved percentage, so a book at
42% that someone had just put back on the pile showed a progress bar and
"Continue reading" three screens away from the shelf that called it
unread. The rule that decides a reading state existed three times: in
`LibraryFilters`, in `SeriesVolume`, and in the reader's own status
derivation.

## Decision

One pure function decides it, `shelfStateFor` in
`domain/LibraryFilters.kt`: a book the reader finished is finished, a
book someone marked unread is unread, a book past `startedAfter` is in
progress, and everything else is unread. The mark wins over the
percentage, because a mark is an act and a percentage is a consequence,
and `startedAfter` is the only threshold in the app.

The shelf filter, `canMarkBookUnread`, the series piles and their
in-progress count, the series volume row and the book details page all
ask that function. Where a bar would be drawn for a book the shelf calls
unread, the screen says the place is kept instead, and the details page
offers Read rather than Continue reading, for the same reason.

This ADR covers the classification only. Nothing here changes how the
mark is written, cleared or synced.

## Consequences

Every screen now gives the same answer, without touching the write path,
the database or any sync. Two rows change hands for existing readers: a
book marked read and then unread sits at 1.0 and now files as unread,
where before it filed as in progress, and a volume marked unread stops
counting towards a series' in-progress total.

A mark that is never cleared still means a book being read stays unread.
That is the next decision, not this one.

## Not decided here

These are the write-path questions, kept together so the next change
lands against a written target rather than rediscovering them.

What counts as reading. A page turn by the reader and a read-aloud
listening checkpoint are the two candidates. The checkpoint is not proof
that anything was heard: `ReadAloudSession.place` saves the anchor to
resume from, which after a failed first sentence is a position nobody
read. It needs playback evidence, not just a saved place.

Ordering. `ReadingPositionPublisher` queues accepted movements and
writes them later, while a status change writes directly. A movement
accepted before a mark can therefore land after it, read the new mark
and clear it. Room's single writer keeps the row coherent, not the
intent: this needs an ordering rule or a generation stamp, and it is the
reason the clearing write cannot be a transaction alone.

Coherence of the write. Clearing the mark has to bump `status_revision`,
and the `status` column has to be derived from the override the write
actually retains, not from the one it read, or a fenced-out write leaves
a book at 99% labelled still-reading.

Undo. Marking a book read runs `clearTranslationsOnFinish` immediately,
so an undo offered afterwards cannot restore what it deleted, and a
snackbar can outlive a newer status change. Either the cleanup waits for
the commit, or the undo does not claim to restore translations. Until
then, marking unread has no undo at all.

BookOrbit. The outgoing status now depends on the progression, so
comparing `status_revision` and the override is no longer enough to
notice a change, and adoption has to persist with its agreement in one
transaction, or a crash between them leaves an imported state that looks
like a local act.
