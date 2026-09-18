# Liseur: Copilot instructions

Open-source Android ebook reader: local EPUB library + calibre-web,
Komga and liseur-sync clients (browse/download and position sync against
any of them), with a Kindle-inspired reading experience. Kotlin + Jetpack Compose + Readium Kotlin Toolkit.
FOSS-only dependencies. The app targets F-Droid inclusion. See `README.md`
for user-facing goals.

## Build, test, lint

Always use the wrapper (`./gradlew`), never a system-wide `gradle`.
Use the Gradle version pinned in `gradle/wrapper/gradle-wrapper.properties`.

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

For trying a change on a phone that already has the real app on it,
there is a second build with its own package name:

```bash
make dev                             # build the side-by-side APK
make dev-install                     # install it beside the real app
make dev-run                         # install it and launch it
make dev-uninstall                   # remove it
make dev-logcat                      # tail its logs
```

It is the debug build in every respect but two: it is
`com.chmouel.liseur.dev`, and it says "Liseur (dev)" under a differently
tinted icon. The package name is the point — it gets its own database,
its own settings and its own folder grant, so it cannot reach the
installed app's library, and it opens empty the first time. `release` is
untouched by all of this, so F-Droid's rebuild is unaffected.

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
`adb shell input`. These operations need no permission or approval.
Everything on an emulator can be rebuilt in a
minute by `make reset`, so there is nothing there worth protecting.
`make shutdown` when you are done, or say you left it running.

A physical phone is somebody's library. **Never install, replace, or
uninstall the app, clear its storage, or write to its database on a
real device without asking first.** A reading position, a highlight and
a half-finished note are not reproducible, and an unlucky
`adb install -r` takes them.

`make dev-install` is the way to put a change on a phone without asking
that question: it carries its own package name, so it lands next to the
real app instead of over it. It is still an install on somebody's
device, so it is still worth saying you are about to do it — but nothing
it does can reach the library that matters.

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

Toolchain: JDK 17, AGP 9.x. AGP has built-in Kotlin support, so **do not** add the
`org.jetbrains.kotlin.android` plugin; only `org.jetbrains.kotlin.plugin.compose`
is applied. compileSdk/targetSdk 37, minSdk 26. Dependencies are managed in
`gradle/libs.versions.toml` (version catalog).

## Git hooks

Install the [pre-commit](https://pre-commit.com/) hooks once after cloning:

    pre-commit install --hook-type pre-push

This runs unit tests, Android Lint, and the release build before every
push. Skip with `git push --no-verify`.

## Hard constraints

- Every dependency must be FOSS and come from Maven Central or Google's
  Maven repo, for F-Droid compatibility. No proprietary blobs, trackers,
  analytics, or Google Play services. In particular, never add
  `readium-lcp`, since it depends on the proprietary liblcp.
- The release build must stay reproducible: `hack/verify-reproducible`
  must pass, because F-Droid rebuilds every tag from source. Do not remove
  the `dependenciesInfo` or `packaging.jniLibs.keepDebugSymbols` settings in
  `app/build.gradle.kts`; both exist only for this. `DEVELOPER.md`
  explains why.
- Network access is limited to the user-configured book server
  (calibre-web, Komga or liseur-sync) and opt-in dictionary lookups.

## Architecture

Single `:app` module, layered packages under `com.chmouel.liseur`:

- `data/`: Room database, DataStore settings, local library repository
  (SAF folder scanning + Readium streamer metadata extraction), and the
  remote-server layer: `data/remote/` holds provider-neutral contracts;
  `data/calibre/` (OPDS + Kobo sync), `data/komga/` (REST) and
  `data/liseursync/` (native REST + append-only op log) implement them,
  and `RemoteRouter` dispatches on the connected server's kind.
  liseur-sync is a full `ServerKind` like the other two: it catalogs,
  serves files, and syncs, and its position sync also covers books
  that never came from a server, resolving them by their hashes.
- `domain/`: small use-case layer, only where logic is non-trivial
  (sync merge, time-left estimator). Keep pure and JVM-testable.
- `reader/`: Readium `EpubNavigatorFragment` hosted in Compose, plus the
  custom reading chrome (tap zones, typography sheet, progress, annotations,
  search).
- `ui/`: Compose screens: theme (`ui/theme`), library, settings.
- `sync/`: WorkManager workers for downloads, uploads and position sync.

State management: plain `ViewModel` + `StateFlow`, unidirectional data flow.
DI is a manual composition root (no Hilt/Koin). Extract non-trivial logic
into pure Kotlin so it stays unit-testable without Robolectric or an
emulator.

## Conventions

- Never add a `Co-authored-by` (or any co-author/AI attribution)
  trailer to commits.

- UI copy lives in `app/src/main/res/values/strings.xml` and is read with
  `stringResource` / `pluralStringResource` — never hardcode user-facing
  English. Every new or changed translatable string must also be
  translated in French, Spanish, Russian, Italian and German
  (`values-fr`, `values-es`, `values-ru`, `values-it`, `values-de`) in
  the same change. Skip keys marked `translatable="false"` (brand and
  product names). Russian plurals need `one` / `few` / `many` /
  `other`. See `docs/TRANSLATING.md`.

- Reading positions are Readium `Locator`s locally. calibre-web sync
  exchanges percentage progression (`locations.totalProgression`); Komga
  and liseur-sync exchange a full locator, so they also restore the
  exact spot. All three go
  through `domain/ReadingStateMerge.kt`; write conflict rules there, once,
  not per provider.
- A position carries two times and they are not interchangeable.
  `updated_at` is when *this* device wrote the row, and it goes out as
  an op's `client_ts` and as kosync's `timestamp`, so it is part of a
  derived op id — do not repurpose it. `read_at` is when the reading
  happened, on whatever device did it: a pull takes it from the remote
  time (clamped to now, since it is a peer's clock), a page turn here
  takes the local one. The Recent shelf and Continue Reading order by
  `COALESCE(read_at, updated_at)`. Importing old state must reproduce
  the other device's order, never invent one from arrival times. A row
  with neither a progression nor a locator records no reading and must
  report none. See `docs/adr/0032-imported-reading-keeps-its-own-time.md`.
- Naming a book is rationed by what naming costs, not by a single
  number: resolving from file hashes opens the file, resolving a
  catalog book is one request. A run that leaves books unnamed or
  unseeded returns `SyncOutcome.Incomplete`, and
  `PositionSyncCoordinator` carries it on — there, not at any call
  site, since a fresh connection is synced from app start, a refresh,
  connecting an account and Settings alike. Only a run that made
  *durable* progress may ask for another, or the chain never ends:
  re-asking a question this device already has an answer to is not
  progress. A refusal worth retrying keeps the run a failure, because
  its retry covers the shortfall too; one that is not must not strand
  the rest of the library. Several books to seed are seeded from one
  `GET /v1/heads`, which is a seed and not a pull: it must not move the
  cursor. A whole catalog shelf is likewise named from one
  `POST /v1/books/resolve` before the per-book pass runs, so the ration
  is what a server too old for that route falls back to rather than the
  ordinary path. That batch never carries `confirmed`, which would speak
  for every id at once: a book holding the reader's yes to a doubtful
  match goes through the single-book route, which can say yes for that
  book alone.
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
  `pending_ops` table. The server compares its own `device_id` too, so
  a reconnect offers the stored one back (`ServerSetup.reconnect`) and
  the phone stays one device; a pasted token cannot, and its replays
  may come back `conflict`, which is answered by moving the book's
  revision on (`renameRevision`, conditional on the sent revision) so
  the same reading goes out under a fresh id.
- A server refusal of a batch is about one item and stores nothing.
  Never mark a whole batch as sent because of it. Sessions: the named
  sitting goes into `session_refusal` for that peer and the rest go
  again, but only for a reason the server calls permanent; a body too
  big is halved; a refusal that names nothing is bisected; a code this
  app does not know is left pending and reported, named item or not.
  Ops: `unknown_work` re-resolves, `locator_too_large` resends the op
  bare under the same id, and a batch refused for its size is cut to
  the limit the server named — a lone op still refused goes bare, since
  its locator is the only part with any size to it.
  `awaitingUploadTo` joins the alias and the refusals *before* it
  limits, or unnamed books block the queue.
- The account key is `liseursync|<url>|<account_id>`, and it changed
  spelling once (from the device id). `carryPeerState` moves every
  peer-keyed table to the new spelling in the connect transaction; add
  any new peer-keyed table there and to `forgetSyncPeer`. A password
  sign-in names the account by itself, so a device id that came back
  changed — the server forgot it, or is too old to be offered it — is
  not an account switch. Only a pasted token, which names nobody, is
  told apart by its device id.
- A book's name on liseur-sync is a `work_alias`. A book from its
  own catalog resolves through `POST /v1/books/{id}/resolve`. The
  server reads the identifiers off its record, so no download is needed
  and two devices name it identically. Any other book resolves from
  SHA-256 + KOReader partial-MD5 + a normalised `ta:` title/author. A
  `ta:`-only match is low confidence and syncs nothing until the reader
  confirms it; a rejection is stored as `confidence = 'rejected'` rather
  than deleted, or the next run asks again.
- A synced setting travels with the time the *reader* changed it, never
  the time it is pushed. `SettingsChangeTracker` stamps that time from
  the application scope whether or not a server is connected, and the
  stamp is advisory: whether a key is offered is still decided by
  comparing it against the account's agreed baseline, which is what
  lets the collector run unlocked. That argument runs out on an account
  with no baseline yet, so the pass says outright which values came off
  a server (`markApplied`) and those are not counted as edits made
  here; it writes the value down *and* drops the stamp, because the
  collector races it in both directions. A key with no stamp at all
  means opposite things on the two sides of that line: with a baseline
  it is an edit the collector has not seen yet and reads as *now*, with
  none it is only what the device happens to hold and reads as the
  beginning of time, or a fresh install pushes its defaults over the
  account on first connect. A `PUT` answers `200` whether the
  value won or lost, so record *both* halves from the merged reply and
  apply the server's value where it differs; a key the server did not
  speak for records nothing, and is re-offered under the timestamp that
  was agreed — or under a later recorded change, since a value the
  reader moved and put back matches the baseline but is still a choice,
  and overrules a server that moved in between. An answer that arrives after the
  reader has changed the setting again is about the value that was
  sent and is not written over the new one. A value this build cannot
  parse or recognise records nothing either, or an older build pushes
  its own fallback over a newer one's choice. A value the server cannot
  store — a NUL byte, or over 4 KiB — is dropped before the request,
  since the `PUT` is one transaction and one bad key would block every
  other one forever. For the same reason no change is dated later than
  now, and the capping happens where the change times are *read*, not
  on the way out, so a stamp is compared as it is sent: the server
  refuses a whole batch dated more than a day ahead, so one time
  recorded while the device's clock was wrong would block every setting
  on every pass from then on, and left uncapped it would also outrank
  every honest edit the account received until that date arrived. A
  time that has not happened yet is a wrong answer whatever it is
  measured against. Absence is a value and travels as
  a sentinel, because the server has no delete and no null. The
  baseline is peer state and moves with the account; the record of what
  this device changed is not. The account is re-checked before the
  request, after it and before anything is recorded, because the
  request is the side effect. A setting that relays out the page is not
  applied while a book is open — that is the settings version of
  turning somebody's page — and which ones those are is
  `SyncableSetting.affectsOpenBook`, not the `reader.` prefix, since
  `app.scroll_mode` rebuilds the page and is not named for it; it is
  asked per write, not once per run, because resuming the last book
  opens one mid-request. Device-shaped settings never travel at all.
  See `docs/adr/0034-settings-travel-by-when-they-were-changed.md`.
- Statistics from a server are decoration. Every failure there is
  null and silent; the stats screen is built from local sessions and
  must stand on its own. Cross-device figures require a coherent snapshot:
  server + captured local - actual server overlap. Upload flags prove
  neither presence nor absence in a cached server answer. Legacy or
  incomplete proof uses the existing local-only provenance. See
  `docs/adr/0021-cross-device-reading-statistics.md`.
- Annotations are the exception to that rule, because they are mutable
  and deletable: an id derived from current content stops being
  reproducible the moment the reader edits the row. `annotation_sync`
  holds what the server confirmed *and* the exact bytes of any request
  in flight, written before the call and replayed verbatim through
  `postRaw`, for both the first send and any retry. Do not rebuild an item from
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
- The annotation pass runs settle -> pull -> reconcile -> push -> deletes,
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
  connected one first, not just before storing the answer, because the
  request is the side effect. A record's home alias is likewise re-read inside
  the transaction that commits it, so a file that took over the path
  mid-pass cannot have another book's highlight anchored into it.
- An annotation id is opaque, so `.` and `..` are ids to carry and push
  like any other. They cannot be *addressed*: a URL parser decodes
  `%2E%2E` before it resolves dot segments, so no escape survives.
  `LiseurSyncApi.addressable()` is what makes a delete decline rather
  than aim at the collection.
- Whether a bookmark is on the page now open is decided by the two
  locators, through `samePage()`, and never by the page number stored
  beside it. That number is rounded off Readium's positions, which are
  far coarser than a screen, so several screens in a row carry the same
  one: testing against it wrote a second bookmark on a page that already
  had one and left the ribbon hanging out pages later (#194, #202). The
  exact anchor settles it wherever both sides carry one, since it names
  the first word on screen and so survives a reflow that rewrites every
  progression. `sameReadingPositionAs` and the ribbon share that one
  function so the catch-up guards and the bookmark cannot drift apart.
  Two things the locators cannot answer for themselves are asked before
  it. A reading order listing one file twice gives both copies the same
  href and the same anchor, so `BookPositions.occurrenceOf()` says which
  copy each locator fell in, as `resolve()` already does; a scrolled
  place carries no position to ask with, and in such a book an
  unanswerable question counts as a different page, since an unlit
  ribbon only fails to offer a bookmark while a wrong yes takes the
  reader's mark off the other copy. And a scrolled
  book has no pages and its anchor moves with every line, so it falls
  back to the Readium position, which is about a screenful and is the
  unit every stable number in the app counts in; whether a book is
  scrolled is `chromeScrolls()`,
  never the bare setting, since a vertical-text book scrolls without it
  and a fixed-layout book paginates despite it. The stored number is for
  the reader to read, and is written from `BookPositions.resolve()` so
  it says what the scrubber said. Correcting that number afterwards writes
  the one column and nothing else (`AnnotationDao.setPosition`), or a
  mark the sync pass changed meanwhile would be written back over.
- The footer's two numbers are the only ones in the app that are *not*
  stable positions. In a reflowable book being paginated they count
  screenfuls, measured from the laid-out document by
  `SectionScreenProgress`: the middle slot counts screens left in the
  resource on screen, so it goes 10, 9, 8, one per turn
  (`pagesLeftInChapter`), and the right edge prints a whole-book page
  number as `137/892` (`footerPages`). A whole-book figure can be
  measured because a resource's *share* of the book is stable even when
  its length is not: `BookPositions` counts positions to give every
  resource a positive share that tiles the book, and
  `BookScreenEstimate` keeps one sample per reading-order index —
  index, not href, since a reading order may list a file twice — and
  divides screens by span. The screens *behind* the reader are counted
  rather than scaled, and only never-visited stretches are guessed at
  the running density; and because a book resumed mid-way has a guessed
  stretch ahead of where it was opened, each resource's `origin` is
  settled when it is first measured and afterwards only pushed further
  along, never pulled back. Those two together are what stop a short
  resource joining the samples from dragging the page number backwards
  on a forward turn — the property `BookScreenEstimateTest` asserts
  screen by screen from every resumption point, not just from page one.
  The total refines rather than lurches and stands still while one
  resource is read, which is what makes the page walk up by exactly one.
  Anything that rebuilds the page bumps `layoutGeneration`, which empties
  the estimate and makes a measurement still in flight from the old shape
  be refused rather than averaged in. A reading is only
  filed against the resource the progress agrees is on screen, since
  progress and navigator are published on separate paths. The estimate
  is signed as one: "About page 137 of 892" to a screen reader, and a
  slash rather than the scrubber's `137 of 892`. A fixed-layout book is
  exact instead, since there a position *is* a page, and a scrolled book
  draws no footer at all. All of it is display only: never stored, never
  sent, never counted as reading, and the wording stays *page*
  throughout, because that is what liseur-sync's web reader says. A
  measurement that has not settled shows nothing rather than a stale
  number, and a turn held under the thumb freezes the count until it is
  made or put back.
  See `docs/adr/0036-the-footer-counts-screens.md`.
- Removing a book keeps its annotations *and* its `annotation_sync`
  rows. They are still on the server and on the other phone, and
  dropping only the agreements would push every mark again as new when
  the book came back. `BookRemoval.contentReplaced()` is the one path
  that clears both, in one transaction, because a different file took
  over the path: a sync row with no annotation behind it reads as a
  deletion the reader made.
- Per-account annotation state is cleared inside
  `RemoteAccountRepository.forgetSyncPeer()`, never at a call site.
  Disconnecting, switching accounts and `forgetUnreadableAccount()` all
  go through that one door.
- `HighlightPalette` holds which colours the selection bar offers (any
  subset of the six, yellow/green/blue by default) and which one an
  unpicked mark gets. It is presentation only: all six `HighlightTint`
  names stay legal in the database and in both directions on the wire,
  because the enum matches liseur-sync's palette. Never filter a stored
  tint through the palette or rewrite one that has fallen outside it —
  `chipsFor()` appends the selected mark's own colour precisely so
  recolouring it stays possible. An empty set means the bar offers a
  plain Highlight in the default colour, never no way to mark a passage,
  and it is stored: an *absent* set is a reader who never chose and gets
  the three, an *empty* one is a reader who chose none. The default is
  not required to be offered, since notes and that plain Highlight need
  it either way. See `docs/adr/0026-a-configurable-highlight-palette.md`.
- A tapped mark opens as what it is: a plain highlight gets the
  `SelectionPopup` bar, a mark carrying a note gets `NoteSheet`, a
  bottom sheet painted in the reading theme that shows the note and only
  then the things to do with it. Both ride the same `tappedSelection`
  state, so the bar's guards (no turn, no auto-scroll, no curl) apply
  to the sheet without a second copy. A passage selected by hand, even
  over a noted mark, still gets the bar. Recolouring from the sheet does
  not close it. See `docs/adr/0027-a-tapped-note-opens-as-a-note.md`.
- A book only on this device can be sent to liseur-sync, where the
  server allows it: `BookUploader`, `ServerCapabilities.canUpload` (read
  from the `library-upload` scope) and `BookUploadWorker`, whose unique
  work name `upload:$bookUrl` is itself the no-double-upload guarantee.
  What follows a successful upload is **adoption, not replacement**: the
  local row keeps its own `url`, because that is the key every reading
  position, annotation and session hangs off, and only `remote_uuid` and
  `download_href` are written (`BookDao.linkToRemote`). Never rewrite
  `books.url` to the server's spelling: the reader's place goes with it.
- A local cover is resolved once, at import, and written as a JPEG that
  every screen then reads through `books.cover_path`. Three routes, in
  this order and no other: `publication.cover()`, a declared `rel=cover`
  that is an SVG, then an image *named* `cover`, raster or SVG — a
  declaration is a statement and a filename is a guess. An SVG is drawn
  by `data/library/SvgCover.kt` rather than decoded, because
  `BitmapFactory` has never known the format; it is drawn on opaque
  white, since the file it lands in has no alpha, and its size is
  chosen rather than read, since a vector has none. AndroidSVG's
  external-file resolver is *static* and cannot suspend, so the images a
  wrapper SVG refers to are read before the render and handed over per
  thread — never let that callback reach the archive, or two books drawn
  at once can swap artwork. See
  `docs/adr/0035-an-svg-cover-is-drawn-not-decoded.md`.
- Live notifications are topic-only hints routed through `data/remote/`.
  Keep their foreground connection separate from the full-sync debounce,
  with a short background grace period. Connection identity includes the
  account and credentials, never feed cursors or sync timestamps.
  Topic refresh shares `PositionSyncCoordinator`'s turn lock; an event
  arriving during a refresh remains owed. Never call `syncAll()` for an
  invalidation or bypass the existing cursor and annotation reconciliation
  rules. Signal local annotation edits after commit, not by observing
  every Room mutation. An incoming position must not turn an open book's
  page or show a new catch-up offer until resume; acceptance uses the
  original preview's account, peer, fingerprint and local revision.
- Blocking network calls move to `Dispatchers.IO` inside the client that
  blocks, not in the caller. A `suspend` signature reads as a promise
  that the thread is safe, and a repository reached from a
  `viewModelScope` is reached from the main thread.
- Reader settings map to Readium `EpubPreferences`; reading themes
  (Light/Sepia/Dark/Black) are decoupled from the app's Material theme.
- Inside the reader, the Material theme *is* the page.
  `readingColorScheme()` turns the reading theme into a `ColorScheme` and
  `ReaderActivity` installs it over the whole activity, so a new sheet or
  dialog is on the reader's paper without being told. Do not hand-paint a
  new one from `ReaderTheme`; the existing hand-painted call sites are
  redundant but correct, and stay. Surfaces and ink are the page; accents
  are inherited untouched from the app palette at the page's lightness,
  which is what makes `error` and the accent buttons right per page.
  Mixes are opaque `lerp`, never alpha, because translucency stacks and
  e-ink dithers it. The mix factors are not an opinion: raise the one
  whose case fails in `ReadingColorSchemeTest`, which checks WCAG
  contrast over every theme, palette and e-ink state. `surfaceTint` must
  stay transparent or elevation tints a sepia sheet lilac, and `scrim`
  must stay black or a night page's scrim lightens the page. Dynamic
  colour stops at the reader's door — a wallpaper accent was never
  checked against a page Liseur picked — while the library and settings
  keep it. Bar icons follow the page, from `SystemBarIcons`, not from
  `ImmersiveMode`, since the page's colours reach the loading and error
  screens too. It speaks for the window it is composed in, not for the
  activity's: a sheet is a window of its own, and Material paints that
  window's icons from the sheet's ink only when it is built, so a theme
  changed under an open sheet leaves them behind unless the sheet says it
  again — which `LiseurModalBottomSheet` does, for every sheet, once. See
  `docs/adr/0031-the-readers-chrome-is-painted-on-the-page.md`.
- The chrome lies over the page, so it reaches the screen's edge. A
  scrolled page runs to that edge, and a bottom panel lifted off it by
  `navigationBarsPadding()` left a strip of the book printed underneath
  the controls, across the gesture pill (#223). The navigation-bar inset
  therefore lives *inside* `ReadingScrubber`, after its background, and
  the column that holds the panel takes it as a spacer for the times the
  panel is not there — that spacer is what keeps a lone pill's lift.
  Where the chrome's inner edges cut a line of type in half,
  `ChromeEdgeFade` ramps the page's colour away so the cut reads as
  something covering the page rather than as a fault. Electronic paper
  gets no ramp, as it gets no shadow: it dithers one and ghosts it.
- A new reading setting goes in the Advanced sheet
  (`reader/chrome/AdvancedSheet.kt`), and on Settings -> Reading
  appearance if it is about how the page *looks*, or on Settings ->
  Reading & navigation (`ui/settings/ReadingNavigationScreen.kt`) if it
  is about how the book is turned, held or looked up. Both screens end
  in an Advanced section, closed on arrival, for the rows a reader sets
  once if ever: Reading & navigation's holds the page turn, the
  page-turn sides and pinch to resize (`SettingsExpandableGroup`, a
  card of `ListItem` rows), Reading appearance's holds the appearance
  settings the Advanced sheet also keeps behind Advanced — line
  spacing, margins, columns, the fine typography and the footer
  (`SettingsExpandableSection`, no card, because that screen is
  controls rather than rows). A setting shown on both surfaces is
  everyday on both or advanced on both; they must not disagree. The
  typography sheet is the
  short list a reader changes often (theme, size, brightness, font, and
  how the book is read), and it only grows for a setting that genuinely
  belongs there. Make that case in the pull request; the default is
  Advanced. It grew to eleven controls once, one reasonable row at a
  time. See `docs/adr/0001-advanced-reading-menu.md`.
- How a page turns is one setting with three answers
  (`PageTurnStyle`: lift, slide, none), not a boolean. `PageTurner`
  already performed all three motions; it is told which by `style()`,
  and two facts overrule the reader's choice with `NONE` in that one
  lambda rather than anywhere else, through
  `pageTurnStyleOnScreen(chosen, eInk, motionRemoved)`: electronic
  paper, and a system whose animation scales are off. They are named
  apart because they are not the same fact even where they agree.
  Reading Android's switch is `ui/SystemMotion.kt`, and it reads
  `ANIMATOR_DURATION_SCALE` alone, observed rather than read once and
  treated as removed when not above zero. That key and no other because
  it is the one Compose's `MotionDurationScale` obeys, so the turn and
  the spring that settles a released curl always agree;
  `TRANSITION_ANIMATION_SCALE` zeroed by itself would stop one and not
  the other, and governs activity transitions anyway.
  Do not add a second check for it anywhere: Compose already collapses
  its own tweens through `MotionDurationScale`, and the one lambda
  covers everything Liseur asks for outside Compose, since
  `scrollScreenful` reads `style() != NONE` for its glide and
  `revealEnd` reads `style() == LIFT` for its lift. The endpaper is
  drawn over the book rather than navigated to, so only `LIFT` animates
  its arrival. Neither overrule is announced in Settings; the control
  goes on showing what the reader chose.
  A sideways drag answers to the same setting, but not by turning the
  page the way a tap does: under `LIFT` and `NONE` it curls the
  departing page off the book under the finger, and it can be pulled
  halfway and put back. `SLIDE` is left to Readium, which is that
  motion already, and so is electronic paper — a curl dragged across
  e-paper is the trail of half-erased pages the lift is refused for, and
  `turnStyle` cannot say so, since a forced `NONE` reads like a chosen
  one. That is why the claim asks a separate `interactive` predicate,
  which answers for the panel alone: a page following a thumb is the
  thumb moving, and removing animations was never a request to stop it.
  `R2WebView` moves the columns in its own
  native gesture code, which a JavaScript `preventDefault()` cannot
  stop, so the drag is claimed and consumed in `ReaderScreen`'s
  `PointerEventPass.Initial` loop instead — the same route the image
  viewer uses.
- A `LIFT` turn that cannot photograph the page jumps, and never
  borrows `SLIDE` (#201). The commonest reason it cannot is that the
  reading controls are up, since the toolbar sits inside the bounds
  `PixelCopy` reads; borrowing the other style's motion there made one
  volume key turn the page two ways depending on whether the menu was
  open, and left the footer behind, because Readium publishes its
  locator when its scroller stops while the lift publishes it as the
  jump is made.
- The curl is a turn that has already happened: `beginDraggedTurn`
  photographs the page and jumps the navigator with `animated = false`,
  and `PageCurl`/`PageCurlOverlay` draw the snapshot over it with
  `drawBitmapMesh`. Putting it back is `nav.go(from)`, exact, so a
  boundary the tentative turn crossed is crossed back to the same spot;
  the ViewModel sees a turn and a turn back, as it does for a tap and a
  tap back. Keep it off what cannot be undone: `turn` and `stepChapter`
  refuse while a dragged turn is live, and the last page is probed
  before the photograph, since the endpaper finishes the book and must
  never be tentative. A refused curl falls back to the swipe. The
  snapshot is of the *publication view*, not the window, so both
  overlays are placed at that view's bounds rather than stretched over
  the screen, and neither is taken while the chrome is still fading in
  or out, or the toolbar is photographed onto the page.
- A turn in the hand is put back at `ON_PAUSE`, never at teardown: a
  rotation swaps the navigator, and driving the one being let go of
  throws once its fragments have lost their views. Disposal only drops
  the page and forgets the turn. Anything that ends a curl from outside
  the gesture — leaving, a rotation, a window resize — goes through
  `PageTurnDrag.abandon`, which puts the page back without animating,
  since an animation wants a next frame there may not be one of.
- What a stylesheet alone cannot put right in the book's document is
  repaired in the page by `repairPage()`: `WideContentFit`,
  `FootnoteLayout` and `SelectionHandleFix` each inject a runtime
  `<style>` and write only token-owned attributes back, never the author's
  classes or markup. Each reports `changed`/`stable`/`blocked`/`failed` so
  the caller knows whether the reader's place has to be put back.
  `SelectionHandleFix` works around a WebView
  bug (Chromium 522869957): selecting a paragraph's first word paints the
  start handle on its last hyphen. It leads each block with two
  non-breaking spaces at `font-size: 0` — NBSP, not ZWSP, so `::first-letter`
  still finds the drop cap; two, since offset 1 triggers the bug too — and
  only on blocks whose first in-flow child is inline with no authored
  `::before`, because on a block of blocks that pseudo gets a line of its
  own. It checks the computed pseudo-element after marking and removes the
  token if a higher-specificity authored rule leaves the reset unsafe. Never
  write it as a blanket `p::before`.
- Bundled fonts must be under open licenses (OFL): Literata et al.
