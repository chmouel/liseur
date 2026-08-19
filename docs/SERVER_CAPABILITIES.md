# Server capabilities

What each supported server exposes, what Liseur implements against it
today, and why the gaps that remain are or are not fixable from the app
side.

## Summary

| Capability     | Komga               | calibre-web        | liseur-sync         |
|----------------|----------------------|---------------------|----------------------|
| Catalog browse | Implemented          | Implemented         | Implemented          |
| Search         | Implemented          | Implemented         | Implemented          |
| File download  | Implemented          | Implemented         | Implemented          |
| Position sync  | Implemented (full)   | Implemented (%)     | Implemented (full)   |
| Book upload    | Not possible          | Not feasible        | Implemented          |
| Book delete    | Not possible          | Implemented         | Implemented          |
| Series claims  | N/A                   | N/A                 | Implemented          |

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

## Position sync quality

| Server | Precision | Format |
|--------|-----------|--------|
| Komga | Exact position in chapter | Full Readium locator, with position snapping |
| calibre-web | Page-level at best | Percentage (`totalProgression`) via the Kobo protocol |
| liseur-sync | Exact position in chapter | Full Readium locator via the op log |

All three go through the shared `reconcileReadingState` merge logic in
`domain/ReadingStateMerge.kt`.
