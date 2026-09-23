# BookOrbit position synchronization

BookOrbit position synchronization remains disabled. `ServerKind.BOOKORBIT`
must stay at `syncAbility = NONE` and `canSync = false` until the complete
round trip and durable reconciliation work is proven.

## Phase 4 bounded exchange (not scheduled)

`BookOrbitPositionExchange.run(bookUrl)` is an opt-in, real selected-file
exchange built on the Phase 3 agreement. It captures the current account,
epoch and binding, reads the selected file, retains the remote candidate,
and uses the verified local locator/CFI/revision pair for a push only when
the exact merge authorizes it. It prepares the exact bytes before sending.
`send` now performs another selected-file GET immediately before marking
the request `MAY_HAVE_BEEN_SENT`; a changed CFI, saved state or percentage
discards the unsent preparation and retains the changed candidate without
POSTing. A changed local revision similarly prevents delivery. A lost
response remains uncertain and the next exchange reads back **without
POSTing**; a still-unchanged preflight is reported for a separate retry,
not automatically replayed. An explicit rejection is reported separately.
Position agreement does not touch local reading status or another
provider's pending status action.

This is **not** a `PositionSync` implementation or a `RemoteRouter` entry.
Normal sync, reader opening and background workers do not call it; an
opt-in live POST is documented below. A remote CFI with no verified
local Readium locator is retained as unsupported, not adopted or pushed
over. There is no verified manual take/keep resolution for a first-time
disagreement or concurrent local and remote movement.
Registering a provider now would either move an open book without proof,
silently treat these unresolved books as synced, or expose an action that
cannot safely resolve them. The opt-in exchange is a bounded protocol
component, not a claim that Phase 4 scheduling or the final gate passed.
BookOrbit still lacks compare-and-swap, so the final GET cannot exclude
a competing write immediately before POST.

Next phase owner: keep this acceptance record current with your own live
findings and caveats before handing it on. Do three bounded review passes
between phase boundaries. App-managed delivery and read-back of a
simulated uncertain attempt passed on the selected file; now implement
checked conflict choices for books with unsent local changes. Revalidate
account, binding, local revision/locator, candidate CFI/percentage, and
active-reader state at the final choice. Do not register normal scheduling
or enable the capability/UI while these paths are unproven.

### Guarded existing-local adoption

An existing local place now makes a bounded selected-file GET while the
reader is loading. Only a book with a prior BookOrbit position agreement
whose **local revision and locator are unchanged** and whose remote CFI
has moved since that agreement may open at the remote passage. A pending
or rejected POST, a first-time disagreement, and any local movement since
agreement leave the local opening place intact; the remote candidate is
retained for later resolution. The selected, app-owned EPUB and original
spine XHTML must resolve the CFI, and the active Readium WebView must
verify the marked passage. Failure uses the saved local locator.

Verification is only a proposal while the reader is visible. When that
reader closes, queued local position writes finish before its open-book
fence is dropped. A separate bounded GET confirms the same selected-file
CFI and percentage; the opened EPUB's original document is rechecked.
Only while the book is **closed**, a Room transaction rechecks account,
binding, pending attempt, agreement baseline, candidate, and the exact
local revision and locator. It stores the verified Readium locator and its
local whole-book progression, paired CFI and BookOrbit agreement together
(which separately records the remote percentage), increasing the
local revision for other providers. It does not change reading status,
finished intent, generic sync acknowledgement, or status baselines.
Another account's locally owned place, an open reader, a page turn, or a changed remote answer declines the
adoption. The next opening uses the saved locator; the current reader is
never moved by a late database write. No BookOrbit POST is made.

On 2026-09-23, a live existing-local check on the disposable API 26
emulator used only the `copilot` account and book 90/file 260. After the
account was reconnected, the newer web-reader CFI passed the agreement
and selected-file guards, resolved in the original EPUB, and was marked
as an opening proposal. The active-WebView check returned true, and the
local revision remained 38 while the reader was open. Closing normally
advanced it to 39 and recorded the newer CFI and remote percentage in the
agreement. **The first passage check was inconclusive:** screenshots taken
both before closing and after reopening still showed the earlier local
passage rather than the distinct later passage captured in the web
reader. The persisted local progression was 4.683% while BookOrbit
reported 6.639%; those scales are not interchangeable. The first
attempt was additionally blocked by an expired BookOrbit session, not by
the position guards. The selected-file server progress was removed with
the API; user 3/book 90 status and reading-attempt rows were removed in
a targeted transaction. All three server counts returned to zero. The
disposable emulator database was restored from its pre-test backup and
the private login trigger and backup were removed. No diagnostic login
hook or credential is part of the committed app.

Following that result, the opening check was tightened to evaluate the
quote directly in the WebView covering the reader's center, instead of
checking the visible WebView's resource and separately asking Readium's
navigator to evaluate JavaScript. It checks that the same view and resource
remain current after the asynchronous response. This guards against
verifying an adjacent chapter while displaying another one. A second
live check used a new, distinct web-reader CFI at 9.267%. The screenshot
of Liseur's open page and BookOrbit's two-page view showed the same
passage; the local revision stayed 38 while open, advanced to 39 only
after closing, and a fresh opening displayed the adopted passage at
revision 39. The agreement matched the newly read CFI and percentage.
This is acceptance evidence for the guarded, agreement-backed incoming
path, not proof that the first discrepancy's root cause was the
mixed-WebView verification: the second check used a different CFI.
The later test again deleted only the selected-file progress and
approved user 3/book 90 status and reading attempts; all three counts
returned to zero. The disposable emulator database and original app
build were restored, with no test agreement or temporary login hook left.

This automatic path is deliberately limited to a clean BookOrbit baseline;
there is no choice UI for unsent local movement. A lost WebView verification
or process death before the reader closes leaves the candidate unadopted.
The selected-file check relies on the app-owned download provenance and
size/mtime, not a cryptographic server content hash. The first live
rendered-passage discrepancy remains unexplained despite a successful
second check; multi-device races have not been exercised. Neither this
guarded path nor the opt-in exchange enables scheduled sync.

## Phase 3 bounded agreement boundary

Schema 57 adds `book_orbit_position_agreement`, separate from retained
foreign CFIs, paired local CFIs, and `reading_progress`'s status/baseline.
It is keyed by account and local book URL, and records the selected book/file,
binding revision, connection epoch/address, agreed local revision and locator,
agreed remote CFI/percentage, latest remote candidate, and an exact UTF-8
outgoing request with its local revision and remote preflight. Account
disconnect clears agreements; binding deletion cascades them. Token renewal
does not change the captured account/epoch and cannot redirect the request.
A same-account reconnect transfers pending read-back to the new epoch; old
contexts remain invalid, and the pending request is not replayed.

`BookOrbitPositionAgreementRepository` is an **opt-in, unscheduled**
boundary. `observe` retains selected-file candidates without adopting an
unverified CFI or touching reading status. Its exact merge uses the durable
local revision, not a timestamp or percentage tolerance: a lone local move
pushes, a lone verified remote move proposes a pull, equal CFIs settle even
when percentages differ, and different CFIs at the same percentage conflict.
The incoming `Pull` decision alone is not an applied reader position. The
reader-opening path below supplies an active-DOM-verified locator and only
adopts it for an unchanged agreement after the reader closes.
Unknown or wrong-edition remote anchors are retained, not overwritten.
An initial saved remote position with no agreement is not assumed to belong
to this device.

`prepare` reads the selected file, verifies the local CFI still pairs with
the stored locator and revision, and commits the exact serialized request
before any delivery. BookOrbit stores percentages as PostgreSQL `real`:
the request serializes the local percentage in that storage precision, so
the exact sent number survives read-back without relaxing the percentage
equality check. A prepared request cannot be replaced unless the
reader moved and `discardStalePreparation` atomically confirms it was never
marked sent. `send` commits `MAY_HAVE_BEEN_SENT` **before** POST and
uses those persisted bytes verbatim; a successful or ambiguous POST is
followed by a selected-file GET. Only a matching CFI **and percentage**
acknowledge the sent revision, so a concurrent page turn remains dirty.
An in-process read-back cannot pass a pending POST. A 401/403 leaves a
blocked rejected attempt, so a later exchange cannot replay it without
an explicit resolution path. If the remote still
matches the preflight, the caller may explicitly prepare afresh, never
blindly replay the uncertain request. A changed remote CFI is retained as
an unresolved conflict with the original bytes. A failed read-back leaves
the request `UNCERTAIN`; `readBack` can settle it after process restart
without another POST. No status column or
generic sync acknowledgement is changed.

The agreement boundary has no normal-operation caller, no automatic
retries, and does not enable `canSync` or register `PositionSync`. Unit tests
include local MockWebServer loss and float-precision read-back cases; an
opt-in app-managed exchange was tested live only with the approved account.
Before enabling a
provider, wire guarded incoming locator adoption, conflict-resolution
actions and bounded orchestration, and complete a disposable-book two-way
reader acceptance check. BookOrbit offers no compare-and-swap: another
device can still write between preflight and POST or between POST and
read-back. A matching read-back proves server state, not sole authorship.

### Live outgoing CFI interoperability

On 2026-09-23 the disposable emulator's selected book 90/file 260 had a
local CFI paired with revision 38 and its saved Readium locator. The same
test account's selected-file progress was empty. A one-time authenticated
test request posted that **app-generated** CFI and the local whole-book
percentage to BookOrbit. A GET returned both values; opening the BookOrbit
web reader showed the same passage as Liseur at the start of its spread.
The web reader wrote its own CFI while opening, so equality of the stored
CFI after opening is not the interoperability check; the rendered passage
is. Test progress, status and reading attempt were then removed, and their
counts returned to zero.

That first request was a **manual test request**, not a call to the Phase 3 durable
agreement boundary or an app-scheduled POST. It proves that BookOrbit can
restore a CFI captured by Liseur, while the separate incoming check proves
that Liseur can restore a web-reader passage.

The opt-in `BookOrbitPositionExchange.run` was then exercised **from the
app** on the same disposable local revision and selected file, with an
empty remote position. The first POST delivered the CFI but read-back
reported a conflict: BookOrbit stored `2.991799657` as `2.9917996` in
its `real` column. After serializing the float32-representable percentage
before POST, the app returned `Pushed`, the selected-file GET returned
the paired CFI and exact sent percentage, and the durable attempt became
`ACKNOWLEDGED` at revision 38 with no outgoing bytes retained. A
test-only `UNCERTAIN` attempt carrying the delivered bytes then returned
`Recovered` on a same-account reconnect. The server's progress
`updated_at` did not change during that read-back, consistent with no
second POST. This tests recovery of an already-delivered attempt, **not**
an actual lost network response or an automatic production caller.
The selected-file progress was deleted through its API and the approved
user 3/book 90 status and reading attempts were deleted narrowly; all
three server counts were zero. The disposable emulator database was
restored and its one-time login hook and trigger removed. Conflict choices,
production scheduling, true uncertain-network failure, and multi-device
races remain unproven. Keep sync disabled until those gates pass.

## Phase 2 incoming cold-open check

Only a book without a saved local reading place makes a bounded,
selected-file progress GET while it is still loading, after the ordinary
foreground sync and before entering `OpenBooks`. It retains the raw CFI under
the captured connection, account, file and binding revision. An exact
opening proposal requires the app-owned EPUB opened by Readium, the original
bounded spine XHTML, a valid CFI endpoint in that document, and a unique matching resource in
the opened Readium publication. The active WebView must show that resource
and the proposed text quote in its viewport. The opening gate suppresses
both pre-layout emissions and the confirming arrival. The local position
is rechecked after the bounded GET, before opening the book; a concurrent
local change wins over the incoming proposal. An unverified proposal falls
back to the previously saved local locator (or the start of the publication
when none exists), without posting or persisting the
incoming position. Failed, timed-out, unsupported and unopened responses
leave ordinary local reopening in place. No BookOrbit position sync
capability or provider scheduling was enabled.

On 2026-09-23 a disposable API 26 emulator with the selected app-owned
book 90/file 260 cold-opened after force-stopping the process, using a
fresh range CFI generated by that book's BookOrbit web reader. Readium's
active WebView verified the proposed passage, and the local
`reading_progress.local_revision` remained 38 after opening. The first
run exposed Android's DOM `compareDocumentPosition` throwing
`UnsupportedOperationException`; ordered node traversal replaced it,
and the second cold open verified the CFI. The web-reader CFI was not
POSTed by Liseur. The account's web-reader visit itself generated
test-account progress, which was deleted through the selected-file API
after the check. No other user's progress was touched.

This is **incoming-only**, not a two-way acceptance gate. Without an unchanged Phase 3 position agreement, books with an existing
saved local position do not pull remote progress on opening; no first-time
conflict resolution or background pull is claimed. The first live opening check above used an existing local
place before this safety restriction was added. A later check removed only
the selected book's progress on a disposable emulator, placed a web-reader
CFI for a later passage on the approved test account, and cold-opened the
app-owned EPUB. The active WebView verified the passage after process restart;
the reader showed a text page rather than the cover, and no local progress
row was created during opening. The emulator's prior paired reading state
was restored from a private backup. Test-account progress, status and
reading attempt were removed afterward; all three counts returned to zero.
This proves fresh-book incoming passage restoration, not a remote position
merge with existing local reading.
File provenance and CFI assertions reject mismatched selected files and
many wrong editions, but a structurally identical, assertion-free CFI cannot cryptographically
prove publication identity. Neither a remote percentage alone nor an
unverified CFI is used as a BookOrbit-derived exact location. The opening
gate still has its existing fail-open deadline, so a stalled navigator
may eventually report its current position; the normal exact-open
verification and fallback execute before that deadline.

## Phase 0 progress transport

`BookOrbitProgressClient.read` fetches only the selected EPUB file in a
captured `BookOrbitCfiContext`. It checks the connection and binding before
and after the GET. The parser requires a numeric percentage in 0..100. It
distinguishes BookOrbit's unopened default (zero, null position fields, no
`updatedAt`) from a saved zero (`updatedAt` present). Malformed JSON, a
partial default, an invalid percentage, or a non-JSON response fails the
call. `lastReadAt`, then `textUpdatedAt`, then `updatedAt` is display context
only; none is a local merge timestamp.

`BookOrbitProgressMutationTransport` is disconnected from sync. A POST
returns `ReadBackRequired` for a successful response, `Rejected` for an
explicit 401/403, or `Uncertain` for other responses and network failures.
It does not automatically retry connection failures, redirects, or
unauthorized POSTs. A 401 invalidates the refused access token so a later
attempt can renew it, without replaying this POST. A later phase must
compare a fresh selected-file GET against the exact sent payload before
acknowledging or retrying. Never interpret `Uncertain` as rejection. The
transport does not persist outgoing bytes or make writes on its own.

The original `progress-*.json` examples are synthetic and source-shaped.
The `progress-live-*.json` fixtures are sanitized authenticated responses
from the `copilot` account on `orbit.chmouel.com`, captured on 2026-09-22.
The deployed arm64 container configuration digest
`sha256:8b811671dab475336b020ab84cd4f463d2acc04c12c943ba50e6cd53e325f880`
matches the published `ghcr.io/bookorbit/bookorbit:3.0.0` image. Fixture
user, file and row IDs and timestamps were replaced with fixed test values;
the position fields and null/presence shapes were preserved. No private
book metadata or credentials were included. `BookOrbitLiveProgressTest`
checks the unopened and saved-zero replies, a three-file progress list,
and both sides of a replacement POST. Eight sanitized `readStatus` projections
were captured after PATCHing the same test book through every status, then
reading its book detail. The parser preserves those names and unknown future
values; full private book-detail DTOs were deliberately not committed.
MockWebServer tests exercise selected-file identity, malformed replies,
a lost POST response, and no automatic POST replay.

Source review of v3.0.0 `book.service.ts` (`resolveTextPosition` and
`saveProgress`) and `book.repository.ts` (`upsertProgress`) shows that a
text POST with only `percentage` sets `cfi` and `pageNumber` to null.
It also clears `positionSeconds`, both media-overlay fields, all four
Kobo fields, and `koreaderProgress` when omitted. `percentage` is replaced
by the sent value. Narration percentage and timestamp are preserved on
an existing row because `narrationColumns` is empty for a text write;
`textUpdatedAt` and `updatedAt` advance. A text write may also update read
status, Kobo state, and sibling EPUB progress through server-side hooks.
The live check used a single-EPUB book that had no prior `copilot` progress,
status or reading attempt. It saved a zero with a CFI, populated all ten
fields, established a narration marker behind the text place, then posted
only `percentage: 30`. GET confirmed that all ten omitted fields were null
while narration percentage and timestamp stayed unchanged. The account's
progress was deleted through the API, and the status and reading attempt
created for this account and book were removed transactionally; post-check
counts matched the clean baseline. No other user's progress was modified.
This proves the replacement semantics on the deployed v3.0.0 image, but
does not test BookOrbit web-reader ↔ Liseur interoperability or prevent
another device from writing between a GET and POST.

## Phase 1 acceptance

Phase 1 provides a parser and a guarded retention API. It does not run
background progress requests or pass foreign CFIs to Readium. Close this
phase against the checks below; another open-ended review is not an
acceptance criterion.

| Requirement | Evidence |
|-------------|----------|
| Point/range parsing and raw retention | `BookOrbitCfiTest`, plus captured Foliate output in `BookOrbitAcceptanceTest` |
| EPUB 2/3 package paths and non-linear items | `BookOrbitEpubPackageTest`; actual package and itemref element indices in `BookOrbitAcceptanceTest` |
| Selected-file info request | `BookOrbitEpubInfoClient`, request-path and malformed-response tests in `BookOrbitCfiRepositoryTest` |
| Wrong account, epoch, file or binding rejection | `BookOrbitCfiRepositoryTest`; checks and writes share a Room transaction |
| Restart-safe foreign CFI | Schema 55 `book_orbit_cfi`; the file-backed database reopen test preserves unsupported raw input |
| Account/book cleanup | Retention rows follow binding deletion by foreign key; disconnect clears retained CFIs while keeping downloaded-file bindings |
| Android XML behavior | Production APK parser smoke on disposable API 26 emulator, 2026-09-22 |

The `/info` test body follows BookOrbit v3.0.0's published DTO; it is not
a captured authenticated response. The Phase 1 run had no BookOrbit
credentials; Phase 0 later added the live progress fixtures described above.

On 2026-09-22, `make check` passed, the Chromium recapture matched all four
committed CFI fixtures, and the API 26 smoke passed. One final review of the
five implementation gates (retention, ownership, lifecycle, metadata
cross-check and evidence) found no significant blockers. Further review
changes should start with a reproducible failure against those gates.

## Implementation

The app now has pure JVM helpers for the identity half of the CFI bridge:

- `BookOrbitCfi` parses point and range CFIs following EPUB CFI 1.1:
  indirections, element/text steps, id assertions, a single terminating
  character offset, and the text location assertion with side bias inside
  the offset's brackets (`/1:18[before,after;s=b]`, the form foliate-js
  writes). Values are split on unescaped `,` and `;` before `^` escapes are
  removed. Range endpoints may be bare offsets (`,:1,:4`); an empty start
  path means the parent location. The indirection into a content document
  may end the parent (foliate's
  `/6/4!,/4/..,/6/..`) or open each endpoint (`/6/4,!/4/..,!/4/..`); both
  mean it applies to the parent's last step. `serialize()`
  returns the exact original string, so a retained CFI is never rewritten.
- Temporal and spatial offsets are refused as unsupported. Malformed syntax
  (an offset mid-path, `!` without a following step or offset, an offset on
  a range parent, an unknown side bias, unbalanced brackets) and duplicate
  assertion parameters are refused as malformed. Unknown unique assertion
  parameters are kept, as the specification asks readers to ignore them
  rather than fail.
- `BookOrbitForeignCfi` ties a raw CFI to the BookOrbit account, book, file
  and local file-binding revision. An unparseable CFI stays intact with its
  reason; it is not silently converted to a percentage.
- `BookOrbitCfiRepository.capture` snapshots the connection and persisted
  binding. `retain` rechecks both inside the write transaction; `load`
  reparses the stored raw string and refuses a changed edition. The stored
  row contains source data only. No position or status acknowledgement
  changes.
- `BookOrbitEpubPackage.parse(File)` opens the EPUB through the zip central
  directory (what Readium trusts) and decompresses only
  `META-INF/container.xml` and the OPF, each capped at 4 MiB. It chooses
  the first OCF-namespace rootfile with the OPF media type, reads only
  OPF-namespace manifest and spine elements including non-linear itemrefs,
  percent-decodes
  hrefs into archive entry names (refusing escapes that are not valid
  UTF-8), leaves remote manifest items out, and refuses duplicate ids,
  absolute hrefs and paths that escape the archive.
- Jsoup checks for document types without fetching or expanding entities;
  bare declarations such as `<!DOCTYPE package>` are accepted, but external
  identifiers and internal subsets are refused. A namespace-aware DOM parser
  then requires well-formed XML. Its hardening features are best effort
  because Android rejects some of them, while an explicit entity resolver
  blocks external references. All XML entry points, including the byte-array
  methods, enforce the 4 MiB limit before building either parser's tree.
- `BookOrbitEpubInfoClient.read` sends a context-bound GET with an explicit
  `fileId`, on `Dispatchers.IO`, and rechecks ownership after the response.
  `BookOrbitEpubInfo.agreesWith` compares the package path and full ordered
  spine. A match is a structural cross-check, not proof of identical book
  bytes or of an exact DOM location.

The focused JVM tests cover the spec forms above, foliate's `[;s=a]` side
bias, escaped Unicode assertions, malformed and unsupported syntax, EPUB 2
(prefixed) and EPUB 3 packages, multiple renditions, non-linear spine
items, invalid spine references, oversized package documents, and external
entities.

## Fixture provenance and reproduction

`app/src/test/resources/bookorbit/OPS/` contains synthetic, project-authored
package and chapter documents. `foliate-cfis.json` records output from
BookOrbit's unmodified `client/public/assets/foliate/epubcfi.js`, pinned to
v3.0.0 commit `6be648b48a9cc376cfeff8953ebf22123583f7eb`. Chromium headless
generated the corpus on 2026-09-22 using real DOM ranges. It includes a
UTF-16 offset after a surrogate pair, two ranges and an escaped element ID.
This is output from BookOrbit's Foliate library, not a live-server or
Liseur-reader round trip.

To reproduce it, place `tests/bookorbit/capture.html`, the pinned upstream
`epubcfi.js`, and the fixture's `package.opf` and `one.xhtml` in the same
temporary directory. Serve that directory on localhost and open
`capture.html` in Chromium. The page prints the JSON corpus. Do not vendor
the upstream JavaScript into the Android app.

The info DTO and request contract were checked against the same upstream
commit's `packages/types/src/epub.ts` and
`server/src/modules/reader/epub/epub.controller.ts`. Its `containerPath`
names the OPF, and its spine `href`s are archive-root paths.

The Android smoke test is `tests/bookorbit/ParserSmoke.java`. Compile it
with `javac --release 8`, convert the class with Android build-tools `d8
--min-api 26`, then copy its dex jar and the debug APK to an emulator's
`/data/local/tmp`. Run `app_process /system/bin ParserSmoke` with both files
in `CLASSPATH`. This executes production parser code without installing
over the reader. The API 26 run passed nine checks, including UTF-16 XML,
bare DOCTYPE acceptance, forbidden DTDs, malformed XML and namespaces.

## Phase 2 handoff and caveats

Phase 2 now has an offline, read-only resolution boundary.
`BookOrbitCfiResource.locate` matches a parsed CFI's spine and itemref
indices and optional ID assertions against the local OPF, returning a
resource href and both range endpoint paths. It rejects another spine,
itemref, or nested indirection; it does not claim DOM accuracy.
`BookOrbitCfiDom.resolve` checks each endpoint against a supplied document:
element parity and ID assertions, grouped adjacent UTF-16 text nodes,
offset and text assertions, endpoint order, and an explicitly supplied
predicate for flattening reader-injected wrappers. Side bias is kept with
each resolved position. `BookOrbitCfiDom.capture` builds point/range CFIs
from supplied text nodes, adjusted for split UTF-16 text and optional
reader wrappers; it returns a CFI only if both endpoints resolve back to
the same nodes and offsets. Captured Foliate output and synthetic
altered-DOM tests cover these checks. The original-document resolver is
used during reader opening; CFI capture is not wired to remote sync.
The caller still needs to prove that the document is the selected file,
and must mark resolution failed if any DOM step cannot be verified.

`BookOrbitViewportCfi` is a reader-side capture helper. It asks
the selected Readium navigator for a visible text position and rejects
stale href, WebView, layout generation, reflow state or selected-file
context. `BookOrbitCfiRepository.openedPackage` checks the opened URL
against the app-owned download, selected file ID, binding, and recorded
file size. `originalDocument` rechecks the file and loads only bounded
original spine XHTML. The candidate must resolve to the same text slot
in that document, including split text nodes. A browser fixture covers
hidden, transparent and absent text; JVM tests cover mismatched files
and source text. This does not compare a fresh server content hash:
it relies on download provenance and the app-owned file.

`BookOrbitLocalPositionWriter` now saves a locator and any supplied
verified CFI in one Room transaction. Schema 56 stores that CFI apart
from foreign BookOrbit input, paired with the saved locator and local
revision. Every subsequent local position without a candidate deletes
the older CFI; a stale account or file binding discards only the
candidate, not the local reading. Removing a binding, reading progress,
or account also removes the paired CFI.

Reader opening now attempts that checked-file contract, without
blocking an EPUB that cannot be verified. A genuine reader movement
captures its locator and CFI together; their visible word and preceding
text must agree, and viewport changes during asynchronous verification
discard the candidate. For a scrolled book,
the last measured place may retain a matching candidate until pause;
otherwise the pause write clears it. The reader still uses its
Readium locator for local reopening. At this Phase 2 boundary, **no
incoming BookOrbit CFI was persisted locally and no outgoing candidate
was sent**; the later agreement and opt-in exchange are described above.
`BookOrbitIncomingAnchor` turns an original-XHTML CFI endpoint into a
Readium text-anchor proposal, including a web-reader range whose start
is an element. Tests cover resource identity, UTF-16 offsets, a partial
word and the opening gate. The reader now verifies the proposal in its
active WebView on cold opening, using the selected-file progress GET.
The dedicated `copilot` account connected on a disposable API 26 emulator.
After a catalog refresh and normal download of selected book 90/file 260,
Readium opened the app-owned EPUB and saved a verified CFI alongside the
same locator and local revision (38) in `book_orbit_local_cfi` and
`reading_progress`. Live capture initially failed because the script used
JavaScript syntax unsupported by that WebView and returned an object rather
than a JSON string. A later guard looked up Readium's resource URL as an
exact ZIP entry name; resolving it to the selected spine entry fixed that
rejection. An earlier page turn legitimately lacked a matching anchor, so
not every movement produces a CFI. This proves local capture and pairing, **not** an outgoing remote write.
At that earlier check, the test account's selected-file progress was still
unopened; the later incoming cold-open check above used a newly generated
web-reader range CFI.
Temporary login/download hooks were removed; no test credential is in Git.

During the same live refresh, `BookOrbitHttp.postObject` triggered
`NetworkOnMainThreadException`. Its blocking HTTP entry points now dispatch
to `Dispatchers.IO`; the authenticated catalog refresh subsequently
completed. Keep that dispatch for future position and status calls.

The BookOrbit v3.0.0 web reader was also exercised with the dedicated
test account. A page turn produced a range CFI with an empty start
endpoint at its parent element. A private download of that selected EPUB
was parsed by the production `BookOrbitEpubPackage` and
`BookOrbitCfiResource` code; `BookOrbitCfiDom` resolved both endpoints
in the selected XHTML. No book bytes or text were committed. The
`BookOrbitCfiResourceTest` and `BookOrbitCfiDomTest` now reproduce the
same range shape using project-authored XHTML. Web-reader progress and
the account's resulting status and reading attempt were removed after
the check. This verifies BookOrbit's outgoing CFI against the original
EPUB, not Readium's modified DOM or Liseur's opening behavior.

The live Phase 0 replacement test is complete; keep the captured source
separate from a BookOrbit CFI that was actually opened by the web reader.
Before scheduling reconciliation or writes, finish the checked conflict
choices and full round-trip acceptance.
In particular, a 200/201/204 POST only authorizes read-back, never a
position or status acknowledgement. Recheck the selected binding after
read-back, and retain the exact bytes of any outgoing request durably before
attempting a mutation. A narration write has different CFI and timestamp
behavior from a text write; do not generalize the text replacement rule.

Any future reconciliation must keep resolving parsed CFIs only against the
EPUB selected by the persisted `BookOrbitBinding`; check account key, file
id and binding revision before accepting any result. A successful parse
alone is not evidence that the CFI belongs to that EPUB or a live DOM.

Do not use `BookOrbitForeignCfi.parsed` directly as a `Locator` or POST
payload. DOM resolution must account for Readium's injected
wrappers, split text nodes and browser UTF-16 offsets. It must preserve both
range endpoints even when restoring from the start endpoint. A failed exact
resolution may retain the raw CFI and offer only an explicitly approximate
fallback; it must never write that fallback back as exact.

The live web-reader-to-Liseur direction passed on a disposable emulator,
as did an opt-in, app-managed Liseur-to-BookOrbit POST/read-back.
Do not treat these selected-book checks as production scheduling or
proof of conflict resolution.

Parser caveats for the resolver:

- Step index parity is not enforced; the resolver must check that an even
  step lands on an element and an odd one between elements.
- A step's `s=` parameter is kept in `Step.parameters`, not `Offset.sideBias`.
  The resolver must honor it for element-boundary locations too.
- `!` followed directly by an offset (`/6/4!:3`) parses, as the grammar
  allows, but has no text meaning and should be treated as unresolved.
- A range parent ending in `!` is an open path, and the resolver must join
  it with each endpoint before walking; the same holds for endpoints that
  begin with `!`. An empty start path denotes the parent location. Do not
  resolve `Range.parent` on its own when it ends in `!`.
- Remote manifest items are omitted, so a spine that names one fails to
  parse rather than pointing at a resource that is not in the archive.
- Use `spineStep` and each spine item's `step`; the package path is not
  necessarily `/6`, and non-linear itemrefs still occupy CFI indices.
- A syntactically parsed CFI is still DOM-unverified even when
  `unresolvedReason` is null. That field describes a parser failure only.
- `BookOrbitCfiDom` takes a DOM supplied by its caller. Incoming opening
  uses the checked original spine XHTML, then verifies the proposed quote
  in the active Readium WebView. It does not translate the CFI against
  Readium's altered DOM or recognize its wrappers. Flatten only known
  reader-owned wrappers; never flatten an element from the EPUB. Only
  opt-in, selected-book two-way checks passed; normal scheduling and
  conflict-choice acceptance remain outstanding.
- Retained foreign CFIs are separate from Phase 3's agreement and outgoing
  bytes. Do not use the retention table as a position acknowledgement or
  outgoing-request queue.
