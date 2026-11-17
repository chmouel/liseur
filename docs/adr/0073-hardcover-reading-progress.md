# 73. Reporting reading progress to Hardcover

Status: proposed

## Context

[Issue #468](https://github.com/chmouel/liseur/issues/468). Readers who
track their reading on [Hardcover](https://hardcover.app) update it by
hand after each session. Hardcover has a public GraphQL API at
`https://api.hardcover.app/v1/graphql` with personal API tokens, a rate
limit of 60 requests a minute (burst of 10), and a library model where
each book is a `user_books` row with a status and its reads are
`user_book_reads` rows carrying `progress_pages`. Setting a library
entry's status to currently reading (2) starts a read and setting it to
read (3) finishes one; the server writes the journal entries itself.

## Decision

**A reading tracker, not a sync partner.** Hardcover is told where the
reader is; nothing it holds moves a position here. It is therefore not a
`PeerPositionSync`, takes no part in `ReadingStateMerge`, and has its own
single-row table (`hardcover_account`) beside whatever server is
connected, or none: a reader with only local files has as much use for it.

**The token is pasted.** The account is a personal access token pasted
in Settings, proved with `me` before anything is stored and sealed with
`CredentialCipher`. Hardcover recommends OAuth for apps other people
use; adopting it needs an OAuth client registered for Liseur, which is
the maintainer's to create, and would replace only the sign-in, not the
rest of this design.

**Matched once, remembered per book** (`hardcover_book`). By the file's
ISBN when Hardcover knows it. Otherwise by a title-and-author search,
adopted only when the hit's title (ignoring case, accents, punctuation
and a subtitle after `:`, `;` or a bracket) and at least one author name
word of three letters or more agree with the file; the reported edition
is Hardcover's default ebook edition, then physical, then most read,
preferring one with a page count. Anything less certain waits for the
reader's choice in Book details, because marking the wrong book read on
someone's account is worse than asking.

**A push sends only the difference.** It reads the book's library entry
and open read, then `HardcoverProgress.plan` decides the fewest writes:
add the book as reading, set it to reading, log pages, or set it to
read. Pages are the reader's share of the book times the edition's page
count, never the last page before finishing, since reaching it finishes
the read on the server. Progress never moves backwards, a book already
read is not turned into a re-read by being opened, and did-not-finish is
left alone. Only reading and finished are reported.

**When.** On closing a book, through a unique `WorkManager` job per book
that needs a network, replaced by a newer close; and when the reader
taps Push to Hardcover in the reader menu or Book details. A switch turns
the automatic push off, and a per-book switch keeps a book off Hardcover
entirely, which a manual push also respects.

## Consequences

- One push costs at most six requests, and the title search and edition
  lookup happen once per book.
- A finished book that was never closed in the reader (marked finished
  from the library) reaches Hardcover only on a manual push.
- Start and finish dates are the days Hardcover receives the status
  changes.
- An edition without a page count gets status changes but no pages.
- Pulling progress back, ratings, and did-not-finish are left for later.
