# ADR-0038: BookOrbit as a remote provider

- **Status:** Accepted
- **Date:** 2026-09-22
- **Depends on:** [ADR-0015](0015-a-server-the-reader-describes.md),
  [ADR-0023](0023-position-sync-versus-whispersync.md)

## Context

BookOrbit is a self-hosted library and reading platform. A reader who runs
one has their books, their reading position and their highlights there,
and reaching it meant either its OPDS feed or its KOReader-compatible
endpoints — the two surfaces Liseur already knows how to speak, and
neither of which is what BookOrbit's own client uses.

Its own client uses its REST API at `/api/v1`. That API is where a book's
files are distinguished from the book, where its eight-value personal
reading status lives, and where the EPUB CFI it records alongside a
percentage can be read and written. Speaking anything else would mean
choosing the coarser surface on purpose.

## Decision

Add `ServerKind.BOOKORBIT` and a `data/bookorbit/` package, behind the
same `data/remote/` contracts every other provider implements. Ship
catalog browsing, search, covers and downloads first; reading position,
status, annotations and sessions follow only as far as the API can be
used honestly.

### The credential is a session, and that is new

Every provider so far signs with one long-lived secret: a calibre-web
password, a Komga API key, a liseur-sync device token. BookOrbit offers a
native client no scoped key at all — only the account password — which
means the app has to hold something new.

A password buys an access token that lasts about fifteen minutes and a
refresh token that lasts a week and **is replaced on every use**. That
last part is what makes this more than bookkeeping:

- Renewal is serialised. Two refreshes spending one refresh token leave
  one caller holding a session the server has already forgotten.
- A renewal that finishes after the reader has switched accounts must not
  put its tokens onto the new account. The row it writes therefore carries
  the epoch and the refresh token it started from, and a write whose
  `WHERE` no longer matches is dropped rather than retried. The pattern is
  `RemoteServerDao.setCanReadInsights`, which already guards a credential
  write on the token that requested it.
- A token the server refused must be remembered as refused. Its stated
  expiry can still be in the future — a revoked session, a clock that
  disagrees — and reading the row again would hand the same rejected
  token straight back.
- A renewal has to reach the paths that are not API calls: a queued
  download and a cover. The download signs when it builds its request, on
  the worker's IO thread, because that is the last point at which it can
  notice an expired token before fetching megabytes.

The password is not kept, exactly as liseur-sync does not keep one. When
a refresh is refused there is nothing left to sign in with, so the account
says so and the reader reconnects.

### Identity has to be namespaced, again

BookOrbit numbers books per installation and starts again at one, and a
downloaded book keeps its `books.url` when a server is disconnected. A
bare `bookorbit:113` would therefore let one server's book adopt the row,
the file and the reading history of another's. This is the problem
`OpdsScope` already solves, and it is solved the same way: a digest of the
address and the account in front of the id.

The account is part of the scope as well as the address. Two people
sharing one installation are two libraries, and one must not adopt the
other's highlights on a login change.

### A book is a book, and a file is what is read

BookOrbit keeps the EPUB, a JPEG cover and an OPF sidecar on the same
record, and a rescan can make a different EPUB the primary one. Liseur
writes positions and highlights against *a file*, so the file is chosen
once and written into `book_orbit_binding`. A refresh reads that choice
back rather than choosing again; a bound file the server no longer offers
keeps its identity and loses its download link, because the reader's place
is in a file and quietly pointing it at a different one is not a repair.

### Position sync requires a verified CFI

BookOrbit records an EPUB CFI. Readium's `ExactLocatorAnchor` uses a
selector and text quote, so the two need a checked conversion. In
BookOrbit's `saveProgress`, a text write speaks for the text position
outright, including clearing a CFI that was not sent. Pushing a
percentage-only position would therefore **delete the exact position the
web reader had recorded**, for every book this phone touched.

The provider initially advertised browsing and downloads only. The verified
CFI bridge now parses and resolves incoming anchors against the selected
EPUB and active reader, and pairs outgoing CFIs with the exact saved local
revision. Independent reader and enabled-path fault checks passed on
2026-09-23. The provider now advertises `syncAbility = EXACT`; an account
with an access or refresh token can sync positions.

Normal sync sends through the durable selected-file exchange, with bounded
account traversal. Remote adoption still needs active-reader proof and
closed-book identity/revision checks. Generic position-choice methods stay
disabled. Reading status and annotations are not synchronized.

## Consequences

BookOrbit supports browsing, downloads and exact EPUB positions. Settings
explain conflict resolution and the last-server-write-wins policy.

The session machinery is the first of its kind and is the part to keep
honest. Anything that signs a BookOrbit request has to go through the
session, or it signs with a token that will expire in a way nothing
notices. Downloads and covers are the two easy places to forget.

Two API limitations are recorded rather than worked around, because no
client can close them:

- `/books/files/{fileId}/download` sets `Accept-Ranges` but does not
  implement range requests, so an interrupted transfer restarts. `/serve`
  implements ranges and skips the download permission check, and using it
  to dodge that gate would be wrong.
- BookOrbit's status route replaces progress-derived fields and there is no
  conditional write on progress, so a percentage-only sync would not only
  be lossy but also unguarded against a concurrent read elsewhere. This is
  why position writes require a verified CFI and the policy below.

A CFI and a final selected-file GET do not close the concurrent-write
gap either: a deterministic race test wrote a different server position
after Liseur's preflight GET and before its POST. The unconditional POST
overwrote it and read-back acknowledged Liseur's position.

On 2026-09-23 the maintainer accepted last-server-write-wins for this
interval. The last position stored by the server wins, even when a delayed
write represents older reading or a lower percentage. Preflight conflict
checks, exact CFI/revision pairing and read-back remain required; an uncertain
POST is not automatically replayed. Server-side conditional writes are no
longer a prerequisite. Automatic position pushes are enabled after
independent-device and final-path acceptance. See the
[approved policy and acceptance handoff](../bookorbit-position-sync.md#approved-write-policy).

Upstream requests worth filing: a client-idempotent annotation create, a
conditional progress write, the stored file digest, and a range-capable
download that still checks `library_download`.
