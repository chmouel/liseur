# 71. Home quotes come from what was read, found without AI

Status: accepted

## Context

KOReader's Bookshelf plugin shows a quote of the day on its start
screen, taken from the reader's highlights. Many readers never
highlight, so a card that relies on highlights alone stays empty for
them. A quote also must not give away a part of the book the reader
has not reached.

Asking a language model which lines are memorable would need a
configured AI service, send book text off the device and leave the
F-Droid build without the feature.

## Decision

Home shows one quote a day from a book with reading progress. A
book's own highlights are used first. A book without any is walked
with Readium's content iterator up to its furthest point reached (the
whole book once marked read), and its sentences are scored by plain
heuristics in `domain/quotes/MemorableLines.kt`: hard limits on
length, ending and capitals, then points for a line that stands alone
in its paragraph or at a chapter edge, general and present-tense
wording, a turn or a repeated word, and words rare in that book;
points off for dialogue, speech verbs, names, a pronoun pointing back
and past-tense narration. Word lists exist for the shipped languages;
others use the language-neutral features only.

The 30 best lines per book are cached in a database of their own,
`quotes.db`, with how far each book was walked, the scorer `VERSION` and
the `books.work_id` they were walked from. A book is walked again only
after the reader moves on by 2% or the version changes, and not at all
while it has a highlight the reader has already read past, since the
pick prefers those and the lines could not be shown. Lines past the
current read point are left out at pick time, so a book reset to the
start shows nothing from later pages; highlights are held to the same
ceiling, because one can be pulled from a device that read further. A
file replaced under the same URL keeps neither its lines nor its
watermark: the work id no longer matches, and the old book's words would
otherwise appear under the new title.

The pick is seeded by the day and a nonce kept in DataStore, so it
holds all day and across restarts; Another quote moves to the next
book. Tapping the card opens the book at the passage as a look, like
a character profile's source: the saved place does not move, and Back
to place returns to it.

## Consequences

The feature is in both flavors and works offline. Quality depends on
the heuristics; changing them means bumping `VERSION`, which rewalks
books in the background. `quotes.db` is a cache and is left out of
backups; losing it costs a rewalk. Walking a long book takes seconds,
so it runs one book at a time on the app scope, most recently opened
first, and never blocks Home.
