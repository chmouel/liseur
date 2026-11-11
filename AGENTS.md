# Liseur: agent instructions

Liseur is an open-source Android EPUB reader (Kotlin, Jetpack Compose,
Readium Kotlin Toolkit) with local shelves and optional calibre-web, Komga,
BookOrbit and liseur-sync connections. It ships on F-Droid, so every change
must stay FOSS-compatible.

This file holds only what you need before changing code.
[DEVELOPER.md](DEVELOPER.md) has the detailed rules (architecture,
protocols, sync invariants, reader behavior, testing, releases, F-Droid);
`docs/adr/` has the reasoning behind decisions. Read the relevant
DEVELOPER.md section before touching that area.

## Build and validation

Use the Gradle wrapper (JDK 17), never a system Gradle:

```bash
./gradlew testFossDebugUnitTest testPlayDebugUnitTest
./gradlew lintFossDebug lintPlayDebug
./gradlew assembleFossDebug assemblePlayDebug
make check   # tests, lint and builds, when a change spans them
```

- Two flavors: `foss` (F-Droid, GitHub) and `play` (Google Play). Read
  aloud, translation and the companion on-device or on a user-listed
  server are in both. Code that calls Gemini or ML Kit goes only in
  `app/src/play/`, because F-Droid builds `foss` and must find none of it
  ([details](DEVELOPER.md#building)).
- Dependencies live in `gradle/libs.versions.toml`. AGP 9 supplies Kotlin;
  adding `org.jetbrains.kotlin.android` breaks the build.
- Pull request CI runs only policy checks; the full build runs on `main`
  and in the pre-push hook. When a PR needs it in CI (something local
  checks could not cover, or an R8/release-build risk), add the label
  with `gh pr edit <number> --add-label full-ci`; every later push builds
  in full until `gh pr edit <number> --remove-label full-ci`. Leave it off
  by default.
- Install the pre-push hook once with
  `pre-commit install --hook-type pre-push`. It runs tests, lint and the
  release build; skip it with `git push --no-verify` only deliberately.
- Emulator and end-to-end checks: [DEVELOPER.md](DEVELOPER.md#testing).

## Emulators and real phones

- `make emulator` starts the emulator in the background; `make run-bg`
  builds, installs and launches without scrcpy.
- Emulators are disposable: install, wipe, seed, rotate, drive with `adb`
  and write test data with `run-as` freely. Pass `SERIAL=` or `adb -s`
  when several devices are attached.
- IMPORTANT: any serial that is not `emulator-*` is a real phone holding
  someone's library. Ask before installing over, uninstalling, clearing or
  editing its database. `make dev-install` uses the separate
  `com.chmouel.liseur.dev` package, so it is safe for the real app's data,
  but announce it.
- To check AI translation or OpenAI-compatible read aloud, run
  `make ai-stub`, point the app at `http://10.0.2.2:8765/v1`, and read
  what it sent from `tmp/ai-stub/requests.jsonl` or `/_stub/requests`.
  When the stub lacks something, extend `hack/ai-stub` and
  `hack/test-ai-stub` in the same change instead of writing a throwaway
  one ([details](DEVELOPER.md#ai-services-on-the-emulator)).

## Repository workflow

- Preserve unrelated worktree changes; touch only files the task needs.
- Ask before committing, amending, rebasing, resetting, force-pushing, or
  creating releases or tags. Add no `Co-authored-by` or AI trailers.
- Keep non-trivial logic pure and JVM-testable. Use the manual
  composition root, `ViewModel` + `StateFlow`, Room, DataStore and the
  existing package boundaries. Blocking network calls run on
  `Dispatchers.IO` inside the blocking client.
- UI changes follow the screenshot and PR guidance in
  [store assets](DEVELOPER.md#store-assets) and
  [releasing](DEVELOPER.md#releasing).
- Keep DEVELOPER.md tight: rules, commands and invariants. When an ADR
  already explains a decision, link it instead of restating its
  rationale, history or examples.

## Finishing work

- Before reporting a code change done, run a real check that exercises
  it: the focused unit test, lint or build for the touched flavor, or the
  emulator for reader and UI behavior. A check that failed to start does
  not count. If none can run here, say which one you skipped and why.
  Docs-only changes need no Gradle run.
- Carry multi-part work through to the end. Stop to ask only when you
  are blocked or before a risky step (a real phone, git history, a
  release).
- When the request is done and checked, stop and report. Add tests only
  where they protect the changed behavior; suggest other tests, docs or
  refactors at the end instead of making them. Run extra review rounds or
  reviewer sub-agents only when asked.
- When asked for a plan, ideas or options, give them and wait before
  changing files.

## Constraints

- Dependencies must be FOSS and come from Maven Central or Google's Maven.
  No `readium-lcp`, proprietary blobs, trackers, analytics, Google Play
  services or unapproved network endpoints; F-Droid rejects them. The one
  exception is ML Kit (`com.google.mlkit:translate`) via
  `playImplementation` under `app/src/play/`, with whatever it brings
  (Play services client libraries, Firebase, datatransport, model
  downloads, diagnostics, attribution assets). It must never reach
  `foss`, and anything that widens what it sends must update
  `docs/PRIVACY.md` ([details](DEVELOPER.md#f-droid-readiness)).
- Keep `dependenciesInfo` off and `packaging.jniLibs.keepDebugSymbols` in
  `app/build.gradle.kts`: F-Droid only publishes builds it can reproduce
  byte for byte ([details](DEVELOPER.md#f-droid-readiness)).
- User-facing text goes in the English strings and, in the same change,
  French, Czech, Spanish, Russian, Italian, German, Brazilian Portuguese and
  Simplified Chinese
  (`values-b+zh+Hans`). Use `stringResource`/`pluralStringResource`, mark
  brand names `translatable="false"`, and keep Russian plural categories
  ([details](DEVELOPER.md#translations)).

## High-risk rules

These are the invariants most likely to break a change; the full list is
in [implementation invariants](DEVELOPER.md#implementation-invariants).

- Provider-specific behavior stays behind the `data/remote/` contracts
  and `RemoteRouter`; callers do not branch on `ServerKind`.
- Local reading positions are Readium locators. Merge rules live only in
  `domain/ReadingStateMerge.kt`. `updated_at` (this device wrote it) and
  `read_at` (when the reading happened) stay separate.
- liseur-sync is an append-only log. Advancing the cursor and applying
  the page it covers happen in one transaction, and ids and payloads
  derive from stored state so retries are byte-identical. No random ids,
  no `pending_ops` queue.
- Account rekeying and per-account cleanup belong in
  `RemoteAccountRepository`; a new peer-keyed table must be handled by
  both paths.
- Annotation requests persist their exact bytes before sending and replay
  those bytes. Reconcile before pushing, settle conflicts against the
  sent version, and keep annotation state when a book is removed.
- A local upload adopts the server identity without rewriting
  `books.url`; positions, annotations and sessions stay keyed by the
  local URL.
- Reader position actions refresh the current scrolled place first,
  because the Readium locator is debounced and lags. Bookmark page
  identity comes from comparing locators, never rounded page numbers.
- Reader chrome follows the page theme. Page-turn overrides, insets and
  layout-generation guards stay in the existing reader helpers.
