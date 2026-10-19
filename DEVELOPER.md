## Important rule

Keep `DEVELOPER.md` tight: it holds the rules, commands and invariants a
contributor needs. When a decision already has an ADR under
[`docs/adr/`](docs/adr/), link to it from `DEVELOPER.md` instead of
repeating its rationale, history or examples there.

## Building

Roadmap: [`docs/ROADMAP.md`](docs/ROADMAP.md). Per-server features and
gaps: [`docs/SERVER_CAPABILITIES.md`](docs/SERVER_CAPABILITIES.md).
Needs JDK 17+ and a recent Android SDK (SDK 37); use the Gradle wrapper.

```bash
./gradlew assemblePlayDebug    # debug APK with Gemini and ML Kit
./gradlew assembleFossDebug    # debug APK of the F-Droid build
./gradlew assembleFossRelease  # minified release APK (F-Droid, GitHub)
./gradlew bundlePlayRelease    # minified release AAB, for Google Play only
```

- `foss` is what F-Droid reproduces and the GitHub release carries.
- `play` adds Gemini read-aloud voices, Gemini translation and the
  companion on Gemini (book text goes to a non-free Google service), and
  the ML Kit translator (on-device, proprietary SDK with diagnostics).
  Ships only on Google Play. Its code lives only under `app/src/play/`
  and `app/src/testPlay/`; the `foss` APK has none of its code, strings
  or endpoints.
- Read aloud, translation and the companion on-device or on a
  user-entered server are in both. Both flavors share application id,
  version and signing key; `assembleRelease`/`assembleDebug` build both.

APKs land in `app/build/outputs/apk/{foss,play}/{debug,dev,release}/`,
the bundle in `app/build/outputs/bundle/playRelease/`. Debug is signed
with the Android debug key (`adb install` works); release is unsigned by
default so any machine/CI can build it (see signing below).

### The dev build, for testing on a phone you use

The debug build uses the production `applicationId`, so installing it
over Liseur replaces it and the library (positions, highlights, notes,
sessions) is lost. Use the `dev` build type instead:

```bash
make dev             # ./gradlew assemblePlayDev (FLAVOR=foss for assembleFossDev)
make dev-install     # adb install -r, beside the real app
make dev-run         # install and launch
make dev-uninstall
make dev-logcat      # logcat filtered to its pid
```

- `applicationId` is `com.chmouel.liseur.dev`: own Room database,
  DataStore, SAF grant and server device key; cannot touch the real app.
  Launcher "Liseur (dev)" with retinted icon (all of `app/src/dev/res/`).
  APK: `app/build/outputs/apk/play/dev/app-play-dev.apk`, debug-signed.
- Device-facing targets (`install`, `run`, `reset`, `dev-*`) and
  `hack/install*` use `play` unless `FLAVOR=foss`.
- `dev-*` device targets run only on a physical device: `PHONE=` picks
  it (default first attached); they fail with none or if `PHONE=` names
  an emulator.
- Neither `release` (F-Droid rebuilds it byte for byte) nor `debug` is
  changed, so `make install`/`run`/`reset`, `hack/screenshots`,
  `hack/e2e-*` and `tests/` scenarios keep their hardcoded package.
- By hand, spell the component in full:
  `adb shell am start -n com.chmouel.liseur.dev/com.chmouel.liseur.MainActivity`
  (the `.MainActivity` shorthand fails because the id no longer matches
  the namespace).

### Signing a release build (optional)

The release key lives in `pass`:

| Entry | Contents |
| --- | --- |
| `android/liseur.keystore.p12` | the PKCS#12 keystore, base64 |
| `android/liseur.keystore-password` | store and key password |
| `android/liseur.keystore-alias` | key alias (`liseur`) |

Release builds pick up a gitignored `keystore.properties` (else
unsigned). Point it at a decoded copy in persistent user data, not `/tmp`:

```bash
signing_dir="${XDG_DATA_HOME:-$HOME/.local/share}/liseur/signing"
install -d -m 700 "$signing_dir"
umask 077
pass show android/liseur.keystore.p12 | base64 -d > "$signing_dir/release.p12"
{
  echo "storeFile=$signing_dir/release.p12"
  echo "storePassword=$(pass show android/liseur.keystore-password)"
  echo "keyAlias=$(pass show android/liseur.keystore-alias)"
  echo "keyPassword=$(pass show android/liseur.keystore-password)"
} > keystore.properties
```

- If `keystore.properties` exists but its `storeFile` is gone, Gradle
  stops at configuration with recovery instructions: rerun the commands
  above. Delete `keystore.properties` only to deliberately build
  unsigned (as clean checkouts and F-Droid do).
- `hack/release` checks this before a release and reruns the recovery
  when the file or keystore is missing; a usable custom signing path is
  left unchanged.

Without the key, generate your own (won't update over release-key APKs):

```bash
keytool -genkeypair -v -keystore /path/to/your.p12 -storetype PKCS12 \
  -alias liseur -keyalg RSA -keysize 4096 -validity 10950
```

## Settings backup archive

A backup is a ZIP with a versioned `manifest.json`, `settings.json`,
`annotations.json`, `positions.json`, `companion.json`, optional
`fonts/<sha256>.(ttf|otf)`, (v5) optional `connections.enc.json` and (v6)
optional `translations.json`. Versions 1-5 stay readable and leave data
they lack unchanged.

- Manifest records each entry's size and SHA-256. Reject malformed known
  fields, unsafe or duplicate paths, checksum mismatches, unsupported
  versions, payloads newer than the declared version, negative or
  overflowing timestamps. Unknown optional fields are ignored.
- Annotations (keyset pages, size-bounded staging file) plus positions
  (paged by URL key) share a 4 MiB limit checked before JSON decoding.
  Recaps export most recently used first under a separate 16 MiB limit,
  stopping before the first recap that would exceed it; no companion
  database exports an empty list without creating one.
- Settings use explicit allowlists per store; transient notices excluded;
  unset keys stay absent so restore leaves destination values alone. Fonts
  keep content-based names and go through the normal font importer.
- Service addresses are included; credentials and API keys are excluded
  unless "Include accounts, services and passwords" (unchecked, password
  entered twice) is set; then only `connections.enc.json` (library
  account, KOReader pairing, API keys, Play Gemini key) is encrypted:
  AES-256-GCM, fresh 12-byte nonce, PBKDF2-HMAC-SHA256, fresh 32-byte
  salt, 600,000 iterations, version and parameters recorded. Passwords
  stay in memory; staging files hold only ciphertext. The liseur-sync
  device token is never copied (re-pair).
- Restore previews account replacement, asks for the password once and
  authenticates and validates before writing anything; a wrong password
  changes nothing and can be retried. Replaced accounts are retired
  without network login, credentials re-encrypted with the destination
  Keystore; sync cursors,
  acknowledgements and pending work are excluded. No encrypted payload:
  accounts and API keys untouched.
- `translations.json` (optional) carries done or restored whole-book
  records with rows and failure markers, whole books only under a
  separate 64 MiB limit (omitted books are counted, never truncated).
  The payload is validated before anything is applied; books must
  already be in the library and match like recaps. Imported coverage is
  `restored` (unverified) until checked, and stays exportable. It is
  never encrypted.

Backup and restore previews everything, including matching books, and also
accepts old annotation-only JSON. Books match by URL or unambiguous title
and author; ambiguous or duplicate matches never overwrite another book.
Annotations add missing marks and request sync. Positions replace locator
and progression (progression alone without an exact locator), keep the
reading time, update the finished flag in the same transaction and write a
new local revision (no baselines or acknowledgements), then request sync,
even if a later write fails; unmatched ones keep their URL. Recaps are added
for library books only (skip missing, ambiguous or many-to-one), existing
ones kept, store limits apply and the count excludes trimmed recaps; other
recap formats are skipped (the reader also ignores recaps hashed for another
file); hashes, chapter keys, timestamps and provenance travel; unfinished
batches, waiting chapters and per-book switches do not. Settings span two
DataStores, so any later storage failure is reported as partial restore.

The screen also exports all offline EPUBs (including hidden and archived) to
a folder as sanitized title-author names, skipping existing or duplicate
names case-insensitively, streaming per file, reporting
exported/skipped/failed counts and continuing after failures. Cancelling
or leaving the screen stops it, keeping finished copies and removing
partial ones where possible; rotation keeps it running. It never changes
the library or downloads remote books.

## Testing

```bash
./gradlew testFossDebugUnitTest testPlayDebugUnitTest   # JVM unit tests
./gradlew lintFossDebug lintPlayDebug                   # Android Lint (0 errors required)
```

No instrumented suite; reader interactions (gestures, immersive mode,
process-death restore, rotation) are checked manually on an AVD.

### AI services on the emulator

`make ai-stub` (`hack/ai-stub`, `PORT=` to change 8765) is a fake
OpenAI-compatible server. Use it for any change to what the app sends to
AI translation or OpenAI-compatible speech, never a real provider or a
one-off stub. It serves `GET /v1/models` (`stub-chat`, `stub-tts`),
`POST /v1/chat/completions` (the `<passage>` prefixed `[T]`,
`--prefix`), `POST /v1/audio/speech` (silent 24 kHz WAV; other
`response_format` gets the 400 that triggers the MP3 fallback),
`GET /v1/audio/voices` (`stub_a`, `stub_b`, and English/French/Spanish
voices), and `GET /_stub/requests`
(all requests, `DELETE` empties; also `tmp/ai-stub/requests.jsonl`; chat
entries split `<book>`, `<context>`, `<passage>` into `blocks`; the API
key is never logged). Flags: `--fail CODE`, `--delay SECONDS`,
`--key KEY` (require bearer), `--think` (prepend `<think>`).

It binds `127.0.0.1` (emulator: `10.0.2.2`); `--host 0.0.0.0` exposes the
log of sent text. In Settings, External services and API keys, add
`http://10.0.2.2:8765/v1`; pick it with model `stub-chat` for
translation ("Translate a test sentence"; scripts type `stub-chat` and
Enter, uiautomator cannot see the menu popup) or as voice service for
read aloud. `hack/lib/demo-books.sh` (`tap_on`, `wait_for`, `on_screen`)
drives these screens given `serial`, `work` and a serial-scoped `adb`.

```bash
curl -s localhost:8765/_stub/requests | jq '.[] | select(.blocks) | .blocks'
```

Missing stub behavior goes into `hack/ai-stub` plus a case in
`hack/test-ai-stub` in the same change.

### Furthest-position emulator check

With a second device posting a farther exact locator, Android declines
it and keeps uploading its own position, which survives force-stop.
Manual sync still offers the historical destination even when the latest
op is Android's own echo, and restores the original locator, not a
matching percentage. Offline, manual recovery and way-back work; on
reconnect the edition-bound peak and lower current position are separate
ops. Catalog-download peaks carry edition hashes. Uncovered: API 26,
rotation, cross-edition rendering, server compaction.

### EPUB font-size remediation

`ReaderPreferencesMapper.epubNavigatorConfiguration()` sets
`useReadiumCssFontSize = false` so `ReaderPrefs.fontSize` reaches
`WebSettings.textZoom` via `EpubPreferences`. Never use CSS `zoom` or
WebView page/pinch zoom instead. Default `ReaderPrefs.DEFAULT_FONT_SIZE`
is a Size slider position (~134%).

```bash
hack/make-font-size-book
```

Import `tmp/books/liseur_font-size-fixture.epub` (kept off the demo
shelf) on a disposable emulator; record `SERIAL`, API, WebView version,
viewport, font scale. At Default typography compare `MEDIUM`,
`ABSOLUTE-BODY`, `ABSOLUTE-DESCENDANT` at 100/150/200%: growth, reflow,
live change, new chapter, reopen; plus a fractional value, global vs
per-book, an advanced setting, a fixed-layout control. A saved pref or
computed CSS is not proof. `hack/verify-wide-content` and
`hack/verify-footnotes` cannot validate native text zoom.

#### Validation record

100% and ~200% verified on API 36. Still manual: 150/60/250%, fractional
pinch, custom fonts, line-height, fixed layout, rotation, RTL/CJK,
selection, footnotes, wide tables.

### The upload end-to-end check

`hack/e2e-upload` uploads through the app's screens to a real
liseur-sync and checks both sides:

```bash
liseur-sync admin folder-uploads <folder-id> on
hack/e2e-upload -u http://10.0.2.2:8686 -t <token> -d /srv/books
hack/e2e-upload -r -u http://10.0.2.2:8686 -t <token> -d /srv/books
```

`-u` is the URL as the device sees it (never `127.0.0.1` on an
emulator); `-t` needs `sync`, `library-read`, `library-upload`; `-d` is
the folder's local root. It asserts `can_upload` is set, files land, no
book's `url` is rewritten (positions hang off it), and the next catalog
pass adds no duplicate row. `-r` (run with the folder closed) asserts
refusal: no scope means never offered; scope with closed folders is
refused on trying and must be remembered; nothing linked, removed or
written. Start from a clean shelf: `hack/reset-books`, then
`adb shell pm clear com.chmouel.liseur`.

### Checking a folder's storage permission

Deleting a file needs the folder's picker grant to include write; old
folders may be read only.

```bash
adb shell dumpsys activity permissions | grep -A2 targetPkg=com.chmouel.liseur
```

`mode=0x1` read, `0x3` read/write, `persisted` survives reboot.
Re-adding the folder upgrades it (`delete_local_failed` says so). AOSP 16
deletes regardless; the `LocalLibraryRepository.addFolder` fallback is
for devices that do not.

### Checking the local network permission

Android 17 makes LAN connections from SDK 37 apps hang until allowed
(#195, [ADR 0028](docs/adr/0028-local-network-permission.md)). Any
`NEARBY_DEVICES` grant includes it.

```bash
adb shell dumpsys package com.chmouel.liseur | grep -i local_network
adb shell am compat enable RESTRICT_LOCAL_NETWORK com.chmouel.liseur.dev  # pre-37 image
adb shell am compat reset RESTRICT_LOCAL_NETWORK com.chmouel.liseur.dev
```

### Why the reading stats say "this device only"

Devices merge only when provably exact
([ADR 0021](docs/adr/0021-cross-device-reading-statistics.md)). With a
liseur-sync token with the `read-insights` scope, failure shows Retry, never
local figures
([ADR 0060](docs/adr/0060-server-stats-when-liseur-sync-is-set-up.md));
otherwise local figures are shown and labelled. Each refusal logs
`Statistics count this device alone: <reason>`:

```bash
adb logcat -s liseur-sync-insights
```

`candidate_payload_mismatch` usually means the device id (part of the
fingerprint) changed: a password reconnect keeps it, a pasted token does
not. `legacy_evidence_unknown` sittings are rebuilt byte-identically
from the row with `active_ms` withheld. Counted locally unasked: unsent
sittings newer than `session_transmission`, and books with no confident
server name. Reproduce on an emulator only, never a real reader's phone;
the total should still say "all your devices" with the local count:

```bash
adb shell "run-as com.chmouel.liseur sqlite3 databases/liseur.db \
  'UPDATE reading_sessions SET legacy_evidence_unknown = 1, uploaded_at = NULL; \
   DELETE FROM session_transmission;'"
```

A sitting joins same-book sessions under five minutes apart, across
devices, in app and server alike, and never crosses midnight. Until all
is uploaded, a local session bridging two server sittings can overcount
by one.

## Releasing

`hack/release` does the whole thing: bump `versionCode` and
`versionName`, write the F-Droid changelog, run the tests, lint and a
release build, commit, tag and push, publish the GitHub release, and
update the F-Droid submission. Run it from a clean, up-to-date `main`
with nothing after it:

```bash
hack/release
```

It lists commits since the last release, flags stale screenshots, offers
the next patch, minor and major versions, and opens an editor on a
changelog drafted by Gemini, falling back to `copilot -p` (`gpt-6-luna`,
or `LISEUR_NOTES_COPILOT_MODEL`). If neither drafts it, it offers to try
again before opening the editor without a draft.

```bash
hack/release -n 0.2.1 "Fix page fitting on tall screens."
hack/release --fdroid-only 0.2.1     # re-run just the F-Droid step
hack/release --no-play 0.2.1 "..."   # leave Google Play out of this one
```

- `-n` (`--non-interactive`, `--yes`) answers the prompts. Any run with
  nothing on stdin needs it, `--rc` included: EOF reads as no and the
  release stops without doing anything.
- It refuses a dirty tree, being off `main`, out of sync with the remote,
  a version that is not newer, or notes over F-Droid's 500 characters.
- Rerun with the same version to resume an interrupted run. Brief GitLab
  outages in the F-Droid step are retried; if it still fails after GitHub
  published, it prints the `hack/release --fdroid-only VERSION` command.
- Pushing the tag starts `.github/workflows/release.yml`, which builds and
  signs the APK in the `release` GitHub environment. That environment
  must hold `LISEUR_KEYSTORE_BASE64`, `LISEUR_KEYSTORE_PASSWORD`,
  `LISEUR_KEY_ALIAS`, `LISEUR_KEY_PASSWORD` and `GEMINI_API_KEY` (release
  notes); without them there is no release.
- If a workflow failure stops publication, never move the tag or bump the
  version. Rerun with the fixed workflow on `main`, which builds the
  original tagged source, then finish F-Droid once GitHub has the APK.
  Rerunning the failed Actions run reuses its old workflow.

  ```bash
  gh workflow run release.yml --ref main -f release_tag=v0.20.0
  hack/release --fdroid-only 0.20.0
  ```

- `LISEUR_PLAY_SERVICE_ACCOUNT_JSON` only enables the Play channel; the
  workflow skips Play when it is absent. `hack/release` uploads it anyway,
  so a release goes to Play unless you pass `--no-play`, which is recorded
  in the annotated tag so a configured credential cannot override it. Do
  not delete the secret by hand; a later release restores it from `pass`.
- `hack/release` creates the environment and uploads missing secrets from
  `pass`, so an unlocked password store is the only setup a fresh clone
  needs. Refresh them all (e.g. after key rotation) with:

  ```bash
  hack/release --sync-secrets
  ```

Local signing is covered in
[Signing a release build](#signing-a-release-build-optional).

### Release candidates

Same build, same release key, for a few testers before the final release:

```bash
hack/release --rc             # the next patch, numbered -rc.1, then .2
hack/release --rc 0.16.0      # a version you are working towards
hack/release --no-play --rc   # skip Google Play testing tracks
```

- Publishes a GitHub prerelease with the signed APK and uploads the bundle
  to Play's internal and closed (`Testing`) tracks. No F-Droid merge
  request, no changelog, no production upload; `--no-play` skips Play.
- Before the tag is pushed, the generated RC commit must pass JVM tests,
  Android lint and the release build in its detached worktree. The
  reproducibility check stays on the final release path only.
- The release key is the one F-Droid publishes under via dual signing, so
  an RC installs over an F-Droid copy without uninstalling, and F-Droid
  offers the next real release over it.
- That holds because `hack/release` takes the next `versionCode` from the
  highest across `main` and every tag (RC at 32, next release 33). RC
  users stay on it until the next release, since F-Droid never offers a
  lower `versionCode`.
- Nothing lands on `main`: the bump commit is reachable only through its
  tag, so the next release bumps from the last real version. Any branch
  works if the tree is clean.
- F-Droid reads public tags. fdroiddata filters with
  `UpdateCheckMode: Tags ^v[0-9.]+$` so neither `-rc.N` nor legacy
  `-test.N` matches; `AutoUpdateMode: None` does not replace that filter,
  since update checks and build submission are separate. Before any final
  or RC tag is pushed or submitted, `hack/release` runs
  `hack/verify-fdroid-tags` against the live metadata and fails closed if
  a final tag would be missed or a prerelease tag discovered.
- `make verify-fdroid-tags` runs the local regression checks;
  `hack/verify-fdroid-tags v0.15.0 v0.15.0-rc.1 v0.15.0-test.1` checks
  the live filter. Changing the RC tag format or the `UpdateCheckMode`
  pattern must update and rerun this check.
- Install with the release-page APK and `adb install -r`.
- After an RC is published, `hack/prune-prereleases` deletes every legacy
  `-test.N` release page (and APK) and keeps only the highest `-rc.N` page
  per target version. Tags are never deleted: they keep `versionCode`
  allocation monotonic, number later RCs, and stay burned under GitHub's
  immutable-release rules.

### Google Play

Play is the third and least load-bearing channel. The release tag also
builds an app bundle, uploads it to internal testing, then promotes that
build to the closed track `Testing`:

```bash
make bundle     # ./gradlew bundlePlayRelease, for Play only
```

- Both tracks are required: only the closed track (the one
  `https://play.google.com/apps/testing/com.chmouel.liseur` leads to)
  counts opted-in testers towards the twelve-for-fourteen-days a personal
  developer account needs before publishing. `hack/store-status` lists
  every track with its version codes, showing a track left behind.
- The APK path is unchanged. F-Droid builds the `foss` flavor
  (`gradle: [foss]`, `assembleFossRelease`) and never sees
  `fastlane/Fastfile`; no Gradle publishing plugin is applied. The Play
  step in `release.yml` is `continue-on-error` and skips itself without
  the service account secret: a fork or rejected upload must not fail a
  release. `--no-play` writes `release:no-play` in the annotated tag,
  which the workflow checks before the stored credential.
- The upload runs `fastlane android internal` with `SUPPLY_JSON_KEY`
  pointing at a credential written to `$RUNNER_TEMP` and removed by a trap
  in the same step. The credential is
  `android/google.play.service.serviceaccount` in `pass`, shared with
  other apps on the account.
- Push a build already on internal (skipped, or predating promotion)
  across without rebuilding; `version_code` defaults to
  `app/build.gradle.kts` and is only needed for older builds:

  ```bash
  fastlane android promote version_code:19
  ```

Who can install it. The tester list is console state; nothing in the
release workflow edits it.

- The closed `Testing` track needs an email list holding the Google
  account each tester uses on their phone, added by hand in the console.
  The API cannot manage email lists (`edits.testers` models only Google
  Groups).
- The console takes an email list or a Google Group, not both. A group
  cuts off email-list testers until they join, restarting the
  fourteen-day clock, and consumer groups have no membership API anyway.
- Country availability is not the cause: the track covers every
  country Google allows (Russia works for free apps). It is track-level
  console state; releases carry no `countryTargeting`.
- The track's release must be `completed`, not draft
  (`hack/store-status` prints it). New addresses take hours to propagate,
  a day or two to move the opted-in count. Otherwise the phone's Play
  Store account is usually not the address that was added.

Permissions and production:

- The service account has app-level access to Liseur and must keep
  *Release apps to testing tracks* (uploads, promotions, the track
  listing `hack/store-status` reads) and *Release to production, exclude
  devices and use Play app signing* (production). Crash and ANR counts in
  `hack/store-status` come from the Google Play Developer Reporting API,
  which must be enabled in the service account's Cloud project and needs
  *View app information*.
- Final releases promote the build already on internal to production,
  since Play refuses re-uploading a seen version code. RC and legacy test
  tags never enter production; `--no-play` skips testing and production.
  A production promotion failure does not block GitHub or F-Droid but is
  reported clearly.
- Release lanes push only the changelog; screenshots sync separately
  (below). The rest of the listing is edited in the console, and its
  declarations (data safety, content rating, target audience, app access,
  ads) must be revisited whenever the app gains a permission, talks to
  something new, or changes what it stores.
- The privacy policy Play links to is `docs/PRIVACY.md`, served by GitHub
  Pages from `main` `/docs`. Change it in a commit, never in the console.
- ML Kit translation: update the console declarations before the first
  release carrying it. Data safety must list what the
  [ML Kit Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
  names for `com.google.mlkit:translate` (device and app info,
  per-installation identifiers, performance metrics, API configuration,
  input and output sizes, events and error codes; diagnostics and
  analytics, encrypted in transit, not shared; no book text). The Play
  full description must carry Google's attribution and disclaimer from
  <https://cloud.google.com/translate/attribution>. F-Droid metadata must
  not advertise it.
- Production publication is automated for every final release. Before the
  first one, review the whole listing in the console (copy, graphics,
  screenshots, contacts, privacy URL, all declarations, countries) and
  verify the privacy URL anonymously. Starting copy lives in
  `fastlane/metadata/android/en-US/`; refresh it when stale. Screenshots
  sync automatically, the rest is copied by hand.

Play screenshots. The repository owns the phone and tablet screenshots.
After a final release's production promotion succeeds, the workflow runs
`fastlane android screenshots`, comparing
`fastlane/metadata/android/en-US/images/` with Play's in order: an
unchanged set touches nothing, anything else is replaced to match the
repository (including console edits). RCs never touch it, since the
listing is shared by every track. Success means the edit was committed,
not reviewed. A failure warns in the job summary without failing the
release; the next final release catches up, or run:

```bash
fastlane android screenshots version_code:19
PLAY_VALIDATE_ONLY=true fastlane android screenshots   # dry run
```

- The dry run uploads into an edit that is validated then dropped.
- Play rejects alpha channels and a long side over twice the short one.
  `hack/screenshots` runs `hack/store-images` on every image it files
  (flatten alpha, pad phone shots to 1200x2400 by repeating edge pixels);
  the lane runs `hack/store-images --check` before opening an edit.
  F-Droid reads the same padded files from the tag.
- Never run the lane by hand or edit console screenshots while a release
  workflow is running: both open Play edits and only one wins.
- Needs *Manage store presence* on Liseur (app-level grant).

The signing key. Play App Signing holds the same key as the GitHub
release, enrolled from `pass` via Google's PEPK tool, so Play and
release-page builds share a signature and readers can switch without
uninstalling. The key cannot be swapped without a rotation request.
F-Droid also publishes an APK with this key grafted onto its reproduced
build (see *F-Droid readiness* below), so all three channels
interoperate. To recreate the PEPK export:

```bash
d=$(mktemp -d) && umask 077
pass show android/liseur.keystore.p12 | base64 -d > "$d/release.p12"
java -jar pepk.jar --keystore="$d/release.p12" \
  --alias="$(pass show android/liseur.keystore-alias)" \
  --output="$d/output.zip" --include-cert \
  --rsa-aes-encryption --encryption-key-path=encryption_public_key.pem
```

`pepk.jar` and `encryption_public_key.pem` come from the console; delete
the temporary directory afterwards.

### The two edge-to-edge advisories

Every release raises two Play Console warnings; neither is a defect:

> Edge-to-edge may not display for all users.

> Your app uses deprecated APIs or parameters for edge-to-edge.

- The first applies to every app targeting SDK 35+ and asks only for
  testing. `MainActivity` and `ReaderActivity` call the no-argument
  `enableEdgeToEdge()`; no theme sets `android:statusBarColor` or
  `android:navigationBarColor`; the manifest has no opt-out. Keep it so.
- The second is bytecode analysis. Our code calls none of the deprecated
  setters; every hit is a version-guarded backport inside androidx
  (`EdgeToEdge`, `WindowCompat`, `WindowInsetsControllerCompat` in
  `androidx.activity:activity` and `androidx.core:core`). Treat it as
  noise; do not hand-roll around androidx to silence it. Re-check when
  androidx moves:

```bash
activity_version=$(sed -n 's/^activityCompose = "\(.*\)"/\1/p' gradle/libs.versions.toml)
unzip -p "$(find ~/.gradle/caches/modules-2/files-2.1/androidx.activity/activity/"$activity_version" \
  -name '*.aar' -print -quit)" classes.jar > /tmp/a.jar
unzip -p /tmp/a.jar '*.class' | grep -ao setStatusBarColor | wc -l
```

Use the first advisory as a prompt to actually test insets. Screens with
a keyboard or that must avoid a cutout use `contentWindowInsets =
WindowInsets.safeDrawing` (`systemBars` omits the IME and the cutout).
Test with an emulated cutout:

```bash
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall
adb reboot   # the display picks the cutout up on boot, not on enable
```

### Release notes

Two different texts per release:

- F-Droid changelog:
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, written
  by hand, max 500 characters, passed to `hack/release` as the release
  notes argument.
- GitHub release notes: generated by `hack/generate-release-notes`
  (Gemini) from commits since the previous tag, led by the changelog,
  using linked PR descriptions and carrying over PR screenshots resized
  to `SCREENSHOT_WIDTH`; changes link back to those PRs. Commit
  diffstats are omitted; transient failures get bounded retries.

`hack/release` generates the notes before tagging a final release
(`--range`, `--changelog`), opens them in the editor, and commits them as
`docs/release-notes/vX.Y.Z.md`; the workflow publishes that file as is.
If Gemini fails locally, `--copilot` uses `copilot -p` (`gpt-6-luna`, or
`LISEUR_NOTES_COPILOT_MODEL`). If both fail or the file is saved empty,
nothing is committed and the workflow generates the notes itself.

In the workflow, a missing `GEMINI_API_KEY` or failed request falls back
to the hand-written changelog; notes never block a release. The key comes
from `pass` under `google/gemini-api` and is uploaded to the release
environment by `hack/release --sync-secrets`.

Release bodies stay editable after publishing:

```bash
GEMINI_API_KEY=$(pass show google/gemini-api) \
  hack/generate-release-notes --output notes.md v0.2.0
gh release edit v0.2.0 --notes-file notes.md
```

### Never delete a final release or release tag

Published releases are immutable, and a tag that has carried a release can
never be reused, even after deleting release and tag. Fix a bad final
release by bumping the patch version and releasing again (`v0.1.0` is
burned; the first published version is 0.1.1).

Exception: `hack/prune-prereleases` removes obsolete RC release pages and
their APK assets but never the tag. Neither it nor the workflow may use
`gh release delete --cleanup-tag`.

### What F-Droid checks

Pushing to the `fdroiddata` metadata merge request starts a pipeline that
can fail after `hack/release` reports success.

- `fdroid rewritemeta` fails unless `metadata/com.chmouel.liseur.yml` is
  byte-identical to its own formatting, trailing newline included.
  Send files through JSON APIs with `jq --rawfile`, never
  `--arg content "$(cat file)"` (command substitution strips trailing
  newlines).
- `hack/release` waits for the metadata checks and stops naming the
  failure. It does not wait for `fdroid build` and `check apk` (about 25
  minutes); it links the pipeline, so check it before assuming the
  release landed.
- An `fdroid build` failure usually means reproducibility; reproduce with
  `hack/verify-reproducible` (see *F-Droid readiness*).

### Store assets

Screenshots and icon are regenerated, never maintained by hand:

```bash
hack/screenshots --setup    # build the demo shelf first, then capture
hack/screenshots            # capture from a device already set up
hack/screenshots --setup-only   # build the shelf and stop, to check it
hack/screenshots --class tablet  # file the captures as a tablet set
hack/screenshots --no-dictionary # skip the one capture that needs the network
hack/icon                   # fastlane icon.png, from the vector drawables
hack/feature-graphic        # fastlane featureGraphic.png, from the brand emblem
```

- `hack/screenshots` drives a device over adb and writes raw captures to
  `docs/screenshots` and Play-acceptable copies (via `hack/store-images`,
  see *Play screenshots*) to fastlane. It finds controls by text and
  fails when one is missing.
- Two sets: `phoneScreenshots` (full tour, shown on `docs/SCREENSHOTS.md`;
  the README embeds three by name) and `tenInchScreenshots` (three
  tablet-only views, in `docs/screenshots/tablet`). The set is picked by
  device width (under 600dp is phone); override with `--class`.
- `--setup` downloads [Standard Ebooks](https://standardebooks.org)
  public domain editions, grants the folder via the real picker, leaves
  the first book part-read with three highlights (one longer than four
  lines), a note and a bookmark, verifies each in the database, and seeds
  six weeks of `reading_sessions` via `run-as sqlite3`. Run it once.
- The definition card needs network; the run enables lookup, retries
  words until one has senses, and restores the setting. It fails rather
  than keep a stale image; `--no-dictionary` marks an expected offline
  run.
- Read aloud is captured with device voices; the run restores the
  previous service.
- `--empty` wipes app storage, captures the empty library, and stops. Run
  it whenever the empty state changes.
- Published images are light theme only. `11-reading-dark` and
  `17-empty-library-dark` are captured but not filed with fastlane or the
  README.
- Sign out of calibre-web first unless the server holds only publishable
  books. The script never writes to it.

Inspect the images before committing:

```bash
montage docs/screenshots/*.png -tile 6x2 -geometry 320x+6+6 /tmp/sheet.png
```

## Translations

English UI copy is `app/src/main/res/values/strings.xml`, with `values-fr`,
`values-es`, `values-ru`, `values-it`, `values-de` and `values-b+zh+Hans`
beside it. `play`-only strings live in `app/src/play/res`, same locales.

- New text goes in English first, then every locale; read it with
  `stringResource` / `pluralStringResource`. Brand and product names are
  `translatable="false"` and omitted from locale files.
- Russian plurals need `one` / `few` / `many` / `other`; Chinese only
  `other`.
- The app follows the system language, falling back to English for
  missing keys. `resourceConfigurations` must not freeze the APK to a
  language list.
- The in-app language picker (`ui/settings/AppLocales.kt`) sits at the
  end of Reading appearance's Advanced section, not the "Aa" sheet, since
  changing it restarts the activity. On 13+ `LocaleManager` is the only
  record (`generateLocaleConfig` lists `values-*`;
  `res/resources.properties` names English the default), and the
  platform's `LOCALE_CHANGED` redraws widgets. Below 13 it is a
  `SharedPreferences` entry applied in each UI activity's
  `attachBaseContext` via `applyOverrideConfiguration` carrying only the
  locale; a new UI activity needs the same override. Widget text then
  keeps the system language; `LiseurApplication` sets the process default
  locale at startup and after every configuration change so dates and
  week starts follow the choice.
- A new language also goes in `AppLanguage` and the Makefile `locale`
  lists.

See [`docs/TRANSLATING.md`](docs/TRANSLATING.md).

## Launcher shortcuts

Static shortcuts Continue reading, Library and Reading stats, available
before first launch, defined in `res/xml/shortcuts.xml`. The dev overlay
targets `com.chmouel.liseur.dev` so it cannot open the production app.

- Continue ignores automatic resume and the last-screen flag. It picks
  the latest eligible local book by the later of opening and reading time
  (synced reading included). Hidden, archived, unavailable, finished, or
  at/above `FINISHED_PROGRESSION` (97%) books are excluded. A saved
  locator counts as reading without a progression; an empty placeholder
  row does not and its timestamp is not ranked. No candidate opens
  Library.
- Android starts static shortcuts with `NEW_TASK | CLEAR_TASK`; each
  replaces the app task and Back returns to Library; accepted reader
  writes survive task destruction. Continue waits for
  `ReadingPositionPublisher.flushLastReader()` to succeed before querying;
  a failed save opens Library with an explicit error.
- `ui/launch/` owns one request stream for shortcuts and widgets: latest
  wins, older async results cannot navigate. Exported `MainActivity`
  accepts only the three fixed actions; widget book targets enter through
  unexported `WidgetLaunchActivity`, never public extras, and a
  downloaded-book widget tap opens the reader directly. Any fresh reader
  start clears a pending request and stops a late server download from
  opening. An unfinished shortcut survives recreation; handled ones never
  replay.
- Labels are translated, but the launcher may show them in the system
  language rather than the in-app one.

Manual checks use a disposable emulator and the real launcher menu: menu
before first open; all actions from cold, Library, Settings and reader;
repeated taps and Back; dev/production isolation; Continue with automatic
resume off, an ineligible newest book, and an empty shelf; move then Home
in paginated and scrolled modes, and confirm the saved locator reopens and
the old session ends once; rotation; a widget tap superseding a pending
continuation; ordinary startup; translated labels. Flag-less `adb`
dispatch does not exercise static-shortcut task replacement.

## Architecture

`AGENTS.md` holds the short agent rules; this document is the source of
truth for the architecture and invariants below.

- Readium Kotlin Toolkit (`readium-shared`, `readium-streamer`,
  `readium-navigator`, `readium-opds`) parses, renders and reads OPDS.
  `readium-lcp` is excluded: it needs proprietary liblcp (not F-Droid).
- calibre-web: OPDS for browse/search/download, Kobo sync
  (`/kobo/<token>/v1/...`) for percentage position sync.
- Komga: its REST API, `POST /api/v1/books/list`,
  `GET /api/v1/books/{id}/file`, and `GET`/`PUT
  /api/v1/books/{id}/progression` syncing a full Readium locator.
- One server at a time. `data/remote/` holds provider-neutral contracts
  (`CatalogSource`, `FileSource`, `ServerSetup`, `PositionSync`);
  `data/calibre/`, `data/komga/`, `data/bookorbit/` implement them and
  `RemoteRouter` picks by `ServerKind`. All share
  `domain/ReadingStateMerge.kt` for conflict rules.
- BookOrbit: REST under `/api/v1` with a session, `POST /books/query` and
  `GET /books/files/{fileId}/download`; chosen files are remembered in
  `book_orbit_binding`. Browse and download only, no position sync (see
  [ADR 0038](docs/adr/0038-bookorbit-as-a-remote-provider.md) and
  [BookOrbit protocol](#bookorbit-protocol)).
- Single `:app` module, manual DI composition root, `ViewModel` +
  `StateFlow`, Room + DataStore.
- Main-activity screens are a Navigation 3 back stack of
  `ui/navigation/Route`s in `NavDisplay`, saved with our own `Saver` (no
  kotlinx-serialization). Back pops the stack. Predictive back except on
  e-paper (all transitions off). Only the top entry gets an enabled
  navigation-event dispatcher, so a `BackHandler` never fires while
  animating out or previewed.
  ViewModels stay activity-scoped and shared; no per-entry ViewModel
  decorator. The reader is a separate activity.
- Wide Settings uses Material's `ListDetailSceneStrategy` (not
  `ListDetailPaneScaffold`): Back pops one route (`PopLatest`), choosing a
  row replaces the open page (`openDetail`). Pages use `PaneBackButton`
  (arrow hidden beside the list); nested pages wrap in `NestedPage` to
  keep it. No pane motion on e-paper.

## Implementation invariants

Contracts whose failure can pass a narrow test while corrupting state or
changing reader behavior.

### Remote providers and reading state

- One server is connected at a time. Provider behavior stays behind
  `data/remote/` contracts; callers use `RemoteRouter`, never branch on
  `ServerKind`.
- Positions are Readium `Locator`s locally (calibre-web exchanges
  percentage; Komga and liseur-sync full locators). All providers merge
  through `domain/ReadingStateMerge.kt`.
- A renewable credential belongs to one published connection.
  `BookOrbitSession` serialises renewal and writes it only if the row still
  has the epoch and refresh token it started from; otherwise drops it.
  Refresh also happens on downloads and covers; a refused token is
  remembered and never read back from the row and resent.
- A remote file binding is chosen once: a refresh must not move a book in
  `book_orbit_binding` to another file when the server promotes another
  edition.
- Ids not globally unique are namespaced: BookOrbit and Custom OPDS prefix
  a digest of address and account, since `books.url` survives a server
  change.
- `updated_at` is when this device wrote the row (derived sync ids,
  outgoing timestamps). `read_at` is when reading happened and orders
  Recent and Continue Reading via `COALESCE(read_at, updated_at)`. A row
  with neither progression nor locator is not a reading.
- Scrolled mode: Readium's last locator may lag the viewport; refresh via
  the current scrolled-place helper before any position-dependent action.

### liseur-sync positions and accounts

- Furthest and current position are separate facts. Locally authored
  peaks live alongside `reading_progress`; complete remote operations in
  `furthest_position`, by account, work, and edition, one per operation
  identity, with optional `origin_alias` provenance in the original JSON.
  Compare valid fractions strictly (no merge tolerance); jumps count;
  rereading or marking unread never resets. Forget is the only reset.
- Migration 66 keeps each valid pre-upgrade position (locator, reading
  time, known edition) with null `peak_revision`: recoverable on request,
  never uploaded as local. A later strict increase records a local peak.
  Local peaks keep a verified `work_alias` edition even without a local
  fingerprint row.
- Observe every changes-page op, own echoes included, before picking the
  newest foreign op for ordinary reconciliation.
- Positions and heads snapshots carry a `furthest` array; its entries never
  become pending conflicts or automatic opening targets. A present array
  replaces that snapshot's cached observations; a missing one (older
  server) keeps them without claiming a lifetime maximum. "Furthest read"
  also works offline from stored observations.
- Forget is online only and server first
  (`POST /v1/works/{id}/furthest/forget` with the preview's
  `furthestUptoSeq`; [ADR 0059](docs/adr/0059-forget-the-furthest-position.md)).
  The server ranks only ops above a per-work floor and leaves the log
  untouched. The client keeps the highest `furthest_floor` /
  `furthest_floors` seen (positions, heads, every changes page) on
  `work_alias`, drops `furthest_position` rows at or below it, and
  `observe()` ignores ops at or below it.
- A rising floor restarts this device's peak from its current place only
  if the server already holds it (acknowledged, or null `peak_revision`);
  an unsent peak is kept. The Forget device always restarts, as does a
  peak retry accepted at or below the floor. A peak first sent as current
  is retried with that op's bytes (`peak_delivery.sent_op`), never a new
  peak id.
- Before a coalesced lower current position, deliver the stored local peak
  as a deterministic, separately namespaced op. `peak_delivery` tracks only
  that acknowledgement, never a general queue. Make a fresh current
  revision owed before each unacknowledged peak attempt; send current only
  after the peak is acknowledged. A missing answer retries the same peak; a
  failed current leaves current owed. Remote observations are never
  uploaded as local peaks.
- Historical adoption checks candidate, account, work, edition, and local
  revision in one transaction. The reader captures its scrolled place
  first; that capture's one revision is usable only if the offered row was
  still current and the passage unchanged; other writes or reader
  movement invalidate it. Adoption uses normal locator
  restoration and way-back. Opening-time farther-current resolution is
  unchanged; other providers do not offer this. Discarded positions and
  history compacted before lifetime retention are unrecoverable.
- liseur-sync is an append-only log. Apply a changes page and advance
  `remote_server.sync_cursor_seq` in one transaction, never earlier.
- Op ids derive from device, work, revision; session ids from device and
  stored local session id; payloads from stored state. Retries are
  byte-identical: no random ids, no `pending_ops` table.
- Reconnect with the stored device id. A pasted token reconnecting as
  another device can get `conflict`; conditionally advance the book's
  revision from the sent one, then retry.
- Naming work is bounded: hash/catalog/batch resolution, seeds, incomplete
  outcomes, retries, and refusals follow the coordinator, never call-site
  logic.
- A batch refusal concerns only the named item: keep others pending, split
  oversized batches, leave unknown codes pending. Persist a session refusal
  only for a known permanent reason; an unnamed refusal settles nothing.
- Account key `liseursync|<url>|<account_id>`. Rekey peer-scoped tables
  transactionally in `carryPeerState`; clear via
  `RemoteAccountRepository.forgetSyncPeer()`.
- Uploads need both server and folder capability. Upload is adoption: keep
  local `books.url`, link only remote identity and download URL.
- Live notifications are topic-only hints through `data/remote/`, on a
  foreground connection separate from the full-sync debounce, carrying
  account and credentials, never cursor or timestamp. Topic refreshes share
  `PositionSyncCoordinator`'s turn lock; an invalidation mid-refresh stays
  owed. Never `syncAll()` for one or bypass cursor and annotation
  reconciliation. Signal local annotation edits after commit. An incoming
  position waits for reader resume; catch-up binds the original account,
  peer, fingerprint, and local revision
  ([ADR 0023](docs/adr/0023-position-sync-versus-whispersync.md)).

### Settings and statistics

- Settings stay on the device
  ([ADR 0041](docs/adr/0041-settings-stay-on-the-device.md)); this device
  is the only writer of its server copy. A key never stored on the account
  is restored; anything else that differs is uploaded. Never read settings
  from a server not answering `scope: device`.
- Never apply a restored setting that changes the open page while a book
  is open (`SyncableSetting.affectsOpenBook`, per write). Device-shaped
  settings are never backed up.
- Validate values before a batch request: reject what the server cannot
  store; keep the absence sentinel (no server delete).
- Re-check the connected account before every network side effect and
  before committing its response.
- Server statistics are optional: failures stay null and silent.
  Cross-device counts only when stored evidence proves the merge, else the
  local-only result with provenance.

### Annotation synchronization

- An annotation id derives from content only until editable; then
  `annotation_sync` stores the server acknowledgement and exact pending
  request bytes, written before the call and replayed verbatim via
  `postRaw`. No queue: it would be a second truth drifting from the live
  table.
- The acknowledged fingerprint excludes request metadata (`base_rev`,
  `edition_sha`); the pending fingerprint identifies the request so an
  answer cannot overwrite a newer local edit.
- Freshness is server `seq`, never `rev` (recreation restarts `rev`).
- Order: settle, pull, reconcile, push, deletes. Pending set and cursor
  are account-wide even in a book-scoped run. Conflicts are server-wins.
- Reconcile a work before pushing, push only marks in that pass's live
  set, with the same `offerable()` predicate for both. Compare conflicts
  with the sent content, not a newer row.
- Absent, null, `""`, and `{}` locators mean no locator. `BOOK_NOTE`
  (standalone) stays distinct from passage `NOTE` and keeps the local
  `bookId`.
- Annotation URLs accept opaque ids but decline `.` and `..` as delete
  targets. Re-read account and home alias in the transaction committing
  each response; check the account before every request.
- Removing a book keeps its annotations and sync rows; only
  `BookRemoval.contentReplaced()` clears them, when another file takes the
  path.

### Reader behavior

- Dictionary lookups follow explicit grammatical links (en, fr, es, nl
  Wiktionary) one step only, matching source language and part of speech
  (`WiktionaryClient`). Follow-ups share fetched entries, add at most four
  HTTP requests (fallbacks and retries included), and refuse redirects.
  `DefinitionSheet` keeps the original text and reports unresolved
  meanings. Ordinary definition links never trigger lookups. Fixtures:
  `DictionaryInflectionTest`.
- `AppSettings.dictionaryAltLanguage`: null = automatic,
  `ALT_LANGUAGE_OFF` ("none", never a valid tag) = off, else a tag.
  `altLanguageFor`: a chosen tag always applies; automatic uses the UI
  locale only when the book declares another language.
  `translationTarget` drops a target equal to the edition's language.
- With an alt language, Define also fetches translations for a sense in
  the edition's language and shows a pill (flag via
  `ULocale.addLikelySubtags`, composable only; first translated word).
  `WiktionaryClient.translations` reuses `define`'s `page/html`, follows
  only an exact `term/translations` subpage, at most two requests, no
  redirects. `parseWiktionaryTranslations` returns whole-word sections in
  page order, never per sense. The pill looks up the target's Wikimedia
  Wiktionary (`translationSite`, never a mirror) with
  `define(strict = true)`, which keeps only the section naming the source
  language; without an entry or site it shows the source table, whose
  words open a third sheet. Stacked sheets: transparent scrim, Back
  closes the top one only.
  Fixtures: `WiktionaryTranslationsTest`.
- Bookmark identity is `samePage()` on the two locators, never the rounded
  page number. Duplicate reading-order entries resolve by occurrence;
  scrolled books use the stable Readium position.
- The paginated reflowable footer is display-only
  ([ADR 0036](docs/adr/0036-the-footer-counts-screens.md)):
  `SectionScreenProgress` and `BookScreenEstimate`, keyed by reading-order
  index. Reset on layout change, reject stale measurements, never store or
  sync the page count.
- `readingColorScheme()` themes the whole reader activity: no dynamic
  color, opaque mixes, transparent `surfaceTint`, black `scrim`. Only the
  loading indicator keeps the app accent (`loadingAccentOn()`) while it
  holds 3:1 against the page, else the page's; both `ReaderLoadingScreen()`
  call sites get the same value, and its tone still follows the page.
- `PageTurnStyle` has three values; e-ink and system-motion overrides live
  only in `pageTurnStyleOnScreen`. A dragged lift turn photographs the
  publication view, restores the exact locator, and abandons on lifecycle
  changes.
- Chrome reaches the screen edge: navigation-bar insets stay inside
  `ReadingScrubber` (its spacer when the panel is absent); no edge fades on
  e-ink.
- While the way-back pill shows, system Back takes it via `takeJumpBack`,
  like a tap (#275). Bars stay sticky: with chrome hidden the first edge
  swipe reveals them, the second goes back. Register the jump
  `BackHandler` before the overlays' so they close first.
- Runtime page repair writes only token-owned attributes; `WideContentFit`,
  `FootnoteLayout`, and `SelectionHandleFix` stay idempotent and preserve
  authored classes and markup.
- The selection bar never dismisses on an outside touch during a live web
  view selection: grabbing a handle (a separate window) is an outside
  touch (#257). Only a tapped mark, with no platform selection, relies on
  the outside touch.
- The selection bar keeps one height; Translate and Read aloud stay in its
  scrolling action row, not behind More
  ([ADR 0049](docs/adr/0049-selection-actions-stay-in-main-row.md), which
  amends only ADR-0047's More-row decision; its one-colour default
  stands).
- CSS `prefers-color-scheme` follows the reading page: `showPagesAs()`
  forces a `PageColorScheme` style (`android:isLightTheme`) on the reader
  theme and sends a configuration change to on-screen WebViews, no reload
  (#256). API 29+; below, pages follow the system.

### Read-aloud

- Engine, player, settings, device voices and the speech server provider
  live in `app/src/main/kotlin/.../tts/`; Gemini (`GeminiSpeechService`,
  client, voices) in `app/src/play/`. The reader sees only
  `ReadAloudFeature` and `OpenBookHandle`. Nothing in `main` may name
  Gemini; see `docs/adr/0044-read-aloud-in-the-f-droid-build.md`.
- A playing session owns the reading place; only the voice writes it. A
  reader page move keeps the voice playing, is not saved, and the page
  stops following until the spoken sentence is on screen again or
  pause/resume; pausing returns to the sentence heard. Auto-scroll and the
  voice exclude each other: starting one pauses or disarms the other.
- Checkpoints go through `prepareLocator` (same `total_progression` as a
  page turn); local while playing (`signalSync = false`), synced on pause
  or stop. A heard sentence is not an exact anchor: while
  `awaitingPageCapture` is set the reader re-captures the page once the
  voice stops and saves it as the exact anchor (and BookOrbit CFI); the
  ViewModel must not drop it as unchanged. On opening, the wide-content
  fit restores to the gate's non-exact target instead of capturing.
- Providers (Read aloud screen under Reading & navigation): Gemini (Play
  only), device voices, and "Speech server": any OpenAI-API server, or
  Gradium, on the External services and API keys page. Each is a
  `SpeechService` (`GeminiSpeechService`,
  `DeviceSpeechService`, `OpenAiSpeechService`) owning its rows, voice
  catalogue and `SessionVoice`; the flavor's `ReadAloudFeatureFactory`
  passes them to `SpeechReadAloud`. All return 24 kHz mono 16-bit PCM, so
  engine, cache and `AudioTrack` output are shared. Protocol details
  (URL roots, `response_format`, model/voice listing per host, decoding)
  are in `OpenAiTts`, `OpenAiTtsClient`, `WavPcm`, `Mp3Pcm` and
  `docs/adr/0043-openai-compatible-read-aloud.md`.
  - Device voices are the default in both builds; saved choices stay. With
    no offline voice installed read-aloud stays unavailable; it never
    switches to a network provider on its own. Device voices are offline
    only (`DeviceVoices.offline`); `read_aloud_device_voice` blank picks
    `DeviceVoices.PREFERRED`, else the engine default.
  - Server requests add `/v1` only when the path has no API version; a
    listed server's id and voice scope keep the older rule (`/v1` unless
    the path has `v1`) so saved servers, backups and synced settings
    match. A 400/422 naming `response_format` retries as `mp3`, then that
    (root, model) asks mp3 first. Error bodies are read only to classify.
    The DeepInfra schema is fetched without the key.
  - Gradium is three presets (default, EU, US hosts, `/api` root, no
    `v1` added) and host branches in `OpenAiTtsClient` (`GradiumTts`):
    `x-api-key` header, one model `default`, voices from `voices/` with a
    name and language each (`SpeechVoice`, shown by
    `VoiceLabel.of(SpeechVoice)`), speech from `post/speech/tts` as WAV
    with `only_audio`, no MP3 fallback; the key check lists voices; a
    500 body matching `error from server 1008` is a refused key. Nothing
    outside the client branches on the host; see
    `docs/adr/0062-gradium-through-the-speech-server.md`.
  - api.openai.com voices are the static `OpenAiTts.openAiVoices`; its
    `audio/voices` lists only custom voices and is never asked. The
    monthly `openai-voices` workflow (`hack/openai-voices`) opens a PR
    when OpenAI's API spec names a voice the list lacks; it only adds.
  - Picking a model saves it with a listed voice in one write
    (`setSpeechServerModelAndVoice`). Voice language flags
    (`VoiceLabel.flag`) are decorative and skipped by screen readers.
    Voice samples go through `SpeechReadAloud.preview`, which pauses any
    session; Gemini plays one only on "Hear this voice" (billed). Gemini's
    model (`read_aloud_model`, blank is `GeminiTts.DEFAULT_MODEL`) comes
    from the key's `v1beta/models` ids containing `tts`, or is typed.
    "Choose voices" narrows to the server's `voices` (empty offers all;
    the voice in use stays).
    "Test connection" (`OpenAiSpeechService.test`) fetches both lists and
    synthesizes one word.
  - The speech server provider runs one request at a time with long
    timeouts; notices name the host. Groq `model_terms_required`
    (`SpeechError.TermsRequired`) and a text-only server with no speech
    route (`SpeechError.NoSpeech`: Ollama's or llama.cpp's 404, LM
    Studio's 200 with `Unexpected endpoint`) stop the session like a
    refused key; a 404 with a string error code or naming a model or
    voice keeps its usual meaning.
- Servers and keys live on `ServicesScreen`; `ServerConnections` owns
  them and the per-origin generation that drops replies from before a
  change.
  - `ServerConnection(id, name, url)` in `ai_servers`; id is
    `OpenAiTts.baseUrl`. Duplicate URLs are refused; an edited URL
    rewrites id, read-aloud reference and server state in one DataStore
    edit.
  - Unparseable servers or references to unlisted servers are kept byte
    for byte and block edits; read aloud falls back to device voices
    without rewriting its reference. A restore leaving any reference
    unlisted fails whole and changes nothing.
  - A connection or key change stops only that origin's playback,
    previews and requests (or Gemini's); a restore carrying servers or a
    server choice counts as a change to every previously listed origin.
    Deleting a server points read aloud at device voices and drops a key
    only when no other server shares its origin.
  - Settings: `read_aloud_provider` (`openai` for a server),
    `read_aloud_server`, `read_aloud_server_state` (per-server model,
    voice, offered voices), `read_aloud_voice_preferences` keyed by API
    root. Legacy `speech_server_*` keys migrate in a single edit; an older
    backup's legacy keys merge by URL (`ServerSettings`).
  - Fields save on Done, focus loss (`Modifier.onLeaving`) and dispose.
    Key saves go through `KeyCommits` in order; a `KeyDraft` belongs to
    the server shown when started; paste reads the clipboard only when
    tapped. New keys are checked against `/models`, fenced on generation,
    a draft only against its own origin; success says "Server reached".
  - Server and model commits share one lock and generation counter so a
    superseded choice never writes; the write runs in
    `ServerConnections.unlessChanged`, so no key or address change lands
    between check and write. Editor saves use `ServerConnections.save`
    with a token per opening, so an editor recreated or closed mid-save
    follows the server it was adding or moving.
  - A new or edited server stays in `read_aloud_unsettled_servers`
    (local, not backed up) until a model and voice are chosen; until then
    a refresh replaces the model. The services page's test only checks
    the key is answered.
- Engine callbacks run on the main thread. A failed sentence pauses with
  a notice; play retries it; nothing is skipped. A refused key or missing
  voice stops the session.
- Pause keeps the sentence's audio or in-flight request in
  `SpeechTtsEngine` and read-ahead in the cache
  (`UtterancePrefetcher.pause` only stops reading further). Resume
  rewinds `REWIND_FRAMES` (a second) without refetching; a skip, jump,
  voice change or retry restarts the sentence.
- Keys (Gemini, speech servers) live in `noBackupFilesDir`, encrypted
  by `SecretCipher`, one file each, per origin for servers (`ServerKeys`,
  `openai-key-<hash>`; old `openai-key` is deleted). Each speech request
  reads only the key stored for the origin it calls. Keys, request bodies
  and audio are never logged. Provider, servers, server state and
  per-language voices are in the settings backup, not liseur-sync
  settings sync. Listening is not reading time.
- While a session exists `ReaderActivity` routes volume keys to
  `STREAM_MUSIC` instead of turning pages.
- The page shows only the spoken sentence's highlight; player controls
  follow the chrome (`ReadAloudFeature.Player`'s `controls`), notices
  show on their own. A tap on the highlight toggles chrome and never
  turns the page; on a translated page `SpokenMarkTaps` hit-tests the
  mark before `ReaderTapZones`, which gets only the misses.
- A session's language comes from `dc:language` at start
  (`SpeechLanguage.ofBook`; none, `und` or several is undecided) and feeds
  the tokenizer, `SpeechTtsPreferences` and prefetcher. The text is never
  inspected and the language never changes mid-session on its own.
- Each service gives a `VoiceCatalogue` (Gemini via `GeminiLanguages`).
  A failed server list yields `failed` with saved, ticked and remembered
  voices; a server with no list still offers its typed voice.
- `VoiceResolver` order: remembered voice for the book's language, global
  voice if it speaks it, exact regional tag, any for the language; ties go
  to the provider default, then list order. Unclassified voices are used
  only once remembered; the service never changes. If undecided the start
  waits on a `PendingChoice` and the voice sheet opens; dismissing,
  leaving, a new start or service change gives up and releases the book
  without touching the position.
- `read_aloud_voice_preferences` holds (provider, context, model,
  language, voice); `context` is the device engine package or normalized
  server API root, `model` the server or Gemini model. Explicit picks
  write the global voice and language entry in one edit
  (`AppSettingsRepository.editReadAloudVoice`) after checking the scope is
  still the one shown; automatic picks never write.
- `ReadAloudVoiceSheet` changes nothing until Apply, which remembers the
  voice, `SpeechCache.swap`s the cache and `ReadAloudPlayback.replay`s the
  sentence in its play/pause state (`recut = true` after a language
  change). A settings pick is heard the same way, in the session's
  language.
- Speed (`read_aloud_speed`, 0.75× to 2×) is applied by
  `AudioTrackPcmOutput` via `AudioTrack.playbackParams`: keeps pitch,
  applies mid-sentence, cached audio stays valid; the stall deadline
  allows for the slowest speed.
- `read_aloud_sentences_per_request` (1 to 5, default 1) is read at
  session start; `BoundedSentenceTokenizer.factory(n)` groups within 600
  characters and is shared by navigator and prefetcher. Highlight and
  previous/next move by group. Clamped to
  `AppSettings.SENTENCES_PER_REQUEST`; backed up
  (`BackupValueType.INT`), not synced. `SelectionTarget` matches a
  paragraph-opening selection by what follows it.
- The sleep timer (5 to 60 minutes or end of chapter) lives with the
  session, counts on `SystemClock.elapsedRealtime`, pauses when it fires
  and ends with the session. It keeps running with the screen off and
  while the device sleeps. End of chapter pauses before the next
  reading-order resource and clears itself.
- The player is a `ChromeCard` above the scrubber; its voice chip shows
  `ReadAloudSession.choice`. While waiting for audio
  (`ReadAloudUi.preparing`, `SpeechObserver.onWaiting`) `VoiceWait`
  replaces play/pause and a "Preparing the voice" pill shows with hidden
  controls, after 400 ms, lingering 150 ms, still on e-ink or with
  animations off.

### Translation

- Selecting text offers Translate next to Search and Share when the
  chosen service can translate; the result opens in `TranslationSheet`,
  whose language lists open in the same sheet, never a second one. Code
  is in `app/src/main/kotlin/.../translate/`; the reader sees only
  `TranslateFeature` (`None` when unwired), built by the flavor's
  `TranslateFeatureFactory`. Gemini and ML Kit code lives only in
  `app/src/play/`; `main` knows them only as service ids and settings.
- Services: the phone (`DeviceTranslationService`, Android 12+
  `TranslationManager`, no library or Play services), an OpenAI chat
  completions server from External services and API keys
  (`ServerTranslationService`), and Play-only Gemini
  (`GeminiTranslationService`, read-aloud key) and ML Kit
  (`MlKitTranslationService`). Opt-in: no Translate action until a
  service is stored. Play's library setup sheet waits for system
  translator availability before defaulting to it or ML Kit, and shows
  ML Kit consent first; FOSS users choose in Settings. Deleting the
  chosen server falls back to the device, never another network service.
  Keys and servers live on External services and API keys; the
  Translation page holds service, model and target.
- Device: `TranslationManager` calls run off the main thread with a
  bounded wait (stuck reads as no pairs), translators are destroyed when
  a request ends or is cancelled, and pairs are re-queried on resume. A
  pair needing a pack offers "Download languages" when the system has a
  screen for it, else says where to go.
- ML Kit (Play only): SDK behind `MlKitEngine` (faked in tests).
  `app/src/play/AndroidManifest.xml` removes `MlKitInitProvider`; the
  engine calls `MlKit.initialize` on first use, so nothing runs until
  chosen. Translating never downloads (`NotDownloaded`); the `Downloads`
  dialog asks, checks Wi-Fi, and uses `requireWifi()`. English is built
  in, not deletable. `MlKitLanguages` offers zh only as zh-Hans and pt
  only as a source. A page run checks the pair before every sentence.
  Google attribution: the action reads "Translate with Google"
  (`TranslationService.action`/`pageAction`) and the `Credit` badge
  (light, dark, greyscale) shows by every ML Kit result, including the
  page bar and translated-page mark; `PageSwap.by` keeps it while any
  ML Kit sentence is shown. Cache identity is the service id, no model.
  `app/proguard-rules-play.pro` keeps Firebase `ComponentRegistrar`
  no-arg constructors, or release crashes on first use. Smoke-test ML
  Kit on a release build after upgrading it.
- Languages (`TranslationLanguages`) keep script and Portuguese region
  only; flags only for languages with a clear country, hidden from
  accessibility. Source is the book's single declared language, else the
  sheet asks unless the service detects it. Target follows the app
  language until picked (`translation_target`). A passage already in the
  target or over `TranslationPrompt.MAX_CHARACTERS` is never sent; the
  former opens the target list once and saves the pick. `TranslationStep`
  decides this as a pure function.
- Network requests follow the read-aloud clients: key of the origin
  called, no redirects, local-network check at request time, bounded
  replies (`TranslationHttp`), cancellable on `Dispatchers.IO`, no
  logging of keys, passages or translations. The system message fixes
  the task; the passage goes as data between markers it cannot close
  (`TranslationPrompt`); replies show as plain text. A leading `<think>`
  block is dropped, unterminated reasoning is truncation. Timeouts match
  read aloud (see [docs/LOCAL_AI_SERVERS.md](docs/LOCAL_AI_SERVERS.md)).
  Temperature is 0 unless Advanced options set it.
- `Thinking` disables thinking (translation and companion) on known
  hosts (Z.ai, OpenAI, Gemini, Groq, DeepInfra, OpenRouter) with a
  ladder of fields; a 400/422 naming thinking or reasoning tries the next
  rung, then none. Only a success remembers the rung, in memory. Every
  attempt checks the service generation and counts against the
  companion's request bound.
- A TranslateGemma server model gets its trained single user message
  without earlier text.
- `TranslationError` separates refusal, empty, quota, malformed, refused
  key, unreachable and `ModelNotFound`. A chat 404 without an
  OpenAI-style error is `NoEndpoint` and opens server settings.
- `TranslationRequests`: a key or address change while a request is out
  discards the reply and asks again; each attempt checks the server it
  used; the sheet drops replies for a passage, language, service or
  model it no longer shows.
- `translation_provider`/`translation_server` reference a listed server.
  `translation_server_state` holds each server's translation model and
  Advanced options (`ServerTranslationState`: temperature, earlier-text
  switch, extra instructions added to the system prompt or before
  TranslateGemma's request, `max_tokens`, Ollama `keep_alive`), each
  stored only once set; Gemini's model is `translation_gemini_model`.
  Temperature is always sent; `max_tokens` and `keep_alive` only once
  set. A 400 naming temperature retries once without it and saves
  `no_temperature` on the entry, cleared by a model change, not part of
  `pageIdentity`. `keep_alive` is seconds (number) or a Go duration
  (string). Older versions read only `model`. Deleting or moving a server
  updates these in the same DataStore edit. Backed up, not synced.
- Model lists are sorted by name and show per-million token prices when
  the list has them. Together's model type keeps non-text models out;
  Together AI is offered at `https://api.together.ai/v1`.
- "Translate the page from here" (selected-passage sheet) translates a
  reflowable book in place from the first sentence on screen
  (`scrolledPlace` when scrolling, else the page locator) in
  `TranslateFeature.pageLanguages`; the translation bar keeps Stop. The
  reader's ⋮ menu and Aa controls have no translation actions; whole-book
  preparation is in the cover's long-press actions and Translation
  settings.
- `PageTranslation` walks with `PublicationUtteranceCursor`, keeps a
  bounded number ahead of the screen, restarts on a jump, skips walked
  sentences behind the screen, and asks one at a time through a
  `TranslationRun`. A service change rebinds at the next sentence; a
  reply is cached only under the service that answered. Sentence errors
  skip it; key, quota, pair and pack errors halt with an action on
  `PageTranslationBar`.
- Context: `translation_context` (`TranslationContextSize`, Standard by
  default), device-local, backed up, never synced. `SentenceTranslations`
  builds it per sentence and answering identity
  (`SentenceTranslator.contextSize`), so page and voice send the same
  text and share one in-flight request. `PrecedingSentences`
  (`TranslatedPages.preceding`) gives a sentence only strictly earlier
  text in its resource, never itself or later; the window
  (`TranslationContext.window`) stops at the resource and falls back to
  the caller's previous sentence. Only `TranslationService.usesContext`
  services take it; a server's Advanced context switch can turn it off.
- Book details: `translation_book_context` template (`{title}`, `{author}`,
  `{chapter}`, `{percent}`; `TranslationBookContext`,
  `translate/BookFacts.kt`); unset is the current default, empty is off.
  Device-local, backed up, never synced. Sent first between `<book>` and
  `</book>` as reference only; `clean()` drops an echo. Only
  `TranslationService.usesBook` services take it, regardless of the context
  switch. `TranslatedPages.bookFacts` takes the percentage from the
  resource's start in `positionsByReadingOrder()`, never the exact sentence,
  so facts are fixed per resource and saved pages stay valid; it joins
  `pageIdentity` as `book=<hash>` only for services that take it.
- `translations.db` (`translate/SavedTranslations.kt`) is a separate
  Room DB so backup allowlists (naming `liseur.db`) exclude it. Key:
  local book URL plus SHA-256 of `pageIdentity` (versioned service id,
  server id, model, languages, `TranslationPrompt.version`, set
  reply-changing Advanced options; never `keep_alive`, display name or
  key), context and sentence; context joins last as `context=<size>`
  only when sent. Bump `TranslationPrompt.VERSION`, or its TranslateGemma
  version, when that prompt changes; context-rule wording changes the
  identities that send context through `context=<size>` and needs no
  bump. A run asks with the settings its
  identity came from; a change mid-sentence rebinds and asks anew.
  Lookup, re-lookup if the service changed, save only under the
  answerer. LRU: 50,000 sentences, 20 million characters. A lookup's
  stamp fences Clear: an earlier reply is shown, not saved.
- Whole-book translation (`WholeBook*.kt`; see
  `docs/adr/0062-whole-book-translations-stay-on-the-phone.md`): requests
  are prepared by the reader's `SentenceRequests`, so keys match. Nothing
  is scanned until a tap and nothing is sent until the reader confirms;
  cost ranges use advertised prices and are estimates. Pinned translators
  (`ServiceTranslate.openPinned`) check settings before every submission,
  never rebind or fall back, and send one-shot bodies. Invalid envelopes
  pause the job; only sentence-local refusals become failure markers.
- Bulk and reader misses share admission (`TranslationAdmissions`): bulk
  waits for submitted reader calls to save and blocks new matching misses
  until its workers settle.
- `kept_translations`/`kept_failures` are outside the LRU and Clear;
  writes are generation-fenced and persist before progress advances. A
  saved row wins over a marker; retrying failures needs confirmation;
  checks precede replacing metadata or clearing markers. Markers never
  sync (backup only). Sources must be inside the EPUB archive
  (network-denying opener).
- Play debug builds log a numeric-only `GeminiCache` summary per bulk
  run: local hits, HTTP attempts, successes and `usageMetadata` token
  totals (input, cached, output, thinking; absent is unknown, not zero),
  plus bounded prompt-prefix byte comparisons (not tokens, not proof of
  cache hits). No prompt, title, endpoint, key or reply is logged.
- Book removal is a sweep, not a delete by URL (`BookRemoval` may run in
  a caller's transaction): `AppContainer` sweeps on Room invalidation of
  `books` and at start; `sweep()` bumps the book's epoch so an open
  reader stops saving. `contentReplaced` keeps rows. Storage failures
  are a miss or skipped save, logged by exception class only; only Clear
  reports one. See
  `docs/adr/0046-translated-sentences-are-kept-on-the-phone.md`.
- liseur-sync carries the cache (`LiseurSyncTranslations`; server
  ADR-0052): only hash and translation travel. The pass runs in position
  sync after annotations, pulls before pushing, and opens the store even
  with no local translations, so a first pull fills the cache before
  translation is used. Pulled rows keep `saved_at` 0 and are never pushed
  back; local beats pulled, and a pulled row follows the account's newer
  text instead of freezing at the first copy. Per peer
  and book, `translation_sync_state` holds the `(pushed_saved_at,
  pushed_rowid)` watermark and pull cursor; save stamps never move
  backward. The watermark moves only on server acknowledgement; a
  refusal naming one item's shape is written off, any other is a
  failure. `unknown_work` re-resolution restarts the exchange.
- Clear keeps the server copy unless the account clear is asked: a
  `translation_account_clears` row (peer, work id or empty, generation),
  written with the local clear under the global `cleared` fence and sent
  before the pass reads. Success or `404` `work not found` settles it; a
  server without the routes (plain `404` or `405`) drops it; other
  failures keep it and block its works. A removal's clear moves the push
  watermark past local rows. Asked by remove/delete dialogs, the reader
  menu, Settings Clear, and `FinishedState.setFinished` behind
  `clearTranslationsOnFinish`. Disconnect or rekey carries bookkeeping
  and owed clears, not sentences. See
  `docs/adr/0050-clearing-translations-reaches-the-account.md`.
- A translated book stays so until Stop (`PageTranslationModes`,
  `page_translation_modes`, swept with the book, kept by Clear), through
  one queue in `SavedTranslations` on the application scope; a failed
  Stop shows a toast. `ReaderScreen` resumes only if nothing started or
  stopped a run on that screen, after the opening gate, with the page at
  rest and no reflow, from `scrolledPlace()` when scrolled.
  `startPageTranslation` owns the translator until the run is installed.
  `TranslatedPageMark` marks a translated page with controls down. See
  `docs/adr/0048-page-translation-stays-on-for-the-book.md`.
- `reader/PageSwaps.kt` keeps every changed text node's original so
  `restore` is byte-exact; note references keep number and link; a
  sentence mixing another link with text stays untranslated.
  `reader/TranslatedPages.kt` resends swaps each layout pass and keeps
  restoring recycled views after Stop. While translated: positions save
  without quoted text, BookOrbit is not pushed, highlights are hidden,
  and highlights, notes and bookmarks are off. See
  `docs/adr/0045-translated-pages-save-coarse-positions.md`.
- Read aloud on a translated page speaks the translation. Page run and
  session share one reference-counted `SentenceTranslations`, so speech
  continues with the reader closed; a reopened book's page run takes the
  session's translations when their languages match. Starting other
  languages stops a translated session; Stop on the bar stops the voice
  first; stopping the voice keeps the page translated.
- Translated speech keeps Readium's original publication as navigation
  authority: source cut in the source language, voice for the target.
  `ReadAloudPlayback(anchored = true)` keys speech by `SpeechKey` (href,
  selector, text before), so identical words never share translation or
  audio. `TranslatedSpeech` (`SpeechCache` `prepare`) uses the previous
  sentence as context only when adjacent (`SpeechObserver.previousOf`) and
  reuses accepted translations so page and voice agree. A pending
  translation or audio holds the voice without timeout; a failure pauses
  with `ReadAloudNotice.TranslationFailed`, never spoken in the book's
  words; play retries it, only an explicit skip passes it. Voice changes
  drop audio, keep translations. Checkpoints save the last heard sentence
  (`ReadAloudPlayback.heard`).
- The spoken sentence is marked by `PageSwaps.mark` (CSS Custom
  Highlight, else Readium decoration), hit-tested by `PageSwaps.markedAt`
  (`SpokenMarkTaps`); visibility is `PageSwaps.showing`; Follow goes to
  `TranslatedPages.shownLocator`. No exact anchor is captured after
  listening.

### Reading companion

Codex is a local name index and co-occurrence graph in the reader's ⋮
menu, in both flavors, with no provider or key. Users see it as
Companion; code, string IDs and the `codex_*` tables say Codex.

- Opening it scans the whole book, resuming from chapters cached in
  `companion.db`. Closing the panel keeps scanning while the reader is
  open; leaving the book or backgrounding pauses it.
- The On-device companion switch (Companion settings, Advanced, default
  on) off cancels scanning and hides Names, Relationships and Mentions,
  keeping the cache. With a service chosen Codex still opens for Story and
  profiles (`CodexViewModel.profiles`) and reads nothing from the index;
  with neither, the entry is hidden. The switch is backed up; the index is
  not.
- Codex hosts the character page, Profile (`CharacterProfile`) and
  Mentions, routed as in
  [ADR-0058](docs/adr/0058-codex-hosts-the-character-profile.md).
  Ask companion opens Profile and asks at once when a service is chosen
  (or there is no scan), otherwise Mentions. Name or pair taps inside
  Codex open Profile with a service, Mentions without. Search's Companion
  pill opens Names, or with the scan off the query's Profile.
  Profile's Ask runs `CompanionLookups.openAt` with Codex's frozen scope;
  pairs use `relationOf`, alias pills `CodexDetail.focus`. Closing Codex
  or leaving the lookup's character page closes the lookup.
- Before Ask, Profile shows recap claims (`recapClaims`, names folded by
  `CodexExtractor.storedName`, aliases one step only) with sources whose
  hash is unchanged (`recapSources`), read like Story's
  (`CompanionViewModel.recaps`) from any server or model. This sends
  nothing; Story's controller runs only while its list is in front
  (`CodexPanel.storyInFront`), never under a character page.
- Codex freezes the current chapter on open and shows only earlier
  chapters, even after a full scan; every count, ranking and graph node
  uses that scope, and uncertain chapter membership cannot make an edge.
- The scan saves chapter statistics with their source passages
  atomically. Cache identity: content fingerprint, chapter boundaries,
  `CodexStore.FORMAT`, `PassageText.VERSION`; bump `CodexStore.FORMAT`
  when extraction or codex evidence changes. Book removal sweeps derived
  rows and stale scan handles cannot write them back. Extraction relies on
  capitalization (no caseless scripts).
- Lookups and recaps read passages through `CodexBackedCatalog`: from
  `CodexStore.stored` when the switch is on, identity matches and every
  needed chapter is committed, else by walking the publication. Stored
  rows go through `CodexPassageRow.source()` and `Eligibility.allows`, so
  the result equals the walk (`PublicationCatalogTest`).

Ask companion opens a character profile drawn only from chapters before
the asking one
([ADR-0051](docs/adr/0051-character-profiles-use-only-earlier-chapters.md)).
Code is in `app/src/main/kotlin/.../companion/` (both flavors), settings
under Settings, Intelligence, requests through `OpenAiChatClient.chat`.
`companion_server`, `companion_server_state` and `companion_recaps` are
separate from translation's, moved and forgotten with server edits in
`ServerSettings`, in the settings backup, and not liseur-sync settings.

- `CompanionServer` picks the `CompanionService` named by
  `companion_provider` (absent means a listed server) and fences every
  request on its `CompanionChoice`. `CompanionFeatureFactory`: `foss` has
  only `ServerCompanionService`; `play` adds `GeminiCompanionService`
  (`GeminiTranslationClient.chat`, `companion_gemini_model`, fenced on the
  key's generation; choosing Gemini with no stored model saves
  `gemini-3.1-flash-lite`, while a reader already on Gemini keeps the shared
  default). Saving a Gemini key with no AI server listed validates it
  (accepting offline) and selects Gemini for the companion and read aloud
  where no provider is chosen. A provider this build lacks (a Play backup on
  F-Droid, the removed Gemini Nano `nano`) reads as not set up, never as a
  fallback. Choosing a listed server clears `companion_provider`.
- A choice carries `CompanionLimits`; a reply cut at the limit gets one
  `too_long` correction. Failures log only the error class and, for
  `CompanionError.Malformed`, fixed rule codes, never text.
  `FailureDetails` (including `TranslationError.serverMessage`, never in
  `message`, and the requests sent) stay in the panel's copyable dialog:
  never logged, cached, saved or sent. A failed panel asks again when the
  choice changes.
- Chapters come from the contents (`ChapterMap`), not files; parts that
  open several files contribute their entries as chapters, and deeper
  entries never split one. Titles stay on the phone. Text of uncertain
  chapter gets the range `[lo, hi]`; no usable contents means one chapter.
- `Eligibility.allows` is the single gate between book and network. A
  passage passes when `hi < asking.lo` or, for lookups, when it ends before
  the scope's `ReadPoint` (selection resource and its element's start, from
  `SelectionBlock`, added only to the locator given to the companion). The
  selection's paragraph never passes, nor an element of unknown end in that
  resource. On a translated page, without a selector, or in a resource
  listed twice, the point is the resource start. The scope is frozen on open
  and kept through sources, Return and reruns. Every seed, search hit, read
  excerpt, recap hint and recap batch passes the gate; recaps have no read
  point. A lookup at the very start of the book shows the first-chapter
  notice without a request.
- Only these go into a request body: the selected name, the numeric scope,
  app-issued refs (`S1`…), and eligible passage text. Never
  `Locator.text.before/after`, chapter titles, notes, recap prose, or the
  model's malformed reply. Sole exception: Story's running summary
  (ADR-0057) sends recap summary sentences of listed chapters in `<summary
  chapter="N">` blocks, with chapter numbers only. Book text and name sit in
  `CompanionPrompt`'s unclosable data blocks, as in `TranslationPrompt`. The
  profile is written in the passages' language, whatever the app language or
  EPUB metadata. `InvestigationTest` and `RecapTest` assert no
  chapter-5-or-later sentinel leaves.
- `Investigation` runs `search`/`read` JSON actions over plain chat
  completions, bounded to 6 rounds, 12 operations, 64,000 characters of
  source and 10 HTTP requests, plus one final request. A name absent from
  eligible text is insufficient with no request. Claims must cite refs
  sent in this lookup; shown quotes are local text, never model text. One
  fixed-code correction, then a retryable error. Answers are cached per
  book by asking chapter and the refs naming the character, reused only at
  their read point or later. An insufficient answer with unseen matches is
  retried within the same limits.
- Background recaps are opt-in. `RecapScheduler` queues a chapter only when
  `READER_MOVEMENT` crosses into the next (no jumps, no backfill), runs in
  the foreground one batch at a time, and never sends a chapter at or after
  the one on screen, the peek origin, or the request's frozen ceiling.
  Lanes: lookups, Story-requested recaps, Story summaries, background.
  Requested work promotes a queued background chapter; a chapter never runs
  twice. Background work yields between batches keeping its parts. Work
  asked while the place was untracked waits until `CompanionViewModel`
  places the last page shown, never a stand-in. A service or model change
  clears the requested lane; `stop()` cancels Story's work. Batches of about
  12,000 characters cover every passage exactly once, are saved as parts and
  are combined locally. Recap names and aliases are kept only if in their
  cited passages, and only pick seeds; their prose is never sent.
- The settings recap switch is the default; the Aa > Advanced switch
  overrides it per book as a `recap_choice` row in `companion.db` (not
  backed up or synced, swept with the book). Turning a book off drops its
  waiting chapters and bumps a per-book counter in the recap ticket, never
  the book's epoch. Turning the settings switch off keeps waiting chapters
  of books turned on here.
- `companion.db` (`CompanionDatabase`, separate from `liseur.db`) keys
  recaps by book URL, content sha (`BookFingerprintStore`), unit key,
  server, model and `Recap.FORMAT`; no content sha, nothing queued or
  saved. Recaps are read under any server or model, so none is redone;
  other sha or format rows are dropped. Trimmed, swept with removed books,
  outside the Android auto-backup allowlist, included in the exported ZIP.
  Bump `PassageText.VERSION` when refs or passage splitting change, and
  `Recap`'s prompt version when the prompt or JSON change.
- Story ([ADR-0057](docs/adr/0057-codex-tells-the-story-so-far.md)) is
  Codex's first tab while a service is chosen
  (`CompanionViewModel.storyAvailable`), over the `AskingScope` frozen on
  open (a peek origin counts as translated). `StoryController` runs while
  the tab shows. `PublicationCatalog.chaptersBefore` lists a chapter only if
  provably started before the place (`ChapterMap.startsBefore`). Go to
  chapter is a peek, withheld for a repeated file or an anchor not found in
  its file.
- Story sends nothing on its own until Allow (`story_auto_ok`, backed up).
  `StoryEvents` then picks only `Story.previous`, once per frozen scope per
  session. Not now and Stop hold for that scope. Self-started work is
  `automatic`; the scheduler reads consent live and refuses, drops and
  cancels such work once revoked. Taps on Recap, Recap missing chapters
  (failed ones included) and Write summary always work. Recap again replaces
  only an undecodable row, after the new one is saved.
- `StorySummaries` folds summaries into `FAN_OUT` windows (none below
  `MIN_RECAPS`, enforced by `SummaryWriter`). A node is written only when
  its whole window is listed. A node is keyed by a hash of formats, content
  sha, level, window and ordered inputs, and shown or reused only while
  every input is still listed with the same key. Nodes live in
  `story_summary` (schema 4), fenced and swept like recaps. Bump
  `StorySummaries.FORMAT` when the prompt or JSON change.
- Following a source is a peek (`SourcePeek` in `ReaderViewModel`):
  nothing there is saved, synced, timed (`ReadingSessionRecorder`) or
  taken as furthest until the reader turns a page and moves, jumps or
  starts auto-scroll. Return and Back end the peek only once arrival is
  verified (`ExactLocatorAnchor` or bookmark place comparison).

### Covers, UI, and dependencies

- Resolve a local cover once at import: publication cover, declared SVG
  `rel=cover`, then an image named `cover`. Draw SVG covers with the
  existing resolver, isolating external-file state per render
  ([ADR 0035](docs/adr/0035-an-svg-cover-is-drawn-not-decoded.md)).
- New reading settings go in the Advanced sheet unless changed frequently.
  Keep Settings and reader surfaces consistent and keep the
  appearance/navigation split.
- UI strings go into English and the six shipped locales together.
  Russian plurals need `one`, `few`, `many`, `other`; escape apostrophes.
- Dependencies and bundled fonts must stay FOSS and reproducible;
  `readium-lcp` is prohibited. Never remove the reproducibility dependency
  metadata or JNI debug-symbol settings.
- Build Readium's `AssetRetriever` with `permissionSafeAssetRetriever`,
  never its default constructor: Readium 3.4.0 leaks a
  `SecurityException` for a content:// book that lost its grant. Drop the
  wrapper once Readium catches it in `ContentResource.stream()`.

### Home-screen widgets

Two Glance widgets in `ui/widget/`, `CoverOnlyWidget` and `LibraryWidget`,
with `CoverOnlyWidgetReceiver` and `LibraryWidgetReceiver` as the only
providers. Rendering reads Room and cached cover files, never the network.

- Taps go through the unexported `WidgetLaunchActivity`, which hands off
  to `MainActivity` via `LaunchRequests`; the exported `MainActivity` never
  reads widget targets from intent extras.
- The only redraw trigger is `AppContainer` collecting
  `LiseurDatabase.widgetInputs()` over `WIDGET_TABLES` (`books`,
  `reading_progress`, `work_alias`, `remote_server`); add any table that
  changes what widgets show. No refresh callbacks in repositories or view
  models.
- `WidgetUpdater.schedule` feeds `RefreshCoalescer`: redraw after 3 s of
  quiet or 15 s after the first unserved request (bounds page-turn
  writes); a request during a redraw earns exactly one more.
- Glance recomposes on `update()` without calling `provideGlance`, so
  `LiveSnapshot` and `LibraryWidget` preload before `provideContent` and
  reload in place when the updater's generation or `LibraryAnchor`
  changes. Each widget loads its own snapshot.
- `LibraryWidget` shows the current book (`bookDao.mostRecentlyOpened()`),
  then books opened here, then unread by title, without hidden or archived
  books.
- No periodic or background refresh. `retireLegacyWork` cancels earlier
  versions' unique work at app start and on `MY_PACKAGE_REPLACED`;
  `TIME_SET`, `TIMEZONE_CHANGED` and `LOCALE_CHANGED` redraw at once. The
  receiver holds each broadcast with `goAsync()` until the work is safe
  (retired, or enqueued as unique one-off work by `requestRedraw`); never
  start receiver work in a process-local scope and return.
- Without `FEATURE_APP_WIDGETS` the updater does nothing.
- Covers decode at most 256 px in `RGB_565` (160 px on the shelf, only the
  reachable page window) to stay under the binder transaction limit.
- Picker previews are `layout/widget_preview_*` with PNG fallbacks in
  `drawable-nodpi`; `widget_preview_colors.xml` must follow
  `WidgetComponents.kt`.

## calibre-web protocols

Verified against a live install (behind Caddy + Cloudflare) and the
calibre-web source (`cps/opds.py`, `cps/kobo.py`, `cps/kobo_auth.py`,
`cps/services/SyncToken.py`).

### OPDS catalog

- OPDS 1.2 Atom under `/opds` (`application/atom+xml;profile=opds-catalog`).
  HTTP Basic on every route; a 401 carries
  `WWW-Authenticate: Basic realm="Authentication Required"`. Anonymous
  browsing is a server-side option.
- Navigation feeds: `/opds/new`, `/opds/discover`, `/opds/rated`,
  `/opds/hot`, `/opds/author`, `/opds/publisher`, `/opds/category`,
  `/opds/series`, `/opds/ratings`, `/opds/formats`, `/opds/language`,
  `/opds/shelfindex`, `/opds/readbooks`, `/opds/unreadbooks`.
  `/opds/books` is a letter index; the full list is `/opds/books/letter/00`.
- Paging is `?offset=N`, 60 per page, with `rel="next"`/`rel="prev"`.
  Search (`/opds/search/{terms}` or `/opds/search?query={terms}`,
  OpenSearch at `/opds/osd`) is not paged.
- An entry carries the calibre UUID as `<id>urn:uuid:...</id>`, covers as
  `/opds/cover/<int id>` (integer, not UUID), and a
  `rel="http://opds-spec.org/acquisition"` link such as
  `/opds/download/74/epub/` with `length`, `mtime` and `type`.
- Downloads honour `Range` (206) and send `ETag`, `Last-Modified` and a
  UTF-8 `Content-Disposition` filename, so they are resumable.
- Without the "Allow Downloads" permission, `/opds/download/...` returns
  401 (403 on the web route) while browsing works. The app must tell the
  user to enable that permission in calibre-web, not show a generic
  failure.

### Kobo sync

- Off by default: the admin enables it in Feature Configuration, then each
  user creates a "Kobo Sync Token", giving `https://host/kobo/<32 hex
  chars>`. The token is the only credential; it never expires and has no
  user agent or device check.
- `GET /v1/library/sync` returns an array of entities (`NewEntitlement`,
  `ChangedEntitlement`, `ChangedReadingState`, `DeletedTag`, ...); an
  entitlement bundles `BookEntitlement`, `BookMetadata`, `ReadingState`.
  Paging uses the `x-kobo-synctoken` header (base64 JSON of per-table
  timestamps), echoed back with `x-kobo-sync: continue` while pages
  remain; sending the previous token returns only changes.
- `GET|PUT /v1/library/<uuid>/state`. The PUT body is
  `{"ReadingStates": [{...}]}` and each state must contain
  `CurrentBookmark` (`ProgressPercent`, `ContentSourceProgressPercent`,
  `Location`), `Statistics` and `StatusInfo` (`{"Status": ...}`); any may
  be `null`, but omitting one returns 400.
  Status is `ReadyToRead`, `Reading` or `Finished`. Writes are
  last-write-wins with no conflict detection, so the client compares
  `LastModified` itself. A `null` `Location` leaves the stored one alone.
- `ProgressPercent / 100` maps to `Locator.locations.totalProgression`.
  `Location.Value` (a kepub span id such as `kobo.7.1`) has no Readium
  equivalent: read, never written.
- With `kepubify` configured, `DownloadUrls` lists KEPUB only; Readium
  opens KEPUB.
- Behind a reverse proxy download URLs may come back `http://`; the
  client rewrites the scheme to match the configured base URL.
- calibre deletions are not propagated (only archived books, as
  `IsRemoved`), and users can restrict sync to shelves, so an empty sync
  is legitimate.

## Komga protocol

Verified against Komga API 1.25.0 with an API key and `BookLifecycle.kt`;
each rejection below was provoked on a live server.

- Auth is `X-API-Key: <key>`. Identity is `GET /api/v2/users/me` ->
  `{id, email, roles[]}` (no v1); `FILE_DOWNLOAD` in `roles` means the
  account may download. Setup reduces a pasted URL (often the API-key
  page) to its origin.
- Browse is `POST /api/v1/books/list?page=&size=&sort=` with an EPUB +
  READY condition (`GET /api/v1/books` is deprecated). The inline
  `readProgress {page, completed, readDate}` is the change detector, so a
  routine sync is one request. Search sets `fullTextSearch` beside
  `condition`.
- `PUT /progression` requires `modified` strictly after the stored
  `readDate` (else 409), `href` (fragment stripped, URL-decoded) exactly
  matching an internal EPUB file name with no leading slash (else 400),
  and `locations.progression`. `totalProgression` is recomputed.
- `PATCH read-progress {"page": N}` is rejected for reflowable EPUB; only
  `{"completed": true}` works. On a 400 the client fetches
  `GET /api/v1/books/{id}/positions` (~330 KB, so only after a rejection)
  and snaps to the nearest known position instead of coarsening.
- 409 is not a failure: the row stays dirty and the next run pulls and
  reconciles.
- `GET /progression` answers 204 with no body when there is no progress;
  404 (unknown book) is a failure.
- `modified` comes back double-offset and must not be used for ordering;
  sync orders by `readProgress.readDate`, which round-trips exactly.
- Server delete is admin-only (`DELETE /books/{id}/file`), so the action
  is hidden for Komga.

## BookOrbit protocol

Liseur uses BookOrbit's native REST API under `/api/v1`, not its OPDS or
KOReader surfaces (see
[ADR 0038](docs/adr/0038-bookorbit-as-a-remote-provider.md)).

- Auth is `Authorization: Bearer <access token>`. Sign-in is
  `POST /auth/login` with the password, `clientKind: "native"` and
  `deviceLabel`; the password is never kept. The access token (~15 min)
  and refresh token (~1 week, replaced on every `POST /auth/refresh`) are
  stored sealed with `orbit_access_expires` and an `orbit_epoch` naming
  their published connection. `BookOrbitSession` owns them; see
  [Implementation invariants](#implementation-invariants).
- Browse is `POST /books/query`, zero-based pages of up to 200, scoped to
  the account's libraries. It asks for books with an EPUB and still skips
  any without an EPUB file (audiobooks, comics, podcasts share shelves).
- Book ids are installation-local integers and a book has several files.
  `books.url` and `remote_uuid` prefix the id with a scope digest of
  address and account (`BookOrbitScope`). The file read is chosen once and
  kept in `book_orbit_binding`: a refresh that makes another EPUB primary
  must not move the reader to another edition, and a bound file gone from
  the server keeps its identity and loses its download link.
- Download is `GET /books/files/{fileId}/download` (gated on
  `library_download`, no range support, so an interrupted transfer
  restarts). Never use `/serve`: it has ranges but skips that permission.
- Positions sync only as exact EPUB CFIs that Liseur can prove
  (`syncAbility = EXACT`, gated by
  `BookOrbitPositionSync.AUTOMATIC_SYNC_ENABLED`). A server CFI is
  resolved against the selected file's original XHTML, then the active
  WebView (`BookOrbitIncomingAnchor`); an outgoing CFI must resolve back
  to the same text (`BookOrbitViewportCfi`).
  `BookOrbitLocalPositionWriter` saves CFI, locator and
  `position_revision` in one transaction; any later write without a CFI
  deletes it. Never send a place without a CFI: it nulls the server's.
- `BookOrbitPositionExchange` persists the exact request bytes, preflights
  the file again, marks the attempt `MAY_HAVE_BEEN_SENT`, POSTs once with
  a one-shot body (OkHttp otherwise resends after `503 Retry-After: 0`)
  and acknowledges only a read-back with the exact CFI and float4
  percentage. An uncertain, retry-required or rejected attempt is
  read-back-only until an explicit reader choice; the attempt generation
  stops an old choice from authorizing a byte-identical retry. Sends,
  read-backs and choices share `database.bookOrbitPositionMutex`. The race
  after the final preflight follows the approved last-server-write-wins
  policy (ADR 0038).
- Pushes run on every saved page turn and on pause while the book is open;
  only adoption is fenced by `OpenBooks`. A verified server place the book
  opened at is agreed by the first page turn in the move's transaction
  (`agreeOpeningPullIn`), so that move pushes. A server place saved
  elsewhere while reading is offered as a catch-up pill: accepting adopts
  it once the WebView shows it (`adoptCaughtUpInReader`); dismissing or
  reading on records it as agreed so the next move overwrites it. A place
  written by someone else between this device's POST and its read-back is
  offered the same way; declining drops the uncertain attempt instead of
  replaying it.
- Remote places found while the book is closed are adopted after close,
  behind a fresh GET and a transaction that rechecks account, binding,
  agreement, and the exact local revision and locator. First-time
  conflicts use a reader-verified choice applied in the open reader while
  its position writes are paused (`OpenBooks.whileHeld`).
  `BookOrbitPositionSync` walks at most 20 bindings per call over a
  persisted traversal (schema 58); its generic choice methods stay
  disabled.
- Percentage-only server places: see
  [Percentage-only openings](#percentage-only-openings).
- Reading status has its own agreements and exact-byte PATCH, keyed on
  `status_revision` so a status change keeps the verified CFI. Automatic
  writes are on (`BookOrbitStatusSync.AUTOMATIC_SYNC_ENABLED`).
- Upload (`BookOrbitUploadClient`, `library_upload`) uses the resumable
  `/uploads` session API against the first library whose `allowedFormats`
  is empty or includes EPUB. Idempotency key is `liseur-<digest>-gN`; the
  digest covers scope, local book URL, library, file name, size and
  content sha256, so two local entries with identical bytes never share a
  session. Act only on the server-reported session state: `receiving`
  resumes from `receivedBytes` (an offset mismatch is settled by reading
  the session back), `processing` is `Pending` even with a `bookId`,
  `completed` is adopted, and `expired`, `cancelled`, an unexplained
  `failed` or a rejected whole-file checksum move to the next generation,
  at most five. Read coded refusals from every answer (`complete`
  rethrows the original code while storing the session as `failed`).
  Never show or log server messages; they can carry disk paths.
- An uploaded book keeps its local URL; the catalog finds its binding via
  `remote_uuid` (`localUrls`), so the binding lives under that URL. The
  server file is linked only when proved: a single EPUB of the sent size
  in the returned book, or, when a `book_per_folder` library joined an
  existing book, the one candidate (of at most three) whose downloaded
  bytes match the sent digest. Otherwise record an `UNLINKED` refusal for
  those bytes and do not link; unlike other refusals it survives a
  disconnect, so the same account is not offered the same bytes again.
  Empty catalog duplicates of the adopted book are removed only when all
  are untouched (`BookRemoval.dropUntouchedCatalogDuplicates`); if one
  holds anything, all stay and the book stays unlinked. A failed candidate
  download fails the attempt (retried), except a 403: an account that can
  upload but not download cannot prove the file, so the upload ends
  unlinked.
- An uploaded book the server loses goes through the usual two finished
  walks; after the second, the entry and its reading stay and
  `BookRemoval.unlinkVanishedUploads` clears `remote_uuid` and that
  account's binding so a reconnect cannot relink it. Bindings other
  accounts kept across a disconnect stay.
- A disconnect clears `remote_uuid` but keeps the bindings of books that
  stay. When the same account (`accountKey`) signs in again,
  `RemoteAccountRepository.relinkUploads` restores `remote_uuid` on
  uploaded books from their binding so the catalog does not add them
  twice. It relinks only a file that still hashes to `local_sha256`, read
  outside the transaction; two local books bound to one server book stay
  unlinked. `BookRemoval.contentReplaced` drops the binding with the rest
  of the old file's description.
- Only the adoption writes `book_orbit_binding.local_sha256` (schema 60);
  it lets an uploaded book's own file stand for the server file when a CFI
  is computed. `BookOrbitCfiRepository` reads an app-owned file in place
  and copies a document URI once into a private spool; the parsed bytes
  must hash to `local_sha256`, and the source's size and modification time
  are rechecked on every use. The spool is deleted on a failed check and
  when the reader closes, and swept at startup. Before a position is
  prepared or sent, and before any status is read or written, the source
  is checked against the digest (`BookOrbitAdoptedSource.holdsUploaded`),
  and again after the network read that precedes the write. The answer,
  match or mismatch, is cached for five minutes while size and mtime are
  unchanged (hashing every page turn is too slow); a replacement keeping
  both is caught by the next process or after that window. A zero mtime
  from a document provider means unknown and is never cached.
- Delete (`BookOrbitDeleteClient`, `library_delete_books`) is
  `DELETE /books` with `{"bookIds":[id]}` and removes the whole book for
  every reader. The id comes from the entry's scoped `remote_uuid` and
  must have been issued for this address and account; a binding naming
  another book refuses. 204 and 404 mean deleted, 403 not allowed.
  `BookDownloadRepository.deleteFromServer` captures the entry and its
  owned files before the request and removes them afterwards only if
  account, `remote_uuid`, `local_uri`, file size and time are unchanged.
  An entry whose file the app does not own (a watched folder: the next
  scan would re-add it) is always kept with its reading history, as is a
  document whose provider reports no size or time. Files go after the
  transaction commits. A kept copy loses the deleting account's binding
  while it still names the deleted id, so a reconnect cannot relink it,
  and loses its `remote_uuid` while that account is still connected. An
  entry relinked to another id during the request keeps that link.

### What the BookOrbit server does

Checked against v3.0.0 (`ghcr.io/bookorbit/bookorbit:3.0.0`).

- `GET /books/files/{fileId}/progress` is per file. An unopened file
  returns a default (percentage 0, null position fields, no `updatedAt`);
  a saved zero has `updatedAt`. `BookOrbitProgressClient` keeps them apart
  and refuses malformed JSON, partial defaults and a percentage outside
  0..100. `lastReadAt`, `textUpdatedAt` and `updatedAt` are shown to the
  reader and never used as merge clocks.
- `POST /books/files/{fileId}/progress` replaces the whole text place,
  unconditionally
  ([`saveProgress`](https://github.com/bookorbit/bookorbit/blob/27cfdc20282eabdc89296c8c45482c578c157c7c/server/src/modules/book/book.service.ts#L2238-L2259)):
  a POST with only `percentage` nulls `cfi`, `pageNumber`,
  `positionSeconds`, the media-overlay, Kobo and `koreaderProgress`
  fields. Its hooks can move read status, Kobo state and sibling EPUB
  progress.
- The percentage column is PostgreSQL `real`; Liseur serializes it at
  `Float` precision before saving the request bytes, so read-back can
  require the exact CFI and percentage.
- `readStatus` lives on `GET /books/{id}` and changes through the
  status-only `PATCH /books/{id}/status`, which leaves file progress and
  its CFI alone. The public API cannot delete a status row.
- Places without a CFI are common (imports, some clients).

### CFI bridge rules

- `BookOrbitEpubPackage` reads `container.xml` and the OPF through the zip
  central directory, each capped at 4 MiB. It keeps non-linear itemrefs
  (they occupy CFI indices), drops remote manifest items, and refuses
  duplicate ids, escaping paths, external identifiers and internal
  subsets. `BookOrbitEpubInfoClient` compares package path and spine with
  the server's `/info` for the selected file.
- `BookOrbitCfi` parses EPUB CFI 1.1 point and range CFIs, including
  foliate's side-biased text assertions; temporal and spatial offsets are
  unsupported. An unparseable CFI is kept with its reason
  (`BookOrbitCfiRepository`) and never turned into a percentage.
- A parsed CFI is DOM-unverified; `unresolvedReason` only reports parser
  failures. The resolver enforces step parity (even steps land on
  elements) and honors `s=` at element boundaries. `/6/4!:3` has no text
  meaning and stays unresolved.
- A range parent ending in `!` is an open path: join it with each endpoint
  before walking; never resolve `Range.parent` alone. Use `spineStep` and
  each spine item's `step` (the package is not always `/6`). Flatten only
  reader-owned wrappers, never an EPUB element.
- Never use `BookOrbitForeignCfi.parsed` directly as a `Locator` or POST
  payload. An approximate place from a failed exact resolution must never
  be written back as exact.
- Android's WebView throws on `compareDocumentPosition`, so the incoming
  check walks nodes in order. The outgoing capture skips tokens without
  letters or digits, accepts an anchor starting inside the captured token,
  and retries briefly while the pager slides to a chapter's first page. A
  page without text sends nothing.

### Agreement and exchange states

- `reconcileExactPosition` decides from the local `position_revision`,
  never a timestamp or percentage tolerance. Equal CFIs settle even at
  different percentages; different CFIs at the same percentage conflict.
  A remote place without an agreement is not assumed to be this device's.
- An exchange moves `PREPARED` → `MAY_HAVE_BEEN_SENT` → acknowledged, or
  `UNCERTAIN` (read-back failed), `RETRY_REQUIRED` (server unchanged) or
  `REJECTED` (401/403). Those four are read-back-only on every later run;
  only an explicit reader choice prepares a new attempt. A read-back
  finding a third exact place means someone else wrote in between; it is
  offered as a catch-up, and declining drops the attempt.
- Every write re-reads the agreement row inside its own transaction.

### Percentage-only openings

| Local place | Condition | Opens at |
| --- | --- | --- |
| None | Any percentage-only server place | Server percentage |
| Agreed and unchanged since | Server moved to another percentage-only place | Server percentage, with a way back |
| Never matched, no verified CFI | Server percentage is further ahead | Server percentage, with a way back |

The opening is an approximate fraction (`approximateOffer`) and records
nothing. The first page turn records the percentage as the agreed remote
place in the same transaction as the move, which then pushes an exact CFI.
Any jump (the way back, a bookmark, the contents, the scrubber or
go-to-page, even onto the same page) declines it and leaves the server
place alone. Any other percentage-only place, including one this device
never matched, stays unresolved at opening and receives no POST; the
open reader offers it as a catch-up instead. A
percentage-only place saved elsewhere while reading, or written between
this device's POST and its read-back, is offered in the catch-up pill as
"Continue near page N" (`approximateCatchUp`): accepting goes there,
reading on agrees it, and dismissing or jumping away agrees it as
declined so the next move pushes.

### Status mapping

Liseur's explicit finished and unread marks map to BookOrbit `read` and
`unread`, and manual BookOrbit `read` and `unread` map back. A manual
`reading` maps only when the local passage is already in Liseur's reading
range. Automatic statuses stay derived from position. `want_to_read`,
`on_hold`, `rereading`, `skimmed`, `abandoned` and unknown values are
preserved on BookOrbit.

### BookOrbit fixtures

- `progress-*.json` in `app/src/test/resources/bookorbit/` are synthetic;
  `progress-live-*.json` are sanitized server responses with ids and
  timestamps replaced and null/presence shapes kept
  (`BookOrbitLiveProgressTest`).
- `OPS/` holds project-authored package and chapter documents.
  `foliate-cfis.json` is output from BookOrbit's unmodified
  `client/public/assets/foliate/epubcfi.js` at commit
  `6be648b48a9cc376cfeff8953ebf22123583f7eb`. To regenerate, serve
  `tests/bookorbit/capture.html`, that `epubcfi.js`, `package.opf` and
  `one.xhtml` from one directory on localhost and open the page in
  Chromium; it prints the corpus. Do not vendor the upstream JavaScript.
- `tests/bookorbit/ParserSmoke.java` runs the production parsers on an
  emulator: compile with `javac --release 8`, convert with
  `d8 --min-api 26`, copy the dex jar and the debug APK to
  `/data/local/tmp`, and run `app_process /system/bin ParserSmoke` with
  both on `CLASSPATH`.

## liseur-sync protocol

calibre-web and Komga hold one current position per book; liseur-sync
holds an append-only log ("what happened since `seq`") and no books, so
it can sync a book that came off an SD card.

- Auth is `Authorization: Bearer <token>`. `POST /v1/login` with a
  username and password mints device tokens through `POST /v1/tokens`,
  one scope per token: sign-in asks for `sync` and `read-insights`. A
  pasted token usually has only `sync`; statistics are then absent.
- The cursor (`sync_account.cursor_seq`) is the only irreplaceable
  state. A page from `GET /v1/changes?since=` is written and the cursor
  advanced in one transaction, never cursor first. A cursor below the
  compaction horizon gets `410 resync_required`; recover from
  `GET /v1/heads` and its `snapshot_seq`.
- Ids are derived, never random. The server treats `op_id` and
  `session_id` as idempotency keys over the whole payload (same id and
  payload is `duplicate`, different payload is a conflict). So the id is
  `UUIDv3(deviceKey|workId|revision)` and every payload field comes from
  stored state: `client_ts` is `reading_progress.updated_at`, never the
  clock. An interrupted push is simply repeated. Ids are opaque, up to 64
  characters (no UUIDv7 check).
- `POST /v1/works/resolve` takes every identifier at once (`sha256:`,
  `pmd5:` (KOReader partial MD5), `dc:`, `ta:`) and registers all of
  them against the match, so re-encoded copies converge. `409` means two
  different works were named; the server changes nothing and merging is
  left to the reader.
- `ta:` normalisation is an interoperability contract outside the
  schema; the server matches by exact string. `WorkIdentifiers.titleAuthor`
  is `fold(title)|fold(author)`, folding = NFKD, strip `\p{Mn}`,
  lowercase, non-alphanumerics collapsed to single spaces, trimmed. Other
  clients must agree exactly or silently fail to match.
- A locator over 16 KB is dropped and the progression sent alone; never
  fail the push.
- A newly resolved book has its history behind the cursor, so it is
  seeded once from `GET /v1/works/{id}/positions?limit=1`.
- `POST /v1/sessions` takes closed sessions only, as progression
  fractions. Never send page numbers; the server derives pages per
  edition. `idle_ms` is always zero: time is counted only while the
  reader is in the foreground.
- Statistics (`/v1/insights/*`) are decoration: every failure is null
  and silent, and a null `eta_seconds` is passed through, never invented.
- Sentence translations (`/v1/works/{id}/translations`) are keyed by the
  client cache hash (SHA-256 of service, model, languages, context and
  sentence), so the sentence never travels. Pull before push; pages of
  500 ordered by `(updated_at, key)` behind an opaque cursor; pushes in
  batches of 500, last-writer-wins, identical push writes nothing. A
  plain-text `404 page not found` means an older server: skip
  translations for the session. A 400 `unknown_work` (or a 404 with a
  body on pull) is a stale work name: re-resolve. Each peer records push
  and pull progress in `translations.db` so interrupted runs repeat. See
  liseur-sync ADR-0052. Clear a work with
  `DELETE /v1/works/{id}/translations`, the account with
  `DELETE /v1/translations` (liseur-sync ADR-0053).
- Uploading is offered only when `GET /v1/token` advertises the
  `library-upload` scope and `GET /v1/folders` marks the folder as
  taking uploads (no version check needed).
  `POST /v1/folders/{folder}/books` is `multipart/form-data` keyed by the
  file's SHA-256: a repeat answers `200 duplicate`. `202` means stored
  but not yet catalogued; the worker asks again.
- An upload adopts, never replaces: the local row keeps its `url` and
  gains `remote_uuid` and `download_href` (`BookDao.linkToRemote`);
  rewriting the URL would orphan positions, annotations and sessions.
  Since the catalog snapshots the library before its walk, both sides
  guard against duplicates: the catalog re-asks about unaccounted ids on
  a page, and adoption drops a catalog row that got there first.

## F-Droid readiness

- Dependencies are all FOSS, from Maven Central or Google's Maven. Never
  add `readium-lcp` (pulls proprietary liblcp). The user-visible list is
  in `LicencesScreen.kt`.
- `foss` has no trackers, analytics or Google Play services. Outbound
  traffic goes only to the user's calibre-web, Komga, BookOrbit or
  liseur-sync server, and to a dictionary site when a definition is
  asked for. The dictionary is off until enabled in Settings and the
  site is user-chosen (`DictionaryUrl`); a hardcoded third-party host
  would earn the TetheredNet anti-feature. That justifies `INTERNET`;
  `ACCESS_NETWORK_STATE` serves the sync workers'
  `NetworkType.CONNECTED` constraint.
- Gemini read-aloud, Gemini translation and the companion on Gemini send
  book text to Google and are `play` only; `foss` has none of their code,
  strings or endpoint, so NonFreeNet does not apply. Device voices (the
  default) are in both and stay on the device. Read aloud and translation
  with a server are in both and talk only to the user's server. Device
  translation uses Android's `TranslationManager`, not ML Kit.
  ML Kit translation is the one exception, `playImplementation` only: a
  proprietary SDK pulling Play services client libraries, Firebase
  components and datatransport. Book text stays on the phone, but once
  chosen the SDK logs diagnostics to `firebaselogging.googleapis.com`,
  and its first model download creates a Firebase installation id and
  fetches ML Kit remote config. Keep `docs/PRIVACY.md` in step with any
  change. Before changing that boundary, check that
  `./gradlew :app:dependencies --configuration fossReleaseRuntimeClasspath`
  lists no `mlkit`, `gms`, `firebase` or `datatransport`, and that the
  `foss` release dex has no `com.google.mlkit` classes.
  The server address field offers hosted services (`SpeechServerPresets`)
  beside a self-hosted example; none is selected or contacted until the
  user picks it, and the app works without them. Before changing that
  boundary, check the `foss` release dex for `generativelanguage` and
  `Gemini` strings.
- No non-free assets: bundled fonts (Literata, Vollkorn, Atkinson
  Hyperlegible, Inter) are OFL; the icon is in-repo vector drawables.
- `fonts.googleapis.com` in the release dex is unreachable: a
  `ReadiumCss` string used only for `EpubNavigatorFactory`'s `googleFonts`
  list, which Liseur never sets. Every `addFontFamilyDeclaration` in
  `ReaderPreferencesMapper.kt` must source faces from bundled assets.
- `versionCode` and `versionName` change only in a
  `chore: release vX.Y.Z` commit made by `hack/release`, and every
  release is tagged. F-Droid uses `UpdateCheckMode: Tags` with
  `AutoUpdateMode: None`, since dual signing needs each signature
  delivered by hand; `hack/release` opens that merge request.
- Store metadata lives in `fastlane/metadata/android/en-US/` (title,
  descriptions, per-versionCode changelogs, icon, screenshots); extra
  listing locales go beside `en-US`. App UI locales: see
  [`docs/TRANSLATING.md`](docs/TRANSLATING.md).
- The build needs no network beyond Gradle dependencies and no signing
  config: `assembleFossRelease` on a clean checkout produces the unsigned
  APK F-Droid builds and signs.
- The build must be reproducible (F-Droid will not publish otherwise).
  `hack/verify-reproducible` builds the release APK from two clean
  checkouts at different paths and compares byte for byte, naming
  differing entries (usually a baked timestamp or leaked absolute path):

  ```bash
  hack/verify-reproducible          # HEAD
  hack/verify-reproducible v0.2.0   # a tag, before submitting it
  ```

  `hack/release` runs it on every release commit and undoes the commit on
  failure; run it before anything unusual ships. Move
  `keystore.properties` aside first: it compares the unsigned APK.

  Two build-file settings exist only for this and must not be removed:
  `dependenciesInfo` is off (otherwise AGP embeds a Play-encrypted
  dependency manifest nobody can reproduce), and
  `packaging.jniLibs.keepDebugSymbols` covers every `.so` (re-stripping
  AndroidX prebuilt native code ties bytes to the local NDK).
- Publishing our own signature: dual signing. Replacing F-Droid's key
  would break updates for existing installs, so F-Droid publishes two
  APKs per version: one with its key, one with our signature grafted on
  its byte-identical rebuild (new installs get this one, interchangeable
  with the GitHub release). The metadata has:

  - `AllowedAPKSigningKeys:` with two SHA-256 digests, our certificate
    and F-Droid's key for this app; both APKs must pass.
  - `AutoUpdateMode: None`: each release arrives as a merge request with
    the new build entry plus signature files under
    `metadata/com.chmouel.liseur/signatures/<versionCode>/`, extracted
    with `fdroid signatures <apk>`. `hack/release` downloads the APK from
    the GitHub release, extracts the signature, appends the build entry,
    runs `fdroid rewritemeta`, and opens or updates the fdroiddata merge
    request. It needs `fdroidserver` (`pipx install fdroidserver`, or
    `FDROID_BIN`); without it the release still ships and
    `hack/release --fdroid-only VERSION` finishes the F-Droid part later.

  Set up in
  [fdroiddata!46390](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46390);
  only v0.9.3 (versionCode 17) and later get the developer-signed twin.
  Modelled on NewPipe
  ([fdroiddata!46133](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/46133)).
- `hack/store-status` shows F-Droid published versions, index age, last
  build run result, upstream metadata and open merge request pipeline
  state, plus GitHub releases, Play tracks and the week's Play crash
  counts: one command for all three channels.
- Inclusion was
  [fdroiddata!44292](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44292).
  See *What F-Droid checks* above for its pipeline and how it can fail
  after a release looks finished.
