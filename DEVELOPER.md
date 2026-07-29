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

The real signing key for published releases lives in `pass`:

| Entry | Contents |
| --- | --- |
| `android/liseur.keystore.p12` | the PKCS#12 keystore, base64 |
| `android/liseur.keystore-password` | store and key password |
| `android/liseur.keystore-alias` | key alias (`liseur`) |

To build a signed APK locally with it, write a `keystore.properties`
(gitignored, per-developer) pointing at a decoded copy:

```bash
pass show android/liseur.keystore.p12 | base64 -d > /tmp/liseur.p12
{
  echo "storeFile=/tmp/liseur.p12"
  echo "storePassword=$(pass show android/liseur.keystore-password)"
  echo "keyAlias=$(pass show android/liseur.keystore-alias)"
  echo "keyPassword=$(pass show android/liseur.keystore-password)"
} > keystore.properties
```

Contributors without access to that key can generate their own instead —
any key produces an installable APK, it simply won't update over one
signed with the release key:

```bash
keytool -genkeypair -v -keystore /path/to/your.p12 -storetype PKCS12 \
  -alias liseur -keyalg RSA -keysize 4096 -validity 10950
```

Either way `./gradlew assembleRelease` picks the file up automatically;
without it the release build is simply unsigned.

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

## Releasing

`hack/release` does the whole thing: bump `versionCode`/`versionName`,
write the F-Droid changelog, run tests, lint and a release build, tag,
push, publish the GitHub release, and update the F-Droid submission.

```bash
hack/release 0.2.0 "In-book search, and a Wiktionary card for any word."
hack/release --fdroid-only 0.2.0     # re-run just the F-Droid step
```

It refuses to run on a dirty tree, off `main`, out of sync with the
remote, on a version that is not newer, or with release notes over the
500 characters F-Droid allows. Interrupted runs can be resumed by
invoking it again with the same version.

The tag triggers `.github/workflows/release.yml`, which builds a signed
APK in the `release` GitHub environment and uploads it to the release.
That environment must hold `LISEUR_KEYSTORE_BASE64`,
`LISEUR_KEYSTORE_PASSWORD`, `LISEUR_KEY_ALIAS` and `LISEUR_KEY_PASSWORD`;
`hack/release` creates the environment and uploads whichever of them are
missing straight from `pass`, so an unlocked password store is the only
setup a fresh clone needs. To refresh them all — after rotating the key,
say — run:

```bash
hack/release --sync-secrets
```

Locally, signing is opt-in through a gitignored `keystore.properties`;
without it the release build is simply unsigned.

### Store assets

Both are regenerated rather than maintained by hand:

```bash
hack/screenshots            # docs/screenshots + fastlane phoneScreenshots
hack/icon                   # fastlane icon.png, from the vector drawables
```

`hack/screenshots` drives a connected device through adb, so the device
has to be prepared first: the app installed with a library folder
granted, a shelf of books whose covers are safe to publish (the
[Standard Ebooks](https://standardebooks.org) public domain editions are
what the current set uses), the most recently read book carrying a few
highlights, notes and bookmarks, and calibre-web signed out unless your
server holds only books you would publish a picture of. It captures a
light set and a dark set, and its output is worth looking at before
committing, because a changed layout can silently produce the wrong
screen.

## F-Droid readiness

- **Dependencies are all FOSS**, from Maven Central or Google's Maven.
  In particular `readium-lcp` is deliberately absent: it pulls in the
  proprietary liblcp. The list users see is in `LicencesScreen.kt`.
- **No trackers or analytics**, and no Google Play services. The only
  outbound traffic is to the calibre-web server the user configured, and
  to Wiktionary when a definition is asked for. That is what justifies
  `INTERNET` in the store description.
- **No non-free assets.** The bundled fonts (Literata, Vollkorn, Atkinson
  Hyperlegible, Inter) are all OFL; the icon is drawn in-repo as vector
  drawables.
- **Reproducible versioning**: `versionCode` and `versionName` only ever
  change in a `chore: release vX.Y.Z` commit made by `hack/release`, and
  every release is tagged, so F-Droid's `UpdateCheckMode: Tags` and
  `AutoUpdateMode: Version` work without further help.
- **Metadata lives in the repo** under
  `fastlane/metadata/android/en-US/`: title, descriptions, per-versionCode
  changelogs, icon and screenshots.
- **The build needs no network beyond Gradle dependencies** and no
  signing config: `assembleRelease` on a clean checkout produces an
  unsigned APK, which is what F-Droid builds and signs itself.
- Remaining step, deliberately not done yet: opening the RFP / metadata
  merge request against `fdroiddata`.
