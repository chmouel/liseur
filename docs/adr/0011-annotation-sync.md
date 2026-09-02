# 11. Annotation sync

Status: accepted

## Context

`liseur-sync` shipped ADR-0028: highlights, notes and bookmarks are the
third kind of reading state it holds, after positions and sessions, and
the first mutable one. The server orders writes with a revision number,
keeps tombstones for 180 days, and its web reader draws what it holds.

This app is the only thing that actually makes annotations, and it did
not speak that API. A highlight lived in one phone's Room database and
died with it: no second device, no web reader, no recovery from a lost
phone beyond the manual backup file.

Positions and sessions were straightforward to sync because they are
append-only or single-valued. `AGENTS.md` records how: "every payload
field comes from stored state rather than the clock, so a retry is
byte-identical and the server answers `duplicate`. Do not introduce a
random id or a `pending_ops` table."

Annotations are neither append-only nor single-valued. A highlight is
edited and deleted, so a payload derived purely from the current row
cannot be rebuilt once the reader changes that row. An ambiguous push
(request sent, answer lost) then becomes unreplayable: the retry carries
different bytes, misses `duplicate`, and resolves as a conflict that
throws the reader's edit away without saying so.

## Decision

Sync annotations bidirectionally against the four ADR-0028 routes,
folded into the existing position-sync pass. Keep the derived-payload
discipline, and add the one thing mutability requires: a record of the
request currently in flight.

`annotation_sync(id, peer_id)` holds what the server confirmed:
`rev`, `seq`, and a content fingerprint, alongside the exact bytes of
any request in the air. Outbound work is the diff between the live
`annotations` table and that fingerprint, so no DAO method and no call
site had to learn about sync: a mark becomes syncable by being in the
table, and importing a backup is a batch of creates for free.

Freshness is ordered by `seq`, never by `rev`. A rev counts writes to
one mark and restarts at 1 when the server recreates an id whose
tombstone has been swept; a device holding the old, higher number would
read the new record as ancient and refuse it for ever. Seq is the
account's own clock and only goes up. For the same reason the feed
cursor moves by the *raw* page: a page whose records this device cannot
represent still happened, and taking the account's high-water mark for
it would step over everything after it.

Two fingerprints, because they answer different questions. The
acknowledged one covers content only: never `base_rev`, which changes
on every accepted write and would make a just-confirmed row look dirty
for ever, and never `edition_sha`, or two devices holding different
files of one book would push at each other endlessly. The pending one
identifies the request, so an answer is applied conditionally: if the
reader edited the row while it was in the air, the revision is adopted
and the newer text is left alone to be pushed next time.

The pass has five phases in a fixed order: settle what is in flight,
pull the delta feed, reconcile a work's live set, push new writes, send
deletes. Reconcile before push is the load-bearing one. An edit made
offline, to a record another device deleted long enough ago that its
tombstone was swept, is an id the server no longer knows. Pushed first,
it is not a conflict but a *create*, and the deleted highlight comes
back. Asking first turns the same situation into a local delete.

Conflicts resolve server-wins. The server orders and never merges;
local-wins needs a re-push loop and two devices can ping-pong in it.

`HighlightTint` gains `PURPLE` and `ORANGE`, amending ADR-0008's "`HighlightTint`
stays what it is". The server's palette has six colours, another
device may legitimately pick one this app cannot yet name, and silently
rewriting an arriving colour would make the palette lossy in a way the
reader would notice and could not undo.

## Design

Room 39 -> 40: the `annotation_sync` table, `annotations.updated_at`
(epoch **microseconds**, backfilled from `created_at`),
`remote_server.annotation_cursor_seq`, and
`work_alias.annotations_reconciled_at`.

Microseconds, not milliseconds, because that is the precision the
server compares at: Postgres truncates there on write and
`SameAnnotationPayload` truncates there when it decides whether a retry
is a duplicate. An arriving RFC3339**Nano** stamp is truncated the same
way before it is stored, or a record would look dirty the instant it
landed and be pushed straight back.

Every push goes through `postRaw` with bytes assembled by string
concatenation. That is true for the first send as much as for a replay. `org.json.JSONObject`
is backed by a hash map with no key ordering, so reserialising a
persisted item would change the bytes the server is comparing and lose
the duplicate answer the whole design rests on. Locators are
canonicalised once, when the request is built.

Pure mapping, fingerprinting, canonicalisation and truncation live in
`AnnotationWire.kt` with no Android types, so they are testable on the
JVM as `AGENTS.md` requires. `LiseurSyncAnnotations.kt` holds the five
phases and every database write.

A book-scoped run is not a book-scoped pass. Closing a book syncs that
book, but the pending set and the delta cursor are account-wide:
settling one book's requests, or landing one book's records before
moving the cursor, would strand every other book for ever. Phases 0 and
1 always run in full; only reconciliation and the new-write diff narrow.

`invalid` is classified rather than blanket-acknowledged, because the
three classes need opposite treatment. A work the server does not know
is repaired by re-resolving the alias and retried. The per-work cap of
2000, or a clock the server reads as more than a day fast, is deferred;
re-resolution repairs neither and retrying loops for ever. A shape the
server will never take is recorded against its fingerprint and dropped
until the reader edits the record, which changes the fingerprint and
tries again. An unrecognised reason is treated as permanent: a stalled
record is recoverable and shows up in a log; an unbounded retry loop
against a server that will never say yes is neither.

Every arriving record is validated as though it were hostile: kind in
the enum, colour on the palette, progression in range, locator parsing.
A client that writes whatever arrives into its own database is one buggy
peer away from trouble, and checking costs nothing.

The batch size is the server's to choose. A hundred is only the
default and `annotation_max_batch` may be lower, so a request refused
whole is halved and tried again rather than replayed unchanged until the
account is disconnected. Down to a single item, which is as far as
splitting goes: one item refused at any size is a bad item, and is
recorded as such.

An annotation id is opaque to the server, so `.` and `..` are ids
another client may legitimately hand out, and this device carries and
pushes them like any other. The id travels in the body there. What no
client can do is *address* one: a URL parser decodes `%2E%2E` before it
resolves dot segments, so every escape collapses back to navigation and
a delete would land on the collection. Refusing such a record on the way
in would make this device blind to a mark the server is perfectly happy
to hold, so instead the delete declines before the row is marked
pending: no request is made, and the agreement is left standing, and
quiet. A row stuck pending would be skipped by the feed, by reconciling
and by every future push, so the id could never converge again.

A work the server answers `404` for was merged into another or split
away. The alias is stale, not the book, so it is dropped and handed back
to the position sync to be renamed; asking again every run would stall
that book's marks for good. The stale work id travels back with the
name, and the position sync deletes against *that* id rather than
against whatever it reads afterwards, because the pass may already have
repaired the name, and deleting the good one would undo the repair. The
same reasoning applies to a file that
took over a path: `BookRemoval.contentReplaced()` clears the
fingerprints and the alias along with the marks, or the next pass would
take the new book for the old one.

A mark the server once knew is offered only if this pass saw the server
holding it. Agreeing a *work* is not enough: the push rescans a mutable
table, so a mark edited after the live set was read, or during it and
therefore deliberately not judged by it, has not been asked about, and
offering it is a guess about a tombstone that may have been swept. A
mark never acknowledged cannot resurrect anything and goes either way.

Which books get asked is decided by the same predicate the push uses,
in one place, so the two cannot drift: a mark already agreed, refused
for good, waiting out a deferral, or that no request can be built from
is not a reason to spend a fetch. Otherwise a handful of permanently
stuck books would eat the budget every pass and starve everything
behind them.

A refusal settles against the copy that was *sent*. The server wins over
that one, and not over a copy the reader wrote after the request left:
the server has never been shown it and has therefore ordered nothing
against it. Such a mark keeps its words, takes the rev the refusal
taught, and reads dirty, so it goes next pass. The sync row cannot see
this on its own, because editing a highlight writes to `annotations`; the
content is compared directly.

A book with a mark to offer is reconciled every pass, however recently
it was settled. The seven-day interval is this device's guess at how
long a tombstone lasts, and it is only a guess: retention is the
server's setting and may be as short as a day. Guessing wrong about a
book with nothing to push costs nothing, because a stale agreement that
is never acted on is never wrong. Guessing wrong about a book holding an
unsettled mark is exactly the resurrection this phase exists to prevent.

Every network call is preceded by a check that the account the pass
began for is still the connected one, not just every write. A pass runs
for as long as the network takes, and a disconnect landing in the middle
of one used to be caught on the way back, by which time this device had
already told a server it no longer belongs to about a mark. Refusing to
store the answer is too late; the request is the side effect. For the
same reason a record's home is re-read inside the transaction that
commits it: a different file may have taken over the path since the
feed was asked, and writing the record against the name the book used to
have would anchor another book's highlight into text that never held it.

Anchorless server notes (a body with no locator) are stored as `BOOK_NOTE`.
They remain distinct from Liseur's `NOTE`, which is a passage highlight
carrying a body on the wire. A book note carries only its body and work:
no locator, progression, excerpt, colour or edition anchor. It appears in
the notebook rather than on the page and follows the same revision,
conflict and tombstone rules as every other annotation.

Version 0.11.0 already advanced its cursor over notes it could not store.
They are recovered when the ordinary seven-day live-set reconciliation next
checks that work. The cursor and Room schema are deliberately not reset for
this additive local representation.

"No locator" is read generously on the way in: an absent key, a JSON null,
an empty string and an empty object all mean the same thing, as they
already do in `SyncOps.locatorFor`. Reading one of the latter spellings as
an anchor would refuse a note on every pull *and* every reconcile, so the
reader would never see it at all. The same normalisation refuses an
anchored mark whose locator holds nothing, which anchors no text.

Because a book note carries no `edition_sha`, `home()` cannot tell which
copy of a work it was written against, and two copies of one book share a
`work_id`. A note landing here for the first time goes to a copy chosen the
same way every run. A note this device has already filed goes back where it
already lives: the `annotation_sync` row, or failing that the annotation,
is consulted before that fallback. Without it a conflict, a re-pair or any
second landing would walk the note over to whichever copy sorts first, and
the reader would watch it move between two copies of one book. Sending
`edition_sha` on a note instead would contradict the wire shape above.

## Consequences

Highlights survive a lost phone and appear on every paired device and in
the web reader. The Room schema version bumps again, and the app gains a
mutable synced entity, which is a genuinely harder thing to hold
correct than a position; most of the new tests exist to pin the
failure modes rather than the happy path.

Pairing a second account uploads the marks already on this device,
including ones a previous account sent down. That is deliberate and
consistent with the rest of this client: positions and sessions behave
the same way, `forgetSyncPeer()` keeps the reading and lets the next
account be told about it, and the reader is the same person either way.
Recording where each mark came from so it could be withheld would put
provenance in one synced entity and not the others, which is a bigger
decision than this ADR, and a wrong one to make silently, since a
reader who moves servers expects to take their reading with them.

Removing a book from this device deliberately leaves both its marks and
its agreements alone: they are still on the server and still on the
other phone. Only a different file taking over the same path clears
them, in one transaction, because a sync row with no annotation behind
it reads as a deletion the reader made.

A note longer than the server's 16 KiB limit is sent whole and refused,
rather than truncated to fit. Truncating would hash the short version as
agreed while the reader still held the long one, so the rest would be
lost with nothing anywhere reading as unsettled. A stalled mark that
shows up in a log is the better failure. The excerpt is different: it is
truncated on the way out at 1 KiB, and an excerpt that comes back as a
prefix of the local passage restores the whole passage rather than
overwriting it. Every other protocol-owned field is taken exactly as it
arrives, cleared values included, or a clear made on another device
would be invisible here for ever.

Server-wins means a conflicting offline edit is lost rather than merged,
which is the trade ADR-0028 already made on the server side and is worth
restating here: `client_ts` decides nothing.

*Where:* `data/db/AnnotationSync.kt`, `data/db/BookAnnotation.kt`,
`data/db/LiseurDatabase.kt`, `data/db/RemoteServer.kt`,
`data/db/WorkIdentity.kt`, `data/liseursync/AnnotationWire.kt`,
`data/liseursync/LiseurSyncAnnotations.kt`,
`data/liseursync/LiseurSyncApi.kt`, `data/liseursync/LiseurSyncHttp.kt`,
`data/liseursync/LiseurSyncPositionSync.kt`,
`data/liseursync/WorkResolver.kt`, `data/library/BookRemoval.kt`,
`data/remote/RemoteAccountRepository.kt`,
`reader/annotations/Annotations.kt`, `reader/ReaderViewModel.kt`.
