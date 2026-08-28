# Server capabilities

What each supported server exposes, what Liseur implements against it
today, and why the gaps that remain are or are not fixable from the app
side.

## Summary

| Capability     | Komga               | calibre-web        | liseur-sync         | Grimmory            |
|----------------|----------------------|---------------------|----------------------|----------------------|
| Catalog browse | Implemented          | Implemented         | Implemented          | Implemented          |
| Search         | Implemented          | Implemented         | Implemented          | Local only           |
| File download  | Implemented          | Implemented         | Implemented          | Implemented          |
| Position sync  | Implemented (full)   | Implemented (%)     | Implemented (full)   | Implemented (%, kosync) |
| Book upload    | Not possible          | Not feasible        | Implemented          | Not implemented      |
| Book delete    | Not possible          | Implemented         | Implemented          | Not implemented      |
| Series claims  | N/A                   | N/A                 | Implemented          | N/A                  |

## Komga

**Auth:** API key (`X-API-Key` header).

| Feature | Server API | Liseur | Notes |
|---------|-----------|--------|-------|
| Catalog browse | `POST /api/v1/books/list` (paginated, server-side filter to EPUBs in READY state) | `KomgaCatalogClient` | |
| Search | Same endpoint with query | `KomgaCatalogClient.search()` | Full-text |
| File download | `GET /api/v1/books/{id}/file` | `KomgaFileSource` | Requires `FILE_DOWNLOAD` role, checked at setup |
| Position sync | `GET/PUT /api/v1/books/{id}/progression` (R2Progression), `PATCH/DELETE .../read-progress`, `GET .../positions` | `KomgaSyncRepository` via `KomgaProgressionClient` | Full Readium locators with position snapping. Bidirectional, uses shared `reconcileReadingState` |
| Series metadata | `GET /api/v1/series/{id}` | `KomgaSeriesClient` | On-demand |
| Book upload | No remote upload API. `POST /api/v1/books/import` accepts JSON with server-side filesystem paths (transient books scanned from a local folder). The only multipart endpoints are for poster/thumbnail images | Not implemented | Not possible from a remote client — Komga's design is filesystem-first, books are placed in library folders and scanned |
| Book delete | No client-facing delete API | Not implemented | Intentionally hidden — deleting a file is an administrator's job |

**Capability detection:** `KomgaSetupClient` probes `GET /api/v2/users/me`
and reads roles. Only `FILE_DOWNLOAD` is currently checked; `canUpload`
is never set to true for Komga.

## calibre-web

**Auth:** Basic (username/password). Kobo sync uses a separate token
provisioned during setup.

| Feature | Server API | Liseur | Notes |
|---------|-----------|--------|-------|
| Catalog browse | OPDS feed at `/opds/books/letter/00` with `next` pagination | `CalibreCatalogClient` | |
| Search | `/opds/search?query=` | `CalibreCatalogClient.search()` | |
| File download | OPDS acquisition links, fallback `/opds/download/{id}/epub/` | `CalibreFileSource` | Uses stored `downloadHref` from the OPDS feed, with a derived fallback |
| Position sync | Kobo protocol: `/v1/library/sync`, `/v1/library/{uuid}/state` | `KoboSyncRepository` via `KoboClient` | Percentages only, no full locators. Bidirectional, shares `reconcileReadingState`. Kobo token provisioned at setup via a web session |
| Book upload | Web UI form at `/upload` — multipart POST behind a session cookie and CSRF token, admin permission required | Not implemented | Not a real API: uploading would mean logging in like a browser (`CalibreWebSession`), fetching a CSRF token, and posting to a web UI route. The pattern exists for delete, but it is fragile and considered out of scope for upload |
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

**Capability detection:** `LiseurSyncServerSetup.introspect()` reads
`GET /v1/token` for scopes. `canUpload` is true when `library-upload` or
admin is among them; `canDelete` is scoped the same way.

## Grimmory

Reached through the Komga-compatibility API Grimmory ships, mounted at
`/komga/api` rather than `/api`. It is a subset: enough to browse and
download, and no more. Verified against **v3.3.3**; see
[`adr/0012-grimmory-komga-shim.md`](adr/0012-grimmory-komga-shim.md) for
why it is its own `ServerKind` rather than a flavour of Komga.

**Auth:** Basic, with a dedicated OPDS user rather than the account you sign
into Grimmory with.

### Connecting to one

Two things have to be set up in Grimmory first, both as an administrator:

1. **Settings → OPDS**: create an OPDS user and share the libraries you want
   on your phone with it. This is the login Liseur uses; an ordinary
   Grimmory account will not work.
2. **Settings**: switch the Komga API on. It is off by default.

Then in Liseur, **Settings → Book server → Grimmory**: the address of your
Grimmory server (the same one you open in a browser, with the `/komga` path
added for you) and the OPDS user's name and password.

A refused sign-in is one of those two things, the wrong kind of user or the
API still switched off. Grimmory answers 403 to both, so Liseur cannot say
which.

| Feature | Server API | Liseur | Notes |
|---------|-----------|--------|-------|
| Catalog browse | `GET /komga/api/v1/books?page=&size=` (paginated) | `GrimmoryCatalogClient` | Komga's `POST /v1/books/list` is explicitly not implemented and answers 501, so the plain paged route is used instead. Filtered client-side on `media.mediaType`, since Grimmory reports MOBI and AZW3 under `mediaProfile: "EPUB"` |
| Search | None on the compatibility API | Returns nothing | Liseur's library search is local and covers the whole catalog, which is walked into the database anyway. Grimmory's OPDS `catalog?q=` is the way in if remote search is ever wired to the UI |
| File download | `GET /komga/api/v1/books/{id}/file` | `GrimmoryFileSource` | Serves the book's primary file. Open to any authenticated OPDS user the library is shared with |
| Position sync | None on the compatibility API. `/progression`, `/read-progress` and `/positions` all fall through to a 404, and `readProgress` is never populated on a book | `KosyncPositionSync`, paired separately | Grimmory speaks KOReader's kosync at `/api/koreader`, behind a third credential set (a KOReader user created in its device settings) and matched by file hash. Liseur pairs it from the KOReader sync section on the server screen; percentages only. See [`adr/0014-kosync.md`](adr/0014-kosync.md) |
| Series metadata | Ids are synthetic (`{libraryId}-{slug}`) and change when a series is renamed | Not implemented | `SeriesExtrasRepository` is gated on Komga and never fires here. The id still arrives on the book and groups the shelf, so a rename regroups rather than corrupts |
| Book upload / delete | Not exposed by the compatibility API | Not implemented | |

**Capability detection:** `GrimmorySetupClient` probes
`GET /komga/api/v2/users/me` and requires a `roles` array, as the Komga
probe does. `canDownload` is unconditionally true: Grimmory hardcodes
`roles: ["USER"]` and has no `FILE_DOWNLOAD` to report, so gating on it as
the Komga client does would refuse every download.

**A walk that is not understood does not prune.** `dropVanished()` deletes
every catalogued book a completed walk did not see, taking its reading
progress with it. So `GrimmoryCatalogClient` reports `complete = false` for
anything it cannot account for — a page that does not describe itself, one
shorter than the count it declared, a catalog whose size changed between
pages, the same book counted twice, a `content` field that is not an array,
an unparseable id, a media type this build has never heard of, or a whole
catalog that filtered down to nothing — rather than letting a changed
response read as an emptied library.

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
- `BookUploadRepository` — enqueue/cancel/inFlight via WorkManager.
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
this needs to be parameterised — e.g. `ServerUploadResult.Uploaded`
carrying its own `downloadHref`, since Komga and calibre-web each use a
different path.

## Delete infrastructure

Same pattern as upload: a `BookDeleter` interface, router dispatch, and
per-server capability detection.

| Server | Entry in `deleters` | How |
|--------|----------------------|-----|
| Komga | No | Admin-only, intentionally hidden |
| calibre-web | Yes (`CalibreBookDeleter`) | Web UI form POST via `CalibreWebSession` |
| liseur-sync | Yes (`LiseurSyncDeleteClient`) | REST API, per-folder permission |
| Grimmory | No | Not exposed by the compatibility API |

## Position sync quality

| Server | Precision | Format |
|--------|-----------|--------|
| Komga | Exact position in chapter | Full Readium locator, with position snapping |
| calibre-web | Page-level at best | Percentage (`totalProgression`) via the Kobo protocol |
| liseur-sync | Exact position in chapter | Full Readium locator via the op log |
| Grimmory | Page-level at best | Percentage via KOReader's kosync, paired alongside the catalog |
| Any kosync server | Page-level at best | Same partner: it speaks the generic protocol, so a stock kosync server pairs the same way |

Every sync goes through the shared `reconcileReadingState` merge
logic in `domain/ReadingStateMerge.kt`.

The kosync partner is not a kind of server: it is paired *alongside* a
connected one, covers that server's downloaded books (matched by
KOReader's partial MD5 of the file), and has its own lifecycle.

It is offered only where the connected server carries no position of its
own, which today means Grimmory. calibre-web, Komga and liseur-sync sync
positions natively, and a second source for the same book is a conflict
the reader can neither see nor resolve. `ServerKind.hostsKosyncPeer` is
the single answer to that question, and where a future kind states its
own. See [`adr/0014-kosync.md`](adr/0014-kosync.md).
