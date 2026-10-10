## Building

What to build next, and what has been ruled out, is in
[`docs/ROADMAP.md`](docs/ROADMAP.md).

What each supported server exposes, what is implemented against it, and
why the remaining gaps are or are not fixable is in
[`docs/SERVER_CAPABILITIES.md`](docs/SERVER_CAPABILITIES.md).

This project uses the Gradle wrapper, so you don't need Gradle installed
separately: just a JDK 17+ and the Android SDK (command-line tools are
enough; `compileSdk`/`targetSdk` 37 requires a reasonably recent SDK
Manager package list).

```bash
./gradlew assemblePlayDebug    # debug APK with Gemini read-aloud and translation
./gradlew assembleFossDebug    # debug APK of the F-Droid build
./gradlew assembleFossRelease  # minified release APK (F-Droid, GitHub)
./gradlew bundlePlayRelease    # minified release AAB, for Google Play only
```

There are two product flavors. `foss` is the build F-Droid reproduces
and the GitHub release carries. `play` adds Gemini's read-aloud voices
and Gemini translation, which send book text to a non-free Google
service, and ships only on Google Play. Gemini's code lives under
`app/src/play/` and `app/src/testPlay/`; the `foss` APK has none of its
code, strings or endpoint. Read aloud and translation themselves, on the
device or with a server the reader enters, are in both. Both flavors share the application id, version and signing
key. Plain `assembleRelease` or `assembleDebug` builds both flavors.

Output APKs land in `app/build/outputs/apk/{foss,play}/{debug,dev,release}/`,
and the bundle in `app/build/outputs/bundle/playRelease/`.

The debug build is signed with the standard Android debug key, so
`app-play-debug.apk` can be installed directly with `adb install` or by
sideloading. The release build is unsigned by default so the project
builds out of the box on any machine/CI; see below if you want a signed
release build.

### The dev build, for testing on a phone you use

The debug build carries the production `applicationId`, so installing it
on a phone that already has Liseur on it *replaces* Liseur. The library
that goes with it — reading positions, highlights, notes, sessions —
does not come back.

The `dev` build type exists so that does not have to happen:

```bash
make dev             # ./gradlew assemblePlayDev (FLAVOR=foss for assembleFossDev)
make dev-install     # adb install -r, beside the real app
make dev-run         # install and launch
make dev-uninstall
make dev-logcat      # logcat filtered to its pid
```

It is the debug build with two differences, both deliberate:

- `applicationId` is `com.chmouel.liseur.dev`. Android keys an app's
  storage off that, so the dev build gets its own Room database, its own
  DataStore and its own SAF grant. It cannot read or damage the real
  app's library, and the first thing it will ask for is a folder,
  because it genuinely has none. Connecting a server is likewise a fresh
  connection, with its own device key.
- The launcher name is "Liseur (dev)" and the icon field is retinted, so
  the two are told apart before either is opened. That is the whole of
  `app/src/dev/res/`.

The APK lands in `app/build/outputs/apk/play/dev/app-play-dev.apk`,
debug-signed like `app-play-debug.apk`. The device-facing make targets
(`install`, `run`, `reset`, `dev-*`) and `hack/install*` use the `play`
flavor unless `FLAVOR=foss` is given. The `dev-*` targets that reach a
device run only on a physical one: `PHONE=` picks it and defaults to the
first one attached, and they fail when there is none or `PHONE=` names
an emulator.

`release` is not involved anywhere in this, which is the point: F-Droid
rebuilds that build type byte for byte from the tag, and nothing here
changes what it produces. `debug` is not involved either, so
`make install`, `make run`, `make reset`, `hack/screenshots`,
`hack/e2e-*` and the scenarios under `tests/` keep the package name they
hardcode and go on working against the emulator unchanged.

One thing to watch if you drive it by hand: `adb shell am start -n
com.chmouel.liseur.dev/.MainActivity` does *not* work. The `.Class`
shorthand is relative to the application id, and the dev build's no
longer matches the namespace the class lives in, so the component has to
be spelled `com.chmouel.liseur.dev/com.chmouel.liseur.MainActivity`.
The Makefile already does.

### Signing a release build (optional)

The real signing key for published releases lives in `pass`:

| Entry | Contents |
| --- | --- |
| `android/liseur.keystore.p12` | the PKCS#12 keystore, base64 |
| `android/liseur.keystore-password` | store and key password |
| `android/liseur.keystore-alias` | key alias (`liseur`) |

To build a signed APK locally with it, write a `keystore.properties`
(gitignored, per-developer) pointing at a decoded copy. Keep that copy in
persistent user data rather than `/tmp`, which may be cleared between builds:

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

If `keystore.properties` exists but its `storeFile` has been removed, Gradle
stops during configuration with recovery instructions. Run the commands above
again to restore the decoded keystore and refresh the properties file. Delete
`keystore.properties` only when you deliberately want the unsigned build used
by clean checkouts and F-Droid.

`hack/release` performs this check before a release and runs the same recovery
automatically when the properties file or decoded keystore is missing. It
leaves an existing usable custom signing path unchanged.

Contributors without access to that key can generate their own instead.
Any key produces an installable APK; it simply won't update over one
signed with the release key:

```bash
keytool -genkeypair -v -keystore /path/to/your.p12 -storetype PKCS12 \
  -alias liseur -keyalg RSA -keysize 4096 -validity 10950
```

Either way `./gradlew assembleFossRelease` picks the file up automatically;
without it the release build is simply unsigned.

## Settings backup archive

Backups are ZIP files with a versioned `manifest.json`, `settings.json` and
`annotations.json` and `positions.json` payloads, and optional `fonts/<sha256>.(ttf|otf)` entries.
Version 3 includes reading positions; version 2 annotation archives and version 1 settings-only archives remain readable.
Annotation export uses indexed keyset pages and a size-bounded staging file;
reading-position export pages through its URL primary key. Annotation and
position payloads have a combined 4 MiB limit, enforced before JSON decoding,
to bound the memory used by inspection and restore. Negative timestamps and
legacy timestamp conversions that would overflow are rejected.
The manifest records each payload entry's size and SHA-256 checksum. The
settings payload uses explicit allowlists for the app and reader preference
stores; account credentials, server connections, sync metadata, and transient
notices are not included. Unset keys remain absent so restoring a backup
leaves destination values alone when the archive did not store that setting.
Custom font entry names retain their content-based identifiers.

The Backup and restore screen previews settings, fonts, reading positions, annotations, and
matching library books before restore. The same Restore action also accepts
older annotation-only JSON files. Restoring annotations adds missing marks,
preserves existing ones, and requests sync for books with newly added marks.
Restoring reading positions replaces the saved Readium locator and progression
(or just progression when sync supplied no exact locator),
matching books by URL or an unambiguous title and author match. The original reading time is kept;
this device refreshes the shelf’s finished flag in the same transaction and
records a new local revision without importing account baselines or
acknowledgements, then requests sync. Positions for missing books retain their
original URL. Older backups leave reading positions unchanged; archives with payloads newer
than their declared version are rejected. Ambiguous or duplicate target matches
retain the original URLs instead of replacing another book’s position.
Completed position writes request
sync even if a later write fails.
Unknown optional JSON fields are ignored; malformed
known fields, unsafe or duplicate paths, checksum mismatches, and unsupported
versions are rejected. Font files are passed through the normal font importer,
so its validation and installation limits still apply. Settings are written
to two independent DataStores, so a storage failure can leave a partial
restore. An annotation storage failure after settings were applied is also
reported as a partial restore.

The same screen offers a separate folder export for all offline EPUBs, including
imports, watched-folder books, hidden books, and archived books. Copies use
sanitized title-and-author filenames. Existing names and duplicate export names
are skipped case-insensitively. Export streams files individually, reports
exported/skipped/failed counts, and continues after per-book failures. Cancelling
or leaving the screen stops the export; completed copies remain and incomplete
copies are removed where the folder provider permits it. Rotation preserves the
running export. This action does not modify the library or download remote books.

## Testing

```bash
./gradlew testFossDebugUnitTest testPlayDebugUnitTest   # JVM unit tests
./gradlew lintFossDebug lintPlayDebug                   # Android Lint (0 errors required)
```

There is no instrumented/emulator test suite. Reader interactions
(gestures, immersive mode, process-death restore, rotation) are verified
manually on a booted AVD.

### Furthest-position emulator check

Verified on 2026-10-05 with `liseur_phone_api36` (Android API 36),
`emulator-5580`, the debug APK, a disposable liseur-sync instance, and
a synthetic ten-chapter EPUB. A second device token posted Chapter 8's
exact `#p1` anchor at 77.78%. Android declined it, turned a page in
Chapter 4, and uploaded 35.19% as the latest position. After force-stop
and reopening, Chapter 4 remained current. Manual sync still offered
the historical destination despite the latest operation being Android's
own echo. Choosing it displayed `CHAPTER 8 PASSAGE 1` and stored the
original locator, not merely a matching percentage.

With Wi-Fi and mobile data disabled, another run recorded a farther
local chapter, returned to Chapter 4, and restarted. Offline manual
recovery and the way-back action both worked. After reconnecting, the
server accepted the edition-bound peak at sequence 7 and the lower
current position at sequence 8; Android acknowledged current separately.
The exercise also caught and fixed missing edition hashes on peaks from
catalog downloads and a misleading other-device label on local recovery.

The second client in this check used the real HTTP API, not a browser.
API 26, rotation, cross-edition rendering, and server compaction were not
exercised on the emulator in this run.

### EPUB font-size remediation

Reflowable EPUB font size uses Readium 3.3.0's Android text-zoom path.
`ReaderPreferencesMapper.epubNavigatorConfiguration()` sets
`useReadiumCssFontSize = false`; `ReaderPrefs.fontSize` remains the stored
multiplier passed through `EpubPreferences`. Do not replace this with CSS
`zoom` or WebView page/pinch zoom. Readium rounds the multiplier to an
integer percentage for `WebSettings.textZoom`.

A reader who never chose a size gets `ReaderPrefs.DEFAULT_FONT_SIZE`, one
of the Size slider's own positions (about 134%).

Build the diagnostic publication with:

```bash
hack/make-font-size-book
```

Import `tmp/books/liseur_font-size-fixture.epub` through the normal local-book
path on a disposable emulator. Record the explicit `SERIAL`, Android API,
WebView provider/version, viewport, and system font scale. With advanced
typography at Default, compare the `MEDIUM`, `ABSOLUTE-BODY`, and
`ABSOLUTE-DESCENDANT` chapters at 100%, 150%, and 200%; verify visible growth,
reflow, live changes, a newly opened chapter, and reopening. Also exercise a
fractional multiplier, global and per-book settings, and one advanced setting.
Check a fixed-layout book as a control. A saved preference or computed CSS
value alone is not proof that the rendered text grew.

The fixture is intentionally separate from the seeded demo shelf. Desktop
`hack/verify-wide-content` and `hack/verify-footnotes` exercise their
JavaScript layout helpers against Readium CSS, including a 100% case without
`--USER__fontSize`, but cannot validate Android native text zoom.

#### Validation record

On 2026-09-22, the fixture was imported through the normal `content://`
handover path on disposable `emulator-5554` (`liseur_phone_api36`, Android
16/API 36, 1080x2400 at 420 dpi, WebView
`com.google.android.webview` 133.0.6943.137, system font scale 1.0).
With advanced typography at Default, the reader was opened at 100%, changed
live to approximately 200%, and screenshots plus accessibility bounds showed
the `MEDIUM`, `ABSOLUTE-BODY`, and `ABSOLUTE-DESCENDANT` chapters growing and
reflowing. For example, the first absolute-body paragraph's visible bounds
changed from 247px high at 100% to 1113px at 200%; the medium chapter's first
paragraph changed from 307px to 1371px. The absolute-descendant chapter,
including its `!important` paragraph, also rendered at the enlarged setting.
Opening a different chapter created a new WebView with the enlarged text, and
reopening the imported book rendered the saved enlarged setting again. No
reader crash was observed.

This run did not cover every matrix combination: 150%, 60%, 250%, fractional
pinch values, custom fonts, advanced line-height, fixed layout, rotation,
RTL/CJK, selection, footnotes, and wide-table interactions remain manual
follow-up coverage.

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
to remember the answer; otherwise it offers, once per book, an action
that silently fails every time. Either way `-r` asserts the app ends up
not offering, no book was linked, none left the shelf and nothing was
written into the folder. Run it with the folder still closed, then
`folder-uploads <folder-id> on` and run the check above, and you have
covered both answers with one server.

Nothing about it is mocked. Start with a clean shelf (`hack/reset-books`
then `adb shell pm clear com.chmouel.liseur`), or the counts it compares
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

### Checking the local network permission

Android 17 blocks an app targeting SDK 37 from reaching addresses on the
phone's own network until the reader allows it, and it blocks below the
HTTP client: a LAN server does not refuse a connection, it swallows it
until the timeout. That is #195, and ADR-0028 explains the shape of the
answer.

What the system holds is worth reading directly:

```bash
adb shell dumpsys package com.chmouel.liseur | grep -i local_network
```

An image older than API 37 can be made to behave like one, which is the
only way to see the failure without an Android 17 device:

```bash
adb shell am compat enable RESTRICT_LOCAL_NETWORK com.chmouel.liseur.dev
adb shell am compat reset RESTRICT_LOCAL_NETWORK com.chmouel.liseur.dev
```

Note that granting any permission in the `NEARBY_DEVICES` group grants
this one with it, so a phone that has been asked about nearby devices
for another reason will not show the prompt.

### Why the reading stats say "Counting this device only"

The dashboard combines every device that has signed in to liseur-sync,
but only when the merge can be *proved* exact. When it cannot, the
screen silently shows what this device counted and says so. That is
deliberate: ADR-0021 would rather report a smaller true number than a
plausible wrong one, and a reader is never shown an error about it.

The reason is written to the log instead, so the answer is one command
away:

```bash
adb logcat -s liseur-sync-insights
```

Every refusal prints `Statistics count this device alone: <reason>`.
The reasons are prose, not codes, and name the exact thing that could
not be established, from the account having no identity to check a
reply against, through a book resolved to a different work since it was
sent, to a reason the server itself gave.

The one worth recognising is `the server could not place this device's
evidence (candidate_payload_mismatch)`. It means this app rebuilt the
description of a sitting and the server disagreed with its own stored
copy. In practice that means the device identity behind those uploads
changed, since the server folds the device id into the fingerprint it
compares. A reconnect with a password keeps the stored device id and is
fine; a pasted token names nobody and gets a fresh one.

Some background on why any of this needs rebuilding. Sessions have been
uploaded since v0.6.0, but the table that retains the exact bytes of
each request only arrived with the dashboard. Every sitting older than
that is of unknown standing: the upload flag does not settle it either,
because disconnecting an account clears the flags while the server
keeps the sessions. So the sitting is described again from the stored
row and offered as a candidate, and the server rules on it. That
rebuild is byte-identical to the original because the payload is a pure
function of the row, and the only field added since, `active_ms`, is
withheld from exactly these sittings.

Two cases are settled without asking. A sitting recorded since the
evidence table existed and never sent is counted here, because a
request is always written down before it goes out. A sitting of a book
the server has no confident name for is also counted here, because
sending reading up requires such a name and the upload query joins on
it, so that sitting has never once been selected.

Reproducing the upgraded state on an emulator is the quickest way to
exercise all of this: flag the sittings, drop their retained requests
and clear their upload flags.

```bash
adb shell "run-as com.chmouel.liseur sqlite3 databases/liseur.db \
  'UPDATE reading_sessions SET legacy_evidence_unknown = 1, uploaded_at = NULL; \
   DELETE FROM session_transmission;'"
```

The screen should still read "Counting all your devices", and the
sitting count should equal this device's own, not that plus the
server's. Never do this on a phone somebody reads on.

## Releasing
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
draft, save, and confirm. The commits are also listed in the editor as
comments. The draft comes from Gemini; when Gemini fails, the script
says why and asks `copilot -p` instead, using `gpt-6-luna` unless
`LISEUR_NOTES_COPILOT_MODEL` names another model. If neither can draft
it, it offers to try again before opening the editor without a draft.

The version can also be given outright, which is what CI and scripts
want:

```bash
hack/release -n 0.2.1 "Fix page fitting on tall screens."
hack/release --fdroid-only 0.2.1     # re-run just the F-Droid step
hack/release --no-play 0.2.1 "..."   # leave Google Play out of this one
```

`-n` (`--non-interactive`, `--yes`) answers the confirmation prompts,
and any run with nothing on stdin needs it: the prompt reads
end-of-file as no and the release stops without having done anything.
That applies to `--rc` too.

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

If a workflow failure stops publication, recover the existing tag with the
fixed workflow on `main`, without moving the tag or bumping the version:

```bash
gh workflow run release.yml --ref main -f release_tag=v0.20.0
```

This builds the original tagged source. Once GitHub has published the APK,
run `hack/release --fdroid-only 0.20.0` to finish the F-Droid submission.
Rerunning the failed Actions run uses its original workflow, so it will not
pick up a workflow fix committed afterwards.

`LISEUR_PLAY_SERVICE_ACCOUNT_JSON` is the odd one out. It buys the third
channel rather than the release itself, and the workflow skips Google
Play entirely when it is absent. `hack/release` uploads it with the rest
all the same, so in practice a release goes to Play unless you say
otherwise:

```bash
hack/release --no-play 0.9.0 "..."
```

The script records that per-release choice in the annotated tag, so an
already configured Play credential cannot override it. Deleting the
secret by hand is neither necessary nor useful: a later ordinary release
will restore a missing credential from `pass`.

`hack/release` creates the environment and uploads whichever secrets are
missing straight from `pass`, so an unlocked password store is the only
setup a fresh clone needs. To refresh them all, after rotating the key
for instance:

```bash
hack/release --sync-secrets
```

Signing a build on your own machine is a separate matter, covered under
[Signing a release build](#signing-a-release-build-optional) above.

### Release candidates

A release candidate is the same build, signed with the same release key,
put where a handful of people can install it before the final release:

```bash
hack/release --rc             # the next patch, numbered -rc.1, then .2
hack/release --rc 0.16.0      # a version you are working towards
hack/release --no-play --rc   # skip Google Play testing tracks
```

It publishes a GitHub **prerelease** with the signed APK attached, uploads
the app bundle to Google Play's **internal** and **closed** (`Testing`)
tracks, and stops there: no F-Droid merge request, no changelog to
write, and no Google Play production upload. Before the tag is pushed,
the generated RC commit must pass the JVM tests, Android lint, and the
release build in its detached worktree. The reproducibility check remains
part of the final release path. Pass `--no-play` to leave Google Play out.

The signature is what makes it worth doing. It is the one F-Droid
publishes under, through the dual-signing flow, so the APK installs
straight over a copy that came from F-Droid, with no uninstall and no
lost library, and F-Droid offers the next real release over it afterwards,
as an ordinary update.

That last part is only true because the RC takes a
`versionCode` and the next real release lands above it. `hack/release`
counts the next code from the highest one across `main` **and every
tag**, so an RC at 32 pushes the following real release to 33, and
F-Droid sees an upgrade. Nobody who installed an RC is stuck,
but they are on it until the next release goes out, since F-Droid
will not offer a lower `versionCode`.

Nothing lands on `main`. The commit that bumps the version is reachable
only through its tag, so `main` stays a history of real releases and the
next one still bumps from the last real version. Run it from any branch,
as long as the tree is clean.

F-Droid is told nothing about an RC, but it reads the public tags.
`v0.9.4-test.1`, the old prerelease spelling, was once picked up by its
`checkupdates` bot, which
opened a merge request proposing it as the current version. The
fdroiddata metadata therefore filters what the bot looks at, with
`UpdateCheckMode: Tags ^v[0-9.]+$`; neither `-rc.N` nor the legacy
`-test.N` spelling matches. Its `AutoUpdateMode: None` also means builds
are submitted only by `hack/release`, but the tag filter remains necessary:
the update checker and build submission are separate mechanisms. Before
any final or RC tag is pushed or submitted, `hack/release` runs
`hack/verify-fdroid-tags` against that live metadata. It fails closed if a
final tag would be missed or either prerelease spelling would be discovered,
so a future metadata or tag-format change cannot silently put an RC on
F-Droid.

Run `make verify-fdroid-tags` for the deterministic local regression checks,
or run
`hack/verify-fdroid-tags v0.15.0 v0.15.0-rc.1 v0.15.0-test.1`
to check the live fdroiddata filter directly. A change to the RC tag format or the
`UpdateCheckMode` pattern must update and rerun this policy check.

To install one, download the APK from the release page and
`adb install -r`, or hand it to whoever is testing.

GitHub release pages are retained more narrowly than their tags. After an
RC is published, `hack/prune-prereleases` deletes every legacy `-test.N`
release page and keeps only the highest `-rc.N` page for each target
version. Its APK goes with the page. The tags are never deleted: they keep
`versionCode` allocation monotonic, number later candidates correctly, and
remain burned names under GitHub's immutable-release rules.

### Google Play

Play is the third channel, after the GitHub release and F-Droid, and it
is deliberately the least load-bearing of the three. The same tag that
publishes the release also builds an app bundle and pushes it to the
**internal testing** track, then promotes that same build to the
**closed** track named `Testing`:

```bash
make bundle     # ./gradlew bundlePlayRelease, for Play only
```

The second track is not decoration. Internal testing holds a hundred
hand-listed addresses and hands out its own opt-in link; the closed
track is the one testers are recruited into (issue #62), the one
`https://play.google.com/apps/testing/com.chmouel.liseur` leads to, and
the only one whose opted-in testers count towards the twelve Play wants
for fourteen days before a personal developer account may publish.
Uploading to internal alone, which is what happened up to 0.10.0, leaves
everyone who followed the instructions on whichever build the closed
track was last given by hand (0.9.3, as it turned out, four releases
back). `hack/store-status` now says so in a line when the closed track
falls behind internal, because nothing else did.

Nothing about the APK path changes. F-Droid's recipe builds
the `foss` flavor (`gradle: [foss]`, so `assembleFossRelease`) and never sees `fastlane/Fastfile`, no Gradle
publishing plugin is applied, and the bundle is an extra output rather
than a replacement, which is also why the Play step in
`.github/workflows/release.yml` is `continue-on-error` and skips itself
entirely when the service account secret is absent. A fork, or a rejected
upload, must not be what makes a release fail. `hack/release --no-play`
turns that skip into something you can ask for: its annotated tag carries
`release:no-play`, and the workflow checks that marker before it considers
the stored credential.

The upload runs `fastlane android internal` with
`SUPPLY_JSON_KEY` pointing at a service account credential written to
`$RUNNER_TEMP` and removed by a trap in the same step. The credential is
`android/google.play.service.serviceaccount` in `pass`, shared with the
other apps on the account; it needs *Release apps to testing tracks* and
*Release to production, exclude devices and use Play app signing* on Liseur
under Users and permissions.

A build that is already on internal, a release whose Play step was
skipped, or one that predates the promotion, can be pushed across
without rebuilding:

```bash
fastlane android promote version_code:19
```

The version code defaults to whatever `app/build.gradle.kts` currently
says, so the argument is only needed for an older build.

**Who can actually install it.** Everything above puts a build on a
track; none of it lets anyone in. The tester list is console state, and
these are the things that quietly turn a correct opt-in link into "this
app isn't available":

- The closed `Testing` track needs an **email list** attached, holding
  the Google account each tester uses *on their phone*. `hack/store-status`
  says which of the two kinds of list the track is on, but it cannot say
  who is on an email list: `edits.testers` models only Google Groups, and
  its own reference says email lists are not supported by the resource.
  That address goes on by hand in the console, and nothing here can do it
  for you.
- The console accepts either an email list or a Google Group, not both.
  The console offers Email and Google Groups as one choice, not two, and
  the API has only a `googleGroups[]` field. Attaching a group cuts off
  every tester already on the email list until each of them joins it,
  which restarts the fourteen-day clock this whole exercise is running
  down. Two things are worth knowing before reaching for one anyway: the
  trap that made the group fail before is avoidable, because a consumer
  group's owner can *directly* add members rather than wait on join
  requests, but a group still buys no automation. Consumer
  `@googlegroups.com` membership has no API of any kind, Admin SDK and
  Cloud Identity both being Workspace-only, so it moves the clicking from
  one web UI to another.
- Country availability is **not** the reason, and has not been for as
  long as anyone has been asking. The track is open to 176 named
  countries *and* to the rest of the world, so there is no country left
  over to be missing; `hack/store-status` prints this outright so the
  question stops being guessed at. Russia is on the list and works: Google
  paused Play *billing* there in 2022 and stopped paying developers with
  Russian bank accounts in 2024, and both notices say in terms that free
  apps still download. Only North Korea and Syria are absent, which is
  Google's sanction policy rather than a switch in the console.
  This is track-level console state, and the release carries no
  `countryTargeting` of its own, which is why a `supply` upload has never
  disturbed it.
- The track's release must be `completed`, not draft. `hack/store-status`
  prints the status of each one.
- Adding an address is not instant. Play takes a few hours to
  propagate, and up to a day or two to move the opted-in count, so a
  tester told to try again should be told to wait.
- If the store still says the app is not available after all that, the
  usual cause is on the tester's side: the account signed into the Play
  Store on the phone is not the address that was added. A browser signed
  into a second Google account looks exactly the same.

The service account has app-level access to Liseur and must retain both
*Release apps to testing tracks* and *Release to production, exclude devices
and use Play app signing*. The first covers testing uploads, promotions and
every read `hack/store-status` makes; the second lets final releases publish
to production. Nothing in the release workflow edits tester lists.

Final releases promote the build already on **internal** to the
**production** track. Play refuses a second upload of a version code it has
already seen, so production reuses the internal artifact rather than sending
the AAB again. RC and legacy test tags never enter production, and
`--no-play` skips both testing and production uploads. A production promotion
failure remains non-load-bearing for GitHub and F-Droid, but the workflow
reports it clearly.

The release lanes push only the changelog. The phone and tablet
screenshots are pushed separately (see *Play screenshots* below). The rest
of the store listing is edited in the console, because Play holds
declarations that no file here describes (data safety, content rating, target audience, app access, ads),
and those have to be revisited whenever the app gains a permission,
talks to something new, or changes what it stores. The privacy policy
Play links to is `docs/PRIVACY.md`, served by GitHub Pages from `main`
`/docs`; it is a published legal document, so change it in a commit and
not in the console.

Production publication is automated for every final release. **Before the
first production release**, finish the Play listing rather than
treating the existing metadata as proof that the store page is launch-ready.
Review the title, short and full descriptions, icon, feature graphic, phone
and tablet screenshots, contact details, privacy-policy URL, data safety,
content rating, target audience, app access, ads declaration and country
availability. The repository's starting copy and assets live under
`fastlane/metadata/android/en-US/`; refresh them when they no longer show the
current app. The screenshots among them are synced automatically (see *Play
screenshots* below); the rest is copied into the console by hand. Verify the privacy URL anonymously before publishing the first
final release.

**Play screenshots.** The phone and tablet screenshots are the
repository's, not the console's. After a final release's production
promotion succeeds, the workflow runs `fastlane android screenshots`,
which compares the files under `fastlane/metadata/android/en-US/images/`
with Play's in order: an unchanged set uploads and deletes nothing, and
anything else is replaced to match the repository, including screenshots
edited in the console since. RCs never touch it, because the listing is
shared by every track and must not show what production cannot install.
A successful sync means the edit was committed, not that Play's review
has finished. A failed sync warns in the job summary and does not fail
the release; the next final release catches up, or run it by hand:

```bash
fastlane android screenshots version_code:19
PLAY_VALIDATE_ONLY=true fastlane android screenshots   # dry run
```

The dry run still uploads the images, into an edit that is validated and
then dropped: the live listing does not change, and Play accepting the
edit says nothing about how its content review will go.

Play refuses a screenshot with an alpha channel or a long side over twice
the short one, and a phone captures RGBA at 1080x2400. `hack/screenshots`
therefore runs `hack/store-images` on every image it files, which flattens
the alpha and pads phone shots to 1200x2400 by repeating the edge pixels;
the lane runs `hack/store-images --check` before it opens an edit. F-Droid
reads the same files from the tag it builds, so it gets the padded set too.
Do not run the lane by hand or edit the console screenshots while a release
workflow is running: both open Play edits, and only one of them wins.
The service account needs *Manage store presence* on Liseur (an app-level
grant, not an account-wide one) for this step.

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

### The two edge-to-edge advisories

Every release raises the same pair of warnings on the Play Console, and
neither is a defect report:

> Edge-to-edge may not display for all users.

> Your app uses deprecated APIs or parameters for edge-to-edge.

The first is attached to every app targeting SDK 35 or later. It asks us
to test, not to change anything. The app has drawn edge-to-edge since it
started targeting 35: `MainActivity` and `ReaderActivity` both call the
no-argument `enableEdgeToEdge()`, no theme sets `android:statusBarColor`
or `android:navigationBarColor`, and the manifest carries no opt-out.

The second is static analysis of the bundle's bytecode, and it does not
say whose bytecode. Ours calls none of the deprecated setters. Every
flagged reference belongs to androidx:

| Artifact | `setStatusBarColor` | `setNavigationBarColor` | `setDecorFitsSystemWindows` | `setSystemUiVisibility` |
| --- | --- | --- | --- | --- |
| `androidx.activity:activity` | 4 | 4 | 4 | 0 |
| `androidx.core:core` | 1 | 1 | 4 | 7 |

They are the version-guarded backports inside androidx's own
`EdgeToEdge`, `WindowCompat` and `WindowInsetsControllerCompat`, which
are the classes Google's own migration guide sends apps *to*. There is
nothing to migrate away from, and hand-rolling around them to quieten a
console page would be strictly worse. The warning stays until androidx
changes, so treat it as noise rather than as something to fix before a
release.

Re-check the claim, rather than trusting this table, if androidx moves:

```bash
activity_version=$(sed -n 's/^activityCompose = "\(.*\)"/\1/p' gradle/libs.versions.toml)
unzip -p "$(find ~/.gradle/caches/modules-2/files-2.1/androidx.activity/activity/"$activity_version" \
  -name '*.aar' -print -quit)" classes.jar > /tmp/a.jar
unzip -p /tmp/a.jar '*.class' | grep -ao setStatusBarColor | wc -l
```

What the first advisory *is* good for is prompting an actual look. Doing
that once turned up two real inset bugs the console never mentioned:
the reader's search results were laid out 820px beneath the keyboard
where nothing could reach them, and neither the search nor the contents
screen kept clear of a display cutout in landscape. Both screens pinned
`contentWindowInsets` to `WindowInsets.systemBars`, which omits the IME
and the cutout; `safeDrawing` covers all three. An emulator with a cutout
is the way to see it:

```bash
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall
adb reboot   # the display picks the cutout up on boot, not on enable
```

### Release notes

Two things are written for every release, and they are not the same
thing:

- The F-Droid changelog is
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
  Written by hand, capped at 500 characters, and passed to
  `hack/release` as the release notes argument. This is what F-Droid
  shows.
- The GitHub release notes are generated during the release by
  `hack/generate-release-notes`. It asks Gemini to turn the commits
  since the previous tag into something a reader would want to read,
  using the hand-written changelog as the summary to lead with. It also
  gives Gemini the associated pull-request descriptions, links changes
  back to those PRs, and carries a relevant screenshot over from a PR
  body when one is available. Those screenshots are re-sized to a fixed
  width (`SCREENSHOT_WIDTH` in the script) before the notes are written,
  so a full-size PR image cannot blow the page apart.

`hack/release` writes these notes before tagging a final release, with
`--range` and `--changelog`, and opens them in the editor for review.
They are committed with the release as
`docs/release-notes/vX.Y.Z.md`, and the release workflow publishes that
file as it is. When Gemini fails locally, `--copilot` asks
`copilot -p` (`gpt-6-luna`, or `LISEUR_NOTES_COPILOT_MODEL`). If both
fail, or the file is saved empty, nothing is committed and the workflow
generates the notes itself as below.

In the workflow, without `GEMINI_API_KEY` or if the request fails, the
generated notes fall back to the hand-written changelog, so a release
is never held up by this. The key comes from `pass` under
`google/gemini-api` and is uploaded to the release environment by
`hack/release --sync-secrets`.
The generator keeps the prompt compact by omitting commit diffstats and
retries transient Gemini or network failures with bounded backoff before
using that fallback.

The notes can be rewritten after the fact: the body of a release stays
editable even though its tag and assets do not:

```bash
GEMINI_API_KEY=$(pass show google/gemini-api) \
  hack/generate-release-notes --output notes.md v0.2.0
gh release edit v0.2.0 --notes-file notes.md
```

### Never delete a final release or release tag

Releases are immutable once published, and that goes further than the
assets: **a tag that has carried a release can never be used again**,
even after deleting both the release and the tag, and even with the
feature turned off. GitHub does this so that a trusted artifact can
never be swapped for another under the same name.

So a final release that went out wrong is not fixed by deleting it. Bump
the patch version and release again. `v0.1.0` was burned exactly this way
and is why the first published version is 0.1.1.

The deliberate exception is the GitHub **release record** for a prerelease.
`hack/prune-prereleases` removes obsolete RC pages and their APK assets to
keep the releases list useful, but never removes the tag. The name remains
burned and the source commit remains reachable, which is also why neither
the script nor the workflow ever uses `gh release delete --cleanup-tag`.

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
hack/screenshots --no-dictionary # skip the one capture that needs the network
hack/icon                   # fastlane icon.png, from the vector drawables
hack/feature-graphic        # fastlane featureGraphic.png, from the brand emblem
```

`hack/screenshots` drives a connected device through adb and writes both
`docs/screenshots` and the matching fastlane directory. The fastlane copies
go through `hack/store-images`, which makes them acceptable to Google Play
(see *Play screenshots*); `docs/screenshots` keeps the raw captures. It finds controls
by what they say rather than by where they sat when it was written, so a
moved button is something it waits for and fails on, not a tap into empty
space.

There are two sets, because fastlane and F-Droid publish
`phoneScreenshots` and `tenInchScreenshots` separately and the README
embeds the phone set by name. Which one a run writes is decided by how
wide the device is (under 600dp phone, above it tablet), so a capture
against a tablet cannot quietly overwrite the phone images. Pass
`--class` for a device whose shape does not match how its pictures
should be filed.

The phone set is the full tour, gathered on
`docs/SCREENSHOTS.md`; the README shows three of them. The tablet set is
three pictures of what a phone cannot show (two columns, the control
that chooses them, and a shelf with room on it) and lands in
`docs/screenshots/tablet`. There is no point photographing the settings
screen twice, and the search and dictionary steps that make the phone
run slow are skipped, so a tablet run takes a few minutes.

`--setup` builds the shelf from nothing: it downloads a handful of
[Standard Ebooks](https://standardebooks.org) public domain editions
(their cover art is what makes the library screens publishable), pushes
them to the device, grants the folder through the real picker, and leaves
the first book part-read with three highlights, a note and a bookmark on
it. Each of those is checked against the database afterwards, because a
highlight that quietly failed looks exactly like one that worked. One of
the three marks runs down the page rather than along a line, because the
notebook only offers to open a mark out once it is longer than four
lines. `--setup` also writes six weeks of sittings into
`reading_sessions` with `run-as sqlite3`: the stats screens count from
the moment a book is first opened, and a shelf seeded an hour ago has
nothing on them. Run it once; later runs can drop `--setup` and take
about ten minutes.

The definition card is the one capture that needs the device to reach
something: the run turns the lookup on in Settings, presses words until
one comes back with senses on it, and puts the switch back where it found
it. It used to keep the previous image and carry on when nothing came
back, which is how a stale picture survived several releases. It now
fails. `--no-dictionary` is how to say an offline run was expected.

Read aloud is captured with device voices, which need no server and no
key. The run switches the service to them in Settings, starts reading
from a word on the page, waits until it is actually speaking, and puts
the previous service back at the end.

`--empty` is its own mode, and short: the empty library is the one screen
the tour cannot reach, because the tour needs a shelf with books on it.
It wipes app storage, photographs what a new reader sees, and stops. Run
it whenever the empty state changes; the rest of the tour would only put
the demo shelf back.

Everything that gets published is in the light theme. A dark screenshot
in a store listing reads as the app looking like that, rather than as the
app being able to; the dark theme earns more as a line in the description
than as one picture in seven that matches none of the others. The script
still captures `11-reading-dark` and `17-empty-library-dark`, which are
useful to look at, but it just does not file them with fastlane or the
README.

Sign out of calibre-web first unless your server holds only books you
would publish a picture of. The script itself never writes to one.

Look at the images before committing. A screen can be found and still be
showing the wrong thing:

```bash
montage docs/screenshots/*.png -tile 6x2 -geometry 320x+6+6 /tmp/sheet.png
```

## Translations

English UI copy lives in `app/src/main/res/values/strings.xml`. French,
Spanish, Russian, Italian, German and Simplified Chinese ship beside it as
`values-fr`, `values-es`, `values-ru`, `values-it`, `values-de` and
`values-b+zh+Hans`.
Strings that only the `play` flavor uses live in `app/src/play/res` with
the same locale set.

- New user-facing text goes in the English file first, then in each
  locale file, and is read with `stringResource` / `pluralStringResource`.
  Mark brand and product names `translatable="false"` — those keys are
  omitted from locale files.
- Russian plurals need `one` / `few` / `many` / `other`; Chinese only
  `other`.
- The app follows the system language and falls back to English for
  missing keys. `resourceConfigurations` must not freeze the APK to a
  language list.
- The in-app language picker sits at the end of Reading appearance's
  Advanced section, not in the reader's "Aa" sheet: changing it restarts
  the activity. `ui/settings/AppLocales.kt` owns it. On Android 13+
  `LocaleManager` is the only record, shared with the system's per-app
  language screen (`generateLocaleConfig` lists the `values-*` folders;
  `res/resources.properties` names English as the default). Below 13 the
  choice is a `SharedPreferences` entry, read in each UI activity's
  `attachBaseContext` and applied with `applyOverrideConfiguration`
  carrying only the locale, so activities that handle their own
  configuration changes keep following orientation and night mode. A new
  UI activity needs the same override. Below 13, widget text keeps the
  system language, while dates and week starts follow the process default
  locale, which `LiseurApplication` points at the chosen language at
  startup and after every configuration change (the platform resets it
  there), as 13+ does. On 13+ the platform sends `LOCALE_CHANGED` to the
  app on a per-app change, so the widget receivers redraw. A new
  language also goes in `AppLanguage` and the Makefile `locale` lists.

See [`docs/TRANSLATING.md`](docs/TRANSLATING.md).

## Launcher shortcuts

Long-pressing the launcher icon offers Continue reading, Library, and Reading
stats. These are static shortcuts, available before the first app launch.
`res/xml/shortcuts.xml` defines their order and icons; the dev resource overlay
targets `com.chmouel.liseur.dev` so it cannot open the production app.

Continue reading ignores the automatic-resume preference and last-screen flag.
It chooses the latest eligible local book using the later of its opening time
and its reading time, including synced reading. Hidden, archived, unavailable,
marked-finished books and progress at or above `FINISHED_PROGRESSION` (97%) are
excluded. A saved locator counts as reading even when progression is unknown;
an empty placeholder progress row does not, and its timestamp is not ranked.
If the latest book is ineligible, the next eligible book is chosen. With no
candidate, the Library opens.

Android starts the first static shortcut intent with `NEW_TASK | CLEAR_TASK`.
Each shortcut replaces the existing app task; Back from a continued book or
statistics returns to Library. Accepted reader writes survive task destruction.
Continue waits for `ReadingPositionPublisher.flushLastReader()` to succeed
before querying candidates, since a pending completion can change eligibility.
A failed save opens Library with an explicit error instead of reopening stale
state.

`ui/launch/` owns a shared request stream for shortcuts and widgets. The latest
request wins; older asynchronous results cannot navigate. Only the three fixed
shortcut actions are accepted from exported `MainActivity`. Widget book targets
still enter through unexported `WidgetLaunchActivity`, never public intent
extras. A downloaded-book widget tap opens the reader directly, and any fresh
reader start clears a request that is still pending. It also stops the library
from opening a server book whose download finishes after that reader started.
An unfinished shortcut is saved across activity/process recreation;
handled requests do not replay.

Labels are translated in all bundled languages. Android/the launcher resolves
static labels, which may follow the system language rather than the in-app
language. The API 36 Pixel launcher also kept system-language shortcut labels
when only the app language was changed.

For manual checks, use a disposable emulator and the actual launcher menu.
Verify the menu before first opening the app; all three actions from a cold app,
Library, Settings and the reader; repeated taps and Back; and dev/production
package isolation. Continue must work with automatic resume disabled, skip an
ineligible newest book, and fall back on an empty shelf. In paginated and
scrolled reading, move immediately before leaving for Home and verify the
shortcut reopens the saved locator and ends the old session once. Also check
rotation, a widget tap superseding a pending continuation, ordinary startup
and translated labels. Flag-less `adb` action dispatch does not exercise
static-shortcut task replacement.

## Architecture

`AGENTS.md` contains the short, immediately actionable agent rules. This
document is the source of truth for the detailed architecture and
implementation invariants below.
Key decisions:

- Readium Kotlin Toolkit (`readium-shared`, `readium-streamer`,
  `readium-navigator`, `readium-opds`) does EPUB parsing, rendering, and
  OPDS feed parsing. `readium-lcp` is deliberately excluded, since it
  depends on the proprietary liblcp, incompatible with F-Droid.
- calibre-web integration is two protocols: OPDS for browse/search/
  download, and the Kobo sync protocol (`/kobo/<token>/v1/...`) for
  reading-position sync, exchanging percentage progression like KOReader
  does.
- Komga integration is Komga's own REST API: `POST
  /api/v1/books/list` to browse, `GET /api/v1/books/{id}/file` to
  download, and `GET`/`PUT /api/v1/books/{id}/progression` to sync a
  full Readium locator rather than a percentage.
- One server is connected at a time. `data/remote/` holds provider-neutral
  contracts (`CatalogSource`, `FileSource`, `ServerSetup`,
  `PositionSync`); `data/calibre/`, `data/komga/` and `data/bookorbit/`
  implement them, and `RemoteRouter` picks the implementation from the
  connected server's `ServerKind`. `domain/ReadingStateMerge.kt` is shared
  by all of them, so the conflict rules are written once.
- BookOrbit integration is BookOrbit's own REST API under `/api/v1`,
  signed with a session rather than a long-lived secret. It browses with
  `POST /books/query` and downloads with
  `GET /books/files/{fileId}/download`; a book's files are chosen once
  and remembered in `book_orbit_binding`. It carries an exact EPUB CFI,
  but Liseur cannot read or write one yet, so it ships as browse and
  download only and advertises no position sync. See
  [BookOrbit protocol](#bookorbit-protocol).
- Single `:app` module, manual DI composition root, `ViewModel` +
  `StateFlow`, Room + DataStore for persistence.
- The main activity's screens are a Navigation 3 back stack of
  `ui/navigation/Route`s shown by `NavDisplay`, saved with our own
  `Saver` rather than kotlinx-serialization. Back pops the stack, with
  predictive back except on e-paper, where every transition is off. Only
  the entry on top gets an enabled navigation-event dispatcher, so a
  screen's own `BackHandler` never fires while it is animating out or
  previewed. ViewModels stay activity-scoped and shared between screens;
  there is deliberately no per-entry ViewModel decorator. The reader is a
  separate activity and does not use it.

## Implementation invariants

These rules are intentionally kept with the detailed developer reference
rather than repeated in the short agent guide. They describe contracts whose
failure can look correct in a narrow test while corrupting state or changing
reader behavior.

### Remote providers and reading state

- One server is connected at a time. Provider-specific behavior belongs behind
  `data/remote/` contracts; callers use `RemoteRouter` rather than branching on
  `ServerKind`.
- Reading positions are Readium `Locator`s locally. calibre-web exchanges
  percentage progression, while Komga and liseur-sync exchange full locators.
  All providers use `domain/ReadingStateMerge.kt` for conflict rules.
- A renewable credential belongs to one published connection. BookOrbit's
  access token expires and its refresh token rotates, so `BookOrbitSession`
  serialises renewal and writes the result only if the row still carries the
  epoch and refresh token it started from; a renewal that no longer owns the
  connection is dropped rather than written. The stored access token is
  almost never the one that signs an API call: token refresh is also expected
  on downloads and covers, and a token the server has refused is remembered
  so it is not read back from the row and sent again.
- A remote file binding is chosen once. `book_orbit_binding` maps an account
  and a book to the server file its reading belongs to, and a refresh must not
  move a book to a different file because the server promoted another edition.
- Provider identity is namespaced when the server's ids are not globally
  unique. BookOrbit and a Custom OPDS catalog both put a digest of the address
  and account in front of the id, because a downloaded book keeps its
  `books.url` across a change of server.
- `updated_at` is when this device wrote a row and is used for derived sync
  ids and outgoing client timestamps. `read_at` is when reading happened and
  must remain the source for Recent and Continue Reading ordering. Those
  queries use `COALESCE(read_at, updated_at)` so legacy rows without `read_at`
  retain their correct order. A row with neither progression nor locator is
  not a reading.
- In scrolled reading, Readium's last locator may lag the viewport. Refresh
  through the current scrolled-place helper before any action that depends on
  the current position.

### liseur-sync positions and accounts

- Historical furthest and current position are separate facts. Room 66 keeps
  locally authored peaks alongside `reading_progress` and complete remote
  operations in `furthest_position`, partitioned by account, work, and edition.
  Preserve each snapshot candidate by operation identity, including optional
  `origin_alias` provenance in its original JSON. Compare valid fractions strictly,
  without the merge tolerance; jumps count, and rereading or marking unread
  never resets them.
  Migration 66 retains each valid pre-upgrade position with its original locator,
  reading timestamp, and known edition. Its author is unknown, so a null
  `peak_revision` keeps it available for explicit recovery without uploading it
  as locally authored reading. A later strict increase records a local peak.
  Catalog downloads can have a verified edition in `work_alias` without a
  local fingerprint row; preserve that edition in locally authored peaks too.
  Every changes-page operation, including this device's echoes, is observed
  before selecting the newest foreign operation for ordinary reconciliation.
- Positions and heads snapshots supply a separate `furthest` array. Its
  historical entries never become pending conflicts or automatic opening
  destinations. A present array replaces that snapshot's cached observations
  so split-off editions do not remain attached to the old work. A missing
  array is an older server: retain observed positions without claiming a
  global lifetime maximum. The manual action says "Furthest known position"
  and also works from durable observations while offline.
- Before sending a coalesced lower current position, deliver its stored local
  peak with a deterministic, separately namespaced operation. `peak_delivery`
  tracks only this peak's acknowledgement, not a general operation queue.
  Make a fresh current revision owed before every unacknowledged peak attempt,
  and do not send current until the peak is acknowledged. A missing answer retries
  the same peak; a failed current request leaves current owed. Remote
  observations are never used as locally authored peak uploads.
- Historical adoption checks the displayed candidate, account, work, edition,
  and local revision in one transaction. The reader captures its current
  scrolled place before acting. The action may use the one revision created by
  that capture only if the offered row was still current and the captured
  passage is unchanged; other writes or reader movement invalidate the choice.
  Adoption still checks that captured revision atomically. It uses the normal locator restoration and
  way-back path. Existing automatic farther-current conflict resolution on
  opening remains unchanged. Other providers retain their existing protocols
  and do not advertise this historical action. An upgrade cannot recover
  already discarded local positions or server history compacted before
  lifetime retention was installed.
- liseur-sync is an append-only log, not a current-position store. Apply a
  changes page and advance `remote_server.sync_cursor_seq` in the same
  transaction, never before applying the page.
- Operation ids are derived from the device, work, and revision; session ids
  are derived from the device and stored local session id. Payload fields come
  from stored state. A retry must be byte-identical and must not require
  random ids or a `pending_ops` table.
- Reconnect with the stored device id. A pasted token that reconnects as a
  different device can return `conflict`; advance that book's revision
  conditionally from the revision that was sent before retrying.
- Naming work is a bounded resource. Hash-based and catalog-based resolution,
  batch resolution, seed requests, incomplete outcomes, retry decisions, and
  refusal handling must follow the coordinator rules rather than being
  reimplemented at call sites.
- A batch refusal concerns only the named item. Keep unrelated items pending,
  split oversized batches, and leave unknown refusal codes pending. Persist a
  session refusal only for a known permanent reason; an unnamed refusal must
  not settle an arbitrary item.
- The account key is `liseursync|<url>|<account_id>`. Rekey all peer-scoped
  tables transactionally in `carryPeerState`, and clear them through
  `RemoteAccountRepository.forgetSyncPeer()`.
- Uploads are opt-in through both the server capability and folder
  capability. Successful upload is adoption: preserve the local `books.url`
  and only link the remote identity and download URL.
- Live notifications are topic-only hints routed through `data/remote/`.
  Their foreground connection is separate from the full-sync debounce and
  identifies the account and credentials, never a cursor or timestamp. Topic
  refreshes share `PositionSyncCoordinator`'s turn lock, and an invalidation
  during a refresh stays owed. Do not call `syncAll()` for an invalidation or
  bypass cursor and annotation reconciliation. Signal local annotation edits
  after commit. An incoming position waits until the reader resumes; catch-up
  acceptance binds the original account, peer, fingerprint, and local
  revision. See `docs/adr/0023-position-sync-versus-whispersync.md`.

### Settings and statistics

- Settings stay on the device (ADR-0041). The server keeps a per-device
  copy and this device is its only writer: a key never stored on the
  account is restored from the server, anything else that differs is
  uploaded. Never read settings from a server that does not answer
  `scope: device`; it shares one copy across devices.
- Do not apply a restored setting that changes the open page while a book
  is open. Decide this per write with `SyncableSetting.affectsOpenBook`.
  Device-shaped settings are never backed up.
- Settings values must be validated before a batch request. Reject values the
  server cannot store, and preserve the sentinel used for absence because the server has no delete.
- Re-check the connected account before every network side effect and again
  before committing its response. A stale account must neither receive a
  request nor store its answer.
- Server statistics are optional decoration. Failures remain null and
  silent; cross-device counts are shown only when the stored evidence proves
  the merge, otherwise the local-only result and provenance are used.

### Annotation synchronization

- An annotation id is derived from mutable content only until it becomes
  editable; thereafter `annotation_sync` stores the server acknowledgement and
  the exact pending request bytes. Write those bytes before the call and
  replay them verbatim through `postRaw`. Do not add a queue: it would be a
  second copy of the truth and can drift from the live annotations table.
- The acknowledged fingerprint excludes request metadata such as `base_rev`
  and `edition_sha`; the pending fingerprint identifies the request so an
  answer cannot overwrite a newer local edit.
- Annotation freshness is ordered by server `seq`, never `rev`, because a
  recreated annotation can restart its revision at one.
- Run annotation synchronization in this order: settle, pull, reconcile,
  push, deletes. The pending set and cursor are account-wide even when a run
  is book-scoped. Conflicts are server-wins.
- Reconcile a work before pushing a mark, and push only marks seen in that
  pass's live set. Use the same `offerable()` predicate for both selection
  steps. Compare conflicts with the content that was sent, not a newer row.
- Treat absent, null, empty-string, and empty-object locators as no locator.
  `BOOK_NOTE` is a standalone note and must stay distinct from a passage
  `NOTE`. Preserve the known local `bookId` for standalone notes.
- Annotation URLs must accept opaque ids but decline `.` and `..` as
  addressable delete targets. Re-read the account and the annotation's home
  alias inside the transaction that commits each response, and check the
  account before every network request.
- Removing a book preserves its annotations and sync rows. Only
  `BookRemoval.contentReplaced()` clears them when a different file takes
  over the path.

### Reader behavior

- Bookmark identity comes from `samePage()` and the two locators, not from the
  rounded stored page number. Resolve duplicate reading-order entries by
  occurrence; use the stable Readium position for scrolled books.
- The paginated reflowable footer is display-only. It counts measured
  screenfuls through `SectionScreenProgress` and `BookScreenEstimate`, keyed
  by reading-order index. Reset estimates on layout changes, reject stale
  measurements, and never store or sync the displayed page count.
- `readingColorScheme()` supplies the Material theme for the entire reader
  activity. Keep dynamic color outside the reader, use opaque mixes, and keep
  `surfaceTint` transparent and `scrim` black. The loading indicator is the
  one exception: it keeps the app's accent through `loadingAccentOn()` when
  that accent holds 3:1 against the page. Both `ReaderLoadingScreen()` call
  sites must be handed the same computed value. The tone still follows the
  page, so a light app opening a dark page moves from the light scheme's
  accent to the dark scheme's when preferences land. The hue stays put only
  while the app's accent clears the guard; an accent too close to the page
  falls back to the page's own, which is the point of the guard.
- Page turning is a three-way `PageTurnStyle` choice. Resolve e-ink and
  system-motion overrides in `pageTurnStyleOnScreen`; do not duplicate the
  motion check elsewhere. A dragged lift turn must photograph the publication
  view, restore with the exact locator, and abandon on lifecycle changes.
- Reader chrome reaches the screen edge. Keep navigation-bar insets inside
  `ReadingScrubber`, use its spacer when the panel is absent, and skip edge
  fades on e-ink.
- While the way-back pill shows, system Back takes it, through the same
  `takeJumpBack` path as a tap (#275). The bars keep their sticky
  behaviour, so with the chrome hidden the first edge swipe only reveals
  them and the second goes back rather than leaving the book. The jump
  `BackHandler` is registered before the overlays' handlers so they still
  close first.
- Runtime page repair must write only token-owned attributes. Keep
  `WideContentFit`, `FootnoteLayout`, and `SelectionHandleFix` idempotent and
  preserve authored classes and markup.
- The selection bar must not dismiss on an outside touch while the web view
  holds a live selection. The handles are separate windows, so grabbing one
  counts as an outside touch, and dismissing clears the selection under the
  drag (#257). The web view clears a live selection on a tap elsewhere by
  itself; only a tapped mark, which has no platform selection, relies on the
  outside touch.
- The selection bar keeps one height. More swaps its row for Read aloud
  and Translate in place, rather than opening a menu (a second window the
  bar would read as an outside touch) or growing (the placement assumes a
  fixed height and would then cover the selection). Labels stay on one
  line and scroll when long. See
  `docs/adr/0047-the-selection-bar-keeps-one-colour-and-a-more.md`.
- A page's CSS `prefers-color-scheme` follows the reading page, not the
  system. WebView reads it from `android:isLightTheme` on the reader
  activity's theme, so `showPagesAs()` forces a `PageColorScheme` style
  onto that theme and sends a configuration change to the WebViews on
  screen, which repaints them without a reload. Standard Ebooks inverts
  its line art under that query (#256). The attribute exists from API 29;
  below it, pages still follow the system.

### Read-aloud

- The engine, player, settings screen, device voices and speech server
  provider live under `app/src/main/kotlin/.../tts/`; Gemini (`GeminiSpeechService`,
  its client and voices) lives under `app/src/play/`. The reader sees the
  feature only through the `ReadAloudFeature` interface and
  `OpenBookHandle`. Nothing in `main` may name Gemini; see
  `docs/adr/0044-read-aloud-in-the-f-droid-build.md`.
- A listening session owns the reading place while it plays. A page move
  the reader makes leaves the voice playing and is not saved: the page
  stops following the voice until the spoken sentence is on screen again
  or the voice is paused and resumed, and pausing brings the page back to
  the sentence heard. Only the voice writes the place while it plays, so
  two owners never write the same row. Auto-scroll and the voice exclude
  each other: starting one pauses or disarms the other.
- Checkpoints go through the handle's `prepareLocator`, so a heard sentence
  stores the same `total_progression` a page turn to it would. Checkpoints
  while playing are local (`signalSync = false`); the one written on pause
  or stop syncs.
- A heard sentence's progression counts paragraphs, not screens, so the
  place it saves is not an exact anchor. While `awaitingPageCapture` is set,
  the reader re-captures the page on screen once the voice stops and saves
  that as the exact anchor (and the BookOrbit CFI). The ViewModel must not
  drop that capture as an unchanged position. On opening, the wide-content
  fit restores to the gate's non-exact target instead of capturing the
  page, which Readium has not scrolled yet.
- Providers are chosen on the Read aloud screen reached from its row on
  the main Settings list, under Reading & navigation: Gemini (Play only),
  device voices, and any server listed on the Services page speaking
  OpenAI's API (OpenAI, a hosted service, or a self-hosted server such as
  Kokoro). Each is a
  `SpeechService` (`GeminiSpeechService`, `DeviceSpeechService`,
  `OpenAiSpeechService`) that owns its settings
  rows, its voice catalogue and labels, and the `SessionVoice` a session
  reads with. The flavor's
  `ReadAloudFeatureFactory` passes the list to `SpeechReadAloud`; the
  device voices are the default in both builds for a reader who never
  chose; Gemini is an alternative in Play. Saved choices stay selected.
  If Android has no installed offline voice, read-aloud stays unavailable
  until the reader chooses another provider; it never switches to a network
  provider on its own. The picker hides itself when there is only one.
  Device voices list the default engine's installed voices that need no
  network (`DeviceVoices.offline`), numbered per language; the choice is
  `read_aloud_device_voice`, blank picks one automatically: for English
  and French `DeviceVoices.PREFERRED` (Google's `en-us-x-tpc-local` and
  `fr-fr-x-frd-local`) when installed, else the engine's default. A sentence
  is synthesized with `synthesizeToFile` to a throwaway file while
  `UtteranceProgressListener.onAudioAvailable` collects the PCM, which
  `DeviceVoices.toSpeechPcm` converts to 24 kHz mono 16-bit. One
  `TextToSpeech` is shared and shut down after a minute idle.
  Requests go to `<root>/audio/speech`, where `/v1` is added to an
  address whose path has none, asking for `response_format: "wav"`.
  A 400/422 whose message names `response_format` (OpenRouter takes
  only mp3 or pcm) is asked again for `mp3`, and that (root, model)
  then asks mp3 first, once it worked. Error messages are read from
  `detail`, `message` or `error.message`, only to classify them. The
  reply is decoded off the network thread by what it is: JSON with a
  base64 `audio_data` (Mistral) is unwrapped first and must hold WAV,
  or MP3 when MP3 was asked; `WavPcm` reads a
  WAV header and converts any rate or channel count; MP3 goes to
  `Mp3Pcm` (platform `MediaExtractor` + `MediaCodec`, injected into
  `OpenAiTtsClient` so JVM tests fake it); a reply without a header is
  taken as raw 24 kHz PCM. The settings screen fills its model menu
  from `<root>/models?output_modalities=speech` (OpenRouter lists its
  speech models only then; a 400/422 asks plain `models`). A model
  makes speech by the first thing its entry says: `output_modalities`
  or `architecture.output_modalities` (Groq, OpenRouter),
  `capabilities.audio_speech` (Mistral), `metadata.tags` with `tts`
  (DeepInfra). When any entry says, only speech models are kept;
  otherwise speech-looking ids, else all; 404/405 is no list. The price
  is `metadata.pricing.input_characters`, or OpenRouter's
  `pricing.prompt` when `pricing.completion` is 0, or Groq's
  `pricing.prompt` with no `completion` (on `api.groq.com` only). Voices are listed
  per model: `<root>/audio/voices` (`voices` or `items`, entries named
  by `slug`, `id` or `name`; a reply with `items` and `total` is read
  by `offset` to the end, and one that stops short is an error), else,
  for DeepInfra's root only
  (`OpenAiTts.modelDescription`), the voice enum in the public
  `https://api.deepinfra.com/models/<model>` schema, asked without the
  key; else OpenAI's standard voices for `api.openai.com` only; else
  for `api.groq.com` only, the voices Groq documents per model
  (`OpenAiTts.groqVoices`, all checked to speak; an unknown model has
  none); else
  the model's `supported_voices` in the model list (OpenRouter), and an
  empty list (a typed voice) anywhere else. Picking a model saves it
  with a voice that model lists (`VoiceChoice`) in one write
  (`setSpeechServerModelAndVoice`). Voices show as chips
  grouped by language, read from Kokoro's `af_bella` naming
  (`VoiceLabel`). A language with a country gets that country's flag
  (`VoiceLabel.flag`), which screen readers skip. Tapping a chip, or typing one, plays a fixed sample
  sentence through `SpeechReadAloud.preview`, which pauses any session
  first. Gemini plays one only on "Hear this voice", since each sample is
  billed. Gemini's model (`read_aloud_model`, blank is
  `GeminiTts.DEFAULT_MODEL`, Flash-Lite TTS) is picked from the key's
  `v1beta/models` ids containing `tts`, or typed. "Choose voices"
  narrows the chips to a ticked set
  (the server's `voices`, empty offers all; `VoiceLabel.offered` always
  keeps the voice in use). "Test connection" (`OpenAiSpeechService.test`)
  fetches both lists and, when a model and voice are set, synthesizes one
  fixed word, so a wrong key, model or voice shows before a book is
  opened. All providers return 24 kHz
  mono 16-bit PCM, so the engine, cache and `AudioTrack` output are
  shared; only the `SpeechSynthesizer` a session is built with differs.
  Servers and keys live on the Services page (`ServicesScreen`, reached
  from Settings and from "Manage services" in the voice service picker);
  `ServerConnections` owns adding, editing and deleting them, their keys
  and the per-origin generation that drops replies from before a change.
  A server is `ServerConnection(id, name, url)` in `ai_servers`; its id
  is the normalised base URL (`OpenAiTts.baseUrl`), so a duplicate URL is
  refused and an edited URL rewrites the id, the read-aloud reference and
  that server's state in one DataStore edit. Saved servers that do not
  parse, or state and a read-aloud reference naming a server that is not
  listed, are kept byte for byte, block edits, and read aloud falls back
  to device voices without rewriting its reference. A restored archive
  must leave every reference pointing at a listed server, otherwise the
  whole restore fails and nothing changes. A connection or key change
  stops only the playback, previews and requests on that origin (or
  Gemini); a restore that carries servers or a feature's choice of one counts
  as a change to every origin listed before it. Deleting a server
  points read aloud at device voices and drops a key only when no other
  server shares its origin. Read aloud's choice is `read_aloud_provider`
  (`openai` for a server) plus `read_aloud_server`; each server's model,
  voice and offered voices are kept apart in `read_aloud_server_state`,
  and its per-language voices in `read_aloud_voice_preferences` under the
  server's API root, so switching servers and back restores them.
  The legacy `speech_server_*` keys are migrated into one server in a
  single edit on upgrade, and an older backup's legacy keys are merged by
  URL on restore (`ServerSettings`).
  The address field's menu (`SpeechServerPresets`) fills in a hosted
  service's root, saving it and focusing the key field, or a Kokoro
  example address with its host selected. The key, address, name and a
  typed model save on Done, on focus loss (`Modifier.onLeaving`) and on
  dispose; key saves go through `KeyCommits` in submission order and a
  `KeyDraft` belongs to the server shown when it was started. Server and
  model commits run in the service's scope under one lock with a
  generation counter, so a superseded model choice or one for a previous
  server never writes; the write itself runs in
  `ServerConnections.unlessChanged`, so a key or address change cannot
  land between its check and its write. The editor's saves go through
  `ServerConnections.save` with a token per opening, so an editor
  recreated or closed mid-save follows the server it was adding or
  moving. A new or edited server is listed in
  `read_aloud_unsettled_servers` (local, not backed up) until its lists
  chose a model and voice; until then a refresh replaces the old model.
  The Services page's "Test connection" only checks that the server
  answers with the key; read aloud's own test stays on its page. Groq's
  `model_terms_required` error is `SpeechError.TermsRequired`, which
  stops the session like a refused key.
  The OpenAI-compatible provider runs one request at a time with long
  timeouts, since a small server can be slower than real time. Its
  notices name the server's host. See
  `docs/adr/0043-openai-compatible-read-aloud.md`.
- Engine callbacks arrive on the main thread. A failed sentence pauses on
  that sentence with a notice; play retries it. Nothing is ever skipped.
  A refused key or a voice the server does not have stops the session.
- Pausing keeps the paused sentence's audio (or its request still on its
  way) in `SpeechTtsEngine` and the sentences read ahead in the cache;
  `UtterancePrefetcher.pause` only stops reading further. Playing on
  resumes that sentence a second (`REWIND_FRAMES`) before where it was
  heard, fetching nothing again. A skip since the pause, a jump, a voice
  change or a retry after a failure starts the sentence over.
- Keys (Gemini, optional OpenAI-compatible) live in `noBackupFilesDir`,
  encrypted by `SecretCipher`, each in its own file, and are never logged.
  Speech server keys are kept per server origin (`ServerKeys`, file
  `openai-key-<hash>`); each request reads the key of the origin it
  calls, and the old single `openai-key` file is deleted unused;
  neither are request bodies or audio. The provider, the listed servers,
  each server's model, voice and offered voices, and the voices
  remembered per language, are app settings in the settings backup but
  not in liseur-sync settings sync, since a server address and the
  installed voices are per device. Listening is not counted as reading time.
- While a session exists, playing or paused, `ReaderActivity` hands the
  volume keys to the system with `STREAM_MUSIC` as its volume stream, so
  they set how loud it reads instead of turning pages.
- While reading aloud the page shows only the spoken sentence's highlight.
  The player controls appear with the rest of the chrome when the reader
  taps the page (`ReadAloudFeature.Player`'s `controls`), and go with it;
  notices such as a failed request still show on their own. A tap on the
  highlighted sentence toggles the chrome through the read-aloud decoration
  listener, so it never turns the page, even in a page-turn zone.
- A session reads in one language, chosen when it starts from the
  book's declared `dc:language` (`SpeechLanguage.ofBook`: tags
  normalized, `eng` is `en`, `fra`/`fre` is `fr`; none, `und` or several
  distinct ones are not decided). The language goes to the sentence
  tokenizer, the navigator's `SpeechTtsPreferences` and the prefetcher;
  the text itself is never inspected and the language never changes
  mid-session on its own.
- Each service lists a `VoiceCatalogue`: its voices with the languages
  they speak (`null` when unknown), its default and the global voice in
  the settings. Device voices use Android's locale, server voices
  Kokoro's naming (`VoiceLabel`; other servers' voices are unclassified),
  Gemini voices the languages its model documents (`GeminiLanguages`,
  bundled for Flash-Lite and Flash TTS; another model is unclassified).
  A server whose list fails gives `failed`, with the saved, ticked and
  remembered voices, so a network error is told apart from no voice. A
  server with no list still offers its typed voice.
- `VoiceResolver` picks, in order: the voice remembered for the book's
  primary language, the global voice if it speaks it, a voice for the
  exact regional tag, then any voice for the language; within a step the
  provider's default, then list order. "Choose voices" narrows what a
  server offers. An unclassified voice is only used once remembered.
  The service is never changed. When nothing is decided the start waits
  on a `PendingChoice` and the player opens the voice sheet before a
  word is spoken; dismissing it, leaving the reader, a new start or a
  change of service gives up the start and releases the book without
  touching the reading position.
- Remembered voices are `read_aloud_voice_preferences`, a JSON list of
  (provider, context, model, primary language, voice): context is the
  device engine's package or the server's normalized API root, model the
  server's or Gemini's. Explicit picks (the sheet, or a settings voice
  whose language is known or matches the session's) write the global
  voice and the language's entry in one DataStore edit
  (`AppSettingsRepository.editReadAloudVoice`), which first checks the
  scope is still the one shown, so a server or model changed meanwhile
  writes nothing. Automatic picks never write.
- The player's voice chip opens the voice sheet (`ReadAloudVoiceSheet`):
  a language menu (the book's and session's first; every language when
  some voices are unclassified), the voices for it grouped under the
  language with their accent's country, then the unclassified ones, and
  Apply. Looking changes nothing. Apply remembers the voice and the
  session reads on in it: `SpeechCache.swap` drops what the old voice
  fetched and `ReadAloudPlayback.replay` starts the current sentence
  again, playing or paused as it was; after a change of language the
  sentence may be cut differently, so `replay(recut = true)` lands on
  the one holding its start, or its element. A voice picked in the
  settings is heard the same way, staying in the session's language.
- Speed (`read_aloud_speed`, 0.75× to 2×, in the player) is applied by
  `AudioTrackPcmOutput` through `AudioTrack.playbackParams`, read on every
  write and poll: it keeps the pitch, applies mid-sentence, works for both
  providers and leaves cached audio valid. The output's stall deadline
  allows for the slowest speed.
- Sentences per request (`read_aloud_sentences_per_request`, 1 to 5,
  default 1, in the read-aloud settings, for every service) is read when a
  session starts. `BoundedSentenceTokenizer.factory(n)` joins n sentences
  (after short ones are merged) into one utterance while it stays within
  600 characters; the navigator and the prefetcher get that one factory,
  so they still cut text alike. The highlight and previous/next move by
  the whole group. `AppSettingsRepository` clamps it to
  `AppSettings.SENTENCES_PER_REQUEST`, so a restored 0 or 99 is read as 1
  or 5. It is in the settings backup (`BackupValueType.INT`) but not in
  liseur-sync settings sync. Play from a selection that opens its
  paragraph has no text before it to compare, so `SelectionTarget` checks
  what follows instead; a group can be longer than that, so it also
  accepts a group when everything the page gave after the selection
  agrees and reaches past the selected sentence.
- The sleep timer (5 to 60 minutes or "Stop at end of chapter") lives
  with the session rather than the reader, so it runs with the screen
  off. It counts on `SystemClock.elapsedRealtime`, which keeps time while
  the device sleeps, pauses the session when it fires, and is dropped
  when the session ends.
  The chapter option pauses before the next EPUB reading section is spoken,
  clears itself, and leaves that section ready to resume. It uses reading-order
  resources as chapter boundaries, like the reader's chapter matching; chapters
  sharing a single resource are not distinguished.
- The player is a card in the page's colours (`ChromeCard`; solid with
  an outline on e-ink) docked above the scrubber. Its first row is the
  voice chip, naming the session's voice and language
  (`ReadAloudSession.choice`, which may differ from the service's global
  voice), and Stop on the right. The second holds speed, previous,
  play/pause as a filled circle, next and the sleep timer. The speed is
  left out of the chip because the speed button already shows it.
- While the voice waits for audio (`ReadAloudUi.preparing`: the session is
  starting, or the engine is in `SpeechCache.take` for the playing
  sentence, reported through `SpeechObserver.onWaiting`), a five-bar
  `VoiceWait` mark replaces the play/pause icon, and a "Preparing the
  voice" pill shows when the controls are hidden. It appears only after
  400 ms and lingers 150 ms, so a fast voice never flashes it, and holds
  still on e-ink or with animations removed.

### Translation

- Selecting text in the reader offers Translate next to Search and Share
  when the chosen service can translate at all. The result opens in
  `TranslationSheet`: the original, quiet and italic, above the
  translation, then the two languages as buttons, Copy, and a line naming
  where the text went. The language lists open in place inside the same
  sheet, never as a second sheet. The feature lives in
  `app/src/main/kotlin/.../translate/`; the reader sees only
  `TranslateFeature` (`None` when nothing is wired), built by the flavor's
  `TranslateFeatureFactory`. Code that talks to Gemini, its client,
  address and model list, lives only in `app/src/play/`; `main` knows
  Gemini only as a service id and the settings that store its choice.
- Services: this phone (`DeviceTranslationService`, Android 12+
  `TranslationManager`, no library and no Google Play services), any
  server on the Services page speaking OpenAI's chat completions
  (`ServerTranslationService`), and Gemini in Play only
  (`GeminiTranslationService`, sharing the read-aloud key). The device is
  the default; a network service is used only once chosen. The Services
  page holds every key and server; the Translation page in Settings
  holds the service, its model and the target language.
- The device service is decided at runtime on each phone. Every
  `TranslationManager` call runs off the main thread with a bounded wait
  (a stuck system service reads as no pairs), translators are destroyed
  when a request ends or is cancelled, and the language pairs are asked
  again when the sheet or settings resume, so a pack downloaded in the
  system settings shows at once. A pair that needs a download offers
  "Download languages" when the system has a settings screen for it, and
  says where to go when it does not.
- Languages keep the script and the Portuguese region
  (`TranslationLanguages`: zh-Hans and zh-Hant, pt-BR and pt-PT) and drop
  other regions. The source is the book's language when it declares
  exactly one; otherwise the sheet asks, unless the service detects it.
  The target follows the app's language until the reader picks one
  (`translation_target`). A passage already in the target, or longer than
  `TranslationPrompt.MAX_CHARACTERS`, is never sent. A passage already in
  the target opens the target list once, without the source language in
  it, and the pick is saved as `translation_target`. `TranslationStep`
  decides which of these the sheet shows, as a pure function.
- Network requests follow the read-aloud clients: the key of the origin
  called, no redirects, the local-network check at request time, bounded
  reply sizes (`TranslationHttp`), cancellable calls on `Dispatchers.IO`,
  and no logging of keys, passages or translations. The system message
  fixes the task and the passage goes as data between markers it cannot
  close (`TranslationPrompt`); the reply is shown as plain text. Refusal,
  empty, quota, malformed, refused key and unreachable are told apart
  (`TranslationError`). Each request runs under the connection's
  generation (`TranslationRequests`): a key or address change while it
  is out discards the reply and asks again. Each attempt checks the
  server it actually used, since a retry may be on another one after a
  move, and the sheet drops a reply
  for a passage, language, service or model it no longer shows.
- `translation_provider` and `translation_server` reference a listed
  server like read aloud's choice; each server's translation model is in
  `translation_server_state`, apart from its read-aloud model, and
  Gemini's is `translation_gemini_model`. Deleting or moving a server
  updates them in the same DataStore edit, and a deleted server's
  translation falls back to the device. Like read aloud's, these are in
  the settings backup but not in liseur-sync settings sync.
- Model lists are sorted by name. A server list that prices its models
  (OpenRouter's `pricing`, dollars per token) shows the input and output
  price per million tokens under each one.
- "Translate the page from here" in the sheet translates the book in
  place (reflowable books only). `PageTranslation` walks sentences from
  the selection with `PublicationUtteranceCursor`, keeps a bounded number
  ahead of the last walked sentence on screen or already passed, restarts
  the walk when the reader jumps elsewhere, and asks one sentence at a
  time through a `TranslationRun` (one device `Translator` per run;
  network services get the previous sentence as context they must not
  translate). A reader who goes past everything walked in the same
  chapter is caught up with: the walk passes over the sentences the page
  says are behind the screen without asking for them. Changing the
  service in settings rebinds the run at the
  next sentence, and a reply is only cached under the service that
  answered it. Errors that
  concern one sentence skip it; key, quota, pair and pack errors halt the
  run with an action on `PageTranslationBar`.
- Page translation replies are saved in `translations.db`
  (`translate/SavedTranslations.kt`), a Room database of its own so the
  backup allowlists, which name `liseur.db`, leave it out. A row is keyed
  by the local book URL and a SHA-256 of `pageIdentity` (a versioned
  service id, server id, model, languages and
  `TranslationPrompt.VERSION`; never a display name or key), the context
  and the sentence. Bump `TranslationPrompt.VERSION` when the prompt
  changes. A run is opened with the settings its identity was read from
  and asks with those, never the settings of the moment, so an answer
  always belongs to its identity. Its retries are judged by that binding
  too: a settings change while a sentence is out binds the run again
  and asks the sentence anew. `PageTranslation` looks up before
  asking, looks up again if the service changed during the lookup, and
  saves only under the service that answered. The store keeps 50,000 sentences and 20 million
  characters, least recently used first. It trims at start and after
  any save that goes past 50,000 sentences; the characters, which take
  a sum over the table, are checked every 100 saves. A lookup's stamp fences Clear: a reply asked before it is
  shown but not saved. Book removal is a sweep, not a delete by URL,
  because `BookRemoval` can run inside a caller's transaction. Room's
  invalidation tracker on `books` reports changes once they commit;
  `AppContainer` sweeps then, and once at start when the file exists.
  `sweep()` drops rows whose book is absent from `books` and bumps that
  book's epoch so a reader still open on it stops saving. A handle
  looks for its book once before its first save, since one made after
  the sweep that took the book has no epoch to fence it. `contentReplaced` keeps the rows, since
  the key holds the exact text, and so does a book removed and added back
  under the same URL before the sweep looks. Every storage failure is a miss or a
  skipped save, logged by exception class only; only Clear reports one.
  See `docs/adr/0046-translated-sentences-are-kept-on-the-phone.md`.
- A book left translated stays so until Stop or read aloud
  (`PageTranslationModes`, a `page_translation_modes` row in
  `translations.db` swept with the book, kept by Clear). Its changes and
  reads go through one queue in `SavedTranslations` on the application
  scope, so a Stop, then Back and reopen, reads the Stop. A failed Stop
  shows a toast. `ReaderScreen` resumes on a navigator only while
  nothing has started or stopped a run on that screen, after the opening
  gate, with the page at rest and no reflow, from `scrolledPlace()` when
  scrolled. `startPageTranslation` owns the translator until the run is
  installed. A resumed run shows the bar for a moment, and
  `TranslatedPageMark` marks a translated page with the controls down.
  See `docs/adr/0048-page-translation-stays-on-for-the-book.md`.
- `reader/PageSwaps.kt` injects the script that applies swaps: it finds
  each sentence by selector, text before and text, keeps the original of
  every text node it changes, and redraws from the originals, so `restore`
  gives the page back byte for byte. A sentence's translation goes into
  its own text rather than an emphasised word; note references keep
  their number and link, a sentence wholly inside one link is translated
  in that link, and a sentence mixing any other link with its text stays
  untranslated. `reader/TranslatedPages.kt` resends
  swaps to every attached WebView on each layout pass, since Readium
  reloads and recycles them, and keeps restoring recycled views after
  Stop. While translated, positions save without quoted text, BookOrbit is
  not pushed, highlights are hidden, and highlights, notes and bookmarks
  are off; read aloud stops the run and restores first. See
  `docs/adr/0045-translated-pages-save-coarse-positions.md`.

### Covers, UI, and dependencies

- Resolve a local cover once at import in this order: publication cover,
  declared SVG `rel=cover`, then an image named `cover`. Draw SVG covers with
  the existing resolver and isolate external-file state per render.
- New reading settings belong in the Advanced sheet unless they are changed
  frequently. Keep the Settings and reader surfaces consistent and use the
  existing appearance/navigation split.
- UI strings must be added to English and the six shipped locale resources
  together. Russian plural resources require `one`, `few`, `many`, and
  `other`; escape apostrophes as required by Android resources.
- All dependencies and bundled fonts must remain FOSS and reproducible.
  `readium-lcp` is prohibited. Never remove the reproducibility-specific
  dependency metadata or JNI debug-symbol settings from the build.
- Build Readium's `AssetRetriever` with `permissionSafeAssetRetriever`, not
  its default constructor. Readium 3.4.0 lets a `SecurityException` escape
  when a content:// book has lost its grant, and that crashed the library on
  launch. The wrapper turns it into an ordinary read failure; drop it once a
  Readium release catches it in `ContentResource.stream()`.

### Home-screen widgets

The Glance widget in `ui/widget/` shows the current cover. Rendering reads
Room and cached cover files; it never waits on a network request.

- Taps on the cover go through the unexported `WidgetLaunchActivity`, which
  hands the request to `MainActivity` in process via `LaunchRequests`.
  `MainActivity` is exported, so it never reads widget targets from intent
  extras.
- One trigger redraws the cover: `AppContainer` collects
  `LiseurDatabase.widgetInputs()`, a Room invalidation flow over
  `WIDGET_TABLES` (`books`, `reading_progress`, `work_alias`,
  `remote_server`). A new table that changes what the widget shows goes into
  that list. Do not add refresh callbacks to repositories or view models.
- `WidgetUpdater.schedule` feeds `RefreshCoalescer`: a redraw runs after 3 s
  of quiet or 15 s after the first unserved request, whichever comes first.
  A page turn writes progress, so this is what bounds the cost while
  reading. A request that lands during a redraw earns exactly one more.
- Glance recomposes a running session on `update()` without calling
  `provideGlance` again, so anything loaded there would go stale.
  `LiveSnapshot` reloads the snapshot whenever the updater's generation
  changes. Each placed widget loads its own snapshot;
  there is no shared cache.
- `CoverOnlyWidgetReceiver` keeps its component identity across upgrades.
  Library, combined-cover, and stats providers were removed outright,
  including their paging, period, and configuration state helpers; their
  placements disappear.
- There is no periodic widget refresh. The cover widget has no
  date-dependent content, so nothing runs in the background for it.
  `retireLegacyWork` cancels the unique work that earlier versions queued
  for the hourly refresh and the stats widget. It runs from app start and
  from the receiver on `MY_PACKAGE_REPLACED`. `TIME_SET`, `TIMEZONE_CHANGED`
  and `LOCALE_CHANGED` redraw at once, since the labels are drawn in the
  local time.
- Any of these broadcasts may be what started the process, and Android
  can kill it once the receiver returns. The receiver holds the broadcast
  with `goAsync()` until its work is safe: the legacy work is retired, or the
  redraw is enqueued as unique one-off work (`requestRedraw`, which
  replaces a request still waiting). Do not start receiver work in a
  process-local scope and return.
- On a device without `FEATURE_APP_WIDGETS` the updater does nothing:
  there is no `AppWidgetManager`, and Glance's id lookup would throw.
- Single covers are decoded at most 256 px on the long edge in `RGB_565`, which
  keeps the RemoteViews bitmap well under the binder transaction limit.
- The picker previews are static layouts (`layout/widget_preview_*`, Android
  12 and later) with PNG fallbacks in `drawable-nodpi`. Their colours in
  `widget_preview_colors.xml` must follow `WidgetComponents.kt`.

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
- A user without the "Allow Downloads" permission gets 401 on
  `/opds/download/...` (403 on the web UI route) while browsing keeps
  working. The app must recognise that case and tell the user to enable
  that permission for their account in calibre-web, rather than showing a
  generic failure.

### Kobo sync

Off by default; the admin enables it in Feature Configuration, then each
user creates a token from their profile page ("Kobo Sync Token"), which
yields a base URL of the shape `https://host/kobo/<32 hex chars>`. The
token is the only credential; it never expires and there is no user
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
  position. The PUT handler indexes its keys directly, so
  `CurrentBookmark`, `Statistics` and `StatusInfo` must all be present
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
  `GET /api/v2/users/me` -> `{id, email, roles[]}`; there is no
  `/api/v1/users/me`. `FILE_DOWNLOAD` in `roles` means the account may
  download. Users paste the API-key *page* address, so setup reduces a
  pasted URL to its origin.
- Browse is `POST /api/v1/books/list?page=&size=&sort=` with an EPUB +
  READY condition; `GET /api/v1/books` is deprecated since 1.19.0. Each
  entry carries `readProgress {page, completed, readDate}` inline, which
  is the change detector: a routine sync costs one request.
- Search is the same endpoint with `fullTextSearch` set, a sibling of
  `condition`.
- `PUT /progression` validates, in order: `modified` strictly after the
  stored `readDate` (else `409`); `href`, fragment stripped and
  URL-decoded, exactly matching an internal EPUB file name, with **no**
  leading slash (else `400`); and `locations.progression`, which is
  required. Our `totalProgression` is ignored and recomputed.
- There is no page-based fallback. `PATCH read-progress {"page": N}`
  is rejected for reflowable EPUB ("not Divina compatible"); only
  `{"completed": true}` works. On a `400` the client instead fetches
  `GET /api/v1/books/{id}/positions` and snaps to the nearest position
  Komga already knows, keeping the position rather than coarsening it.
  That index is ~330 KB for an ordinary book, so it is only ever fetched
  after a rejection.
- `409` is not a failure. The server holds something at least as
  new; the row stays dirty and the next run pulls and reconciles.
- `GET /progression` answers `204` with an empty body when there is no
  progress. A `404` means the book is unknown, and *is* a failure.
- `modified` comes back with a local UTC offset and is double-offset, so
  it must not be used for ordering; `readProgress.readDate` round-trips
  exactly and is what the sync orders by.
- Deleting a book from the server is admin-only
  (`DELETE /books/{id}/file`), so that action is hidden for Komga.

## BookOrbit protocol

BookOrbit is a self-hosted library and reading platform with its own REST
API under `/api/v1`. Liseur speaks that API directly rather than going
through its OPDS or KOReader-compatible surfaces, because the native one
is where the book's files, its personal reading status and (later) its
CFI live.

- Auth is `Authorization: Bearer <access token>`. A reader signs in once
  with their account password (`POST /auth/login`, `clientKind: "native"`,
  `deviceLabel`), and the password is not kept: BookOrbit offers a native
  client no scoped key, so the password is exchanged for an access token
  that lasts about fifteen minutes and a refresh token that lasts a week
  and is replaced on every use (`POST /auth/refresh`). Both are stored
  sealed, with `orbit_access_expires` and an `orbit_epoch` that names the
  published connection they belong to. `BookOrbitSession` owns them; see
  the invariants below.
- Browsing is `POST /books/query`, zero-based pages of up to two hundred
  books, already scoped to the libraries the account may see. The walk
  asks the server for books that hold an EPUB, and skips anything with no
  EPUB file anyway, because audiobooks, comics and podcasts live on the
  same shelf.
- A book has several files and an installation-local integer id.
  `books.url` and `remote_uuid` therefore carry a scope digest of the
  address and the account in front of the book id (`BookOrbitScope`), and
  the file a book is read as is chosen once and remembered in
  `book_orbit_binding`. A refresh that made a different EPUB primary must
  not move a reader's place to another edition; a bound file that has gone
  from the server keeps its identity and loses its download link.
- Download is `GET /books/files/{fileId}/download`, which is gated on
  `library_download` and does not implement range requests, so an
  interrupted transfer restarts. `/serve` supports ranges but skips that
  permission check, and is deliberately not used.
- Positions sync as exact EPUB CFIs, and only when Liseur can prove the
  passage (`syncAbility = EXACT`, gated by
  `BookOrbitPositionSync.AUTOMATIC_SYNC_ENABLED`). BookOrbit stores a CFI
  and Liseur stores Readium locators, so the CFI bridge resolves a server
  CFI against the selected file's original XHTML and then the active
  WebView (`BookOrbitIncomingAnchor`), and captures an outgoing CFI that
  must resolve back to the same text (`BookOrbitViewportCfi`).
  `BookOrbitLocalPositionWriter` saves that CFI with the locator and
  `position_revision` in one transaction; any later write without one
  deletes it. Sending a place without a CFI would null the server's, so
  Liseur never does.
- `BookOrbitPositionExchange` persists the exact request bytes, preflights
  the file again, marks the attempt `MAY_HAVE_BEEN_SENT`, POSTs once with a
  one-shot body (OkHttp otherwise resends after `503 Retry-After: 0`) and
  acknowledges only a read-back with the exact CFI and float4 percentage.
  An uncertain, retry-required or rejected attempt is read-back-only until
  an explicit reader choice; the attempt generation stops an old choice
  from authorizing a byte-identical retry. Sends, read-backs and choices
  share `database.bookOrbitPositionMutex`. The race after the final
  preflight follows the approved last-server-write-wins policy.
- Pushes run on every saved page turn and on pause while the book is
  open; only adoption is fenced by `OpenBooks`. A verified server place
  the book opened at is agreed by the first page turn in the move's
  transaction (`agreeOpeningPullIn`), so that move pushes. A server place
  saved elsewhere while reading is offered as a catch-up pill; accepting
  adopts it once the WebView shows it (`adoptCaughtUpInReader`), and
  dismissing or reading on records it as agreed so the next move
  overwrites it. A place written by someone else between this device's
  POST and its read-back is offered the same way; declining drops the
  uncertain attempt instead of replaying it.
- Remote places found while the book is closed are adopted after close,
  behind a fresh GET and a transaction that rechecks account, binding,
  agreement and the exact local revision and locator. First-time conflicts use a
  reader-verified choice applied in the open reader while its position
  writes are paused (`OpenBooks.whileHeld`). `BookOrbitPositionSync` walks at
  most 20 bindings per call over a persisted traversal (schema 58); its
  generic choice methods stay disabled.
- A percentage-only server place opens as an approximate fraction
  (`approximateOffer`). The first page turn records it as the agreed
  remote place in the same transaction as the move, so that move pushes
  an exact CFI; any jump declines it and leaves the server place alone.
  A percentage-only place saved elsewhere while reading, or written
  between this device's POST and its read-back, is offered through the
  same catch-up pill as "Continue near page N" (`approximateCatchUp`).
  Accepting goes there the same way, reading on agrees it, and
  dismissing or jumping away agrees it as declined so the next move
  pushes.
- Reading status has its own agreements and exact-byte PATCH, keyed on
  `status_revision` so a status change keeps the verified CFI. Automatic
  writes are on (`BookOrbitStatusSync.AUTOMATIC_SYNC_ENABLED`).
- Upload (`BookOrbitUploadClient`, `library_upload`) uses the resumable
  `/uploads` session API against the first library whose
  `allowedFormats` is empty or includes EPUB. The idempotency key is
  `liseur-<digest>-gN`: the digest covers the scope, the local book URL,
  the library, the file name, the size and the content sha256, so two
  local entries with identical bytes never share a session. The client
  acts only on the session state the server reports: `receiving` resumes
  from `receivedBytes` (an offset mismatch is settled by reading the
  session back), `processing` is `Pending` even with a `bookId`,
  `completed` is adopted, and `expired`, `cancelled`, an unexplained
  `failed` or a rejected whole-file checksum move to the next generation,
  at most five. Coded refusals are read from every answer, because
  `complete` rethrows the original code while storing the session as
  `failed`. Server messages are never shown or logged; they can carry
  disk paths.
- An uploaded book keeps its local URL. The catalog finds its binding
  through `remote_uuid` (`localUrls`), so the binding lives under that
  URL. The server file is named only when it is proved: a single EPUB of
  the sent size in the returned book, or, when a `book_per_folder`
  library joined an existing book, the one candidate (of at most three)
  whose downloaded bytes match the sent digest. Otherwise the worker
  records an `UNLINKED` refusal for those bytes and does not link. That
  refusal survives a disconnect, unlike the others, so the same account
  is not offered the same bytes again. Empty
  catalog duplicates of the adopted book are removed only when every one
  is untouched (`BookRemoval.dropUntouchedCatalogDuplicates`); one
  holding anything keeps them all and the book stays unlinked. A
  candidate download that fails fails the attempt, which is retried,
  except a 403: an account allowed to upload but not to download cannot
  prove the file, so the upload ends unlinked.
- An uploaded book the server later loses goes through the same two
  finished walks as any other. After the second, the entry and its
  reading stay and `BookRemoval.unlinkVanishedUploads` clears
  `remote_uuid` and that account's binding, so a reconnect cannot relink
  it. Bindings other accounts kept across a disconnect stay.
- A disconnect clears `remote_uuid` but keeps the bindings of books that
  stay. When the same account (same `accountKey`) signs in again,
  `RemoteAccountRepository.relinkUploads` restores `remote_uuid` on
  uploaded books from their binding, so the catalog does not bring them
  in twice. It relinks only a file that still hashes to `local_sha256`,
  read outside the transaction. Two local books bound to one server book
  stay unlinked. `BookRemoval.contentReplaced` drops the binding with the
  rest of what described the old file.
- The adoption writes `book_orbit_binding.local_sha256` (schema 60), and
  nothing else does. It is what lets an uploaded book's own file stand
  for the server file when a CFI is computed. `BookOrbitCfiRepository`
  reads an app-owned file in place and copies a document URI once into a
  private spool; the bytes parsed must hash to `local_sha256`, and the
  source's size and modification time are checked again on every use.
  The spool is deleted when a check fails and when the reader closes, and
  swept at startup. Before a position is prepared or sent, and before any
  status is read or written, the source is checked against the digest
  (`BookOrbitAdoptedSource.holdsUploaded`), and checked again after the
  network read that precedes the write. The answer, match or mismatch,
  is remembered for five minutes while the size and modification time
  are unchanged, because a full hash on every page turn is too slow; a
  replacement that keeps both is caught by the next process or after
  that window. A zero modification time from a document provider means
  unknown and is never remembered.
- Delete (`BookOrbitDeleteClient`, `library_delete_books`) is
  `DELETE /books` with `{"bookIds":[id]}` and removes the whole book for
  every reader. The id comes from the entry's scoped `remote_uuid` and
  must have been issued for this address and account; a binding naming
  another book refuses. 204 and 404 are deleted, 403 is not allowed.
  `BookDownloadRepository.deleteFromServer` captures the entry and its
  owned files before the request and removes them afterwards only if the
  account, `remote_uuid`, `local_uri` and file size and time are
  unchanged. An entry whose file the app does not own, such as a book in
  a watched folder, is always kept with its reading history, since the
  next scan would bring the file back as a new book. A document whose
  provider reports no size or time is kept too.
  Files go after the transaction commits. A copy kept this way loses the
  deleting account's binding when it still carries the deleted id, so a
  reconnect cannot relink it, and loses its `remote_uuid` too while that
  account is still connected. An entry relinked to another id during the
  request keeps that link.

### What the BookOrbit server does

Checked against v3.0.0 (`ghcr.io/bookorbit/bookorbit:3.0.0`).

- `GET /books/files/{fileId}/progress` answers for one file. An unopened
  file returns a default (percentage 0, null position fields, no
  `updatedAt`); a saved zero has `updatedAt`. `BookOrbitProgressClient`
  keeps the two apart and refuses malformed JSON, partial defaults and a
  percentage outside 0..100. `lastReadAt`, `textUpdatedAt` and `updatedAt`
  are shown to the reader and never used as merge clocks.
- `POST /books/files/{fileId}/progress` replaces the whole text place. A
  POST with only `percentage` nulls `cfi`, `pageNumber`, `positionSeconds`,
  the media-overlay, Kobo and `koreaderProgress` fields. The write is
  unconditional
  ([`saveProgress`](https://github.com/bookorbit/bookorbit/blob/27cfdc20282eabdc89296c8c45482c578c157c7c/server/src/modules/book/book.service.ts#L2238-L2259)),
  and its hooks can move read status, Kobo state and sibling EPUB progress.
- The percentage column is PostgreSQL `real`. Liseur serializes it at
  `Float` precision before saving the request bytes, so read-back can
  require the exact CFI and the exact percentage.
- `readStatus` lives on `GET /books/{id}` and changes through the
  status-only `PATCH /books/{id}/status`, which leaves file progress and
  its CFI alone. The public API cannot delete a status row.
- Places saved without a CFI are common: imports and some clients write
  them.

### CFI bridge rules

- `BookOrbitEpubPackage` reads `container.xml` and the OPF through the zip
  central directory, each capped at 4 MiB. It keeps non-linear itemrefs
  (they still occupy CFI indices), drops remote manifest items, refuses
  duplicate ids, escaping paths, external identifiers and internal subsets.
  `BookOrbitEpubInfoClient` compares the package path and spine with the
  server's `/info` for the selected file.
- `BookOrbitCfi` parses EPUB CFI 1.1 point and range CFIs, including
  foliate's side-biased text assertions. Temporal and spatial offsets are
  unsupported. An unparseable CFI is retained with its reason
  (`BookOrbitCfiRepository`) and never turned into a percentage.
- A parsed CFI is still DOM-unverified; `unresolvedReason` only reports
  parser failures. The resolver enforces step parity (even steps land on
  elements) and honors a step's `s=` parameter at element boundaries.
  `/6/4!:3` has no text meaning and stays unresolved.
- A range parent ending in `!` is an open path: join it with each endpoint
  before walking, and never resolve `Range.parent` alone. Use `spineStep`
  and each spine item's `step`; the package is not always `/6`. Flatten
  only reader-owned wrappers, never an EPUB element.
- Never use `BookOrbitForeignCfi.parsed` directly as a `Locator` or a POST
  payload. An approximate place from a failed exact resolution must never
  be written back as exact.
- Android's WebView throws on `compareDocumentPosition`, so the incoming
  check walks nodes in order instead. The outgoing capture skips tokens
  without letters or digits, accepts an anchor that starts inside the
  captured token, and retries briefly while the pager is still sliding to
  a chapter's first page. A page without text sends nothing.

### Agreement and exchange states

- `reconcileExactPosition` decides from the local `position_revision`,
  never a timestamp or a percentage tolerance. Equal CFIs settle even at
  different percentages; different CFIs at the same percentage conflict.
  A remote place without an agreement is not assumed to be this device's.
- An exchange moves `PREPARED` → `MAY_HAVE_BEEN_SENT` → acknowledged, or
  `UNCERTAIN` (read-back failed), `RETRY_REQUIRED` (server unchanged) or
  `REJECTED` (401/403). Those four are read-back-only on every later run;
  only an explicit reader choice prepares a new attempt. A read-back that
  finds a third exact place means someone else wrote in between; it is
  offered as a catch-up, and declining drops the attempt.
- Every write re-reads the agreement row inside its own transaction.

### Percentage-only openings

| Local place | Condition | Opens at |
| --- | --- | --- |
| None | Any percentage-only server place | Server percentage |
| Agreed and unchanged since | Server moved to another percentage-only place | Server percentage, with a way back |
| Never matched, no verified CFI | Server percentage is further ahead | Server percentage, with a way back |

Opening records nothing. The first page turn records the percentage as
the agreed remote place in the same transaction as the move, which then
pushes an exact CFI. Any jump (the way back, a bookmark, the contents, the
scrubber or go-to-page, even onto the same page) declines it and leaves
the server place alone. Any other percentage-only place, including one
this device never matched, stays unresolved at opening and receives no
POST; the open reader offers it as a catch-up instead.

### Status mapping

Liseur's explicit finished and unread marks map to BookOrbit `read` and
`unread`, and manual BookOrbit `read` and `unread` map back. A manual
`reading` maps only when the local passage is already in Liseur's reading
range. Automatic statuses stay derived from position. `want_to_read`,
`on_hold`, `rereading`, `skimmed`, `abandoned` and unknown values are
preserved on BookOrbit.

### BookOrbit fixtures

- `progress-*.json` in `app/src/test/resources/bookorbit/` are synthetic.
  `progress-live-*.json` are sanitized server responses with ids and
  timestamps replaced and the null/presence shapes kept
  (`BookOrbitLiveProgressTest`).
- `OPS/` holds project-authored package and chapter documents.
  `foliate-cfis.json` is output from BookOrbit's unmodified
  `client/public/assets/foliate/epubcfi.js` at commit
  `6be648b48a9cc376cfeff8953ebf22123583f7eb`. To regenerate it, serve
  `tests/bookorbit/capture.html`, that `epubcfi.js`, `package.opf` and
  `one.xhtml` from one directory on localhost and open the page in
  Chromium; it prints the corpus. Do not vendor the upstream JavaScript.
- `tests/bookorbit/ParserSmoke.java` runs the production parsers on an
  emulator: compile with `javac --release 8`, convert with
  `d8 --min-api 26`, copy the dex jar and the debug APK to
  `/data/local/tmp`, and run `app_process /system/bin ParserSmoke` with
  both on `CLASSPATH`.

## liseur-sync protocol

A second kind of partner, and a different shape from the two above.
calibre-web and Komga each hold one current position per book and
answer "where am I"; liseur-sync holds an append-only log and answers
"what has happened since `seq`". It holds no books at all, which is
what lets it sync a book that came off an SD card.

- Auth is `Authorization: Bearer <token>`. `POST /v1/login` with a
  username and password mints device tokens through `POST /v1/tokens`;
  one scope per token, so signing in asks for two, `sync` and
  `read-insights`, and a reader who pastes a token made elsewhere
  usually has only the first. Statistics are simply absent then.
- The cursor is the only irreplaceable state. Everything else can
  be asked for again, but ops behind `sync_account.cursor_seq` cannot.
  So a page from `GET /v1/changes?since=` is written and the cursor
  advanced in one transaction, never the other way round. A cursor that
  has fallen below the server's compaction horizon gets `410
  resync_required`; the answer is `GET /v1/heads` and its
  `snapshot_seq`.
- Ids are derived, not drawn. The server treats `op_id` and
  `session_id` as idempotency keys and compares the whole payload
  behind each: same id and same payload is `duplicate`, same id and a
  different payload is a conflict. So the id is
  `UUIDv3(deviceKey|workId|revision)` and every payload field comes
  from stored state: `client_ts` is `reading_progress.updated_at`,
  never the clock. A push interrupted by a dead network is simply
  repeated. The server does not check that the id is a UUIDv7; it is
  opaque, up to 64 characters.
- `POST /v1/works/resolve` takes every identifier at once (`sha256:`,
  `pmd5:` (KOReader's partial MD5), `dc:` and `ta:`) and registers all
  of them against whichever matched, which is how a re-encoded copy and
  the original converge. `409` means they named two different works;
  the server changes nothing and merging is left to the reader.
- `ta:` normalisation is an interoperability contract and is not in
  the schema. The server matches `ta` aliases by exact string and
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
  and honestly so: time is counted only while the reader is in the
  foreground, so time spent elsewhere is already absent rather than
  included and subtracted.
- Statistics (`/v1/insights/*`) are decoration. Every failure is null
  and silent, and a null `eta_seconds` is carried through untouched: no
  estimate beats an invented one.
- Uploading is opt-in twice over. The server advertises the
  `library-upload` scope on `GET /v1/token` and marks the folders that
  take uploads in `GET /v1/folders`; without both, the action is not
  offered at all, which is how an older server needs no version check.
  `POST /v1/folders/{folder}/books` is `multipart/form-data`, keyed by
  the file's SHA-256, so a repeated upload answers `200 duplicate` and
  stores nothing twice. `202` means the bytes are safe but the server
  had not catalogued them yet; the worker simply asks again, and the
  digest makes the second ask free.
- What follows an upload is adoption, not replacement. The local row
  keeps its own `url` and gains `remote_uuid` and `download_href`
  (`BookDao.linkToRemote`). Rewriting the URL to the server's spelling
  would take every reading position, annotation and session with it.
  Because the catalog reads what the library holds once, before its
  walk begins, both sides guard against the book being introduced twice:
  the catalog re-asks about the ids on a page it has not accounted for,
  and adoption drops a catalog row that got there first.

## F-Droid readiness

- Dependencies are all FOSS, from Maven Central or Google's Maven.
  In particular `readium-lcp` is deliberately absent: it pulls in the
  proprietary liblcp. The list users see is in `LicencesScreen.kt`.
- No trackers or analytics, and no Google Play services. The only
  outbound traffic is to the calibre-web, Komga, BookOrbit or liseur-sync
  server the user configured, and to a dictionary site when a definition
  is asked for.
  That second one is off until switched on in Settings and the site is
  the user's to choose (`DictionaryUrl`), because F-Droid review will
  otherwise treat a hardcoded third-party host as grounds for the
  TetheredNet anti-feature. Together those justify `INTERNET`;
  `ACCESS_NETWORK_STATE` is there for the `NetworkType.CONNECTED`
  constraint on the sync workers.
- Gemini read-aloud and Gemini translation, which send book text to
  Google, are in the `play` flavor only. F-Droid builds `foss`, which has none of its code, strings
  or endpoint, so the NonFreeNet anti-feature does not apply. Device
  voices are in both and stay on the device, and they are the default.
  Read aloud and translation with a server are in both and talk only to
  the server the user sets. Device translation uses Android's own
  `TranslationManager`, not ML Kit, so it needs no Google Play services. The address field offers a menu of hosted services
  (`SpeechServerPresets`) next to a self-hosted example; none is selected
  or contacted until the user picks it, and the app works
  without any of them. Before
  changing that boundary, check the `foss` release dex for
  `generativelanguage` and `Gemini` strings.
- No non-free assets. The bundled fonts (Literata, Vollkorn, Atkinson
  Hyperlegible, Inter) are all OFL; the icon is drawn in-repo as vector
  drawables.
- `fonts.googleapis.com` appears in the release dex and is unreachable.
  It is a string inside Readium's `ReadiumCss`, emitted only for families
  registered through `EpubNavigatorFactory`'s separate `googleFonts` list.
  Liseur never sets that list: every `addFontFamilyDeclaration` in
  `ReaderPreferencesMapper.kt` sources its faces from bundled asset paths.
  Worth knowing, because a reviewer grepping the dex for hosts will find
  it and ask.
- Reproducible versioning: `versionCode` and `versionName` only ever
  change in a `chore: release vX.Y.Z` commit made by `hack/release`, and
  every release is tagged. F-Droid's `UpdateCheckMode: Tags` still
  notices a new tag, but `AutoUpdateMode` is `None`: under dual signing
  (below) every release needs its extracted signature delivered by hand,
  which their bot cannot do yet, so `hack/release` opens that merge
  request itself.
- Metadata lives in the repo under
  `fastlane/metadata/android/en-US/`: title, descriptions, per-versionCode
  changelogs, icon and screenshots. Extra listing locales go beside
  `en-US` when translated; app UI locales are documented in
  [`docs/TRANSLATING.md`](docs/TRANSLATING.md).
- The build needs no network beyond Gradle dependencies and no
  signing config: `assembleFossRelease` on a clean checkout produces an
  unsigned APK, which is what F-Droid builds and signs itself.
- The build is reproducible. F-Droid rebuilds from source and will
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
  re-stripping it ties the bytes to the build machine's NDK: this was
  the one thing that made the CI APK differ from a local rebuild).
- Publishing our own signature: dual signing (merged 2026-08-21).
  Because the build is reproducible, F-Droid can publish the
  developer signature. Replacing their signature outright was declined
  in review, since existing F-Droid installs carry F-Droid's key and Android
  would refuse them every further update, so the app uses F-Droid's
  dual-signing flow instead: for each version F-Droid publishes **two
  APKs**, one signed with its own key (existing users keep updating,
  nothing breaks) and one carrying our signature, grafted onto F-Droid's
  own rebuild after that rebuild comes out byte-identical with ours.
  New installs from F-Droid get the developer-signed copy, which is
  interchangeable with the GitHub release. Concretely, the metadata has:

  - `AllowedAPKSigningKeys:` with **two** SHA-256 digests: our
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
- Checking where things stand. `hack/store-status` prints the
  published versions, the index age, what the last build run did with
  the app, the upstream metadata, and any open merge request with its
  pipeline state, alongside the GitHub releases and the Play tracks,
  so one command answers what each of the three channels is showing.
- Submitted. The inclusion merge request was
  [fdroiddata!44292](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44292)
  (merged), and `hack/release` opens the per-release signature merge
  request described above. See *What F-Droid checks* above for what its
  pipeline runs and how it can fail after a release looks finished.
