# Liseur — Copilot instructions

Open-source Android ebook reader: local EPUB library + calibre-web,
Komga and liseur-sync clients (browse/download and position sync against
any of them), with a Kindle-inspired reading experience. Kotlin + Jetpack Compose + Readium Kotlin Toolkit.
FOSS-only dependencies — the app targets F-Droid inclusion. See `README.md`
for user-facing goals.

## Build, test, lint

Always use the wrapper (`./gradlew`), never a system-wide `gradle` —
the project is pinned to Gradle 9.6.1 via `gradle/wrapper/`.

```bash
./gradlew assembleDebug              # unsigned debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease            # minified+shrunk release APK (unsigned unless keystore.properties exists)
./gradlew testDebugUnitTest          # JVM unit tests (no emulator needed)
./gradlew lintDebug                  # Android Lint; must pass with 0 errors (warnings OK)
```

Common workflows are also available through the Makefile:

```bash
make build                           # build the debug APK
make release                         # build the release APK
make test                            # run JVM unit tests
make lint                            # run Android Lint
make check                           # run tests, lint, and the debug build
make run                             # boot headlessly, install, launch, and show with scrcpy
make run-bg                          # boot headlessly, install, and launch without scrcpy
make install                         # install the current debug APK
make reset                           # reinstall, wipe app storage, and reseed a demo library
make emulator                        # boot the default AVD
make stop                            # stop the selected emulator
make shutdown                        # shut down the selected emulator
```

The default AVD is `liseur_phone_api36`. Override it when needed:

```bash
make AVD=liseur_phone_api26 emulator
make AVD=liseur_phone_api36 run
make SERIAL=emulator-5556 run         # use a specific emulator with SERIAL=...
```

CI (`.github/workflows/build.yml`) runs `testDebugUnitTest`, `lintDebug`, and
`assembleDebug` on every push/PR to `main`.

## Emulators and phones

An emulator is scratch space. Boot one, install over whatever is on it,
wipe its storage, reseed the demo shelf, write straight into the app's
database with `adb shell run-as`, rotate it, drive it with
`adb shell input` — no permission needed for any of it, and no need to
ask before starting. Everything on an emulator can be rebuilt in a
minute by `make reset`, so there is nothing there worth protecting.
`make shutdown` when you are done, or say you left it running.

A physical phone is somebody's library. **Never install, replace, or
uninstall the app, clear its storage, or write to its database on a
real device without asking first.** A reading position, a highlight and
a half-finished note are not reproducible, and an unlucky
`adb install -r` takes them.

When several devices are attached, always pass `-s` / `SERIAL=` rather
than letting `adb` choose. `adb devices` reports emulators as
`emulator-NNNN`; treat anything else as a phone.

`hack/screenshots --setup-only` builds a demo shelf on an emulator from
scratch: it downloads a handful of Standard Ebooks editions, grants the
folder through the real picker, and leaves the first book part-read with
highlights, a note and a bookmark on it. It is the fastest way to get a
device that has something to photograph.

## Pull requests

When creating a pull request for a UI change, if an emulator is
available or one can be booted, build and install the debug APK,
navigate to the affected screen, capture a relevant screenshot, and
attach it to the pull request description through GitHub's
user-attachments API. If a screenshot is not possible, state why in the
pull request.

Releases go out with `hack/release`. **Never cut a release or create a
tag without asking the user first.** The script also updates the F-Droid
submission, whose pipeline runs on GitLab and can fail after the script
has finished, because the long `fdroid build` is not waited for. Check
the pipeline it links before calling a release done. `DEVELOPER.md`
explains what those checks are.

The release workflow uploads an app bundle to Google Play's **internal
testing** track, which 0.10.0 was the first release to reach. The step
is `continue-on-error` and skips itself when the service account
credential is absent, so Play is never what makes a release fail, and
`hack/release --no-play` asks for that skip. Promoting a build out of
internal testing is a manual action in the console.

Toolchain: JDK 17, AGP 9.x (built-in Kotlin support — **do not** add the
`org.jetbrains.kotlin.android` plugin, only `org.jetbrains.kotlin.plugin.compose`
is applied), compileSdk/targetSdk 37, minSdk 26. Dependencies are managed in
`gradle/libs.versions.toml` (version catalog).

## Git hooks

Install the [pre-commit](https://pre-commit.com/) hooks once after cloning:

    pre-commit install --hook-type pre-push

This runs unit tests, Android Lint, and the release build before every
push. Skip with `git push --no-verify`.

## Hard constraints

- **F-Droid compatibility**: every dependency must be FOSS and come from
  Maven Central or Google's Maven repo. No proprietary blobs, trackers,
  analytics, or Google Play services. In particular, never add
  `readium-lcp` (depends on the proprietary liblcp).
- **The release build must stay reproducible** (`hack/verify-reproducible`
  must pass): F-Droid rebuilds every tag from source. Do not remove the
  `dependenciesInfo` or `packaging.jniLibs.keepDebugSymbols` settings in
  `app/build.gradle.kts` — both exist only for this. `DEVELOPER.md`
  explains why.
- Network access is limited to the user-configured book server
  (calibre-web, Komga or liseur-sync) and opt-in dictionary lookups.

## Architecture

Single `:app` module, layered packages under `com.chmouel.liseur`:

- `data/` — Room database, DataStore settings, local library repository
  (SAF folder scanning + Readium streamer metadata extraction), and the
  remote-server layer: `data/remote/` holds provider-neutral contracts;
  `data/calibre/` (OPDS + Kobo sync), `data/komga/` (REST) and
  `data/liseursync/` (native REST + append-only op log) implement them,
  and `RemoteRouter` dispatches on the connected server's kind.
  liseur-sync is a full `ServerKind` like the other two — it catalogs,
  serves files, and syncs — and its position sync also covers books
  that never came from a server, resolving them by their hashes.
- `domain/` — small use-case layer, only where logic is non-trivial
  (sync merge, time-left estimator). Keep pure and JVM-testable.
- `reader/` — Readium `EpubNavigatorFragment` hosted in Compose, plus the
  custom reading chrome (tap zones, typography sheet, progress, annotations,
  search).
- `ui/` — Compose screens: theme (`ui/theme`), library, settings.
- `sync/` — WorkManager workers for downloads, uploads and position sync.

State management: plain `ViewModel` + `StateFlow`, unidirectional data flow.
DI is a manual composition root (no Hilt/Koin). Extract non-trivial logic
into pure Kotlin so it stays unit-testable without Robolectric or an
emulator.

## Conventions

- Never add a `Co-authored-by` (or any co-author/AI attribution)
  trailer to commits.

- Reading positions are Readium `Locator`s locally. calibre-web sync
  exchanges percentage progression (`locations.totalProgression`); Komga
  and liseur-sync exchange a full locator, so they also restore the
  exact spot. All three go
  through `domain/ReadingStateMerge.kt` — write conflict rules there, once,
  not per provider.
- One server is connected at a time. Anything provider-shaped belongs
  behind a `data/remote/` contract, not in a `when (kind)` at the call
  site.
- liseur-sync is an append-only log, not a current-position store. The
  cursor (`remote_server.sync_cursor_seq`) is the only irreplaceable
  state:
  advance it in the same transaction that writes the page it covers,
  never before. Op and session ids are *derived*
  (`UUIDv3(deviceKey|…)`) and every payload field comes from stored
  state rather than the clock, so a retry is byte-identical and the
  server answers `duplicate`. Do not introduce a random id or a
  `pending_ops` table.
- A book's name on liseur-sync is a `work_alias`. A book from its
  own catalog resolves through `POST /v1/books/{id}/resolve` — the
  server reads the identifiers off its record, so no download is needed
  and two devices name it identically. Any other book resolves from
  SHA-256 + KOReader partial-MD5 + a normalised `ta:` title/author. A
  `ta:`-only match is low confidence and syncs nothing until the reader
  confirms it; a rejection is stored as `confidence = 'rejected'` rather
  than deleted, or the next run asks again.
- Statistics from a server are decoration. Every failure there is
  null and silent; the stats screen is built from local sessions and
  must stand on its own.
- Annotations are the exception to that rule, because they are mutable
  and deletable: an id derived from current content stops being
  reproducible the moment the reader edits the row. `annotation_sync`
  holds what the server confirmed *and* the exact bytes of any request
  in flight, written before the call and replayed verbatim through
  `postRaw` — first send and retry alike. Do not rebuild an item from
  `JSONObject` on the way out; `org.json` has no key ordering and the
  server compares raw bytes. Two fingerprints: the acknowledged one is
  content only (never `base_rev`, never `edition_sha`), the pending one
  identifies the request so an answer applies without overwriting an
  edit made while it was in the air. Every answer is written down
  against the row re-read inside the transaction, never against the
  snapshot that was sent. See `docs/adr/0011-annotation-sync.md`.
- Annotation freshness is ordered by `seq`, never by `rev`. A rev
  restarts at 1 when the server recreates an id whose tombstone was
  swept. For the same reason the feed cursor moves by the raw page, not
  by the records this device could represent: `high_water` is only ever
  for a page the server sent empty.
- Liseur's `NOTE` is a note attached to a passage and maps to a server
  `highlight` carrying a body. `BOOK_NOTE` is the server's standalone
  `note`: it has a body and deliberately no locator, progression,
  excerpt, colour or edition anchor. Keep the two kinds distinct.
- "No locator" is read generously on the way in: absent, JSON null, an
  empty string and an empty object all mean the same thing, as they
  already do in `SyncOps.locatorFor`. Reading one of those as an anchor
  refuses a standalone note on every pull *and* every reconcile, so the
  reader never sees it once.
- Because a book note carries no `edition_sha` and two copies of one
  book share a `work_id`, `home()` cannot tell which copy it was written
  against. A note landing the first time gets a copy picked the same way
  every run; one already filed here goes back where it already lives.
  Pass the known `bookId` rather than sending `edition_sha` on a note.
- The annotation pass runs settle → pull → reconcile → push → deletes,
  and that order is not negotiable. Reconciling a work's live set before
  pushing is what stops an offline edit to a swept tombstone being sent
  as a create, which resurrects a highlight the reader deleted
  elsewhere. Phases 0 and 1 are account-wide even for a book-scoped run:
  the pending set and the cursor belong to the account, and settling one
  book's requests would strand the rest. Conflicts are server-wins.
  `client_ts` decides nothing. A work with an unsettled mark is
  reconciled every pass, whatever the seven-day interval says: that
  interval guesses at the server's retention, which may be a day, and
  guessing wrong about a work with something to push is the
  resurrection the phase exists to prevent.
- A mark with a rev is pushed only if the same pass saw it in the
  server's live set. Agreeing the work is not enough: the push rescans a
  mutable table. One predicate, `offerable()`, decides both which books
  are worth reconciling and what the push sends, so they cannot drift.
- A conflict is settled against the copy that was sent, never against a
  newer one the reader wrote after the request left. Compare the local
  content, not the sync row: editing a highlight does not touch it.
- Every annotation network call checks the account is still the
  connected one first, not just before storing the answer — the request
  is the side effect. A record's home alias is likewise re-read inside
  the transaction that commits it, so a file that took over the path
  mid-pass cannot have another book's highlight anchored into it.
- An annotation id is opaque, so `.` and `..` are ids to carry and push
  like any other. They cannot be *addressed*: a URL parser decodes
  `%2E%2E` before it resolves dot segments, so no escape survives.
  `LiseurSyncApi.addressable()` is what makes a delete decline rather
  than aim at the collection.
- Removing a book keeps its annotations *and* its `annotation_sync`
  rows. They are still on the server and on the other phone, and
  dropping only the agreements would push every mark again as new when
  the book came back. `BookRemoval.contentReplaced()` is the one path
  that clears both, in one transaction, because a different file took
  over the path — a sync row with no annotation behind it reads as a
  deletion the reader made.
- Per-account annotation state is cleared inside
  `RemoteAccountRepository.forgetSyncPeer()`, never at a call site.
  Disconnecting, switching accounts and `forgetUnreadableAccount()` all
  go through that one door.
- A book only on this device can be sent to liseur-sync, where the
  server allows it: `BookUploader`, `ServerCapabilities.canUpload` (read
  from the `library-upload` scope) and `BookUploadWorker`, whose unique
  work name `upload:$bookUrl` is itself the no-double-upload guarantee.
  What follows a successful upload is **adoption, not replacement**: the
  local row keeps its own `url`, because that is the key every reading
  position, annotation and session hangs off, and only `remote_uuid` and
  `download_href` are written (`BookDao.linkToRemote`). Never rewrite
  `books.url` to the server's spelling — the reader's place goes with it.
- Blocking network calls move to `Dispatchers.IO` inside the client that
  blocks, not in the caller. A `suspend` signature reads as a promise
  that the thread is safe, and a repository reached from a
  `viewModelScope` is reached from the main thread.
- Reader settings map to Readium `EpubPreferences`; reading themes
  (Light/Sepia/Dark/Black) are decoupled from the app's Material theme.
- A new reading setting goes in the Advanced sheet
  (`reader/chrome/AdvancedSheet.kt`), and directly on Settings → Reading
  appearance, which has no Advanced section to collapse it behind. The
  typography sheet is the short list a reader changes often — theme,
  size, brightness, font, and how the book is read — and it only grows
  for a setting that genuinely belongs there. Make that case in the pull
  request; the default is Advanced. It grew to eleven controls once, one
  reasonable row at a time. See `docs/adr/0001-advanced-reading-menu.md`.
- Bundled fonts must be under open licenses (OFL): Literata et al.
