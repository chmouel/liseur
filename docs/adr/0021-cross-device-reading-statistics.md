# 21. Cross-device reading statistics

Status: accepted

## Context

The reading dashboard is built from this device's own sessions, and
liseur-sync is asked for the same span so that reading done on a laptop
or a second phone is not lost. That much has been true since "Show
reading done on your other devices". The complaint the reader actually
has is different, and it is fair: the screen looks device-local, and
there is no way to find out whether it is.

Both halves work. `LiseurSyncInsights` asks
`GET /v1/insights/summary`, `/v1/insights/works` and
`/v1/insights/calendar` for the window the screen is showing, spanned
either as `from`/`to` or as `range=all`; the server echoes back what it
counted, and an answer that names a different span is thrown away rather
than shown under the wrong caption. `ReadingStatsViewModel` tags each
reply with the question it answered, refuses one whose window has since
moved, and cancels a refresh the reader has superseded. None of that is
the problem.

Four things are, and they compound.

The original merge took the larger figure because the server already
contained uploaded local sessions. That avoided some duplication but
lost reading that only one source knew about. Adding pending uploads
later exposed the acknowledgement races addressed in the accounting
amendment below.

The list beneath it is bounded by the local shelf. Per-book aggregates
arrive keyed by the server's `work_id` and are mapped onto local book
URLs through `work_alias`; a work with no usable alias here (a book
read only on the other device, or one this device has never resolved)
contributes nothing. `booksRead` and `booksFinished` are then counted
off those rows, so they cannot exceed what is in this library. A reader
who did a year on a laptop sees the year in the total and not one book
of it in the list.

Every failure is a silent null, by design: a statistics screen is not
worth an error banner, and a reader offline on a train should see their
own figures rather than a complaint. But silence is now carrying more
than it can. Offline, a token without the scope, and a server that
answered and agreed are the same blank.

And the scope is the one thing never written down.
`LiseurSyncServerSetup.introspect()` reads `sync`, `library-read`,
`library-manage`, `library-upload`, `library-delete` and `admin` into
`ServerCapabilities`; `read-insights` is not among them. The app's own
pairing mints it, so this is invisible until somebody pastes in a token
minted elsewhere, and liseur-sync's own token form leaves that box
unticked. Such a reader gets a 403 on every insights call for the life
of the account and is never told.

## Decision

Server statistics stay decoration, and the rules that make them safe
stand as they are:

Both sides are asked about the same window, and an answer to a different
one is refused. Exact overlap proof is required for a cross-device merge
(see the accounting amendment below). A
failure produces no error state; the local figures are a complete
screen on their own. The period-over-period comparison stays local on
both sides, for the reasons ADR-0018 gives: a relationship only holds
between two figures gathered the same way, and half a comparison
counting two devices while the other counts one is arithmetic pretending
to be a habit.

What changes is that the reader is told what they are looking at, and
the list stops being smaller than the total above it.

Record `read-insights` as a stored capability, alongside the five scopes
already read at connect time. A token that lacks it is then a fact the
app holds rather than a silence it repeats, and the one reader who has
to do something about it (re-pair, or tick the box) can be told so.

Say on the screen where the figures came from: this device, or every
device. One line, and only ever a statement of provenance. It must not
become a warning, an error, or anything the reader has to dismiss;
"offline, so this is just this phone" is information, not a fault.

Count a work the server knows and this library does not. The total
already includes it, so leaving it out of the list is what makes the two
disagree, and `booksRead` and `booksFinished` follow from the merged set
rather than from the local rows. A book with no local file cannot be
opened from the dashboard and must not pretend otherwise.

Leave `total_pages` unread, and `current_progression` unread for any
book this device has. Pages are a KOReader notion that a reflowable EPUB
does not have, and for a book that is here the local position is fresher
than anything a server can relay. Neither is an oversight. The one row
that does read `current_progression` is the one with no local book,
because there the server's is the only account of the reader's place
there is, and without it a book finished on the laptop would sit in the
list forever unread and never reach `booksFinished`. Whether that place
is the end is decided by the same threshold position sync applies when
the same reading arrives as a peer's position, so the two cannot
disagree about one book.

The accounting amendment below specifies the shared timezone and the
evidence needed to combine uploaded sessions.

## Accounting amendment

An upload acknowledgement cannot establish whether a cached server
answer contains a sitting. The server may have accepted a request whose
reply was lost, or a successful upload may be newer than the cached
answer. Adding pending time double-counts the first case and loses
reading in the second. Maximum alone also loses reading that only one
source knows about.

The required union is `server + captured local - actual server overlap`.
All three quantities must describe one immutable set of local candidates
and one coherent server snapshot. Overlap uses the duration the server
actually counted: if an old upload stored ten wall-clock minutes for
thirty measured minutes, subtract ten when adding the local thirty.
Acknowledgement-only updates cannot change the result.

Use the account timezone for an exact merge, with each sitting assigned
whole to its ending day. Use the phone timezone for local-only statistics.
Keep both comparison periods local in the phone timezone, even when the
dashboard uses the account timezone, and preserve their shared wall-clock
cutoff.
Compute streaks from the union of positive-activity days, including days
outside the selected totals range.

Legacy aggregate endpoints provide no overlap proof. Until a supported,
complete snapshot can be obtained, the dashboard shows `THIS_DEVICE`;
it does not publish independently fetched summary, works and calendar
answers as an exact cross-device result. Missing calendar chunks,
incompatible historical attribution, malformed numbers and oversized
evidence require the same fallback.

Room version 48 adds `session_transmission`, keyed by peer and local
session id. Before a first request, persist the selected payload and the
server device identity. Replay its stored bytes, even after an alias
changes. Only a structured atomic `unknown_work` refusal permits replacing
the rejected work identity. Rekey and forget this table with the account.

A same-account reconnect can change the server's device id. Retried
payloads and session ids remain unchanged. A reply confirms the current
device only when `accepted` equals the entire batch size: the server
checks device identity for duplicates, including archived ones. In the
account transaction, update the confirmed device conditionally on the
original payload and previous device, together with `uploaded_at`.
Lost replies and conflicts retain the original evidence. If the current
device is unknown, clear only that peer's device proof; do not mark other
peers or the local history unknown.

Existing sessions are marked `legacy_evidence_unknown`: the previous
client did not retain attempted identities, so an upgrade cannot
reconstruct proof from today's alias or device key. Forgetting that
evidence also marks the remaining history unknown. New sessions can
select measured `active_ms` only after capability negotiation; legacy
attempts retain their original wire format and deterministic id.

Calendars contain every date in their selected interval, including an
empty today. All-time requests start at retained activity rather than an
application release date and require nonoverlapping chunks of at most
4,000 inclusive days with matching server revision proof. The screen
uses its sampled date, resamples on resume, and invalidates a changed
day or timezone without periodic HTTP polling. Full-history reductions
run on the default dispatcher and long heatmaps compose weeks lazily.

`LiseurSyncSnapshots` discovers version 1 through
`GET /v1/insights/capabilities`, then sends the captured candidates and
all-history active days to `POST /v1/insights/snapshot`. Version 1 defines
all-time ranges; the client also honors `all_time:false` when supplied.
The client requires attribution version 2 and validates the reply against
the stored account id. If discovery also supplies an authenticated
`account_id`, it must agree with the stored id when one exists, and can
identify an older connection that has not stored one. Without either
identity, statistics stay local. Discovery does not rekey account state.
Candidates include every transmitted local sitting contributing to the
selected totals range. Older sittings still supply active-day evidence
for the streak, but their payloads do not consume that range's candidate
budget. Unknown legacy transmission identity only blocks a range to
which that sitting contributes.
Every page must echo the snapshot id, account timezone, today, selected
bounds and `calendar_from`/`calendar_to`, and carry the same decimal-string
`stats_revision`. Summary, works, overlap and earliest-activity metadata
must also agree between pages. Sparse day totals must cover the complete
selected duration once all pages have arrived.

The client obeys `max_candidates` and optional `max_body_bytes` and
`max_local_active_days`. Without the optional limits it allows at most
1 MiB of UTF-8 request data and 25,000 active days; its hard upper bounds
are 4 MiB, 10,000 candidates and 25,000 active days. Histories beyond
366,000 calendar days or 128
calendar requests also use local-only statistics. These are resource
refusals: neither path clips
candidates or presents a partial chart. Unknown pre-upgrade transmission
history and incompatible retained server rollups remain local-only.

The upload path reads `session_active_ms` from `GET /v1/token` before
selecting a new payload. That endpoint also serves sync-only tokens;
the dashboard capabilities endpoint requires `read-insights`. A
statistics refusal therefore does not prevent measured-time uploads.
Discovering support later never changes an
existing transmission. `active_out_of_range` is a named permanent
session refusal, handled without discarding other sittings in its batch.

## Design

`ServerCapabilities` gains `canReadInsights`, read from
`read-insights` in `introspect()` and stored on `remote_server` beside
`can_upload` and `can_delete`. Like those two it is half an answer: it
says the token may ask, not that the server has anything to say. An
account paired before the column existed defaults to the pessimistic
value. Introspection happens only at connect, so waiting for the next
one would leave every upgraded phone telling its reader that statistics
are refused while the statistics work. The answers correct it instead:
`LiseurSyncInsights` writes the column from what the server actually
said, true on any body and false on a 403, guarded on the account being
the one the question was asked of. Offline, a server too old and a
malformed body prove nothing about the token and change nothing.

`LiseurSyncSnapshots` returns no decoration when proof fails. The view
model publishes `ALL_DEVICES` only for a complete snapshot whose local
session content, transmissions, aliases and account still match.
Transmission evidence is checked after the response and before
publication. A subsequent upload cannot change that already-proved
server snapshot, so it keeps the cached total, as do acknowledgement-only
updates. Range changes cancel
the previous generation; replies also recheck generation after suspended
database reads. Cached display state expires when observation stops, so
reopening the screen cannot replay another account's figures.

The per-book merge already groups server works by `work_id` before
mapping them onto local URLs, which is what stops one file counted under
two names being charged twice. A work with no alias falls out at that
mapping, and it is there that it has to be kept instead: the row it
becomes has the server's title and no local book behind it, so it
carries no cover and no tap target — but it does carry the same
`current_progression`-derived progression and finished state described
above, since that is the one figure this device has for it.

## Consequences

A reader can tell whether they are looking at one device or all of them,
which is the thing the screen has never said. It costs a line of text
and the honesty is the point: the same blank meaning "you are offline"
and "your token cannot ask" was worse than either message.

The dashboard's list stops being a subset of its own headline. That is
the visible change, and it brings a new kind of row with it (a book
this device does not have), which every consumer of `BookReadingStats`
has to tolerate rather than assume away.

The scope becomes visible at the moment it can still be fixed, when an
account is connected, rather than silently much later on a screen that
gives no reason. It also gives liseur-sync a reason to reconsider
leaving the box unticked on a form whose whole purpose is to furnish a
reading client.

Nothing here makes statistics load-bearing. Every one of these is still
a screen that has to work with the network off.

*Where:* `data/liseursync/LiseurSyncServerSetup.kt`,
`data/liseursync/LiseurSyncInsights.kt`, `data/remote/RemoteSources.kt`,
`data/remote/RemoteAccountRepository.kt`, `data/db/RemoteServer.kt`,
`data/db/LiseurDatabase.kt`, `domain/ReadingStats.kt`,
`data/db/SessionTransmission.kt`, `data/liseursync/LiseurSyncSnapshots.kt`,
`data/liseursync/StatisticsCapabilities.kt`, `domain/StatsUnion.kt`,
`ui/stats/StatsSnapshotUnion.kt`,
`ui/stats/ReadingStatsViewModel.kt`, `ui/stats/ReadingStatsScreen.kt`,
`ui/settings/ServerAccountScreen.kt`, `res/values/strings.xml`.

The server names each work in `GET /v1/insights/works`, which is what
lets a book with no local file be listed at all: liseur-sync's
`internal/api/insights.go`, `docs/openapi.yaml` and `docs/integrating.md`.
