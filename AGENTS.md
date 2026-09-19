# Liseur: agent instructions

Liseur is an open-source Android EPUB reader with local shelves and optional
calibre-web, Komga, and liseur-sync connections. It uses Kotlin, Jetpack
Compose, and the Readium Kotlin Toolkit. The app must remain FOSS-compatible
with F-Droid.

`DEVELOPER.md` is the detailed source of truth for architecture, provider
protocols, synchronization invariants, reader behavior, testing procedures,
translations, releases, F-Droid, and implementation rationale. Keep this file
limited to rules an agent needs before changing code, and link to the detailed
section when a rule is not self-contained.

## Build and validation

Use the Gradle wrapper, never a system Gradle:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Use `make check` when the change crosses tests, lint, and the debug build.
See [DEVELOPER.md](DEVELOPER.md#testing) for emulator and end-to-end checks.

The project uses JDK 17, AGP 9.x, compile/target SDK 37, and min SDK 26.
Dependencies are managed in `gradle/libs.versions.toml`. AGP supplies Kotlin
support; do not add `org.jetbrains.kotlin.android`.

## Emulator and phone safety

Emulators are disposable: install, wipe, seed, rotate, drive with `adb`, and
write test data with `run-as` as needed. Use `SERIAL=` or `adb -s` whenever
more than one device is attached. Treat any serial that is not
`emulator-*` as a real phone.

Never install over, uninstall, clear, or modify the database of a real phone
without asking first. `make dev-install` is safe from the production app's
data because the dev package is `com.chmouel.liseur.dev`, but it is still an
installation on a real device and must be announced.

## Repository workflow

- Preserve unrelated worktree changes. Inspect before editing and touch only
  files required by the task.
- Keep non-trivial logic pure and JVM-testable; use the manual composition
  root, `ViewModel` + `StateFlow`, Room, DataStore, and the existing package
  boundaries.
- Blocking network work belongs on `Dispatchers.IO` inside the blocking
  client.
- Do not commit, amend, rebase, reset, force-push, or create releases/tags
  without explicit user approval.
- Do not add `Co-authored-by` or other co-author/AI trailers.
- For UI changes, follow the repository's screenshot and PR guidance in
  [DEVELOPER.md](DEVELOPER.md#store-assets) and
  [DEVELOPER.md](DEVELOPER.md#releasing).

## Non-negotiable constraints

- Every dependency must be FOSS and come from Maven Central or Google's Maven
  repository. Never add `readium-lcp`, proprietary blobs, trackers,
  analytics, Google Play services, or unapproved network endpoints.
- Preserve release reproducibility. Do not remove the `dependenciesInfo` or
  `packaging.jniLibs.keepDebugSymbols` settings in
  `app/build.gradle.kts`; see [DEVELOPER.md](DEVELOPER.md#f-droid-readiness).
- User-facing UI text belongs in the English resource file and in the French,
  Spanish, Russian, Italian, and German resources in the same change. Use
  `stringResource` or `pluralStringResource`, mark brand names
  `translatable="false"`, and preserve Russian plural categories. See
  [DEVELOPER.md](DEVELOPER.md#translations).

## High-risk implementation rules

The detailed rules and rationale live in
[DEVELOPER.md#implementation-invariants](DEVELOPER.md#implementation-invariants).
The following are the rules most likely to invalidate a change:

- Keep provider-specific behavior behind `data/remote/` contracts and
  `RemoteRouter`; do not spread `when (ServerKind)` through callers.
- Reading positions are Readium locators locally. Centralize merge rules in
  `domain/ReadingStateMerge.kt`. Keep `updated_at` (this device wrote it)
  separate from `read_at` (when reading happened).
- liseur-sync is an append-only log. Cursor advancement and applying the page
  it covers are one transaction; ids and payloads are derived from stored
  state so retries are byte-identical. Never introduce random ids or a
  `pending_ops` queue.
- Account rekeying and per-account cleanup belong in
  `RemoteAccountRepository`; new peer-keyed tables must be handled by both
  paths.
- Annotation requests persist their exact bytes before sending and replay
  those bytes; reconcile before pushing, settle conflicts against the sent
  version, and preserve annotation state when a book is removed.
- A local upload adopts the server identity without rewriting `books.url`;
  positions, annotations, and sessions are keyed by the local URL.
- Reader position actions must refresh the current scrolled place rather than
  trusting a debounced locator. Bookmark page identity comes from locator
  comparison, not rounded page numbers.
- Reader chrome follows the reading page theme. Keep page-turn overrides,
  insets, and layout-generation guards centralized in the existing reader
  helpers.

## Hooks

Install the pre-push hook once after cloning:

```bash
pre-commit install --hook-type pre-push
```

It runs tests, lint, and the release build. `git push --no-verify` skips it
only when that is deliberate.
