# 12. Connecting to Grimmory through its Komga-compatibility API

Status: accepted

## Context

[Issue #89](https://github.com/chmouel/liseur/issues/89): a reader runs
[Grimmory](https://github.com/grimmory-tools/grimmory) — a self-hosted
library manager, formerly BookLore, hence the `org.booklore` package
names — which advertises a Komga-compatible API. Pointing Liseur's Komga
client at it got nowhere.

It is a compatibility *shim*, not Komga. Read against v3.3.3, five things
stop the Komga client dead:

- **Auth is Basic, with a dedicated OPDS user.** There is no API key
  anywhere in Grimmory, and `KomgaSetupClient` rejects anything that is
  not one.
- **It is mounted at `/komga/api`, not `/api`.** Liseur's candidate walk
  only ever strips path segments, so a bare host never reaches it.
- **`POST /v1/books/list` answers 501.** That is the only route the Komga
  catalog client walks with, so the library would arrive empty.
- **Downloads would be refused.** Grimmory hardcodes `roles: ["USER"]`;
  Liseur gates `canDownload` on `FILE_DOWNLOAD`.
- **A Basic password would not have been persisted.** `storeLocked()`
  sealed one only for calibre-web, so the account would connect and be
  unusable on the next refresh.

Reading position is absent, not merely different: `/progression`,
`/read-progress` and `/positions` all fall through to a 404, and
`readProgress` is never populated on a book, so even the catalog walk
that doubles as Komga's change detector carries nothing.

The scope agreed for this work was the shim: connect, browse, download.
Not Grimmory support in general.

## Decision

A new `ServerKind.GRIMMORY`, with its own implementations of the existing
`data/remote/` contracts under `data/grimmory/`. Grimmory's DTO shapes are
Komga's, so `KomgaHttp` is reused as-is and `KomgaBooks` after a small
widening; what genuinely differs — auth, path prefix, listing route,
format filter, href shape, role gate — is written plainly in the Grimmory
classes.

No position sync: no entry in `AppContainer.positions`, so
`RoutedPositionSync` answers `NotApplicable` and nothing is ever offered.

## Design

**A separate kind, not a Komga variant.** The alternative was a flag on
the Komga classes. Six behaviours differ, and every one of them would have
become a branch inside code that real Komga also runs — so a change made
for Grimmory could break a server the app already supports. The house rule
is that anything provider-shaped lives behind a `data/remote/` contract
rather than a `when (kind)` at the call site; a second kind is what that
rule asks for. `ServerKind.GRIMMORY` is written into `books.url` as
`grimmory:` and is effectively schema, so it is permanent either way.

**The stored base URL is the Grimmory root; `/komga` is added per
request.** `GrimmoryUrl.api()` prefixes it, so a reader who types
`https://host` and one who types `https://host/komga` end up with the same
stored row. Setup probes the `/komga`-suffixed path *first* for each
candidate, which is not cosmetic: a bare `{root}/api/v2/users/me` reaches
Grimmory's main security chain and 401s, so probing the other way round
would blame the password for what is really an address.

The candidate walk does *not* strip a trailing `komga` from what was
typed. It looks like it should — an address pasted at the shim would
otherwise double the prefix — but only the server can say whether that
segment is the shim's or the reverse proxy's, and a Grimmory genuinely
served under `/komga` would become unreachable. So both spellings are
tried in order: the doubled one 404s, its parent connects, and the row
that gets stored is the same either way.

**A rejected sign-in does not end the candidate walk**, which is where
`GrimmorySetupClient` parts company with the Komga client it is otherwise
copied from. A pasted address is walked up its parents, and the deeper one
need not be Grimmory: a reverse proxy with its own password on
`example.com/private` answers 401 to credentials that work one level up.
The rejection is remembered and reported if nothing else answers, since it
is the more useful complaint; an unreachable *host* still stops the walk,
every candidate sharing one.

The same rule applies across the two protocols. A schemeless address is
tried over HTTPS and then, once the reader has allowed it, over plain
HTTP; the answer that decides is the second one. Reporting the HTTPS
complaint instead would tell someone whose password is simply wrong that
their server "did not answer securely", and send them to fix the wrong
thing. Only an unreachable host on the retry keeps the HTTPS wording,
since then nothing answered either way.

**A refresh may not downgrade the scheme.** Every setup client falls back
to plain HTTP when it is told it may, and `refreshCapabilities()` used to
tell all of them so unconditionally — which meant an https account that
was merely down for the afternoon got its stored secret retried in the
clear, with nobody deciding to. It now passes `allowHttp` only for an
account already stored as `http://`, so the scheme a reader agreed to at
setup is the one every later probe keeps. The fix is in
`RemoteAccountRepository`, not here: all four kinds sign with a stored
secret and all four were affected. Grimmory is only the one where that
secret is the reader's actual password, with no token to fall back to.

**Ids are parsed once, in `GrimmoryId`.** They are database `Long`s, but
they arrive as untrusted JSON, get embedded in URL paths, and are
persisted as `remoteUuid`. One `parse()` accepts `^[1-9][0-9]*$` and then
round-trips it through `Long`, so an id Grimmory could not address is
refused rather than carried as a plausible string. Both the catalog and
the file source use it — a row that somehow stored a bad id still cannot
aim a request anywhere unintended. This is the rule already written down
for annotation ids: an id is opaque to carry, but must be checked before
it is *addressed*.

**Format is filtered on `mediaType`, never `mediaProfile`.**
`KomgaMapper.getMediaProfile` reports MOBI and AZW3 as `"EPUB"` too, so
the profile would shelve books the reader cannot open. `mediaType` is read
off the same primary file that `/file` serves, so it describes exactly the
bytes a download returns.

**A response this client cannot account for fails the walk.** A walk
reporting `complete = true` runs `dropVanished()`, which deletes every
catalogued book it did not see — and the reading progress, sessions and
history hanging off it. `KomgaBooks.parsePage()` defaults a *missing*
`last` to `true`, so on the paged route one shape mismatch on page 0 reads
as "the catalog is one page long" and wipes the rest. So the Grimmory
walk earns the right to prune, page by page, and gives it up on anything
it cannot account for:

- **The envelope has to describe itself.** `last`, `number`,
  `totalPages`, `numberOfElements`, `size` and `totalElements` must all
  be present, be of the type they claim, and agree with each other and
  with the page that was asked for. They are read by type rather than by
  presence: a field that is there and null passes `has()` while `optInt`
  answers 0 and `optBoolean` answers its default, which together spell
  "page 0 of 0, and the last one" — a body that said nothing, believed
  as one saying the catalog ends here.
- **The counts have to add up.** `numberOfElements` catches a page short
  of what the server said it sent, which 199 rows where it said 200
  otherwise is not: internally plausible, and a book that would be
  pruned as vanished. A page before the last must be full measured
  against the server's *own* declared `size`, not the 200 that was
  requested, so a server entitled to clamp its page size is not locked
  out of pruning forever. `totalPages` must be the number of pages
  `totalElements` at that size actually needs — which is how the server
  works it out too — and on the last page the running count of entries
  must equal `totalElements` exactly.
- **The catalog is pinned to its first answer.** `totalElements`,
  `totalPages` and `size` are held constant for the whole walk. Checking
  each page only against itself lets a catalog shrink underneath it —
  page 0 saying three books over three pages, page 1 saying two and that
  it is the last — with every count self-consistent and the third book,
  never sent, pruned as one that went away. A catalog being written to
  while it is read is not one to prune against.
- **No book may be counted twice.** Ids are tracked across the walk,
  because counts alone cannot tell a page of two books from a page
  holding one book twice, and the second shape leaves a book the server
  counted unsent and therefore prunable.
- **`content` has to be an array.** Absent, JSON null and some other
  type all parse to no books, which is exactly what a library someone
  emptied looks like. This is the one shape that ends the walk rather
  than merely forfeiting completion: there is nothing to stream, and no
  reason to trust the paging fields wrapped around it either.
- **Every entry has to be readable.** An entry that is not an object or
  carrying an unparseable id forfeits completion while its well-formed
  neighbours still stream through — on that page and on every page
  after it. Entries that never became books are counted back into the
  duplicate check, or one malformed row would read as the same book
  twice and stop the walk by the back door.
- **A media type has to be one of two known lists.** EPUB is readable;
  the six other formats `KomgaMapper.getMediaType()` can return are
  known and skipped; anything else is unknown. The tempting rule — "not
  an EPUB means not readable" — cannot tell a comic from an EPUB whose
  type was spelled differently, and gets the second one wrong by
  deleting it. One ordinary EPUB alongside a hundred respelled ones
  would otherwise keep the walk complete and prune the hundred. Meeting
  a new format costs a refresh and is fixed by adding a line to
  `NOT_READABLE`.

  `application/zip` is deliberately *not* on that list, though the same
  method returns it: it is what a book of no recognised type gets, which
  is Grimmory saying it does not know either. An EPUB is a zip, so
  filing it under "not a book" would prune one that is merely
  mislabelled.
- **An unknown type costs the pruning, not the browsing.** It forfeits
  completion, but the walk carries on to the end. Stopping there would
  hide every book on the pages behind it: one audiobook early in a large
  library would empty most of the shelf, and the reader would go looking
  for a server fault rather than a format this build has not met.
- **A catalog of nothing readable is not an emptied one.** A walk that
  reaches the last page having seen entries but shelved none of them
  refuses to prune, as the backstop for the case where the types are all
  known and all wrong. A server that genuinely holds no EPUBs loses
  nothing by this, having nothing to prune.

**What none of that catches**, and is worth writing down rather than
implying otherwise: a catalog edited *while* the walk is reading it, in a
way that keeps its size. Delete one book and add another between two
requests and the pages shift under the offset, so a book that is still
there is never sent — and every count, the page arithmetic and the
uniqueness check all still agree. It would be pruned, taking its reading
position with it.

Offset pagination cannot be made safe against that from the envelope
alone; a fix means a snapshot the server does not offer, or confirming
the walk against a second one before deleting anything. That belongs in
`RemoteCatalogRepository` rather than here, because Komga's and
calibre-web's walks have exactly the same property, and the house rule is
that the conflict rules are written once rather than per provider. Filed
as [#93](https://github.com/chmouel/liseur/issues/93).

It ends on the raw page rather than the filtered one, for the same
reason: a page that is all comics filters to empty and would otherwise
look like the end.

**Search is local, not OPDS.** An earlier revision implemented
`search()` over Grimmory's OPDS feed. `RemoteCatalogRepository.search()`
has no caller: library search is local, filtering the cached catalog
through `domain/survivesLibrarySearch()`. Since the walk pulls the entire
catalog into the database, that local search already covers every book on
the server, and an OPDS implementation would have added Atom parsing,
`rel="next"` following and a shared hardened-XML boundary in support of
dead code. It would also have introduced a bug: OPDS advertises an
acquisition link per format, but `/file` serves only the primary file, so
a book whose primary file is MOBI with an EPUB alternate would have passed
an OPDS format test and then downloaded a MOBI. `search()` returns nothing
and says why. If remote search is ever wired to the UI, Grimmory's OPDS
`catalog?q=` is the way in.

**`canDownload` is unconditionally true.** There is no `FILE_DOWNLOAD`
role to report — the shim hardcodes `["USER"]` — and `/file` is open to
any authenticated OPDS user the library is shared with. Gating on the role
as the Komga client does would refuse every download.

**One error message names both causes.** `KomgaEnabledInterceptor` answers
403 when the compatibility API is switched off, which Liseur maps to
`BadCredentials`, and no `SetupFailure` variant fits "an administrator has
not turned this on". Rather than invent one for a condition the app cannot
distinguish, the Grimmory credential error says both things: the wrong
kind of user, or the API still off. Both were confirmed by hand against a
running server, and they are genuinely indistinguishable from the client.

**Verified against a real server, not a reading of its source.**
`hack/grimmory-dev` brings up a pinned `ghcr.io/grimmory-tools/grimmory`
image — **v3.3.3**, never `latest`, so a red test stays attributable —
seeds it over REST with 210 generated EPUBs and one CBZ, and
`tests/grimmory-connect` drives the app's own screens against it: sign in,
a shelf that spans more than one real HTTP page, the comic absent, a
second walk that prunes nothing, covers actually fetched through the
`/komga` prefix, a download that opens, and nothing anywhere offering to
keep the reader's place.

## Consequences

Grimmory browses and downloads. It does not sync reading position, and
says so on the account screen rather than presenting a switch that cannot
work.

Grimmory does speak KOReader's kosync at `/api/koreader`, behind a third
credential set and matched by file hash. That is the way in if position
sync is ever wanted here; it is a different protocol and a different
credential, not an extension of this.

Series ids are synthetic — `{libraryId}-{slugified-name}` — so renaming a
series in Grimmory changes its id. `SeriesExtrasRepository` is gated on
Komga and never fires here, which is the right outcome: the id still
arrives on the book and groups the shelf, and a rename regroups rather
than corrupts.

`sizeBytes` is `fileSizeKb * 1024`, rounded to the kilobyte. Cosmetic.

No new dependency, and with OPDS dropped, no XML parsing added at all.
