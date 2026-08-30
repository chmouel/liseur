# 16. A book's details

Status: accepted

## Context

[Issue #113](https://github.com/chmouel/liseur/issues/113). What Liseur
remembers about a book fits on a spine: a title, an author, a cover, a
series and a place in it, a page count and a size the server claimed.
Most of what the book says about itself is read past.

`LocalLibraryRepository` opens every EPUB through Readium's streamer —
in `indexBook()`, in `reindexBook()` when the file's modification time
moves, and in `indexDownloadedFile()` when a download lands — and takes
five things: the title, the author, the cover, the identifier (folded
into `work_id` by `workIdOf()`), and the series. Behind those,
`Publication.metadata` was also holding a subtitle, a description, a
publisher and imprint list, a language list, a publication date, a
subject list, a page count, an `Accessibility` record, and a full
contributor list with roles
— translators, narrators, illustrators, editors. The publication closes
and they go with it.

The catalogs fare no better. calibre-web puts the book's description in
the `<content>` block of its OPDS entry, and `OpdsParser` opens that
block only to dig the series line back out of the prose around it.
Komga's `metadata` object carries a summary, tags, an ISBN and a release
date; `KomgaBooks` reads a title, an author and a series index off it.
liseur-sync publishes a `sha256` on every catalog book, which
`LiseurSyncCatalogClient` parses onto `RemoteBook` and which nothing
then stores.

The device knows a few things no server does: the watched folder tree a
local book was found under, what the file weighs, when it arrived, when
it was downloaded, and when it was last modified. It knows the two
hashes as well, but only for books something has already asked about —
`BookFingerprintStore` reads the whole file to compute them and does it
lazily, for sync.

There is nowhere to show any of this. `BookActionsSheet` is a long-press
menu of verbs, and already offers download, remove, delete, mark read,
archive, reading stats, series and upload. `Endpaper` is a colophon
printed after the last page. `BookReadingStatsScreen` is about time
spent. A reader who takes a book off the shelf three years after
downloading it and wants to know what it is has to open it and read the
first chapter.

## Decision

**A screen, not a bigger sheet.** The actions sheet is a column of
conditional rows that has grown once per verb the app learned, and
details are not a verb: they are somewhere to stay, scroll, and come
back to. So a full screen, routed from `MainActivity` beside
`Screen.BOOK_STATS`, with the same `returnsTo` treatment, reachable from
the actions sheet wherever that sheet already opens — the library and
the series screen. The sheet gains one row and loses the pressure to
become a details view itself.

The reader does not open it. `ReaderActivity` is a separate activity
that does not host the actions sheet and does not route through
`MainActivity.Screen`, so an entry point there is an activity contract,
not a row: a separate decision, and not this one.

**Read-only.** Correcting what a book says about itself is a different
feature with a different shape. An edit has to survive every catalog
refresh and every re-index, which means a third precedence layer — the
one the series columns already carry in `user_series_name`, with its
own conflict rules. Series editing exists and stays where it is. This
screen displays.

**Five bands, and a field with nothing behind it is not drawn.**

- *The book* — cover, title, subtitle, contributors with their roles,
  series and position.
- *About* — description, subjects.
- *Publication* — publishers, imprints, publication date, languages,
  identifiers, page count, and the accessibility record when the file
  declares one.
- *This copy* — format, size, where it came from, added, downloaded and
  file-modified dates, and the two hashes when they have already been
  computed. Nothing here computes a hash: reading a whole book off a
  document provider to fill in a row the reader did not ask for is the
  wrong trade, and `BookFingerprintStore` will fill it in the first time
  sync needs it. A book with no file on this device shows no hash, even
  if a row survives from a copy that used to be here.
- *Reading* — progress, time left, finished and archived state,
  annotation counts, and a way into `BookReadingStatsScreen`.

A screen of em dashes says less than a short screen, so an absent field
is absent, and a band whose fields are all absent is not drawn either. A
sideloaded EPUB with a bare OPF shows a cover, a title and a file size,
and that is an honest answer.

The reading band links rather than repeats. Everything on it is one
line; the moment it wants a chart it is the stats screen, which exists.

**Stored, with a backfill pass, not read while the reader waits.**
Mounting a document provider, opening a container and parsing an OPF is
the work the indexing pass already does, and it is not work to start
inside a navigation. The larger reason is that a book in
`DownloadState.REMOTE` has no file on this device at all, so its details
can only ever be what the catalog said and what was written down at the
time.

**One row per source, not one column per field.** `books` keeps
`file_series_name` apart from `catalog_series_name` precisely so a
catalog refresh cannot overwrite what the file said, and that reasoning
holds for every field here. Doing it the same way would add fourteen
columns to a table already carrying more than thirty.

The real reason for a side table is not the column count, though. It is
that each source's contribution is a *snapshot* that has to be replaced
whole: when a catalog refresh no longer mentions a description, the
right answer is that the server no longer has one, and paired columns
make that an update per field where a row makes it one write. A
`book_metadata` table keyed by `(book_url, source)` holds exactly that —
one row per source, replaced atomically, resolved by one pure function.

**Derived state dies with the book.** `book_metadata` is the one table
in this schema that should cascade, and it gets this schema's first
foreign key: `book_url` references `books.url` `ON DELETE CASCADE`.
`reading_progress`, `annotations` and `annotation_sync` have no foreign
key, and for their own reasons rather than one shared one: a reading
position and a highlight are the reader's and irreplaceable, and the
agreements in `annotation_sync` have to outlive an ordinary removal or
the book coming back would push every mark again as new. Metadata is
derived. A re-index rebuilds it from the file in front of it, so nothing
is lost by dropping it and something is wrong if it lingers.

## Design

### The table

```
book_metadata(
  book_url        TEXT NOT NULL,   -- FK -> books.url, ON DELETE CASCADE
  source          TEXT NOT NULL,   -- 'file' | 'catalog'
  subtitle        TEXT,
  description     TEXT,            -- plain text, already stripped
  publishers      TEXT,            -- JSON array of names
  imprints        TEXT,            -- JSON array of names
  published       TEXT,            -- ISO-8601, precision as the source gave it
  languages       TEXT,            -- JSON array of BCP-47 tags
  subjects        TEXT,            -- JSON array of strings
  contributors    TEXT,            -- JSON array of {role, name}
  identifiers     TEXT,            -- JSON array of {scheme, value}
  page_count      INTEGER,
  file_size_bytes INTEGER,         -- file source only, measured
  accessibility   TEXT,            -- JSON, file source only
  PRIMARY KEY (book_url, source))
```

Lists are JSON in a column, which is what `reading_progress.locator_json`
already does. Child tables would buy queries nobody has asked for — the
screen reads a book's rows and renders them — and cost three more tables
and three more cascades.

There is no `updated_at`. A row is replaced whole and precedence never
asks when either source last spoke, so a timestamp here would be a
column that decides nothing — the same reason `client_ts` decides
nothing in annotation sync.

`file_size_bytes` is the size the device measured, and it is deliberately
not `books.size_bytes`, which is the catalog's claim and is treated as
untrusted wherever it is added up. The screen labels the two differently
— what the file weighs here, and what the server says it weighs —
because when they disagree that is worth knowing rather than worth
hiding.

One function answers it, for every path that indexes a book. A folder
scan already has the number: `findEpubs()` runs a projection per
directory, so `COLUMN_SIZE` joins `COLUMN_LAST_MODIFIED` in it at no
extra round trip, and the scanned size is carried on `ScannedEpub`. A
downloaded book is a `File` in `booksDir()` with a `length()`. Anything
else — a single book imported through the picker, and every old book
`backfillMetadata()` walks, where no scan is in flight — is one
`ContentResolver.query()` for `COLUMN_SIZE` against that document URI.
A provider that answers null, or that will not answer at all because the
permission was never persisted, leaves the size unknown, which is a
blank row and not an error.

That query blocks, and so does the one that asks a tree for its display
name. Both move to `Dispatchers.IO` inside the function that makes them,
not in the caller: a `suspend` signature reads as a promise that the
thread is safe, and this one is reached from a view model scope.

`source` is a string, and a row whose source is not one this build knows
is ignored rather than deleted. A downgrade after a future third source
should lose a screen's worth of detail, not another version's data.

Blank is normalised to null on the way in, before precedence runs.
Otherwise a catalog that sends `<summary></summary>` beats a file that
has a description, and the reader gets a blank band instead of the
blurb.

### Resolving two sources

Scalars coalesce, catalog first: description, subtitle, published date,
page count. This is the way round the series columns
already resolve — `COALESCE(:catalogSeriesName, file_series_name)` — and
for descriptions in particular it is right on its own merits: a calibre
library has usually been curated, and the blurb a reader put on the
server is the blurb they want to see, while the OPF's is whatever the
retailer shipped.

Collections merge and deduplicate: contributors, subjects, languages,
identifiers. Coalescing them would lose data the screen exists to show —
a catalog author would hide the file's translator and illustrator, a
catalog ISBN would hide the EPUB's UUID, and a subject list from either
side would silently replace the other's. Order is catalog first, then
file, and each has its own key, because one key does not fit them:

- publishers and imprints on the case-folded, whitespace-collapsed name.
  Readium gives both as contributor lists, not as one string, and a book
  with two publishers or a named imprint is exactly the book whose
  details someone opened this screen to read;
- contributors on `(role, name)`, case-folded and whitespace-collapsed.
  One person in two roles is two entries, and showing Ursula Le Guin as
  both author and translator of her own book is the correct answer when
  that is what the metadata says;
- identifiers on `(scheme, value)`, the value trimmed and case-folded,
  ISBNs compared with hyphens and spaces removed;
- languages on the canonicalised BCP-47 tag, so `EN-gb` and `en-GB` are
  one;
- subjects on the case-folded, whitespace-collapsed name.

Bounds are applied after the merge (below), so a catalog cannot spend
the whole budget and push the file's contributors off the end.

This is a weaker rule than the series one, which also has a user layer
and resolves name and index independently. The similarity worth keeping
is the direction, not the machinery.

### Where each source is written

The file snapshot is written wherever a publication is already open —
`indexBook()`, `reindexBook()`, `indexDownloadedFile()` — in the same
write that stores the book, and marked with `file_metadata_checked`.

`reindexBook()` needs its order fixed to survive this. It writes the
book through `refreshIndexedFile()` and only then, on a `work_id` that
does not match, calls `contentReplaced()` — so under these rules it
would write the new file's snapshot and delete it a line later. The
replacement case becomes one transaction that clears first and writes
second: the old reading, sync, fingerprint, metadata, remote, catalog
and user-series state goes, then the newly parsed book fields and the
new file snapshot are written, and only then is the effective series
resolved from the file columns that now hold the new book's. A reindex
of the same work is the ordinary case and replaces the file snapshot
alone.

`backfillMetadata()` is for the books indexed before this existed. It is
`backfillSeries()` again, with its rules unchanged: a file that was read
is marked checked whatever the answer was, so a shelf of bare OPFs is
walked once; a file that could not be read is left unchecked and skipped
for the rest of the run, because an unmounted card is a reason to ask
again later. It selects only locally openable books, so catalog-only
rows are not unchecked work forever. `LibraryViewModel.init` launches it
where it launches the series pass.

The catalog snapshot is written by `RemoteCatalogRepository`, in the
same transaction as the book row it came with, and replaced whole. An
omitted field clears the previous value: for descriptive metadata that
is the point, because otherwise a description deleted on the server
never reaches the phone.

`BookDownloadRepository.removeDownload()` deletes the `file` row and
clears `file_metadata_checked`. It keeps the book and removes the file,
so leaving the snapshot would have a `REMOTE` book showing details read
off a copy that is no longer here.

It drops the book's `book_fingerprint` row in the same breath, which is
a hole this ADR inherits rather than opens. `BookFingerprintStore`
caches on `file_modified_at`, and a downloaded book has none —
`indexDownloadedFile()` never sets one — so a retained row's null
matches the null of whatever is downloaded next. A server that replaced
the file behind the same UUID would then be told the old hash, and the
reading state would be filed against the wrong bytes. Removing the file
is the moment the cache stops describing anything.

`BookRemoval.contentReplaced()` clears both metadata rows in its
existing transaction, and unlinks the whole remote and catalog
association with them: `remote_uuid`, `remote_book_id`, `download_href`,
`cover_url`, `remote_updated_at`, `remote_page_count`, `size_bytes`,
`catalog_series_name`, `catalog_series_index`, `catalog_folder_id`,
`catalog_series_source` and `catalog_missing_since` — after which the
effective series is resolved again from the file's own columns.

Clearing the catalog snapshot alone is treating the symptom, and
clearing only the `remote_*` fields is treating half of it.
`clearSeriesForReplacedWork()` today drops `series_id` and the user
layer, then sets `series_name = COALESCE(catalog_series_name,
file_series_name)` — so a replaced file stays filed under the old work's
catalog series even with `remote_uuid` gone. And for a row that was
adopted after an upload, which keeps its local `url` and gains only
`remote_uuid` and `download_href` per `BookDao.linkToRemote`, the new
file at that path would otherwise still answer to the old book's name on
the server, be refreshed from its catalog entry, and have the old work's
description written back over it on the next pass. A file nobody has
identified is a local book until something identifies it, and that has
to be true of every column that says otherwise, in one transaction.

### RemoteBook

`RemoteBook` gains subtitle, description, publishers, imprints,
published date, languages, subjects, identifiers and
contributors-with-roles. The rule
is not that two servers must supply a field — `calibreBookId`,
`pageCount`, `seriesId` and `sha256` are each one server's and each on
the contract already. It is that provider-neutral catalog data with a
common destination belongs on the contract, and everything here has one:
a `book_metadata` row and a band on the screen.

`docs/SERVER_CAPABILITIES.md` gains a row saying what each server
actually fills in, because the answers differ and a reader switching
servers will notice the screen getting thinner.

### Untrusted text

Every text field here is somebody else's, and the file is not the
trustworthy half. A `dc:description` is markup written by whoever made
the EPUB, an OPF is a document the app did not author, and a sideloaded
book has passed through no server at all. So the stripping and the
bounds below apply to both sources, on the way in, without exception.

The stripper is `HtmlCompat.fromHtml(…).toString()`, which is AndroidX
and therefore lives in `data/`, not in `domain/MetadataSanitizer.kt` —
the domain layer is pure Kotlin so it stays testable without Robolectric,
and dragging an Android dependency into it to save an import is the
wrong trade.

calibre-web's `<content>` is not a description with tags in it. It mixes
generated labels — `SERIES:`, ratings, tags — with the actual blurb, and
`OpdsParser.directText()` skips nested paragraphs deliberately for that
reason. Flattening the element wholesale would put catalog boilerplate
on screen as the book's description, so the description is taken from
the nested prose the existing parser already distinguishes. For generic
OPDS, `<summary>` and `<content>` are both accepted, with `type` of
`text`, `html` or `xhtml`; anything else is ignored rather than guessed
at.

Bounds, because untrusted input has no length in its contract and a row
nobody can read is still a row every query carries:

- description: the raw value truncated before conversion at 64 KiB, and
  the converted text again at 4,000 characters, cut on a code point
  boundary with an ellipsis;
- subtitle: 256 characters;
- publishers and imprints: at most 8 each, 256 characters per name;
- subjects: at most 32, each at most 128 characters;
- contributors: at most 64; name at most 256 characters, role at most 64;
- identifiers: at most 8; scheme at most 64 characters, value at most 256;
- languages: at most 8, each tag at most 64 characters;
- accessibility: the typed fields Readium parses — conformance profiles,
  access modes, sufficient access modes, features, hazards, the summary,
  the certification and the exemptions — and nothing else. Each list at
  most 32 entries of at most 64 characters; the summary and the
  certifier's report at most 1,000. A value the enumerations do not
  cover is dropped rather than shown, because an unrecognised access
  mode on this screen is a claim Liseur cannot stand behind, and the
  certification is shown as who certified it rather than as a badge;
- page count: 1 to 100,000, anything else discarded;
- series position: finite, non-negative, at most 10,000;
- `file_size_bytes`: a measured `Long` from `Cursor.getLong()` or
  `File.length()`, kept only within `0..2^53` and discarded otherwise.
  There is no parsing here — the value never was text.

Numbers are checked here because `size_bytes` is barely checked today.
`OpdsParser` reads the `length` attribute with `toLongOrNull()` and
accepts whatever comes back, negatives included; `KomgaBooks` and
`LiseurSyncCatalogClient` keep a size only when it is `> 0`, with no
ceiling on either. This ADR does not fix that column — that is a change
to what every catalog refresh writes, and it belongs to whoever makes it
— so the screen validates it on the way out instead, against the same
`0..2^53` window. Outside it, the catalog's size is not formatted and
not shown, which is the answer the reader would have got if the server
had said nothing. The new column has the rule the old one lacks, and the
old one is not made worse.

Truncating before the HTML conversion matters on its own: the conversion
is the expensive step, and a megabyte of nested tags is a parse nobody
should pay for.

Everything is stored and rendered as plain text. No `URLSpan`, no
`LinkAnnotation`, no autolinking, and nothing on this screen produces an
`ACTION_VIEW`. An ISBN is a string on a screen; making it a link means a
lookup against a service Liseur does not talk to, and network access is
limited to the configured book server and opt-in dictionary lookups.

### Dates, languages, and the rest of the presentation

A publication date is often a year, sometimes a year and month.
Converting it to epoch milliseconds invents a timezone and a precision
the book never claimed, so it is stored as ISO-8601 text that keeps
whatever precision arrived — a bare `1962` is not `1962-01-01T00:00:00Z`
— and rendered at the precision it has.

For the catalog sources that is achievable, because their parsers hold
the server's own string and can normalise it without widening it. For
the file source it is not: Readium 3.3.0 hands back
`Publication.metadata.published` as a `kotlin.time.Instant`, so an OPF
that said `1962` has already become a full instant before
`LocalLibraryRepository` sees it. Re-parsing `dc:date` out of the OPF to
recover the year would mean reading the package document a second way,
past the API that exists to read it, and that is a larger cost than the
month it buys. So the file source stores the date derived from that
instant, and the column's contract is the precision of what arrived —
not a promise that the book only claimed that much.

Languages are plural in EPUB metadata and all valid tags are kept.
Display goes through `Locale.forLanguageTag(tag).getDisplayName(locale)`
rather than `displayLanguage`, which throws away script and region and
would render `zh-Hant` and `fr-CA` as Chinese and French. A tag that
resolves to nothing is shown as written, because a reader on a details
screen would rather see the odd tag than a gap.

Every label, state description and unit comes from `strings.xml`, and
dates, byte sizes and counts are formatted for the current locale.
Hashes and identifiers are selectable and rendered LTR with bidirectional
isolation, so a hash next to an RTL label reads in the right order.

E-Ink: no crossfades or animated section entry, and no distinction
carried by colour alone. Accessibility: sections are headings, each
label and value is one grouped node rather than two, the back action is
labelled, the cover is decorative where the title sits beside it, the
layout reflows at large font scales, and the stats link is a full-size
target.

`otherMetadata` is not stored. Readium hands back every remaining OPF
meta tag as a bag of arbitrary keys, and showing it fills the screen
with `calibre:timestamp` and `dcterms:modified`. That is a debug view.
The fields here are chosen; the bag is not.

"Where it came from" is what the row itself says, not what is connected
now. For a local book it is `books.source`, the watched folder tree.
That column holds a tree URI and nothing stores its display name, so the
name is asked of the document provider when the screen opens and falls
back to a string resource — not to the raw URI, which is a
percent-encoded document id no reader should be shown — when the
provider is gone or the permission was revoked. For a book that arrived
from a catalog it is the provider kind, recovered from `books.url`
through `ServerKind.urlPrefix`, which is already how the app tells a
catalog book from a local one. That is the only provenance this schema
persists per book. Not the server's name or address: those live on
`remote_server`, one row for one connection, so a book fetched from a
server the reader has since left would be labelled with whoever is
connected today. A kind is a fact about the row; a name would be a
guess.

### The screen's state

The route carries `book.url` and nothing else. The screen observes the
`Book` and its metadata rows through a view model, the way every other
screen here does, rather than being handed a `Book` that was current
when the sheet opened — a catalog refresh during a long read of the
description would otherwise show yesterday's row. Progress, time left
and annotation counts come from that same model, not from DAO calls in
a composable. If the book disappears while the screen is open, because
a catalog prune or a deletion took it, the screen goes back rather than
sitting on a row that no longer exists.

### Migration

Room goes to 43 → 44: a hand-written `MIGRATION_43_44` creating the
table and adding `file_metadata_checked`, registered in `MIGRATIONS`,
with `app/schemas/…/44.json` exported and `MigrationTest` replaying it.
There is nothing to backfill in SQL; the pass fills the rows from the
files.

Tests that have to exist: the migration replay; the cascade; the scalar
and collection precedence rules, including two contributors who share a
name and differ in role; a catalog refresh that drops a field; an
unreadable file retried rather than marked; download removal, including
that the fingerprint goes with it; content replacement, including the
remote unlink, that the replaced file is no longer filed under the old
work's catalog series, and that a reindex which replaces the work ends
with the new file's snapshot rather than none; and the sanitiser at its
boundaries — an oversized description, a subject list past its cap, a
negative size, a page count of zero, an unparseable date, an `xhtml`
summary, and a calibre
`<content>` block whose `SERIES:` label must not become the
description.

## Consequences

The shelf gets another catch-up pass on upgrade, walking every local
book that predates the feature. It is the series pass again with the
same cost and the same pacing: a big library takes a while, and the
shelf is drawn and usable throughout.

A catalog-only book shows what the server said and no more, and that
looks thin beside a downloaded one. It is thin — nothing has read the
file, because there is no file.

This schema now has a foreign key. It is one table and the right table,
but it is a precedent, and the next one should have to argue for itself
the same way.

Two things outside this feature get fixed on the way past, because this
work touches both paths and leaving them would be knowingly building on
them: a fingerprint retained across a download removal, and a replaced
file that keeps the old book's server identity. Both are narrow, and
both belong in their own commits.

The details screen becomes the obvious home for anything per-book that
arrives later, which is what keeps the actions sheet from growing into
one. That is ADR-0001's problem turning up in a second place, and this
is the answer to it there too.

Searching descriptions and subjects is not in scope, but a table holding
them is what would make it possible without opening every file again.

*Where:* `ui/library/BookDetailsScreen.kt` and its view model (new),
`ui/library/LibraryScreen.kt`, `ui/library/LibraryViewModel.kt`,
`MainActivity.kt`, `AppContainer.kt`,
`data/db/BookMetadata.kt` (new), `data/db/Book.kt`,
`data/db/LiseurDatabase.kt`, `data/library/LocalLibraryRepository.kt`,
`data/library/BookRemoval.kt`, `data/calibre/BookDownloadRepository.kt`,
`data/remote/RemoteBook.kt`, `data/remote/RemoteCatalogRepository.kt`,
`data/opds/OpdsParser.kt`, `data/komga/KomgaBooks.kt`,
`data/liseursync/LiseurSyncCatalogClient.kt`, `data/db/WorkIdentity.kt`,
`res/values/strings.xml`,
`app/schemas/com.chmouel.liseur.data.db.LiseurDatabase/44.json`,
`app/src/test/kotlin/com/chmouel/liseur/data/db/MigrationTest.kt`,
`docs/SERVER_CAPABILITIES.md`.
