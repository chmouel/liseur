# Liseur — Copilot instructions

Open-source Android ebook reader: local EPUB library + calibre-web and
Komga clients (browse/download and position sync against either), with a
Kindle-inspired reading experience. Kotlin + Jetpack Compose + Readium Kotlin Toolkit.
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

CI (`.github/workflows/build.yml`) runs `testDebugUnitTest`, `lintDebug`, and
`assembleDebug` on every push/PR to `main`.

Releases go out with `hack/release`. It also updates the F-Droid
submission, whose pipeline runs on GitLab and can fail after the script
has finished, because the long `fdroid build` is not waited for. Check
the pipeline it links before calling a release done. `DEVELOPER.md`
explains what those checks are.

Toolchain: JDK 17, AGP 9.x (built-in Kotlin support — **do not** add the
`org.jetbrains.kotlin.android` plugin, only `org.jetbrains.kotlin.plugin.compose`
is applied), compileSdk/targetSdk 37, minSdk 26. Dependencies are managed in
`gradle/libs.versions.toml` (version catalog).

## Hard constraints

- **F-Droid compatibility**: every dependency must be FOSS and come from
  Maven Central or Google's Maven repo. No proprietary blobs, trackers,
  analytics, or Google Play services. In particular, never add
  `readium-lcp` (depends on the proprietary liblcp).
- Network access is limited to the user-configured book server
  (calibre-web or Komga) and opt-in dictionary lookups.

## Architecture

Single `:app` module, layered packages under `com.chmouel.liseur`:

- `data/` — Room database, DataStore settings, local library repository
  (SAF folder scanning + Readium streamer metadata extraction), and the
  remote-server layer: `data/remote/` holds provider-neutral contracts,
  `data/calibre/` (OPDS + Kobo sync) and `data/komga/` (REST) implement
  them, and `RemoteRouter` dispatches on the connected server's kind.
  `data/liseursync/` is *not* a third `ServerKind`: liseur-sync holds no
  catalog and is connected alongside a catalog server rather than
  instead of one, so it is a second **sync peer**. Both peers implement
  `PeerPositionSync` and `CompositePositionSync` runs them, which is why
  a pull bumps `local_revision` — reading that arrived from one peer is
  new to the other.
- `domain/` — small use-case layer, only where logic is non-trivial
  (sync merge, time-left estimator). Keep pure and JVM-testable.
- `reader/` — Readium `EpubNavigatorFragment` hosted in Compose, plus the
  custom reading chrome (tap zones, typography sheet, progress, annotations,
  search).
- `ui/` — Compose screens: theme (`ui/theme`), library, settings.
- `sync/` — WorkManager workers for downloads and position sync.

State management: plain `ViewModel` + `StateFlow`, unidirectional data flow.
DI is a manual composition root (no Hilt/Koin). Extract non-trivial logic
into pure Kotlin so it stays unit-testable without Robolectric or an
emulator.

## Conventions

- Reading positions are Readium `Locator`s locally. calibre-web sync
  exchanges percentage progression (`locations.totalProgression`); Komga
  exchanges a full locator, so it also restores the exact spot. Both go
  through `domain/ReadingStateMerge.kt` — write conflict rules there, once,
  not per provider.
- One *catalog* server is connected at a time, and at most one sync
  server alongside it. Anything provider-shaped belongs behind a
  `data/remote/` contract, not in a `when (kind)` at the call site.
- liseur-sync is an append-only log, not a current-position store. The
  cursor (`sync_account.cursor_seq`) is the only irreplaceable state:
  advance it in the same transaction that writes the page it covers,
  never before. Op and session ids are *derived*
  (`UUIDv3(deviceKey|…)`) and every payload field comes from stored
  state rather than the clock, so a retry is byte-identical and the
  server answers `duplicate`. Do not introduce a random id or a
  `pending_ops` table.
- A book's name on a sync server is a `work_alias`, resolved from
  SHA-256 + KOReader partial-MD5 + a normalised `ta:` title/author. A
  `ta:`-only match is low confidence and syncs nothing until the reader
  confirms it; a rejection is stored as `confidence = 'rejected'` rather
  than deleted, or the next run asks again.
- Statistics from a sync server are decoration. Every failure there is
  null and silent; the stats screen is built from local sessions and
  must stand on its own.
- Reader settings map to Readium `EpubPreferences`; reading themes
  (Light/Sepia/Dark/Black) are decoupled from the app's Material theme.
- Bundled fonts must be under open licenses (OFL): Literata et al.
