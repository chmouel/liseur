# 74. Read aloud problems stay on screen and the player gains entry points

Status: accepted

## Context

Read-aloud failures used to surface as a toast and vanish. A first tap
showed nothing until audio arrived, a paused session left the reader's
page with no way back, voice rows played a sample when picked, and the
only skips were by sentence.

## Decision

### Problems persist

A failure is a `ReadAloudProblem` (book, notice, whether a retry can
help) held by `ReadAloudFeature`, and `ReadAloudProblems.next` is the
only reducer. A problem stays until the user dismisses it, changes the
voice or service, starts again, or audio of that book actually plays.
Audio is the only proof: `played` is read against the session's failure
count (`playingNow`), so a start that fails before any sound does not
clear it. The card offers Retry when recoverable, plus Choose voice and
Open settings; both lead to a standalone voice sheet, so a book without
a working voice can still reach one. Setup problems (`NotSetUp`) are
not recoverable by retrying.

`starting` is set from the first tap, before any audio. The card shows
a preparing state with Cancel, so a slow server is not mistaken for a
dead button.

### Entry points in the reader

When read aloud is not configured, the reader menu offers setup instead
of hiding the action. When the voice is elsewhere (playing and
detached, or paused), the card shows Return to voice, which navigates
to the spoken sentence without resuming, and Read from this page, which
starts a new session where the reader is. Paused "away" is derived from
page visibility of the spoken sentence, because `followDetached` is not
maintained while paused. Play still resumes at the voice's place.

### Voices: preview is not selection

A voice row selects; a separate button previews, and Gemini's sample
stays a billed, explicit action. Saving the voice list needs at least
one voice ticked: an empty selection means "all" elsewhere and broke
configuration.

### Chapter skip follows reading order

Chapter buttons sit beside sentence skip and use the publication's
`readingOrder` (`ChapterTarget`), not the table of contents: TOC entries
can point mid-resource, while reading order is what the TTS navigator
walks. Next goes to the next resource; previous goes to the start of the
current one, or to the previous resource when already at its start. The
jump restarts the navigator through `ReadAloudPlayback.start`, keeping
play or pause.

One jump runs at a time, and a later move (a voice change, another
skip) supersedes it: the landing checks `stillWanted` against a move
counter and opens nothing when it lost. The media session keeps mapping
next and previous to sentences, the controls headphones and the
notification name.

### Continue listening on the shelf

A row at the top of every library style names a book with a paused
record and reopens it with `EXTRA_RESUME_LISTENING`, consumed once on
first creation so rotation does not replay it. The row is hidden when
read aloud is unavailable, the book is gone, or its session is playing.

In the reader, once the page has landed: if a paused session for this
book already exists, `resumeIfPaused(bookId)` resumes it, matching the
book. Otherwise the record is restored paused, with `resumeAfterRestore`
set; the session plays only when the sentence aligned
(`Landing.Sentence`), and a pause, stop, put-away or new start clears
the flag so a user action during alignment wins. An unmatchable
sentence stays silent: the record is forgotten (the row goes away),
and a failed landing keeps it for the next opening.

The record is forgotten once playback resumes; the row therefore
disappears while listening and returns on the next pause. That is by
design.

## Consequences

- Nothing here touches sync, Room schema or the `foss`/`play` split.
- The reducer, `ChapterTarget` and the landing rules are pure and
  JVM-tested with fakes.
- New strings are in all nine locales.
