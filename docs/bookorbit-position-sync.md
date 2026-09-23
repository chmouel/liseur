# BookOrbit position synchronization

Liseur syncs the reading position of verified EPUBs with BookOrbit.
`ServerKind.BOOKORBIT` advertises `syncAbility = EXACT`, and an account
with an access or refresh token has `canSync = true`
(`BookOrbitPositionSync.AUTOMATIC_SYNC_ENABLED`). Reading-status sync is
implemented but its automatic writes stay off
(`BookOrbitStatusSync.AUTOMATIC_SYNC_ENABLED`) until an independent client
has checked it live. Annotations are not synchronized.

This file describes the protocol and the rules the code relies on. The
dated work log that led here is in the git history of this file.

## What BookOrbit stores

BookOrbit keeps one progress row per user and book file: a `percentage`
and an optional EPUB `cfi`, plus page, audio, Kobo and KOReader fields.
The checks below were made against v3.0.0 (container configuration
digest `sha256:8b811671dab475336b020ab84cd4f463d2acc04c12c943ba50e6cd53e325f880`,
which matches `ghcr.io/bookorbit/bookorbit:3.0.0`).

- `GET /books/files/{fileId}/progress` answers for one file. An unopened
  file returns a default (percentage 0, null position fields, no
  `updatedAt`); a saved zero has `updatedAt`. `BookOrbitProgressClient`
  keeps the two apart and refuses malformed JSON, partial defaults and a
  percentage outside 0..100. `lastReadAt`, `textUpdatedAt` and
  `updatedAt` are shown to the reader and never used as merge clocks.
- `POST /books/files/{fileId}/progress` replaces the text place. A POST
  with only `percentage` sets `cfi`, `pageNumber`, `positionSeconds`, both
  media-overlay fields, the four Kobo fields and `koreaderProgress` to
  null; narration percentage and timestamp survive. Liseur therefore
  never sends a place without a verified CFI. A text write can also move
  read status, Kobo state and sibling EPUB progress through server hooks.
  The source is `saveProgress` and `resolveTextPosition` in
  `book.service.ts`, and `upsertProgress` in `book.repository.ts`.
- The percentage column is PostgreSQL `real` (float4). Liseur serializes
  its outgoing percentage at `Float` precision before saving the request
  bytes, so read-back can require the exact CFI and the exact percentage.
- The write is unconditional
  ([server source](https://github.com/bookorbit/bookorbit/blob/27cfdc20282eabdc89296c8c45482c578c157c7c/server/src/modules/book/book.service.ts#L2238-L2259)).
  BookOrbit has no compare-and-swap, so another device can write between
  Liseur's last GET and its POST.
- Reading status is `readStatus` on `GET /books/{id}`, changed by the
  status-only `PATCH /books/{id}/status`, which leaves file progress and
  its CFI alone.
- Places saved without a CFI are common: imports and some clients write
  them. On the maintainer's server 21 of 29 rows had none.

## The CFI bridge

BookOrbit's web reader is foliate-js and speaks EPUB CFI; Liseur's local
positions are Readium locators. The bridge converts between them only
when it can prove the result, and otherwise keeps the local place.

- `BookOrbitCfi` parses point and range CFIs per EPUB CFI 1.1, including
  foliate's text assertions with side bias (`/1:18[before,after;s=b]`),
  bare range endpoints and both indirection placements.
  `serialize()` returns the original string. Temporal and spatial offsets
  are unsupported; malformed syntax and duplicate assertion parameters
  are refused.
- `BookOrbitForeignCfi` ties a raw CFI to the account, book, file and
  binding revision. `BookOrbitCfiRepository` retains it (schema 55) after
  checking the connection and binding in the same transaction; an
  unparseable CFI is kept with its reason and never turned into a
  percentage.
- `BookOrbitEpubPackage` reads `container.xml` and the OPF through the
  zip central directory, each capped at 4 MiB, keeps non-linear itemrefs
  (they still occupy CFI indices), drops remote manifest items and
  refuses duplicate ids and escaping paths. XML parsing refuses external
  identifiers and internal subsets. `BookOrbitEpubInfoClient` compares the
  package path and spine with the server's `/info` for the selected file.
- `BookOrbitCfiResource.locate` maps spine and itemref steps to a
  resource. `BookOrbitCfiDom.resolve` walks a supplied document with
  element parity, id and text assertions, grouped UTF-16 text nodes and
  endpoint order. `BookOrbitCfiDom.capture` builds a CFI and returns it
  only if it resolves back to the same nodes and offsets.
- Incoming: `BookOrbitIncomingAnchor` turns a CFI resolved in the
  selected file's original XHTML into a Readium text anchor. The active
  WebView must then show that resource and the quoted text before the
  place counts as verified. Android's WebView throws on
  `compareDocumentPosition`, so the check walks nodes in order instead.
- Outgoing: `BookOrbitViewportCfi` captures the visible text position
  from the navigator and discards it on a stale href, WebView, layout
  generation, reflow or file. The candidate must resolve to the same text
  in the original XHTML of the app-owned download.
  `BookOrbitLocalPositionWriter` saves it with the locator in one
  transaction (schema 56); any later local write without a candidate
  deletes it. Not every page turn yields a CFI.

Parser and resolver caveats:

- A parsed CFI is still DOM-unverified; `unresolvedReason` only reports
  parser failures.
- The resolver enforces step parity: even steps land on elements, odd
  steps between them.
- A step's `s=` parameter lives in `Step.parameters`, and element-boundary
  locations must honor it.
- `/6/4!:3` parses but has no text meaning; treat it as unresolved.
- A range parent ending in `!` is an open path. Join it with each
  endpoint before walking, and never resolve `Range.parent` alone.
- Use `spineStep` and each spine item's `step`; the package is not always
  `/6`.
- Flatten only reader-owned wrappers, never an EPUB element.
- Never use `BookOrbitForeignCfi.parsed` directly as a `Locator` or a POST
  payload. A failed exact resolution may offer an approximate place, which
  must never be written back as exact.

## Agreement and exchange

`book_orbit_position_agreement` (schema 57, attempt generation in 58) is
keyed by account and local book URL. It records the selected book and
file, binding revision, connection epoch and address, the agreed local
revision and locator, the agreed remote CFI and percentage, the latest
remote candidate, and one outgoing request with its sent revision and
preflight. Disconnecting the account clears it; deleting the binding
cascades.

`reconcileExactPosition` in `domain/ReadingStateMerge.kt` decides from the
local `position_revision`, never a timestamp or a percentage tolerance. A
local move alone pushes, a verified remote move alone proposes a pull,
equal CFIs settle even at different percentages, and different CFIs at
the same percentage conflict. A remote place without an agreement is not
assumed to be this device's. Status changes bump `status_revision`, not
`position_revision`, so they never invalidate a verified CFI.

`BookOrbitPositionExchange.run` then walks one request through these
states:

1. `prepare` reads the file, checks that the local CFI still pairs with
   the stored locator and revision, and commits the exact UTF-8 bytes as
   `PREPARED`.
2. `send` reads the file again. A changed CFI, saved state, percentage or
   display time discards the unsent request and keeps the new candidate.
   A changed local revision stops it too. Otherwise it commits
   `MAY_HAVE_BEEN_SENT` and POSTs the stored bytes once. POST bodies are
   one-shot and connection retry is off, because OkHttp otherwise resends
   after `503 Retry-After: 0`.
3. A 2xx or ambiguous answer only authorizes read-back. A GET that returns
   the exact CFI and percentage acknowledges the sent revision, so a page
   turn made meanwhile stays dirty. A failed GET leaves `UNCERTAIN`, and a
   later `readBack` settles it without another POST, including after
   process death. An unchanged server gives `RETRY_REQUIRED`; a changed
   one is a conflict. A 401/403 gives `REJECTED`.
4. `UNCERTAIN`, `MAY_HAVE_BEEN_SENT`, `RETRY_REQUIRED` and `REJECTED` are
   read-back-only on every later run. Only an explicit reader choice can
   prepare a new attempt, and the attempt generation stops an old choice
   from authorizing a byte-identical retry.

Sends, read-backs and choices share `database.bookOrbitPositionMutex`.
Every write re-reads the agreement row inside its own transaction.

## Reader behaviour

- Opening a book first reads back any potentially sent request, so an
  interrupted save is acknowledged instead of offered again.
- A book with no local place reads its file's progress while loading and
  opens at a verified server CFI. Failure falls back to the start.
- A book whose local place is unchanged since the last agreement may open
  at a newer verified server CFI. The local row changes only after the
  reader closes, a fresh GET confirms the same place, and a transaction
  rechecks account, binding, attempt, agreement, candidate and the exact
  local revision and locator.
- A first-time disagreement, or movement on both sides, shows a choice
  once the active WebView has verified the server place. Taking the
  server's place or keeping this device's runs after the reader closes
  and its queued writes land. A server CFI that cannot be verified offers
  only "send this device's place again" or cancel. A choice interrupted
  by process death is dropped and offered again on the next opening.
- Closing the reader signals another sync after queued writes release
  the open-book fence, so the last page is not left unsent.
- A failed exact opening clears the proposal and its proof.

## Percentage-only server places

Liseur never writes a place without a CFI and never overwrites one it has
not agreed with. It opens at one as an approximate whole-book fraction
(`approximateOffer`) in three cases:

| Local place | Condition | Opening |
| --- | --- | --- |
| None | Any percentage-only server place | Server percentage |
| Agreed with BookOrbit and unchanged since | Server moved to a different percentage-only place | Server percentage, with the way back |
| Never matched with BookOrbit and no verified CFI | Server percentage is further ahead | Server percentage, with the way back |

Opening records nothing. The first page turn (`READER_MOVEMENT`) accepts
the opening: it records the percentage as the agreed remote place and the
place before opening as the agreed local one, so that move is pushed with
an exact CFI through the usual preflight, POST and read-back.
`BookOrbitLocalPositionWriter` records the agreement in the same
transaction as the move, so a restart cannot keep one without the other.
Later page turns carry the same offer, which covers a failed first write;
once the row has changed, the offer records nothing.

Any jump declines the opening: the way back, a bookmark, the contents,
the scrubber or go-to-page, even onto the page already shown
(`keepsApproximateOpening`). The book stays unresolved and the server
place is untouched. The first page turn withdraws the way back, and a tap
on a pill already withdrawn does nothing. The recording is also refused
if the server was seen to change or an attempt started meanwhile.

`reconcileExactPosition` compares percentages when both the agreed and
the current server place lack a CFI. A different percentage-only place
stays unresolved and receives no POST.

## Account sync

`BookOrbitPositionSync` handles at most 20 selected EPUB bindings per
call. Schema 58 stores a frozen member list, cursor and accumulated
result for the captured connection, so a recreated worker resumes the
same finite walk and a connection change invalidates it. A changed
binding stops the page instead of retargeting. Other accounts' local
places are skipped. Conflicts and unverified CFIs report non-retryable
`PositionUnresolved`, and a later settled page cannot hide them; a
separate continuation flag still lets workers reach later pages. Unread
catalog entries without pending bytes need no request. A complete
traversal without a recorded failure updates the account's sync time.

The provider's observation-only mode never prepares or POSTs. The
generic `PositionSync` preview and choice methods stay disabled because
they cannot prove a foreign CFI against the active reader.

## Approved write policy

On 2026-09-23 the maintainer accepted last-server-write-wins for the race
after the final preflight: the last write BookOrbit stores wins, whatever
device read most recently or furthest. A delayed write can replace
another device's newer place. Liseur still refuses a conflict it can see
before sending, keeps the CFI and revision pairing, the account and file
guards and the no-replay rule, and acknowledges only an exact read-back.
Another device winning before read-back leaves the request unresolved.
Parity with the BookOrbit iOS app's conflict and offline rules is not
established; its public repository has no sync source.

## Reading status

Liseur's explicit finished and unread marks map to BookOrbit's `read`
and `unread`, and manual BookOrbit `read` and `unread` map back. A manual
`reading` maps only when the local passage is already in Liseur's
reading range. Automatic statuses stay derived from position.
`want_to_read`, `on_hold`, `rereading`, `skimmed`, `abandoned` and unknown
values are preserved on BookOrbit.

Status agreements are separate from position agreements. Liseur persists
the exact PATCH bytes, sends once and reads the status back; it never
replays an uncertain write. A newer local status action replaces a
pending attempt. When only BookOrbit changed since the agreement, Liseur
adopts a status it can represent. An unconfirmed write stays unresolved
until the next local status action.

Before enabling automatic status writes, check live on a disposable
account: detail and PATCH behavior, competing edits, interrupted requests
and that the position survives. The public API cannot delete a status row
after a test, so plan the cleanup in the database.

## Acceptance

All checks used the disposable `copilot` account on `orbit.chmouel.com`
and book 90/file 260 (EPUB SHA-256
`ed79d46102ca8b465db15ee66d0c0b4d6d7858afb6b60b75beacd32b049fbc6c`).
The catalog has a second book with the same title, so browser checks used
the selected-file reader URL.

| Area | Checked on 2026-09-22 and 2026-09-23 |
| --- | --- |
| Server semantics | A percentage-only POST nulled the ten other text fields and kept narration; the float4 round trip needed `Float` serialization. |
| Web reader to Android | Web-reader range CFIs restored the same passage on a fresh book and on an agreed book; missing anchors kept the local place. |
| Android to web reader | A Liseur CFI reopened at the same passage in an isolated browser session. |
| Two Android installs | API 26 and API 36, separately signed in, adopted each other's places through normal routing and close scheduling. |
| Faults | A proxy dropped a successful response: one POST, acknowledged by GET. Killing the app after receipt left `MAY_HAVE_BEEN_SENT`; restarting acknowledged it with no second POST. |
| Races | A write just before Liseur's POST lost to it (accepted policy); a write before read-back left the request `UNCERTAIN`. A stale choice failed without changing the server. |
| Lifecycle | Rotation and reopening during post-close work changed nothing; a queued choice killed before running was not replayed. |
| Identity | Changed file id, epoch and account refused choices without touching any row. |
| Percentage-only places | Each of the three openings; reading on pushed an exact CFI; the way back and a same-page jump sent nothing and kept the server place. |

These checks do not prove every EPUB or the iOS app's replay behavior;
an anchor Liseur cannot verify keeps the local place. After each run the
file's progress was deleted through the API. BookOrbit's automatic
`reading` status row and one reading attempt for the test account remain,
since the API cannot remove them.

## Fixtures and reproduction

- `progress-*.json` in the test resources are synthetic.
  `progress-live-*.json` are sanitized responses from the test account
  captured on 2026-09-22, with ids and timestamps replaced and the
  null/presence shapes kept. `BookOrbitLiveProgressTest` covers them.
  Eight sanitized `readStatus` projections come from PATCHing a test book
  through every status.
- `app/src/test/resources/bookorbit/OPS/` holds project-authored package
  and chapter documents. `foliate-cfis.json` is output from BookOrbit's
  unmodified `client/public/assets/foliate/epubcfi.js` at v3.0.0 commit
  `6be648b48a9cc376cfeff8953ebf22123583f7eb`, generated in headless
  Chromium from real DOM ranges. To regenerate it, serve
  `tests/bookorbit/capture.html`, the pinned `epubcfi.js`, `package.opf`
  and `one.xhtml` from one directory on localhost and open the page;
  it prints the corpus. Do not vendor the upstream JavaScript.
- The `/info` DTO follows v3.0.0's `packages/types/src/epub.ts` and
  `epub.controller.ts`; `containerPath` names the OPF and spine hrefs are
  archive-root paths.
- `tests/bookorbit/ParserSmoke.java` runs the production parsers on a
  device: compile with `javac --release 8`, convert with
  `d8 --min-api 26`, copy the dex jar and the debug APK to
  `/data/local/tmp`, and run `app_process /system/bin ParserSmoke` with
  both on `CLASSPATH`.
