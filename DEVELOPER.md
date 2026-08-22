## Building

What to build next, and what has been ruled out, is in
[`docs/ROADMAP.md`](docs/ROADMAP.md).

What each supported server exposes, what is implemented against it, and
why the remaining gaps are or are not fixable is in
[`docs/SERVER_CAPABILITIES.md`](docs/SERVER_CAPABILITIES.md).

This project uses the Gradle wrapper, so you don't need Gradle installed
separately — just a JDK 17+ and the Android SDK (command-line tools are
enough; `compileSdk`/`targetSdk` 37 requires a reasonably recent SDK
Manager package list).

```bash
./gradlew assembleDebug    # unsigned debug APK, installable as-is
./gradlew assembleRelease  # minified release APK
./gradlew bundleRelease    # minified release AAB, for Google Play only
```

Output APKs land in `app/build/outputs/apk/{debug,release}/`, and the
bundle in `app/build/outputs/bundle/release/`.

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

### The upload end-to-end check

Sending a local book to liseur-sync is the one path where a unit test
proves almost nothing: what can go wrong is the shape of the whole
round trip, not a function. `hack/e2e-upload` drives it through the
app's own screens against a real server and then reads both sides.

```bash
hack/e2e-upload -u http://10.0.2.2:8686 -t <token> -d /srv/books
```

`-u` is the server as the *device* sees it, so an emulator wants
`10.0.2.2` and never `127.0.0.1`. `-t` is a device token holding at
least `sync`, `library-read` and `library-upload`. `-d` is the watched
folder's root on this machine, which is how the script sees what landed.
The folder has to accept uploads already:

```bash
liseur-sync admin folder-uploads <folder-id> on
```

It asserts four things, and each one is a bug that actually happened:
the `library-upload` scope reaches the app as `can_upload`, the files
appear in the folder, no uploaded book had its `url` rewritten (that
key is what every reading position hangs off), and no book came back
from the following catalog pass as a second row. That third one is
worth the whole script: a book uploaded from the device was being
deleted by the next refresh, and its reading position with it.

The other half of the capability is refusing, and `-r` checks it:

```bash
hack/e2e-upload -r -u http://10.0.2.2:8686 -t <token> -d /srv/books
```

A server says no in two places. A token without the scope is refused on
sight, and the action is never offered. A token that holds the scope but
finds every folder closed can only be refused by trying, and the app has
to remember the answer — otherwise it offers, once per book, an action
that silently fails every time. Either way `-r` asserts the app ends up
not offering, no book was linked, none left the shelf and nothing was
written into the folder. Run it with the folder still closed, then
`folder-uploads <folder-id> on` and run the check above, and you have
covered both answers with one server.

Nothing about it is mocked. Start with a clean shelf — `hack/reset-books`
then `adb shell pm clear com.chmouel.liseur` — or the counts it compares
are counting an earlier run.

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
hack/release --no-play 0.2.1 "..."   # leave Google Play out of this one
```

It refuses to run on a dirty tree, off `main`, out of sync with the
remote, on a version that is not newer, or with release notes over the
500 characters F-Droid allows. An interrupted run can be resumed by
invoking it again with the same version.

Pushing the tag is what starts `.github/workflows/release.yml`, which
builds and signs the APK in the `release` GitHub environment and
attaches it to the release. Signing is what that environment must hold:
`LISEUR_KEYSTORE_BASE64`, `LISEUR_KEYSTORE_PASSWORD`,
`LISEUR_KEY_ALIAS`, `LISEUR_KEY_PASSWORD` and, for the release notes,
`GEMINI_API_KEY`. Without them there is no release.

`LISEUR_PLAY_SERVICE_ACCOUNT_JSON` is the odd one out. It buys the third
channel rather than the release itself, and the workflow skips Google
Play entirely when it is absent. `hack/release` uploads it with the rest
all the same, so in practice a release goes to Play unless you say
otherwise:

```bash
hack/release --no-play 0.9.0 "..."
```

That is the only way to keep Play out of a release once the credential
has been uploaded once. Deleting the secret by hand does not do it —
`hack/release` reads one missing secret as a stale environment and
uploads them all again from `pass`, Play credential included.

`hack/release` creates the environment and uploads whichever secrets are
missing straight from `pass`, so an unlocked password store is the only
setup a fresh clone needs. To refresh them all, after rotating the key
for instance:

```bash
hack/release --sync-secrets
```

Signing a build on your own machine is a separate matter, covered under
[Signing a release build](#signing-a-release-build-optional) above.

### Test releases

A test release is the same build, signed with the same release key, put
where a handful of people can install it from and nowhere else:

```bash
hack/release --test          # the next patch: v0.9.4-test.1, then .2
hack/release --test 0.10.0   # a version you are working towards
```

It publishes a GitHub **prerelease** with the signed APK attached, and
stops there: no F-Droid merge request, no Play upload, no changelog to
write, and no local test, lint or reproducibility run. The point of a
test release is to be quick.

The signature is what makes it worth doing. It is the one F-Droid
publishes under, through the dual-signing flow, so the APK installs
straight over a copy that came from F-Droid — no uninstall, no lost
library — and F-Droid offers the next real release over it afterwards,
as an ordinary update.

That last part is only true because the test build takes a
`versionCode` and the next real release lands above it. `hack/release`
counts the next code from the highest one across `main` **and every
tag**, so a test release at 18 pushes the following real release to 19,
and F-Droid sees an upgrade. Nobody who installed a test build is stuck
— but they are on it until the next release goes out, since F-Droid
will not offer a lower `versionCode`.

Nothing lands on `main`. The commit that bumps the version is reachable
only through its tag, so `main` stays a history of real releases and the
next one still bumps from the last real version. Run it from any branch,
as long as the tree is clean.

To install one, download the APK from the release page and
`adb install -r`, or hand it to whoever is testing.

### Google Play

> **Not live yet.** The app has not been created in the Play Console, so
> every upload — CI or manual — fails with `Package not found:
> com.chmouel.liseur`. That failure is expected and must not hold up a
> release; the GitHub release and F-Droid are the channels that count
> today. The very first upload has to go through the console's release
> wizard by hand (that is where the upload key gets enrolled, see **The
> signing key** below), and only a human with console access can do it.
> Delete this notice once that first upload has happened.

Play is the third channel, after the GitHub release and F-Droid, and it
is deliberately the least load-bearing of the three. The same tag that
publishes the release also builds an app bundle and pushes it to the
**internal testing** track:

```bash
make bundle     # ./gradlew bundleRelease, for Play only
```

Nothing about the APK path changes. F-Droid's recipe builds
`assembleRelease` and never sees `fastlane/Fastfile`, no Gradle
publishing plugin is applied, and the bundle is an extra output rather
than a replacement — which is also why the Play step in
`.github/workflows/release.yml` is `continue-on-error` and skips itself
entirely when the service account secret is absent. A fork, or a rejected
upload, must not be what makes a release fail. `hack/release --no-play`
turns that skip into something you can ask for, by leaving the credential
off the release environment instead of topping it up.

The upload runs `fastlane android internal` with
`SUPPLY_JSON_KEY` pointing at a service account credential written to
`$RUNNER_TEMP` and removed by a trap in the same step. The credential is
`android/google.play.service.serviceaccount` in `pass`, shared with the
other apps on the account; it needs *Release to testing tracks* on Liseur
under Users and permissions.

Only the changelog is pushed from the repository. The store listing is
edited in the console, because Play holds declarations that no file here
describes — data safety, content rating, target audience, app access, ads
— and those have to be revisited whenever the app gains a permission,
talks to something new, or changes what it stores. The privacy policy
Play links to is `docs/PRIVACY.md`, served by GitHub Pages from `main`
`/docs`; it is a published legal document, so change it in a commit and
not in the console.

Promotion out of internal testing is a manual action in the console, on
purpose. Nothing in this repository can put a build in front of the
public.

**The signing key.** Play App Signing holds the same key as the GitHub
release, enrolled from `pass` through Google's PEPK tool rather than
generated by Google. That means a build installed from Play and a build
downloaded from the release page carry the same signature, so a reader
can move between them without uninstalling and losing their library. It
also means the key cannot be swapped without a rotation request. F-Droid
publishes two APKs per version, one with its own signature and one with
this same key grafted onto its reproduced build (see *F-Droid readiness*
below), so a reader can cross between all three channels.

Recreating the PEPK export, should it ever be needed:

```bash
d=$(mktemp -d) && umask 077
pass show android/liseur.keystore.p12 | base64 -d > "$d/release.p12"
java -jar pepk.jar --keystore="$d/release.p12" \
  --alias="$(pass show android/liseur.keystore-alias)" \
  --output="$d/output.zip" --include-cert \
  --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
```

`pepk.jar` and `encryption_public_key.pem` are downloaded from the
console, and the temporary directory goes away afterwards.

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
  using the hand-written changelog as the summary to lead with. It also
  gives Gemini the associated pull-request descriptions, links changes
  back to those PRs, and carries a relevant screenshot over from a PR
  body when one is available. Those screenshots are re-sized to a fixed
  width (`SCREENSHOT_WIDTH` in the script) before the notes are written,
  so a full-size PR image cannot blow the page apart.

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
hack/screenshots --class tablet  # file the captures as a tablet set
hack/icon                   # fastlane icon.png, from the vector drawables
hack/feature-graphic        # fastlane featureGraphic.png, from the brand emblem
```

`hack/screenshots` drives a connected device through adb and writes both
`docs/screenshots` and the matching fastlane directory. It finds controls
by what they say rather than by where they sat when it was written, so a
moved button is something it waits for and fails on, not a tap into empty
space.

There are two sets, because fastlane and F-Droid publish
`phoneScreenshots` and `tenInchScreenshots` separately and the README
embeds the phone set by name. Which one a run writes is decided by how
wide the device is — under 600dp phone, above it tablet — so a capture
against a tablet cannot quietly overwrite the phone images. Pass
`--class` for a device whose shape does not match how its pictures
should be filed.

The phone set is the full tour, twelve screens. The tablet set is three
pictures of what a phone cannot show — two columns, the control that
chooses them, and a shelf with room on it — and lands in
`docs/screenshots/tablet`. There is no point photographing the settings
screen twice, and the search and dictionary steps that make the phone
run slow are skipped, so a tablet run takes a few minutes.

`--setup` builds the shelf from nothing: it downloads a handful of
[Standard Ebooks](https://standardebooks.org) public domain editions
(their cover art is what makes the library screens publishable), pushes
them to the device, grants the folder through the real picker, and leaves
the first book part-read with three highlights, a note and a bookmark on
it. Each of those is checked against the database afterwards, because a
highlight that quietly failed looks exactly like one that worked. Run it
once; later runs can drop `--setup` and take about ten minutes.

`--empty` is its own mode, and short: the empty library is the one screen
the tour cannot reach, because the tour needs a shelf with books on it.
It wipes app storage, photographs what a new reader sees, and stops. Run
it whenever the empty state changes; the rest of the tour would only put
the demo shelf back.

Everything that gets published is in the light theme. A dark screenshot
in a store listing reads as the app looking like that, rather than as the
app being able to; the dark theme earns more as a line in the description
than as one picture in six that matches none of the others. The script
still captures `11-reading-dark` and `17-empty-library-dark` — they are
useful to look at — it just does not file them with fastlane or the
README.

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

## liseur-sync protocol

A second kind of partner, and a different shape from the two above.
calibre-web and Komga each hold one current position per book and
answer "where am I"; liseur-sync holds an append-only log and answers
"what has happened since `seq`". It holds no books at all, which is
what lets it sync a book that came off an SD card.

- Auth is `Authorization: Bearer <token>`. `POST /v1/login` with a
  username and password mints device tokens through `POST /v1/tokens`;
  one scope per token, so signing in asks for two — `sync` and
  `read-insights` — and a reader who pastes a token made elsewhere
  usually has only the first. Statistics are simply absent then.
- **The cursor is the only irreplaceable state.** Everything else can
  be asked for again, but ops behind `sync_account.cursor_seq` cannot.
  So a page from `GET /v1/changes?since=` is written and the cursor
  advanced in one transaction, never the other way round. A cursor that
  has fallen below the server's compaction horizon gets `410
  resync_required`; the answer is `GET /v1/heads` and its
  `snapshot_seq`.
- **Ids are derived, not drawn.** The server treats `op_id` and
  `session_id` as idempotency keys and compares the whole payload
  behind each: same id and same payload is `duplicate`, same id and a
  different payload is a conflict. So the id is
  `UUIDv3(deviceKey|workId|revision)` and every payload field comes
  from stored state — `client_ts` is `reading_progress.updated_at`,
  never the clock. A push interrupted by a dead network is simply
  repeated. The server does not check that the id is a UUIDv7; it is
  opaque, up to 64 characters.
- `POST /v1/works/resolve` takes every identifier at once — `sha256:`,
  `pmd5:` (KOReader's partial MD5), `dc:` and `ta:` — and registers all
  of them against whichever matched, which is how a re-encoded copy and
  the original converge. `409` means they named two different works;
  the server changes nothing and merging is left to the reader.
- **`ta:` normalisation is an interoperability contract and is not in
  the schema.** The server matches `ta` aliases by exact string and
  computes nothing itself. `WorkIdentifiers.titleAuthor` defines it as
  `fold(title)|fold(author)`, where folding is NFKD, strip `\p{Mn}`,
  lowercase, non-alphanumerics collapsed to single spaces, trimmed. Any
  other client must agree exactly or it will silently fail to match.
- A locator over 16 KB is dropped and the progression sent on its own.
  Failing the push outright would leave the other device with no idea
  where the reader is, which is far worse than reopening at a
  percentage.
- A book resolved *today* has all of its history behind the cursor, so
  a newly named book is seeded once from
  `GET /v1/works/{id}/positions?limit=1`.
- `POST /v1/sessions` takes closed sessions only, as progression
  fractions. **Never send page numbers**: a page is a property of one
  rendering of one edition at one type size, and the server derives
  pages itself when it knows the edition. `idle_ms` is always zero here
  and honestly so — time is counted only while the reader is in the
  foreground, so time spent elsewhere is already absent rather than
  included and subtracted.
- Statistics (`/v1/insights/*`) are decoration. Every failure is null
  and silent, and a null `eta_seconds` is carried through untouched: no
  estimate beats an invented one.
- **Uploading is opt-in twice over.** The server advertises the
  `library-upload` scope on `GET /v1/token` and marks the folders that
  take uploads in `GET /v1/folders`; without both, the action is not
  offered at all, which is how an older server needs no version check.
  `POST /v1/folders/{folder}/books` is `multipart/form-data`, keyed by
  the file's SHA-256, so a repeated upload answers `200 duplicate` and
  stores nothing twice. `202` means the bytes are safe but the server
  had not catalogued them yet; the worker simply asks again, and the
  digest makes the second ask free.
- **What follows an upload is adoption, not replacement.** The local row
  keeps its own `url` and gains `remote_uuid` and `download_href`
  (`BookDao.linkToRemote`). Rewriting the URL to the server's spelling
  would take every reading position, annotation and session with it.
  Because the catalog reads what the library holds once, before its
  walk begins, both sides guard against the book being introduced twice:
  the catalog re-asks about the ids on a page it has not accounted for,
  and adoption drops a catalog row that got there first.

## F-Droid readiness

- **Dependencies are all FOSS**, from Maven Central or Google's Maven.
  In particular `readium-lcp` is deliberately absent: it pulls in the
  proprietary liblcp. The list users see is in `LicencesScreen.kt`.
- **No trackers or analytics**, and no Google Play services. The only
  outbound traffic is to the calibre-web or Komga server the user
  configured, and to a dictionary site when a definition is asked for.
  That second one is off until switched on in Settings and the site is
  the user's to choose (`DictionaryUrl`), because F-Droid review will
  otherwise treat a hardcoded third-party host as grounds for the
  TetheredNet anti-feature. Together those justify `INTERNET`;
  `ACCESS_NETWORK_STATE` is there for the `NetworkType.CONNECTED`
  constraint on the sync workers.
- **No non-free assets.** The bundled fonts (Literata, Vollkorn, Atkinson
  Hyperlegible, Inter) are all OFL; the icon is drawn in-repo as vector
  drawables.
- **`fonts.googleapis.com` appears in the release dex and is unreachable.**
  It is a string inside Readium's `ReadiumCss`, emitted only for families
  registered through `EpubNavigatorFactory`'s separate `googleFonts` list.
  Liseur never sets that list: every `addFontFamilyDeclaration` in
  `ReaderPreferencesMapper.kt` sources its faces from bundled asset paths.
  Worth knowing, because a reviewer grepping the dex for hosts will find
  it and ask.
- **Reproducible versioning**: `versionCode` and `versionName` only ever
  change in a `chore: release vX.Y.Z` commit made by `hack/release`, and
  every release is tagged. F-Droid's `UpdateCheckMode: Tags` still
  notices a new tag, but `AutoUpdateMode` is `None`: under dual signing
  (below) every release needs its extracted signature delivered by hand,
  which their bot cannot do yet, so `hack/release` opens that merge
  request itself.
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
  in. `hack/release` runs it on every release commit and undoes the
  commit rather than tag a build F-Droid could never publish; run it
  yourself before anything unusual ships. Move `keystore.properties`
  aside first if you have one: the check compares the unsigned APK.

  Two build-file settings exist purely to keep this true and must not
  be removed: `dependenciesInfo` is switched off (AGP would otherwise
  embed a dependency manifest encrypted for Google Play, which nobody
  else can reproduce), and `packaging.jniLibs.keepDebugSymbols` covers
  every `.so` (the only native code arrives prebuilt in AndroidX AARs;
  re-stripping it ties the bytes to the build machine's NDK — this was
  the one thing that made the CI APK differ from a local rebuild).
- **Publishing our own signature: dual signing (merged 2026-08-21).**
  Because the build is reproducible, F-Droid can publish the
  developer signature. Replacing their signature outright was declined
  in review — existing F-Droid installs carry F-Droid's key and Android
  would refuse them every further update — so the app uses F-Droid's
  dual-signing flow instead: for each version F-Droid publishes **two
  APKs**, one signed with its own key (existing users keep updating,
  nothing breaks) and one carrying our signature, grafted onto F-Droid's
  own rebuild after that rebuild comes out byte-identical with ours.
  New installs from F-Droid get the developer-signed copy, which is
  interchangeable with the GitHub release. Concretely, the metadata has:

  - `AllowedAPKSigningKeys:` with **two** SHA-256 digests — our
    certificate and the key F-Droid signs this app with (their reviewer
    added the second; both APKs must pass the check).
  - `AutoUpdateMode: None`: their bot cannot drive this flow, so every
    release must arrive as a merge request adding the new build entry
    plus the signature files under
    `metadata/com.chmouel.liseur/signatures/<versionCode>/`, extracted
    from the signed release APK with `fdroid signatures <apk>`.
    `hack/release` does all of this: it downloads the APK from the
    GitHub release, extracts the signature, appends the build entry,
    normalises the file with `fdroid rewritemeta`, and opens (or, when
    one is already open, updates) the fdroiddata merge request. It
    needs `fdroidserver` installed (`pipx install fdroidserver`, or
    point `FDROID_BIN` at one); without it the release still goes out
    and `hack/release --fdroid-only VERSION` finishes the F-Droid part
    later.

  The merge request that set this up was
  [fdroiddata!46390](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46390)
  (merged); the first verifiable tag is v0.9.3 (versionCode 17), because
  reproducibility landed after the v0.9.2 tag, so only 17 and later get
  the developer-signed twin. NewPipe
  ([fdroiddata!46133](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46133))
  is the worked example this follows.
- **Checking where things stand.** `hack/fdroid-status` prints the
  published versions, the index age, what the last build run did with
  the app, the upstream metadata, and any open merge request with its
  pipeline state — one command instead of four tabs.
- **Submitted.** The inclusion merge request was
  [fdroiddata!44292](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44292)
  (merged), and `hack/release` opens the per-release signature merge
  request described above. See *What F-Droid checks* above for what its
  pipeline runs and how it can fail after a release looks finished.
