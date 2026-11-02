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

Marking unread in Liseur resets the current locator and percentage to
the beginning and advances both position and status revisions. Sync
delivers the reset before accepting an old remote place. Reading history,
annotations and the furthest-read position are kept. An imported
status-only mark still keeps the saved passage.

The unread write waits for earlier position and completion events in
`ReadingPositionPublisher`, including their retries. Opening a book keeps
a preserved remote conflict on disk while its peer still owes an
acknowledgement of the reset. Explicit choices remain available.

The next persisted reader movement or local jump clears the unread
override in the same transaction as the new position and advances the
status revision once. Opening, reflow, lifecycle replay, backup imports
and read-aloud checkpoints do not clear it. An explicit finished mark
remains set. If BookOrbit still has a manual unread status, the resumed
reading replaces that status without replacing the saved passage.

## Consequences

Every screen gives the same answer. Existing or imported unread marks may
still have a saved place, which the classification honours. A new local
unread mark opens at the beginning. A volume marked unread stops counting
towards a series' in-progress total.

## Not decided here

Read-aloud needs playback evidence before it can clear an unread mark.
`ReadAloudSession.place` saves the anchor to resume from, which after a
failed first sentence is a position nobody read.

Undo. Marking a book read runs `clearTranslationsOnFinish` immediately,
so an undo offered afterwards cannot restore what it deleted, and a
snackbar can outlive a newer status change. Either the cleanup waits for
the commit, or the undo does not claim to restore translations. Until
then, marking unread has no undo at all.
