# Server capabilities

What each supported server exposes, what Liseur implements against it
today, and why the gaps that remain are or are not fixable from the app
side.

## Summary

| Capability     | Komga              | calibre-web       | BookOrbit          | liseur-sync        | Custom (OPDS)            |
|----------------|--------------------|-------------------|--------------------|--------------------|--------------------------|
| Catalog browse | Implemented        | Implemented       | Implemented        | Implemented        | Implemented              |
| Search         | Implemented        | Implemented       | Client only        | Implemented        | Local only               |
| File download  | Implemented        | Implemented       | Implemented        | Implemented        | Implemented              |
| Position sync  | Implemented (full) | Implemented (%)   | Implemented (CFI)  | Implemented (full) | Implemented (%, kosync) |
| Book upload    | Not possible       | Not feasible      | Not implemented    | Implemented        | Not possible             |
| Book delete    | Not possible       | Implemented       | Not implemented    | Implemented        | Not possible             |
| Series claims  | N/A                | N/A               | N/A                | Implemented        | N/A                      |
| Settings sync  | Not possible       | Not possible      | Not implemented    | Implemented        | Not possible             |

For how each kind's position sync measures up against Kindle
Whispersync, behaviour by behaviour, see
[`adr/0023-position-sync-versus-whispersync.md`](adr/0023-position-sync-versus-whispersync.md).

## Komga

**Auth:** API key (`X-API-Key` header).

| Feature | Server API | Liseur | Notes |
|---------|-----------|--------|-------|
| Catalog browse | `POST /api/v1/books/list` (paginated, server-side filter to EPUBs in READY state) | `KomgaCatalogClient` | |
| Search | Same endpoint with query | `KomgaCatalogClient.search()` | Full-text |
| File download | `GET /api/v1/books/{id}/file` | `KomgaFileSource` | Requires `FILE_DOWNLOAD` role, checked at setup |
| Position sync | `GET/PUT /api/v1/books/{id}/progression` (R2Progression), `PATCH/DELETE .../read-progress`, `GET .../positions` | `KomgaSyncRepository` via `KomgaProgressionClient` | Full Readium locators with position snapping. Bidirectional, uses shared `reconcileReadingState` |
| Series metadata | `GET /api/v1/series/{id}` | `KomgaSeriesClient` | On-demand |
| Book upload | No remote upload API. `POST /api/v1/books/import` accepts JSON with server-side filesystem paths (transient books scanned from a local folder). The only multipart endpoints are for poster/thumbnail images | Not implemented | Not possible from a remote client. Komga's design is filesystem-first: books are placed in library folders and scanned |
| Book delete | No client-facing delete API | Not implemented | Intentionally hidden. Deleting a file is an administrator's job |

**Capability detection:** `KomgaSetupClient` probes `GET /api/v2/users/me`
and reads roles. Only `FILE_DOWNLOAD` is currently checked; `canUpload`
is never set to true for Komga.

## BookOrbit

**Auth:** account password exchanged at setup for a renewable session
(`Authorization: Bearer <access token>`). The password is not kept.

| Feature | Server API | Liseur | Notes |
|---------|-----------|--------|-------|
| Catalog browse | `POST /api/v1/books/query` (zero-based pages, up to 200 books, scoped to the account's libraries) | `BookOrbitCatalogClient` | Asks for books holding an EPUB and skips anything with no EPUB file |
| Search | Same endpoint with `q` | `BookOrbitCatalogClient.search()` | Client is implemented; the library UI still searches its local shelf only |
| File download | `GET /api/v1/books/files/{fileId}/download` | `BookOrbitFileSource` | Requires `library_download`, checked at setup. No range support, so an interrupted transfer restarts |
| Position and status sync | `GET`/`POST /api/v1/books/files/{fileId}/progress`, `GET /api/v1/books/{id}`, `PATCH /api/v1/books/{id}/status` | Exact EPUB position sync and finished/unread status sync enabled | Position sync retains exact CFIs and selected-file identity. Status uses a separate agreement and one-shot status-only PATCH. `syncAbility = EXACT` describes positions |
| Book upload | `POST /api/v1/libraries/{id}/upload`, chunked `/api/v1/uploads/*` | Not implemented | Requires `library_upload`; a book's bytes would be adopted through the file binding |
| Book delete | `DELETE /api/v1/books`, `DELETE /api/v1/books/files/{fileId}` | Not implemented | Requires `library_delete_books` |
| Collections | `/api/v1/collections/*` | Not implemented | |
| Statistics | `/api/v1/user-statistics/*` | Not implemented | |
| Settings | `/api/v1/reader/*`, `/api/v1/user-preferences/*` | Not implemented | |

**Capability detection:** `BookOrbitSetupClient` reads the login response, or
`GET /api/v1/auth/me` after renewing an existing session, and maps
`permissions` (or `*`) onto the download, upload, delete and metadata-editing
capabilities. `GET /api/v1/app-info` reports a version but is not required to
connect.

**Identity:** BookOrbit numbers books per installation, so a book's
`books.url` and `remote_uuid` carry a digest of the server address and the
account id in front of the book id (`BookOrbitScope`). The file a book is
read as is remembered in `book_orbit_binding`, so a rescan that promotes a
different EPUB does not move an existing reader's place.

## calibre-web

**Auth:** Basic (username/password). Kobo sync uses a separate token
provisioned during setup.

| Feature | Server API | Liseur | Notes |
|---------|-----------|--------|-------|
| Catalog browse | OPDS feed at `/opds/books/letter/00` with `next` pagination | `CalibreCatalogClient` | |
| Search | `/opds/search?query=` | `CalibreCatalogClient.search()` | |
| File download | OPDS acquisition links, fallback `/opds/download/{id}/epub/` | `CalibreFileSource` | Uses stored `downloadHref` from the OPDS feed, with a derived fallback |
| Position sync | Kobo protocol: `/v1/library/sync`, `/v1/library/{uuid}/state` | `KoboSyncRepository` via `KoboClient` | Percentages only, no full locators. Bidirectional, shares `reconcileReadingState`. Kobo token provisioned at setup via a web session |
| Book upload | Web UI form at `/upload`; multipart POST behind a session cookie and CSRF token, admin permission required | Not implemented | Not a real API: uploading would mean logging in like a browser (`CalibreWebSession`), fetching a CSRF token, and posting to a web UI route. The pattern exists for delete, but it is fragile and considered out of scope for upload |
| Book delete | Web UI form: `POST /delete/{bookId}` with session cookie and CSRF | `CalibreDeleteClient` via `CalibreBookDeleter` | Works through `CalibreWebSession.logIn()`; checks for the login-page-as-200 pattern that a refused session returns |

**Capability detection:** `CalibreSetupClient` probes `/opds` for the
feed, checks download rights by probing a book's download link, and
provisions a Kobo sync token via a web session. It does not detect
upload or admin capability.

## liseur-sync

**Auth:** Bearer token (device token minted during setup from a
password). Scopes control what the token can do.

| Feature | Server API | Liseur | Notes |
|---------|-----------|--------|-------|
| Catalog browse | REST API (paginated) | `LiseurSyncCatalogClient` | |
| Search | REST API | `LiseurSyncCatalogClient.search()` | |
| File download | `GET /v1/books/{id}/download` | `LiseurSyncFileSource` | |
| Position sync | Append-only op log, cursor-based | `LiseurSyncPositionSync` | Full Readium locators. Cursor (`sync_cursor_seq`) advances in the same transaction as the write it covers. Retry-safe: op and session ids are derived, so a retry lands as a duplicate rather than a second write |
| Book upload | `POST /v1/folders/{folderId}/books` (multipart, `application/epub+zip`) | `LiseurSyncUploadClient` + `BookUploadWorker` | Requires the `library-upload` scope. Folder selection via `GET /v1/folders` (filters on `accepts_uploads`). Adoption links the local book to the remote copy without rewriting its URL |
| Book delete | REST API, per-folder permission | `LiseurSyncDeleteClient` | Only for books in a folder marked as accepting uploads |
| Series claims | REST API (personal layer) | `LiseurSyncSeriesClaimClient` | |
| Settings sync | `GET`/`PUT /v1/me/settings` | `LiseurSyncSettings` | Account-wide key/value pairs, last writer wins on a client-supplied change time. Only the keys in `syncableSettings()` travel; anything about a particular device stays put. Not applied while a book is open |

**Capability detection:** `LiseurSyncServerSetup.introspect()` reads
`GET /v1/token` for scopes. `canUpload` is true when `library-upload` or
admin is among them; `canDelete` is scoped the same way.

## Custom (OPDS, KOReader sync, or one of the two)

Custom is a standard-based connection for servers with no dedicated client:
Calibre's own content server, COPS, Kavita, or a static feed on a NAS. See
[`adr/0015-custom-server.md`](adr/0015-custom-server.md).

Grimmory users should choose Custom, enter `/api/v1/opds` with their OPDS user
credentials, and optionally pair `/api/koreader` with their KOReader sync
credentials. Grimmory's OPDS server is disabled by default in some versions;
enable it first (`OPDS_SERVER_ENABLED=true`) before reconnecting or migrating
the account.

A Custom connection holds two addresses and either may be left blank:
an OPDS catalog root, and a KOReader sync (kosync) server. Filling in
both is the ordinary case; a catalog with no sync and a sync server with
no catalog are both real.

**Auth:** Basic or none at all. Plain OPDS is often open, and a blank
username and password are stored as an anonymous credential rather than
as a missing one. The kosync half keeps its own login and stores a
derived key, never a password.

### Connecting to one

In Liseur, **Settings -> Book server -> Custom**:

1. **OPDS catalog address:** the server *root*, not a books path.
   Liseur walks the navigation feeds from there to find the shelves.
2. **Username and password**, if the catalog asks for them. Both blank
   means an open catalog.
3. **KOReader sync address**, with its own username and password, if you
   have one.

Both addresses are checked before anything is saved, and the failing one
is reported on its own field. Whatever is in the form when the connection
succeeds is what you get: an empty sync address removes a pairing left by
a previous server.

| Feature | Server API | Liseur | Notes |
|---------|-----------|--------|-------|
| Catalog browse | Any Atom/OPDS 1.x feed | `OpdsCatalogClient` | Starts at the root and walks navigation entries breadth-first, following each `next` chain. Bounded by a visited set, a depth limit of 4 and a budget of 400 requests; a walk stopped by a bound reports `complete = false` and prunes nothing |
| Search | OpenSearch description document, advertised per server | Returns nothing | The description is not at a path that can be guessed and every server fills it in differently. Liseur's library search is local and covers the whole catalog, which is walked into the database anyway |
| File download | The entry's acquisition link | `OpdsFileSource` | Only DRM-free EPUB (`application/epub+zip`, `application/x-kobo-epub+zip`, or a link that states no type). `buy`, `borrow`, `sample` and `subscribe` are not downloads. An entry with no usable format is listed without a download rather than dropped |
| Position sync | None. OPDS carries no reading state | `KosyncPositionSync`, paired on the same screen | Percentages only, matched by file hash. With no OPDS address, the pairing covers the books already on the device instead of a catalog's |
| Series metadata | calibre-style `SERIES: Name [n]` in the entry's content block, where a server writes it | Parsed opportunistically | No standard field exists |
| Book upload / delete | Not part of OPDS 1.x | Not implemented | |

**Capability detection:** `OpdsSetupClient` fetches the root and requires
it to parse as an Atom `<feed>`. A sign-in page answering 200 with HTML is
well-formed XML and would otherwise be accepted as a catalog. What is stored
is the URL that *answered*, so a root that redirects is not redirected again
on every refresh. `canDownload` is true even for a root that lists only
shelves: the books are a walk away.

**The catalog's password goes to the catalog's origin and nowhere else.**
A feed is written by someone else and may point anywhere. OPDS is federated,
so pointing at another host is a feature. Links outside the configured
origin are still followed, unsigned. Redirects are walked by hand so the
decision is made before each hop rather than after, and an https catalog is
never followed down to http.

**A book carries the catalog that issued it.** Entry ids are opaque and
unique only within their own feed, so `books.url` is
`custom:{fingerprint}:{entry-id}`, the fingerprint derived from the
catalog's origin and path. Two Custom servers both issuing `1` stay
separate.

## Upload infrastructure

The upload plumbing in `data/remote/` is generic and server-agnostic.
Adding a new server needs only:

1. A `BookUploader` implementation (`targets()` + `upload()`).
2. Capability detection in that server's `ServerSetup` (set `canUpload = true`).
3. One entry in `AppContainer.uploaders`.

Already in place, independent of any one server:

- `BookUploader` interface (`RemoteSources.kt`).
- `BookUploadWorker`, a WorkManager `CoroutineWorker` that resolves the
  local file, picks a folder, calls the uploader, and adopts the result.
- `BookUploadRepository`: enqueue/cancel/inFlight via WorkManager.
- `ServerCapabilities.canUpload`, detected at setup and stored on
  `RemoteServer.can_upload`.
- `UploadPolicy` (ASK / ALWAYS / NEVER).
- UI: manual upload from the library sheet, a batch offer dialog under
  ASK, automatic enqueue under ALWAYS, and a trigger from the reader
  when a book is opened from outside the library.
- `canUploadTo()`: server exists, `canUpload` is true, and the router
  has an uploader registered for that server kind.

**Known issue:** `BookUploadWorker.adopt()` hardcodes
`downloadHref = "/v1/books/$remoteBookId/download"`, which is
liseur-sync's path shape. If a second server ever gets upload support,
this needs to be parameterised, for example with
`ServerUploadResult.Uploaded` carrying its own `downloadHref`, since Komga
and calibre-web each use a different path.

## Delete infrastructure

Same pattern as upload: a `BookDeleter` interface, router dispatch, and
per-server capability detection.

| Server | Entry in `deleters` | How |
|--------|----------------------|-----|
| Komga | No | Admin-only, intentionally hidden |
| calibre-web | Yes (`CalibreBookDeleter`) | Web UI form POST via `CalibreWebSession` |
| liseur-sync | Yes (`LiseurSyncDeleteClient`) | REST API, per-folder permission |

## Position sync quality

| Server | Precision | Format |
|--------|-----------|--------|
| Komga | Exact position in chapter | Full Readium locator, with position snapping |
| calibre-web | Page-level at best | Percentage (`totalProgression`) via the Kobo protocol |
| liseur-sync | Exact position in chapter, in the same file | Full Readium locator via the op log |
| Any kosync server | Page-level at best | Same partner: it speaks the generic protocol, so a stock kosync server pairs the same way |

Every sync goes through the shared `reconcileReadingState` merge
logic in `domain/ReadingStateMerge.kt`.

A locator is only followed into the file it was written against. Ops
carry the edition's SHA-256 (`edition_sha`), and a position from an
edition this device cannot confirm it holds is used as a percentage and
nothing more — the same paragraph is not at the same offset in another
translation or another conversion of the book.

Within a matching edition the restoration descends three rungs, in
`reader/progress/ResourceAnchor.kt`:

1. **The passage.** A locator this app marked as exact
   (`liseurAnchor`): a block selector and a bounded quote, verified to
   be on screen after the chapter has laid out.
2. **The chapter.** The resource and the fraction *within* it. Kept
   whenever the exact rung is absent or will not verify, including
   positions from clients that do not provide a usable exact anchor.
3. **The percentage.** The whole-book fraction, and the only rung that
   can land in the wrong chapter: two engines compute that fraction
   from different things, and on a book with a few large chapters they
   disagree by pages.

The kosync partner is not a kind of server: it is paired *alongside* a
connected one, covers that server's downloaded books (matched by
KOReader's partial MD5 of the file), and has its own lifecycle.

It is offered for Custom, whose OPDS catalog carries no position of its own.
calibre-web, Komga and liseur-sync sync positions natively, and a second
source for the same book is a conflict the reader can neither see nor resolve.
`ServerKind.hostsKosyncPeer` is the single answer to that question, and where
a future kind states its own. See [`adr/0014-kosync.md`](adr/0014-kosync.md).
