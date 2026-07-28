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
