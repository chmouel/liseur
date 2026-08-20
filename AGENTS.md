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

## Pull requests

When creating a pull request for a UI change, if an emulator is available and
the user has authorized deployment, build and install the debug APK, navigate
to the affected screen, capture a relevant screenshot, and attach it to the
pull request description through GitHub's user-attachments API. If a screenshot
is not possible, state why in the pull request.

Releases go out with `hack/release`. **Never cut a release, create a
tag, or install/replace an app on a device without asking the user
first.** The script also updates the F-Droid
submission, whose pipeline runs on GitLab and can fail after the script
has finished, because the long `fdroid build` is not waited for. Check
the pipeline it links before calling a release done. `DEVELOPER.md`
explains what those checks are.

The app is **not on Google Play yet**: the release workflow's Play
upload step fails with `Package not found: com.chmouel.liseur` and that
is expected, not a release blocker. The first upload must be done by
hand in the Play Console (see the Google Play section of
`DEVELOPER.md`); do not try to automate around it.

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
- Bundled fonts must be under open licenses (OFL): Literata et al.
