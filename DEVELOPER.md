## Building

This project uses the Gradle wrapper, so you don't need Gradle installed
separately — just a JDK 17+ and the Android SDK (command-line tools are
enough; `compileSdk`/`targetSdk` 37 requires a reasonably recent SDK
Manager package list).

```bash
./gradlew assembleDebug    # unsigned debug APK, installable as-is
./gradlew assembleRelease  # minified release APK
```

Output APKs land in `app/build/outputs/apk/{debug,release}/`.

The debug build is signed with the standard Android debug key, so
`app-debug.apk` can be installed directly with `adb install` or by
sideloading. The release build is unsigned by default so the project
builds out of the box on any machine/CI; see below if you want a signed
release build.

### Signing a release build (optional)

If you want a properly signed release APK (e.g. to install updates over
an existing install without uninstalling first), generate your own
keystore and point a local `keystore.properties` file at it. This file is
gitignored — it's per-developer, not checked in.

```bash
keytool -genkeypair -v -keystore /path/to/your.jks \
  -alias liseur -keyalg RSA -keysize 2048 -validity 10000
```

Create `keystore.properties` in the project root:

```properties
storeFile=/path/to/your.jks
storePassword=yourStorePassword
keyAlias=liseur
keyPassword=yourKeyPassword
```

Then `./gradlew assembleRelease` will automatically pick it up and sign
the release build.

## Testing

```bash
./gradlew testDebugUnitTest   # JVM unit tests
./gradlew lintDebug           # Android Lint (0 errors required)
```

There is no instrumented/emulator test suite. Reader interactions
(gestures, immersive mode, process-death restore, rotation) are verified
manually on a booted AVD.

## Releasing

Use `hack/release` from a clean, up-to-date `main` branch:

```bash
hack/release 0.2.0 "Add the reading screen."
```

The script increments `versionCode`, writes the Fastlane changelog, runs the
tests, lint, and release build, then commits, tags, pushes, and publishes the
GitHub release.

## Architecture

See `AGENTS.md` for the layered package layout and project conventions.
Key decisions:

- **Readium Kotlin Toolkit** (`readium-shared`, `readium-streamer`,
  `readium-navigator`, `readium-opds`) does EPUB parsing, rendering, and
  OPDS feed parsing. `readium-lcp` is deliberately excluded (proprietary
  liblcp — incompatible with F-Droid).
- **calibre-web** integration is two protocols: OPDS for browse/search/
  download, and the Kobo sync protocol (`/kobo/<token>/v1/...`) for
  reading-position sync, exchanging percentage progression like KOReader
  does.
- **Single `:app` module**, manual DI composition root, `ViewModel` +
  `StateFlow`, Room + DataStore for persistence.

## calibre-web protocols

Verified against a real calibre-web install (behind Caddy + Cloudflare) and
against the calibre-web source (`cps/opds.py`, `cps/kobo.py`,
`cps/kobo_auth.py`, `cps/services/SyncToken.py`).

### OPDS catalog

- OPDS 1.2, Atom XML, served under `/opds`
  (`application/atom+xml;profile=opds-catalog`).
- Auth is HTTP Basic on every route; a 401 carries
  `WWW-Authenticate: Basic realm="Authentication Required"`. Anonymous
  browsing is a server-side option.
- Navigation feeds: `/opds/new`, `/opds/discover`, `/opds/rated`,
  `/opds/hot`, `/opds/author`, `/opds/publisher`, `/opds/category`,
  `/opds/series`, `/opds/ratings`, `/opds/formats`, `/opds/language`,
  `/opds/shelfindex`, `/opds/readbooks`, `/opds/unreadbooks`.
- `/opds/books` is a letter index; the full list is
  `/opds/books/letter/00`.
- Paging is `?offset=N`, 60 entries per page, with `rel="next"` and
  `rel="prev"` links. **Search results are not paged.**
- Search is `/opds/search/{terms}` or `/opds/search?query={terms}`;
  the OpenSearch description is at `/opds/osd`.
- An acquisition entry carries the calibre UUID as
  `<id>urn:uuid:...</id>`, covers as `/opds/cover/<int id>` (integer, not
  UUID) and a download link:

  ```xml
  <link rel="http://opds-spec.org/acquisition"
        href="/opds/download/74/epub/" length="156172" title="EPUB"
        mtime="2026-07-26T10:26:49+00:00" type="application/epub+zip"/>
  ```

- Downloads honour `Range` (206) and send `ETag`, `Last-Modified` and a
  UTF-8 `Content-Disposition` filename, so resumable downloads work.
- **A user without the "Allow Downloads" permission gets 401 on
  `/opds/download/...` (403 on the web UI route) while browsing keeps
  working.** The app must recognise that case and tell the user to enable
  that permission for their account in calibre-web, rather than showing a
  generic failure.

### Kobo sync

Off by default; the admin enables it in Feature Configuration, then each
user creates a token from their profile page ("Kobo Sync Token"), which
yields a base URL of the shape `https://host/kobo/<32 hex chars>`. The
token is the only credential — it never expires and there is no user
agent or device check.

- `GET /v1/library/sync` returns a JSON array of entities:
  `NewEntitlement`, `ChangedEntitlement`, `ChangedReadingState`,
  `DeletedTag`, ... Each entitlement bundles `BookEntitlement`,
  `BookMetadata` and `ReadingState`.
- Paging is via the `x-kobo-synctoken` header (base64 JSON of per-table
  timestamps); the response repeats it, and sends `x-kobo-sync: continue`
  when more pages remain. Sending the previous token returns only what
  changed since.
- `GET|PUT /v1/library/<uuid>/state` reads and writes the reading
  position. **The PUT handler indexes its keys directly, so
  `CurrentBookmark`, `Statistics` and `StatusInfo` must all be present**
  (any of them may be `null`); omitting one returns 400:

  ```json
  {"ReadingStates": [{
    "CurrentBookmark": {"ProgressPercent": 42,
                        "ContentSourceProgressPercent": 42,
                        "Location": null},
    "Statistics": null,
    "StatusInfo": {"Status": "Reading"}}]}
  ```

  Status is `ReadyToRead`, `Reading` or `Finished`. Writes are
  last-write-wins, with no conflict detection, so the client compares
  `LastModified` itself. A `null` `Location` leaves the stored location
  untouched rather than clearing it.
- Position mapping: `ProgressPercent / 100` maps to a Readium
  `Locator.locations.totalProgression`. `Location.Value` is a kepub span
  id (`kobo.7.1`) that has no Readium equivalent, so it is read but not
  written.
- Books are offered as KEPUB when the server has `kepubify` configured
  (`DownloadUrls` then lists KEPUB only). KEPUB is EPUB3 with extra
  spans, and Readium opens it fine.
- Behind a reverse proxy, download URLs are built from the forwarded
  host; a Cloudflare/Caddy install can still emit `http://` URLs, so the
  client must rewrite the scheme to match the configured base URL.
- Deletions in calibre are not propagated (only archived books are, as
  `IsRemoved`), and users can restrict syncing to selected shelves, which
  makes an empty sync legitimate.
