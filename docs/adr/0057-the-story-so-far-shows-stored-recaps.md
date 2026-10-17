# 57. The story so far shows stored recaps

Status: proposed

Amends [51. Character profiles use only earlier chapters](0051-character-profiles-use-only-earlier-chapters.md),
which said there is no recap browser.

## Context

With background recaps on, the reading companion writes two or three
sentences on each stretch of a chapter the reader finishes by reading on,
and keeps them in `companion.db`. Lookups use only the names and refs in
a recap to pick passages. The summaries themselves are never shown, so a
reader who comes back to a book after a week has paid for recaps they
cannot read.

Readest's unreleased assistant offers a chapter summary on request. Liseur
already has the text for many chapters; it lacks a place to show it
([#355](https://github.com/chmouel/liseur/issues/355)).

## Decision

The reader's ⋮ menu gets "Story so far". It opens a sheet over the book
listing earlier chapters with their stored summaries. It makes no network
request and changes nothing that is sent.

### Which chapters

The sheet is frozen on the place it was opened from:

- Normally the place on screen, refreshed as the other position actions
  refresh it, with the page's translation state.
- While a source is a peek, the peek's origin. The peek does not record
  whether the origin was on a translated page, so the origin is placed as
  if it were: anywhere in its resource, which can only show fewer
  chapters.

A chapter is listed only when both hold:

- Its index is below the asking scope's `lo`, the ceiling recaps are
  written under.
- Its start is known to come before the asking place in reading order.
  Contents can list chapters out of reading order, and then an index
  below `lo` does not mean an earlier chapter. A start in an earlier
  resource is before; a start in a later resource is not. In the asking
  resource, a start at the top of the file is before, and a start at an
  anchor is before only when both the anchor and the asking element are
  located and the anchor comes first. A resource listed more than once
  in the reading order is taken at its first listing for the asking
  place, as `PublicationCatalog.scopeOf` does, and at its last listing
  for a chapter start, so neither is placed too favourably.

Chapters that fail either rule are not listed at all, so no title or
destination of a later chapter shows. A book with no usable contents is
one chapter and has nothing to list; the sheet says there is nothing
before this chapter, as it does for the first chapter.

### What each row shows

The contents title, or the chapter number, and one of:

- the summary sentences of the chapter's recap in batch order;
- a note that the recap holds no summary (a recap can hold only
  characters, or nothing for a chapter without text);
- a note that the recap could not be read;
- a note that the chapter has no recap.

Rows are read under the book's content sha and `Recaps.FORMAT`, by any
server or model, as lookups read them. A row whose unit key no longer
matches the chapter's boundaries counts as no recap. Without a content
sha nothing is read and the menu entry does not show. The read is a plain
query that deletes nothing; only the recaps shown are touched as used
for the store's least-recently-used trim. The text is shown as plain
text.

### Going to a chapter

Each listed row has Go to chapter, which opens the chapter's start as a
peek through the existing `SourcePeek`: the saved place does not move and
Return comes back. The start is the resource and anchor the chapter map
already uses. A start in a resource listed more than once, or whose
anchor is not found in its document, cannot be opened reliably; the
action is disabled there rather than opening the top of the file.

### Where it shows

The menu entry shows when the book has at least one stored recap for its
current version and format, whether or not a companion service is chosen
now and whether or not Codex is on. That check reads nothing but a
count. The sheet is one of the companion panels: opening it closes a
profile or Codex panel, it steps aside during a peek and comes back with
Return or Show, and its scroll position survives the peek. While it is
visible the page does not auto-scroll.

### Wording

The sheet says the summaries were written by an AI model from the
chapters before this one, may be wrong, and may contain spoilers a model
knows from elsewhere.

### What stays

The recap prompt and its JSON stay as they are. Summary sentences carry
no refs, so the sheet cannot cite passages for them. Adding refs would
change the prompt and bump `Recaps.FORMAT`, and the store drops rows of
another format, throwing away every recap readers already paid for.
Cited summaries, if wanted, come with the next format change made for
another reason.

## Consequences

- Readers can read what background recaps cost them, and judge whether
  to keep them on.
- The summaries are model text with no citations, unlike everything else
  the companion shows. The sheet says so and shows them only for chapters
  known to be behind the reader.
- Books whose contents run out of reading order, or with several
  chapters in one file whose anchors cannot be found, show fewer
  chapters.
- Chapters finished by jumping, before recaps were on, or trimmed from
  the store show as not recapped.
  [#356](https://github.com/chmouel/liseur/issues/356) plans a way to ask
  for one.
- Recaps stay a local cache under the same limits, sweep and backup rules
  as before. Nothing new is stored.
