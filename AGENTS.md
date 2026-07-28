# Liseur — Copilot instructions

Open-source Android ebook reader: local EPUB library + calibre-web client
(OPDS browse/download, Kobo-protocol position sync), with a Kindle-inspired
reading experience. Kotlin + Jetpack Compose + Readium Kotlin Toolkit.
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

Toolchain: JDK 17, AGP 9.x (built-in Kotlin support — **do not** add the
`org.jetbrains.kotlin.android` plugin, only `org.jetbrains.kotlin.plugin.compose`
is applied), compileSdk/targetSdk 37, minSdk 26. Dependencies are managed in
`gradle/libs.versions.toml` (version catalog).

## Hard constraints

- **F-Droid compatibility**: every dependency must be FOSS and come from
  Maven Central or Google's Maven repo. No proprietary blobs, trackers,
  analytics, or Google Play services. In particular, never add
  `readium-lcp` (depends on the proprietary liblcp).
- Network access is limited to user-configured calibre-web servers and
  opt-in dictionary lookups.

## Architecture

Single `:app` module, layered packages under `com.chmouel.liseur`:

- `data/` — Room database, DataStore settings, local library repository
  (SAF folder scanning + Readium streamer metadata extraction), calibre-web
  clients (OPDS + Kobo sync).
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

- Reading positions are Readium `Locator`s locally; calibre-web sync
  exchanges percentage progression (`locations.totalProgression`),
  newest-wins conflict resolution.
- Reader settings map to Readium `EpubPreferences`; reading themes
  (Light/Sepia/Dark/Black) are decoupled from the app's Material theme.
- Bundled fonts must be under open licenses (OFL): Literata et al.
