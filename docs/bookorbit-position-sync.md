# BookOrbit position synchronization

Automatic BookOrbit position synchronization remains disabled.
`ServerKind.BOOKORBIT` must stay at `syncAbility = NONE` and
`canSync = false` until the full acceptance gate passes.
Last-server-write-wins was approved on 2026-09-23 for the remaining
post-preflight race; the policy is recorded below.

## Task ledger and next-phase handoff

This is the complete phase checklist. "Done" records work already committed;
it does not mean automatic sync is enabled.

| Task | State | Result or remaining work |
| --- | --- | --- |
| `acceptance-handoff` | Done | Phase 1 checks and handoff recorded. |
| `fixtures-runtime` | Done | Pinned CFI corpus, parser smoke, and build checks passed. |
| `identity-retention` | Done | CFIs retained against account, selected file, and binding revision. |
| `package-crosscheck` | Done | Selected-file EPUB package and spine checked. |
| `mutation-outcome` | Done | Rejection, uncertain delivery, and read-back distinguished. |
| `phase0-handoff` | Done | Protocol evidence and live-fixture limits recorded. |
| `progress-read` | Done | Strict selected-file and multi-file progress readers built. |
| `phase0-live-evidence` | Done | Test-account protocol samples and targeted cleanup verified. |
| `phase0-three-reviews` | Done | Three Phase 0 review passes and fixes completed. |
| `phase2-cfi-bridge` | Done | Incoming CFI resolution and outgoing viewport capture checked. |
| `phase3-agreement` | Done | Durable position-only agreement, exact bytes, and read-back recovery built. |
| `phase4-provider` | In progress | Bounded read-back account checks and reader choices implemented. Scheduled pushes remain unwired and disabled. |
| `phase5-acceptance` | In progress | Independent Android/web passage checks, response loss, process death, result messages and the identity/lifecycle checks below passed. Finish the remaining acceptance and final-path reviews before enabling. |

Remaining steps for the next phase:

1. Independent Android and web-reader logins now work. Both-direction
   passage interoperability and stale-choice refusal passed on 2026-09-23,
   as recorded below. Do not count the separate API logins as reader
   acceptance or clone a session/database for another device.
2. Finish the remaining explicit-path checks: a second independently
   authenticated Android installation, reopening during post-close work,
   and live competing writes after preflight and before read-back. The
   identity checks below changed emulator database fields; they do not
   exercise the account-switch UI or replacement downloads.
3. Build the normal bounded push orchestration behind a disabled gate,
   then repeat acceptance through that final path and the bounded reviews.
   Change `syncAbility`, account `canSync`, routing, UI and tests together
   only if the gate passes.

### Independent Android and web-reader acceptance

On 2026-09-23, the committed debug build ran on the disposable API 26
emulator. Android and an isolated Chrome browser each signed in normally
to the approved disposable account. They did not share tokens or a local
database. No temporary app hooks were used.

The fixture was book 90/file 260. The catalog contains another book with
the same displayed title, so the browser used the explicit selected-file
reader URL. The downloaded EPUB and a fresh authenticated server download
had identical SHA-256 hashes and lengths (1,200,418 bytes). The EPUB's
internal title differs from the catalog title; the comparison used the
selected file and passage anchors.

| Check | Observed result |
| --- | --- |
| Web reader to Android | Browser page turns saved `epubcfi(/6/22!/4,/80/1:332,/106/1:326)`. Android verified the preview in the active reader. Taking the server's place closed the reader and persisted an acknowledged agreement at local revision 39 with the exact server CFI and percentage. |
| Stale choice | While Android's conflict dialog was open, the web reader saved another position. Choosing the old keep-local offer left that competing server position unchanged. This checked the final stored result; it did not count server-facing POST requests. |
| Android to web reader | A fresh keep-local choice saved `epubcfi(/6/22!/4/158/3:448)` and persisted an acknowledged agreement at revision 40. A new isolated browser session restored that position. Its rendered document matched the Android locator's selector and surrounding text, and the highlighted word's DOM range intersected the visible viewport. |
| Unusable front-matter anchor | Browser section jumps produced anchors without usable passage text. Android kept its local position and did not offer an unverified take-remote choice. |

These checks cover real reader interoperability and checked post-close
choices. The later fault checks below cover a network-level response drop
and termination between send and read-back. The queued-choice and toast
checks below cover interruption before execution and result messages.
Rotation and emulator identity-change checks are recorded below.
Normal scheduled pushes remain
disabled and require acceptance through their final orchestration path.

After the checks, the selected-file progress was deleted through the API.
Only this account's book 90 status and reading-attempt rows were removed.
All three counts returned to their captured empty baseline. The Android
reader was stopped before cleanup; its local reading position was kept.

### Network response loss and Android process death

The API 26 emulator used a loopback-only fault proxy restricted to the
approved BookOrbit origin. A disposable certificate was trusted only on
that emulator; the proxy verified upstream HTTPS normally and used IPv4
because the host's IPv6 route timed out. The app retained its original
HTTPS server address. No app transport hook or permissive trust manager
was added. The proxy recorded request counts and fault events, without
tokens or payloads.

The first explicit keep-local choice reached the server once. After
receiving the successful upstream response, the proxy closed the client
socket without returning that response. Liseur fetched the selected-file
progress and persisted `ACKNOWLEDGED` at local revision 42. The proxy
count remained one POST, and the acknowledged CFI matched the server.

For a second choice, the proxy waited for successful server receipt and
force-stopped the Android app before returning anything to it. A database
snapshot showed `MAY_HAVE_BEEN_SENT`, attempt generation 3, sent revision
43, and retained outgoing bytes whose CFI matched the server. The proxy
had forwarded exactly two POSTs across the two tests.

Restarting the original build exposed a missing recovery call: the reader
fetched progress but left the matching attempt pending and showed another
choice. Reader opening now calls `readBackIfPending` before deciding
whether to offer a remote position. That operation holds the shared
attempt mutex and only reads back potentially sent attempts. It neither
sends prepared requests nor retries rejected requests.

Installing the fixed build without clearing its database and reopening
recovered the actual interrupted attempt. The choice disappeared, the
agreement became `ACKNOWLEDGED` at revision 43, and the retained request
was cleared. The proxy still counted two POSTs. Regression tests cover
all recoverable pending states across database reopen, preservation of
a newer local position, and leaving prepared, rejected and unknown
attempts untouched. The focused tests and `make check` passed.

The proxy was stopped, its emulator trust certificate and routing were
removed, and the disposable private key was deleted. Selected-file
progress and this account's book 90 status/attempt rows returned to their
captured empty baseline.

This verifies the explicit reader path. The subsequent check below covers
process death before queued execution. These results do not qualify the
still-disabled scheduled push path for enablement.

### Interrupted queued choice and result messages

On 2026-09-23, an approved temporary debug-only pause stopped an accepted
keep-local choice inside `afterQueuedWrites`, before releasing the
open-book guard or launching the choice operation. A log marker confirmed
that boundary before the Android app was force-stopped.

Snapshots before acceptance, at the pause, and after termination contained
identical complete local progress, agreement and server progress rows.
Local revision stayed 45 and attempt generation stayed 3. The temporary
hook and its private trigger were removed, the clean debug build was
installed without clearing app data, and the book was reopened. It offered
a fresh conflict choice; all three stored rows still matched. The
interrupted in-memory choice was not replayed.

Accepting the fresh keep-local choice on the clean build produced the
visible toast "Reading place saved. Reopen the book to continue." The
agreement acknowledged revision 45 at generation 4, with CFI and percentage
matching the server exactly.

For the failure case, the independent web reader moved while a later
Android choice was visible. Accepting that stale choice displayed
"The choice could not be confirmed. Reopen the book to check its saved
place and try again." Complete local progress, agreement and server
progress rows remained unchanged at local revision 46 and generation 4.

Screenshots are saved locally as
`~/tmp/liseur/sshot/bookorbit-choice-success-1.png` and
`~/tmp/liseur/sshot/bookorbit-choice-failure-1.png`.
These are Android toast messages, not notification-shade entries.
No diagnostic hook is retained in source or in the final emulator build.
After stopping the reader, the selected-file progress and this account's
book 90 status/attempt rows were restored to their captured empty baseline.

### Identity guards, rotation and missing-anchor recovery

On 2026-09-23, the clean API 26 build displayed a checked choice against a
position saved by the independently authenticated web reader. Each identity
change below was made directly in the disposable emulator database while
the choice was visible. The original field values were restored after
each case. No real server account, file identity or EPUB content was changed.

| Emulator change | Choice | Result |
| --- | --- | --- |
| Selected binding file ID replaced with a nonexistent test ID, revision incremented | Keep local; take remote, tested separately | Both choices closed without changing the complete local progress, agreement or server progress rows. |
| Connection epoch incremented | Keep local | All three position rows stayed unchanged. |
| Account ID replaced with a nonexistent test identity | Take remote | All three position rows stayed unchanged. |

The refused choices left local revision 48 and attempt generation 4
unchanged. These tests exercise stale identity guards in the running app;
they do not establish full account-switch or replacement-download behavior.
They compare persisted state rather than counting network requests.

With the original identity restored, the open choice was rotated to
landscape and back to portrait. Both orientations displayed the choice,
and snapshots matched the complete pre-rotation local progress, agreement
and server progress rows. The original Android rotation settings were
restored afterward.

For missing-anchor recovery, the approved test file's progress endpoint
was given the syntactically valid CFI `epubcfi(/6/22!/4/999998/1:0)`, whose
element is absent from the original XHTML. Reopening offered no unverified
choice and preserved the complete local progress row at revision 48.
Subsequent page turns changed the saved locator and advanced the local
revision to 49. The server row stayed unchanged, confirming that failed
anchor resolution did not suppress later local reading.

The reader was stopped before cleanup. The original emulator identity
fields were verified restored, and the selected-file progress plus this
account's book 90 status/attempt rows returned to their empty baseline.
No app code changes or diagnostic hooks were needed for these checks.

### Approved write policy

On 2026-09-23 the maintainer accepted last-write-wins for BookOrbit.
The last progress write stored by the server wins, regardless of which
device read most recently or reached the furthest percentage. A delayed
write can therefore replace another device's newer reading position.
This accepts the race after the final preflight GET; it does not make
that GET atomic with the POST.

Liseur still refuses a conflict visible before sending, verifies the local
CFI/revision pair, and retains account/file guards. Each prepared request
gets one POST attempt. A lost response is resolved through GET, and a
write is acknowledged only when read-back matches its exact CFI and stored
percentage. Another device winning before read-back leaves the request
unresolved. No device timestamp or highest-percentage rule is introduced.

This matches the verified unconditional
[BookOrbit server write contract](https://github.com/bookorbit/bookorbit/blob/27cfdc20282eabdc89296c8c45482c578c157c7c/server/src/modules/book/book.service.ts#L2238-L2259).
The public iOS repository contains support pages rather than sync source;
exact parity with its client-side conflict and offline-replay rules has
not been established.

The policy decision removes the requirement to wait for server-side
conditional writes. It does not enable scheduling or waive independent-device
acceptance. The account provider remains observation/read-back-only until
that gate passes.

### Unscheduled bounded provider

`BookOrbitPositionSync` remains inert by default and is not registered in
`RemoteRouter`. The separate `accountSync` opt-in lets `syncAll` inspect at
most 20 selected EPUB bindings per call. It reads a database page, captures
the account/connection and each selected-file revision, and checks them
before exchange. It skips another account's local positions. A changed
connection or binding stops the page instead of recapturing a new target.

Schema 58 stores the traversal's frozen binding list, cursor and cumulative
result. Recreating the provider or worker resumes the same finite walk;
finishing a continuation does not start another one. A connection change
invalidates the old walk. A transient failure leaves the failed item for
retry. Account cleanup removes the traversal and its items.

Conflicts and unverified CFIs produce non-retryable `PositionUnresolved`;
a later settled page cannot hide an earlier failure. A separate continuation
flag on failure/partial outcomes lets the coordinator and worker reach later
pages without relabelling the conflict as retryable or successful. A
failure-free page with more bindings still returns `Incomplete`. The provider
remains unregistered, so normal workers do not start these BookOrbit walks.

Account runs only observe candidates and read back potentially sent
requests. They leave a prepared request unsent, and do not prepare or POST
when local movement would otherwise authorize a push. An unchanged
read-back stays unresolved even on another run. `syncBook` follows the
same rule unless the separate `manualBookSync` opt-in is supplied;
`syncAll` cannot POST even with both options set. This preserves the
existing explicit single-book diagnostic path without enabling normal
pushes.

The generic preview and choice methods remain disabled: their contract
cannot prove a foreign CFI against the active reader. The reader's
checked post-close choice remains the only take-remote path. Provider
tests cover page bounds, continuation, mixed results, account and file
changes, foreign ownership, unsent preparation, and durable uncertain-send
recovery with a recreated provider. These are local protocol tests, not
independent-device or Android process-death acceptance.

On 2026-09-23, the provider tests and the full BookOrbit test selection
passed. `make check` also passed the complete JVM suite, Android lint,
debug build, and release-tool checks. No live account or device was changed
for this orchestration work. Independent-device acceptance remains pending.

### Phase 4 local review passes

Three bounded code-review passes on 2026-09-23 covered:

| Pass | Scope | Result |
| --- | --- | --- |
| 1 | Account paging, identity, durable recovery | Confirmed the 20-book bound, cumulative unresolved results and changed-account/file refusal. Added a database-reopen test for `MAY_HAVE_BEEN_SENT`: preserved bytes are acknowledged through GET without POST. |
| 2 | Reader verification, fallback and close | Found that cold-opening fallback did not clear the pending choice, so subsequent reading positions could remain suppressed. Both failed-anchor paths now clear the proposal and proof. Eight reader-state tests cover failure, replacement navigators, explicit choice, close and recreation. |
| 3 | HTTP follow-ups and uncertain delivery | Reproduced a second POST after `503 Retry-After: 0` despite `retryOnConnectionFailure(false)`. Progress request bodies are now one-shot. Transport and exchange tests require one POST followed by read-back. |

The reader state keeps an accepted choice and a verified automatic pull
through view teardown so the queued post-close action can still run.
A failed exact-anchor check clears them; a pending choice on a replacement
navigator requires fresh verification. A recreated reader does not inherit
the old instance's choice.

These passes used local code and JVM tests. Database reopen and reader-state
recreation do not prove Android process-death behavior. No independent
second device, live network disconnect or notification check was exercised
in this continuation. The post-preflight server write race is accepted under
the policy above; neither routing nor sync capabilities were enabled.

### Live response-fault check

On 2026-09-23 the disposable app sent one paired local CFI to the
approved test account's selected file. A one-time diagnostic hook
discarded the successful HTTP response *after* the server accepted
the POST, so the exchange saw an uncertain result and read the file
back. It returned `Pushed`, acknowledged local revision 38, and a
separate authenticated GET confirmed the exact local CFI and stored
float percentage. This tests live-server read-back after client-side
response loss; it does **not** simulate a network-level disconnect or
prove a second POST was impossible on every transport. The socket-drop
unit test below verifies the one-POST property for that failure.
The selected-file progress was deleted through the API; test-account
book status and reading-attempt rows were deleted in a targeted
transaction, with all three server counts zero. The emulator database
was restored at revision 38 without an agreement, and the temporary
code, private markers and backup were removed. No diagnostic hook is
part of the app.

### Reader-facing checked choice (normal sync still disabled)

An unresolved first-time or two-sided conflict now proposes the saved
BookOrbit place while the reader opens, but only when the local locator
and CFI were paired at the same revision and the selected EPUB's original
XHTML resolves the server CFI. The active WebView must then verify that
place before a choice is shown. Until that check, the candidate is
provisional and the local reading position is not rewritten. An invalid
opening falls back to the local place; replacing the navigator rechecks
the proposal. The dialog does not compare percentages from different
position scales.

Taking the server's place or keeping this device's place closes the
reader. Only after queued position writes finish and the open-book fence
is released does the checked choice run: take-remote revalidates the
original EPUB, fresh server answer, local revision, pairing and preview
before an atomic local adoption; keep-local prepares an exact request,
preflights the server again and reads back its POST. A success or failure
notification tells the reader when it is safe to reopen. Cancelling
closes without applying either side. A choice interrupted by process
death before this post-close step is lost, not replayed; the unchanged
position can be offered again on opening. An unresolvable CFI has no
take-remote button. The generic `PositionSync` choice and capability
remain disabled.

On the disposable API 26 emulator, the approved account's book 90/file
260 showed the choice after active-view verification with a different
remote CFI. While the book was open, local revision stayed 38. Taking
the server's place advanced it to 39 only after close; the agreement
recorded the server percentage separately from the local Readium
progression. A fresh first-time choice kept the local CFI and confirmed
the selected-file POST by read-back at revision 38. A competing write
to the same selected file while the dialog was open made a stale
keep-local choice fail without POST; the other write remained on the
server. Rotating out and back kept the local revision unchanged and
required another active-view check before the choice reappeared.
A dialog capture is in `~/tmp/liseur/sshot/bookorbit-conflict-choice.png`.
These tests used another session on the same approved account as the
competitor, not an independent second device; BookOrbit still has no
compare-and-swap for a write after the final GET.
In a deterministic post-preflight test, another device wrote after the
last GET but before Liseur's POST. BookOrbit's unconditional POST
overwrote that newer position, and read-back reported the local write as
acknowledged. A further client-side GET cannot eliminate this interval.
The maintainer subsequently accepted last-server-write-wins for this
interval. Normal automatic pushes remain off pending independent-device
acceptance.
The selected-file progress was removed through its API and only the test
account's book 90 status and reading-attempt rows were deleted; all three
server counts returned to zero. The emulator database was restored at
revision 38 without a test agreement, and the temporary login hook,
private trigger and backup were removed.

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
POSTing**; a still-unchanged preflight retains the exact request in
`RETRY_REQUIRED`. Later runs and process restarts cannot prepare another
POST until the reader explicitly chooses to send again. An explicit
rejection is reported separately.
Position agreement does not touch local reading status or another
provider's pending status action.

The exchange is **not** itself a `PositionSync` implementation or a
`RemoteRouter` entry. The checked reader choice calls `keepLocal` after
closing; normal sync and background workers do not call it. A remote CFI
with no verified local Readium locator is retained as unsupported, not
adopted or pushed over. The reader choice handles a verified first-time
or two-sided disagreement; it does not make the generic choice API safe.
The opt-in exchange is a bounded protocol component, not a claim that
Phase 4 scheduling or the final gate passed. BookOrbit still lacks a
conditional write, so the final GET cannot exclude a competing write
immediately before POST.

### Durable explicit retry and pending choices

An unchanged read-back no longer clears an uncertain request. It enters
`RETRY_REQUIRED` and keeps the exact outgoing bytes and sent revision.
`UNCERTAIN`, `MAY_HAVE_BEEN_SENT` and `RETRY_REQUIRED` are read-back-only
on subsequent exchanges, including after database reopen or local movement.
A rejected request also remains blocked until an explicit choice.

The reader can now resolve these pending attempts. Its fresh preview binds
the selected account/file, local CFI/revision, remote answer and exact pending
bytes/state. Schema 58 adds `attempt_generation`, incremented whenever a new
attempt is prepared. An old choice cannot authorize another retry merely
because the later attempt returned to the same state with identical bytes.
Send/read-back checks also bind that generation.

Choosing the verified server place after close atomically adopts it and
settles the matching pending request. Choosing to send this device's place
again authorizes one new preparation and POST after fresh checks. If the
remote anchor is absent or cannot be verified, the dialog offers only the
explicit send-again action and cancel; it cannot adopt that remote place.
The retry explanation and action are translated in all six app languages.

Agreement repository instances share a per-database mutex around sends,
read-back and pending choices. A choice waits for an older in-flight result,
then rechecks its captured evidence. It cannot replace an attempt while an
older read-back is still able to settle it. Tests cover that ordering,
byte-identical retry generations, changed pending bytes/state, rejection,
checked take-remote, and durable read-back-only behavior after restart.
These checks do not claim Android process-death or independent-device
acceptance.

### Opt-in keep-local conflict choice

`BookOrbitPositionExchange.previewConflict(bookUrl)` now reads the selected
file and records a choice fingerprint only when the local locator and CFI
are paired at the same revision, the position belongs to this account (or
has no owner), and the remote has a different saved CFI. The fingerprint
holds the captured account/connection/file, local revision, locator,
progression, paired CFI, agreement baseline, and the remote CFI,
percentage, and timestamps. It is a snapshot of a first-time
disagreement or two-sided movement, not permission to post by itself.

An explicit `keepLocal(preview)` rereads that remote answer, checks that
the candidate, baseline, local position, selected file, and account still
match, and prepares exact request bytes only while the book is closed.
The normal final preflight also rejects an open reader, a changed
percentage, CFI, or displayed reading time, a new local position, and
another account's local ownership before marking the attempt possibly
sent. It then uses the existing read-back and blocked-rejection rules.
A changed answer is reported as unresolved without POST; it must be
previewed again, not silently replaced with the current remote candidate.

On 2026-09-23 the first live keep-local diagnostic offered a valid choice
but was refused before POST because the app had automatically resumed the
same book while the test was running; its prepared request remained unsent.
After restoring the disposable emulator's pre-test database, a cold launch
that kept the reader closed offered the choice again. The app sent the
locally paired CFI and acknowledged it by selected-file read-back at local
revision 38. This verifies the guarded keep-local path with the approved
test account and file, not a reader-facing choice. A MockWebServer test
also applies one POST and drops its response: read-back acknowledges the
same request without a second POST. It is not a real network-loss test.
The test account's selected-file progress was deleted through the API,
and its book status and reading attempt were removed in a targeted
transaction; all three server counts returned to zero. The emulator
database was restored, the temporary login hook was removed, and the
private trigger and backup were deleted.

The reader now calls these choice methods after an active-WebView
verification and after closing; the coordinator and normal sync do not.
`adoptChosenVerifiedClosed(preview, offer)` accepts a caller-verified
opening offer only when it matches the saved choice.
After a fresh GET and a closed-book fence, its transaction rechecks the
candidate, baseline, local locator/revision/progression, paired local
CFI, account, and selected file before storing the remote locator and
CFI together. The unit tests cover stale choices, an open reader, and
another account's locally owned place; the live reader check above
covers an explicit choice, the original EPUB, and active-WebView proof.
Never call the transaction with an unverified locator. A keep-local POST
still has BookOrbit's unconditional-write race after final GET.

An app-managed keep-local choice and the reader-facing take/keep choices
passed on the disposable account, as did a stale-choice refusal. A
dropped-response socket test recovered without replay. The UI's
post-close intent is in memory: process death before it runs leaves the
choice unapplied, so reopen and choose again. The ledger above names the
remaining recovery, orchestration, race, and review work for the next
phase.

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
a paired, unsent local movement can instead offer the checked reader
choice described above. A lost WebView verification or process death
before the reader closes leaves the candidate unadopted.
The selected-file check relies on the app-owned download provenance and
size/mtime, not a cryptographic server content hash. The first live
rendered-passage discrepancy remains unexplained despite a successful
second check; an independent-device race has not been exercised.
Neither this guarded path nor the opt-in exchange enables scheduled sync.

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
an explicit resolution path. If the remote still matches the preflight,
`RETRY_REQUIRED` preserves the request until a fresh reader preview and
explicit send-again choice authorize a new attempt. A changed remote CFI is retained as
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
altered-DOM tests cover these checks. The original-document resolver is used during reader opening. At this
phase boundary, CFI capture was not yet wired to remote sync; the later
guarded exchange uses it.
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
