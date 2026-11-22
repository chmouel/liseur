# 75. Hardcover reading tracker

Status: accepted

## Context

[Issue #468](https://github.com/chmouel/liseur/issues/468). Readers who
log their reading on [Hardcover](https://hardcover.app) update it by
hand. Hardcover has a GraphQL API at `https://api.hardcover.app/v1/graphql`
with personal tokens and a rate limit of a burst of 10 requests refilled
one a second. A book in a reader's library is a `user_books` row with a
status (1 want to read, 2 currently reading, 3 read, 4 paused, 5 did not
finish) and a rating; its reads are `user_book_reads` rows with
`progress_pages`, `started_at` and `finished_at`. Setting status 2 starts
a read and status 3 finishes it, dated today.

The first design and most of the client, matching and planning code come
from lambda3141's contribution to the issue.

## Decision

Hardcover lives only in the Google Play build. It is one fixed
proprietary service, which F-Droid lists as the NonFreeNet anti-feature,
the same reason Gemini lives only in `play`
([ADR 0042](0042-gemini-read-aloud.md)). All of it is under
`app/src/play/`. `main` sees a `ReadingTracker` class that `foss`
implements as no-ops, and reaches it only through callbacks and
composable slots.

Liseur reports to Hardcover and brings nothing from the reader's library
back. The edition picker reads the public catalogue to show candidates:
search text only on an explicit submission, the slug of a pasted
Hardcover book link, or a pasted ID or ISBN, with the token, to
`api.hardcover.app`. Its cover images come from `assets.hardcover.app`
through an `ImageLoader` of its own (`HardcoverImages`): a bare OkHttp
client with no interceptors, authenticator, cookie jar or redirects, no
disk cache, and only validated https URLs on that host. The app's shared
loader signs requests for the reader's servers and must not serve it.
The tracker is no
`PeerPositionSync`, takes no part in `ReadingStateMerge` or
`RemoteRouter`, and works with or without a library server. Its state
(account, per-book match and exclusion, push queue) is a separate Room
database, `hardcover.db`, so `LiseurDatabase` and the `foss` schema stay
unchanged.

The reader pastes a personal token. Liseur proves it with `me` before
storing anything and seals it with `CredentialCipher`. Hardcover
recommends OAuth for apps other people use; that needs a client
registered for Liseur and would replace only the sign-in.

Each book is matched once and remembered, keyed by the local
`books.url`. The match uses the ISBN when the file's `dc:identifier` is
one. Otherwise it takes a search hit whose title and an author both
agree with the file, and two different subtitles count as two books.
Anything less certain waits for the reader to choose an edition in Book
details. An edition found without asking never replaces the one already
on the reader's Hardcover shelf; one the reader chooses moves the entry
and its latest read onto it, whatever their status. A book removed from
the library, or whose file now holds another book, loses its match once
the library transaction commits. Until then `hardcover.db` marks it, so
no push recreates the match; if the transaction rolls back the mark goes
and the book is queued again, and marks a stopped process left behind
are settled at the next start. Duplicate pruning keeps a copy with a
chosen edition or an exclusion.

A push sends only the difference (`HardcoverPlan`). It reads the library
entry and its latest read, changes the status first, then corrects the
read Hardcover made:

- reading and read follow the local status; want to read, paused and did
  not finish go out only when the reader chose them; unread sends nothing
  and never removes a book;
- a book read on Hardcover stays read, and a paused or abandoned one
  moves only for a chosen status;
- pages are the reader's share times the page count of the edition the
  read is logged against, never the last page before finishing and never
  lower than Hardcover has;
- the read's start is the first local reading session and its finish
  `books.finished_at`, replacing the dates Hardcover stamped;
- the rating is the local one halved; removing it clears Hardcover's only
  when this device set the value there.

Closing a book, choosing a status and rating it queue the book. A single
unique `WorkManager` job drains the queue one book at a time, and the
client spaces requests a second apart. Statuses that arrive from a sync
partner queue nothing. The reader menu and Book details push by hand,
which bypasses the automatic-sync switch but not a book's exclusion. All
pushes share one lock, so a manual push and the worker cannot both add
the same book. A book that still fails after five tries is dropped from
the queue until it changes again, and the books after it get a fresh
run, so it cannot hold up the others. Once
Hardcover refuses the token, automatic pushes stop
until the reader pastes a new one.

## Consequences

- A push costs at most about eight requests; matching happens once.
- An edition without a page count gets statuses, dates and rating, but no
  pages.
- Android backup does not carry `hardcover.db`; the connection backup
  carries the account, and matches are made again on the new device.
- Pulling from Hardcover, OAuth, and ISBNs from sources other than the
  EPUB are left for later.
