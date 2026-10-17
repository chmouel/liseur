# 57. Codex tells the story so far

Status: proposed, amended by
[58. Codex hosts the character profile](0058-codex-hosts-the-character-profile.md)

Amends [51. Character profiles use only earlier chapters](0051-character-profiles-use-only-earlier-chapters.md),
which said there is no recap browser and that recap prose is never sent.

## Context

With background recaps on, the reading companion writes two or three
sentences on each stretch of a chapter the reader finishes by reading on,
and keeps them in `companion.db`. Lookups use only the names and refs in
a recap to pick passages. The summaries themselves were never shown, so a
reader who comes back to a book after a week has paid for recaps they
cannot read, and chapters finished by jumping, or before recaps were on,
have none at all.

Readest's unreleased assistant offers a chapter summary on request.
Liseur already has the text for many chapters and the machinery to
recap one; it lacks a place to show them and a way to ask
([#355](https://github.com/chmouel/liseur/issues/355),
[#356](https://github.com/chmouel/liseur/issues/356)).

## Decision

Codex gets a Story tab, first and opened by default from the reader's ⋮
menu. It shows only while a companion service is chosen and the book has
a content sha; without one Codex keeps Names and Relationships, and with
Codex off nothing shows. Search, the selection fallback and a profile's
Codex link still open on Names.

The tab has three parts: a running summary of the story so far, a
timeline of the earlier chapters with their recaps, and a way to recap
chapters that have none.

### One frozen scope

Codex freezes the full `AskingScope` (chapter, count and read point)
when it opens, and keeps it through tab changes, peeks and Return. Story
lists chapters with `PublicationCatalog.chaptersBefore` for that scope:
a chapter shows only if its number is below the asking chapter and its
start is provably before the place (`ChapterMap.startsBefore`), so
contents listed out of reading order cannot show a later chapter. A
peek origin is placed as if on a translated page, which can only show
fewer chapters; the ⋮ Codex entry now does the same, closing a gap where
it did not. A profile's Codex link passes the profile's own scope.

### The timeline

Each earlier chapter is a node on a rail, newest first so the one just
read sits under the summary. It shows the contents title or number, a
status (recapped, no summary in the recap, unreadable, not recapped,
waiting, recapping with its batch, failed with the usual failure view),
the summary sentences clamped to three lines, and Go to chapter. Go to
chapter is a peek through `SourcePeek`, withheld for a file listed twice
or an anchor not found in its file.

Rows are read under the book's content sha and `Recaps.FORMAT`, by any
server or model, as lookups read them, from a Room flow so a recap
finishing shows at once. Decoded recaps are memoized by unit key and
json; a recap is touched for the store's trim once when it first shows.

### Recapping on request

A not-recapped chapter offers Recap, an unreadable one Recap again, a
failed one Retry, and "Recap k missing chapters" asks first with the
count. The request is a normal chapter recap: same batching, prompt,
parts and `chapter_recap` row, so lookups use it too. It works with the
background switch off, because the reader asked.

`RecapScheduler` gains a requested lane ahead of background work and
behind lookups. A requested chapter already queued in the background is
promoted, one in flight is not run twice, and background work gives way
between batches, keeping its saved parts. Recap again replaces a row
only when it does not decode, and only after the new one is saved. The
safety ceiling still applies: every request is checked before it is sent
against the lowest of the place on screen, the peek origin and the
request's own frozen ceiling, and a backward jump cancels what is in
flight. Tracking the place is now separate from queueing finished
chapters, so the place is followed whenever any companion work is
pending, and forgotten when none is. Work asked for while the place was
forgotten waits until the page on screen is placed again; the asking
chapter is never used in its stead, since a backward peek may show an
earlier one. A change of service or model
clears the requested lane.

### Automatic work needs one Allow

The first time Story shows with a service chosen, a card names the
service and says Story can send the text of the chapter before the
place to recap it, then send the recaps to write the summary. Allow stores
`story_auto_ok`, which travels in the settings backup and is a switch in
the companion settings. Not now holds for that scope for the rest of the
reading session. Until
allowed nothing is sent on the reader's behalf; each Recap or Write
summary tap still works.

Each frozen scope is one event: once allowed, Story picks the chapter
right before the place, only when it has no recap and is neither queued
nor failed, and asks for it once. It never reaches further back: a book
opened mid-way would otherwise pay for many chapters the reader may not
want, so earlier chapters are a tap away with Recap or Recap missing
chapters. Reopening
the tab, Codex or the same scope in the same session, peeks and Return
never pick again, and failures are not retried on their own. Stop
cancels what is in flight, clears the requested lane and ends the event
for that scope. Turning the switch off drops what Story queued on its
own, and stops it if in flight, whether the tab shows or not; chapters
the reader tapped stay. The scheduler reads the switch before queueing
and before every request, so nothing offered on a stale value goes out.
Closing Codex leaves the work running while the book is open.

### The running summary

The summary is written by the model from the recaps' summary sentences
of the listed chapters only, never from book text, titles, character
facts, refs or notes. Each chapter's sentences are capped at 600
characters. Each request names, as ranges such as 1-9, only its own
chapters and windows that have no summary, a window by its span; one
written from some
summaries already said what it lacked, so sparse coverage stays bounded
too. Chapters fold into fixed windows of eight; a window is
summarized only once all of it is below the ceiling, and windows of
windows fold the same way, so the top request carries at most seven
items per level plus seven chapters and stays bounded for any book
length. Windows are written in at most 500 characters and the top in
at most 1,200; a window whose sentences already fit is passed on as
they are, with no request. The top is written only when at least two
chapters it reads have a recap: from one, the model would only reword
that recap, so the card asks for more recaps instead.

Every node is keyed by a hash of its formats, the content sha, its level
and window, and its ordered inputs, and stores the chapters and unit
keys it read. A summary is shown or reused only when those are all in
the current listed set with the same unit keys and below the ceiling,
so one written further on, or over a chapter this scope excludes, never
shows. Staying put is a cache hit; a new recap costs its chain of
windows and the top. The best valid summary shows meanwhile, labelled
with the chapters it covers and those it read without a recap. Once the
summary for the current recaps is saved it is the one shown, even over
an older, richer one that read recaps since trimmed.

It builds only while the tab is on screen (a peek hides it), when
allowed, the event is not declined or stopped, no requested recap is
waiting, and the recaps it would read match a fresh read of the store,
since the lane can drain before the last recap reaches the view;
otherwise from
Write summary or Update summary. It runs in the scheduler's summary
lane, after requested recaps and before background ones, one at a time,
with one fixed-code correction per node, and is checked against the
ceiling before every request like recaps are.

Recaps go into the prompt in unclosable `<summary chapter="N">` data
blocks, escaped like `CompanionPrompt.block`. A fixed system prompt
says they are data, may be wrong, and that nothing known from elsewhere
may be used. The reply is strict JSON `{"summary": "…"}`.

### Storage and backup

`companion.db` goes to version 4 with a `story_summary` table keyed by
book URL and node key, holding the level, window, content sha, both
formats, the dependencies, the text and the save and use times. Rows of
another sha or format are dropped on read, writes are fenced by the book
epoch like recaps, removed books are swept, and the table has its own
size limit. It stays outside Android auto-backup. The exported ZIP
writes recaps first, up to the shared limit less a reserve, then
summaries in what is left; restore validates every field and accepts
archives without summaries.

### Wording

The summary card says it was written by an AI model from the recaps of
earlier chapters, may be wrong, may say things the book does not, and
may give away what the model knows about this book from elsewhere.

### What stays

The recap prompt, its JSON and `Recaps.FORMAT` stay as they are; a format
change would drop every recap readers already paid for. Summary
sentences carry no refs, so neither the timeline nor the running summary
cites passages.

## Consequences

- Readers see what background recaps produced, and can fill the gaps a
  chapter at a time or all at once, with background recaps off.
- Recap prose now leaves the device, only for the running summary, only
  for chapters behind the reader, and only after Allow or a tap. The
  request body rule in DEVELOPER.md and docs/PRIVACY.md say so.
- The summary is model text built on model text, without citations. The
  card says so.
- Books whose contents run out of reading order, or with several
  chapters in one file whose anchors cannot be found, show fewer
  chapters.
- `companion.db` gains a table and a migration; the backup format gains
  an optional array older versions ignore.
