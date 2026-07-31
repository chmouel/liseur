## Building

What to build next, and what has been ruled out, is in
[`docs/ROADMAP.md`](docs/ROADMAP.md).

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

### Checking a folder's storage permission

Deleting a book's file needs write access to the folder it lives in, and
that access is granted once, by the system picker, when the folder is
added. A folder added by a version of Liseur older than
"deleting a book deletes the book" was only ever granted read.

What the system actually holds is worth checking directly rather than
inferring from behaviour:

```bash
adb shell dumpsys activity permissions | grep -A2 targetPkg=com.chmouel.liseur
```

`mode=0x1` is read only, `mode=0x3` is read and write, and `persisted`
says which of those survives a reboot. Adding the same folder again
through the picker upgrades a read-only grant in place, which is what
the "add it again to grant deletion" wording in `delete_local_failed`
is telling the user to do.

Worth knowing: on AOSP 16 deletion succeeds even from a read-only
persisted grant, so a stale grant does not reproduce the failure there.
The fallback in `LocalLibraryRepository.addFolder` and the message that
goes with it are for the devices where it does.

## Releasing

`hack/release` does the whole thing: bump `versionCode` and
`versionName`, write the F-Droid changelog, run the tests, lint and a
release build, commit, tag and push, publish the GitHub release, and
update the F-Droid submission.

Run it from a clean, up-to-date `main` branch, with nothing after it:

```bash
hack/release
```

It shows what has landed since the last release, grouped by commit type,
says so when the screens have changed since the screenshots were last
taken, offers the next patch, minor and major version, and opens an
editor on a changelog drafted from those same commits. Correct the
draft, save, and confirm.

The version can also be given outright, which is what CI and scripts
want:

```bash
hack/release --yes 0.2.1 "Fix page fitting on tall screens."
hack/release --fdroid-only 0.2.1     # re-run just the F-Droid step
```

It refuses to run on a dirty tree, off `main`, out of sync with the
remote, on a version that is not newer, or with release notes over the
500 characters F-Droid allows. An interrupted run can be resumed by
invoking it again with the same version.

Pushing the tag is what starts `.github/workflows/release.yml`, which
builds and signs the APK in the `release` GitHub environment and
attaches it to the release. That environment must hold
`LISEUR_KEYSTORE_BASE64`, `LISEUR_KEYSTORE_PASSWORD`,
`LISEUR_KEY_ALIAS` and `LISEUR_KEY_PASSWORD`; `hack/release` creates the
environment and uploads whichever of them are missing straight from
`pass`, so an unlocked password store is the only setup a fresh clone
needs. To refresh them all, after rotating the key for instance:

```bash
hack/release --sync-secrets
```

Signing a build on your own machine is a separate matter, covered under
[Signing a release build](#signing-a-release-build-optional) above.

### Release notes

Two things are written for every release, and they are not the same
thing:

- The **F-Droid changelog**, `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
  Written by hand, capped at 500 characters, and passed to
  `hack/release` as the release notes argument. This is what F-Droid
  shows.
- The **GitHub release notes**, generated during the release by
  `hack/generate-release-notes`. It asks Gemini to turn the commits
  since the previous tag into something a reader would want to read,
  using the hand-written changelog as the summary to lead with.

Without `GEMINI_API_KEY`, or if the request fails, the generated notes
fall back to the hand-written changelog, so a release is never held up
by this. The key comes from `pass` under `google/gemini-api` and is
uploaded to the release environment by `hack/release --sync-secrets`.

The notes can be rewritten after the fact — the body of a release stays
editable even though its tag and assets do not:

```bash
GEMINI_API_KEY=$(pass show google/gemini-api) \
  hack/generate-release-notes --output notes.md v0.2.0
gh release edit v0.2.0 --notes-file notes.md
```

### Never delete a release

Releases are immutable once published, and that goes further than the
assets: **a tag that has carried a release can never be used again**,
even after deleting both the release and the tag, and even with the
feature turned off. GitHub does this so that a trusted artifact can
never be swapped for another under the same name.

So a release that went out wrong is not fixed by deleting it. Bump the
patch version and release again. `v0.1.0` was burned exactly this way
and is why the first published version is 0.1.1.

### What F-Droid checks

Updating the submission is not the end of it. Pushing to the metadata
merge request starts a pipeline on `fdroiddata`, and that pipeline can
fail long after `hack/release` has finished and reported success.

The check that catches people out is `fdroid rewritemeta`. It reformats
`metadata/com.chmouel.liseur.yml` and fails if the result differs from
what is committed, byte for byte, trailing newline included. It is not a
linter with opinions to argue with: the file has to be what it would
have written.

Every release for a month failed this job while the builds themselves
passed. `hack/release` had been sending the metadata through `jq` as
`--arg content "$(cat file)"`, and command substitution strips trailing
newlines, so what arrived ended mid-line. Use `jq --rawfile`, which
reads the file as it is. The same trap is waiting in any script that
sends a file through a JSON API.

`hack/release` now waits for the metadata checks and stops the release
naming whatever failed. What it does not wait for is `fdroid build`,
which compiles the app from source, and the `check apk` that follows it:
about twenty-five minutes between them. Those are left running and the
pipeline is linked in the output, so look at it before assuming a
release landed.

A failure in `fdroid build` usually means reproducibility, which
`hack/verify-reproducible` will reproduce locally. See *F-Droid
readiness* below.

### Store assets

The screenshots and the icon are regenerated rather than maintained by
hand:

```bash
hack/screenshots --setup    # build the demo shelf first, then capture
hack/screenshots            # capture from a device already set up
hack/screenshots --setup-only   # build the shelf and stop, to check it
hack/icon                   # fastlane icon.png, from the vector drawables
hack/feature-graphic        # fastlane featureGraphic.png, from the banner
```

`hack/screenshots` drives a connected device through adb and writes both
`docs/screenshots` and the fastlane `phoneScreenshots`. It finds controls
by what they say rather than by where they sat when it was written, so a
moved button is something it waits for and fails on, not a tap into empty
space.

`--setup` builds the shelf from nothing: it downloads a handful of
[Standard Ebooks](https://standardebooks.org) public domain editions
(their cover art is what makes the library screens publishable), pushes
them to the device, grants the folder through the real picker, and leaves
the first book part-read with three highlights, a note and a bookmark on
it. Each of those is checked against the database afterwards, because a
highlight that quietly failed looks exactly like one that worked. Run it
once; later runs can drop `--setup` and take about ten minutes.

Sign out of calibre-web first unless your server holds only books you
would publish a picture of. The script itself never writes to one.

Look at the images before committing. A screen can be found and still be
showing the wrong thing:

```bash
montage docs/screenshots/*.png -tile 6x2 -geometry 320x+6+6 /tmp/sheet.png
```

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
- **Komga** integration is Komga's own REST API: `POST
  /api/v1/books/list` to browse, `GET /api/v1/books/{id}/file` to
  download, and `GET`/`PUT /api/v1/books/{id}/progression` to sync a
  full Readium locator rather than a percentage.
- **One server at a time.** `data/remote/` holds provider-neutral
  contracts (`CatalogSource`, `FileSource`, `ServerSetup`,
  `PositionSync`); `data/calibre/` and `data/komga/` implement them, and
  `RemoteRouter` picks the implementation from the connected server's
  `ServerKind`. `domain/ReadingStateMerge.kt` is shared by both, so the
  conflict rules are written once.
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

## Komga protocol

Verified against a real Komga install (API 1.25.0) with an API key, and
against `BookLifecycle.kt` on master. Every rejection below was provoked
deliberately rather than read off the schema.

- Auth is `X-API-Key: <key>`, created in Komga's web UI. Identity is
  `GET /api/v2/users/me` → `{id, email, roles[]}`; there is no
  `/api/v1/users/me`. `FILE_DOWNLOAD` in `roles` means the account may
  download. Users paste the API-key *page* address, so setup reduces a
  pasted URL to its origin.
- Browse is `POST /api/v1/books/list?page=&size=&sort=` with an EPUB +
  READY condition; `GET /api/v1/books` is deprecated since 1.19.0. Each
  entry carries `readProgress {page, completed, readDate}` inline, which
  is the change detector — a routine sync costs one request.
- Search is the same endpoint with `fullTextSearch` set, a sibling of
  `condition`.
- `PUT /progression` validates, in order: `modified` strictly after the
  stored `readDate` (else `409`); `href`, fragment stripped and
  URL-decoded, exactly matching an internal EPUB file name, with **no**
  leading slash (else `400`); and `locations.progression`, which is
  required. Our `totalProgression` is ignored and recomputed.
- **There is no page-based fallback.** `PATCH read-progress {"page": N}`
  is rejected for reflowable EPUB ("not Divina compatible"); only
  `{"completed": true}` works. On a `400` the client instead fetches
  `GET /api/v1/books/{id}/positions` and snaps to the nearest position
  Komga already knows, keeping the position rather than coarsening it.
  That index is ~330 KB for an ordinary book, so it is only ever fetched
  after a rejection.
- **`409` is not a failure.** The server holds something at least as
  new; the row stays dirty and the next run pulls and reconciles.
- `GET /progression` answers `204` with an empty body when there is no
  progress. A `404` means the book is unknown, and *is* a failure.
- `modified` comes back with a local UTC offset and is double-offset, so
  it must not be used for ordering; `readProgress.readDate` round-trips
  exactly and is what the sync orders by.
- Deleting a book from the server is admin-only
  (`DELETE /books/{id}/file`), so that action is hidden for Komga.

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
- **The build is reproducible.** F-Droid rebuilds from source and will
  not publish a build it cannot reproduce, so `hack/verify-reproducible`
  builds the release APK twice from two clean checkouts at deliberately
  different paths and compares the two byte for byte:

  ```bash
  hack/verify-reproducible          # HEAD
  hack/verify-reproducible v0.2.0   # a tag, before submitting it
  ```

  If they differ it names the entries responsible, which is usually a
  timestamp baked into a resource or an absolute build path that leaked
  in. Run it before every release. Move `keystore.properties` aside
  first if you have one: the check compares the unsigned APK.
- **Submitted.** The metadata merge request is
  [fdroiddata!44292](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44292),
  and `hack/release` keeps it up to date with each version. See *What
  F-Droid checks* above for what its pipeline runs and how it can fail
  after a release looks finished.
