# 26. Imported reading keeps the time it was read

Status: accepted
GitHub issue: [#180](https://github.com/chmouel/liseur/issues/180)

## Context

A reader connected a fresh install to liseur-sync and found that pulling
to refresh kept rearranging the Recent shelf and kept changing which
book the Continue Reading card offered — on a device where nobody had
opened a book at all.

Two separate things were doing it.

**The shelf was ordering by when this phone heard about the reading.**
`reading_progress.updated_at` is the column the Recent shelf and the
Continue Reading card sort by, and every pull path stamped it with the
local clock. Six months of reading, imported in one afternoon, all read
as "this afternoon", in whatever order the ops happened to land. The
originating device's own time did arrive — liseur-sync sends it as an
op's `client_ts`, Komga sends `readDate`, calibre-web's Kobo sync sends
`modified` — and it was even stored, in `remote_updated_at`. Nothing
ordered by it.

**And the import genuinely arrived in batches.** A book with no
`work_alias` can neither send nor receive, and naming was rationed to 25
books a run because naming a local file means reading and hashing it.
Each refresh therefore named the next 25 books, imported their history
stamped "now", and that batch leapfrogged the one before it. Nothing
re-ran on its own, which is why the reader had to keep pulling — and why
the order kept changing when they did.

## Decision

### Two timestamps, because there are two facts

`reading_progress` gains `read_at`:

- **`updated_at`** — when *this device* last wrote the row. It is what
  goes out as an op's `client_ts` and as kosync's `timestamp`, and it is
  therefore part of a derived op id and of a byte-identical replay. It
  does not change meaning.
- **`read_at`** — when the reading this position records actually
  happened, on whatever device did it. A pull takes it from the remote
  time; a page turn here takes the local clock, because a page turn here
  *is* the reading.

Null means "not known separately", and every read is
`COALESCE(read_at, updated_at)`, so rows written before this exist
happily and nothing regresses.

The remote time is clamped to `now`. It is another device's wall clock,
and one running fast would otherwise pin a book to the top of the shelf
for as long as the two disagreed. A clock running slow files its reading
too far back, which is a worse answer than the truth and a much better
one than "everything was read at import time". There is nothing better
on the wire: the server stamps `seq`, which orders events but is not a
time.

Nothing about conflict resolution changes. Revisions decide, as
`ReadingStateMerge` documents; `read_at` is only for display order.

### `synced_at = updated_at` is what makes the backfill safe

Devices already have shelves in import order, so migration 48 → 49
backfills. The difficulty is telling a row whose position came from a
pull from one genuinely read here, and the two columns already say it:

- both pull paths set `updated_at` and `synced_at` to the same `now`, in
  one statement;
- a local page turn moves `updated_at` and leaves `synced_at` alone;
- an acknowledgement moves `synced_at` and leaves `updated_at` alone.

So `synced_at = updated_at` holds exactly for rows whose current
position was last written by a pull. Those get `read_at =
remote_updated_at`; everything else keeps a NULL and falls back to
`updated_at`, which for those rows is already the right answer.

Two alternatives were rejected. `MIN(remote_updated_at, updated_at)`
demotes a book read here after an old pull, because its
`remote_updated_at` is stale. And `local_revision > acked_revision`
looks like a dirty check but is not one: `applyPeerPullIfUnchanged`
bumps `local_revision` without touching `acked_revision`, because for a
liseur-sync peer the real acknowledgement lives in `sync_peer_state`, so
every pulled row reads as dirty.

The backfill repairs only what it can prove, and two kinds of pulled row
are deliberately left alone. `retireAccountState` clears `synced_at` on
every row when an account is dropped, so a device that has switched
accounts has no equality left to test. And a status adopted from the
server without a position (`setStatusOnly`) moves `remote_updated_at`
past `updated_at`, which the second guard rejects. Both keep exactly the
behaviour they have today and correct themselves the next time the book
is read or pulled — which is a better answer than filing a book under
the moment somebody pressed a button somewhere else.

### A row with no position reports no reading

`observeReadAt` now excludes rows with neither a progression nor a
locator. `startIfMissing` and `insertPending` create such rows to make a
row exist, not to record reading, and a bare one was reporting its own
creation time — which `Book.recentRank` reads as "being read" and throws
to the top of the shelf with nothing behind it.

### Naming is rationed by what naming costs

The budget existed because resolving by identifiers hashes the file. A
book from the server's own catalog resolves through
`POST /v1/books/{id}/resolve` instead: one request, no file opened. On a
fresh device connected to liseur-sync that is *every* book, so the
ration was being spent entirely on the cheap case.

The budget splits: 25 a run for resolves that must read a file, 500 for
catalog resolves. Candidates are ordered by the most recent of `read_at`
and `last_opened_at`, so a partial run names the books the reader
actually wants — the previous ordering was by `last_opened_at`, which on
a fresh device is null for everything, leaving title order. Books whose
question is already on file sort behind all of that. They are still
worth asking about, since another device may have named one since, but
not out of the same purse as a book nobody has asked about at all: a
library with more ambiguities than budget would otherwise spend every
run re-asking the same questions and never reach the books behind them.

Seeding follows. A newly named book asks the server where it stands, and
one request per book is what made lifting the resolve budget useless. A
run with several books to seed asks `GET /v1/heads` once instead, takes
the newest op per work, and lands the lot. It falls back to the per-book
route for a single book — somebody is waiting on that one answer — and
for a server that refuses. The cursor is **not** moved: a heads
bootstrap is a seed, not a pull, and the cursor advances only in the
transaction that writes the page it covers.

A work whose head comes back unreadable falls back to the per-book route
too. Heads carries only the newest record per work and device, so there
is nothing left in that answer to fall back on, while the per-book route
scans a window and can find a position behind the record this device
cannot read. Marking such a book seeded on an empty answer would seal
over a reading that was there to be had, and a seed is only asked for
once.

### A run that could not finish says so

`SyncOutcome` gains `Incomplete`: everything asked for went well, and
there is more to fetch. `LiseurSyncPositionSync` returns it when a run
got somewhere *and* something is still owed — a book with no name, or
one named but never told where it stands.

Both halves count durable advancement rather than effort. A book named,
a book whose debt to the server was finally paid, or a book whose
question reached the reader for the first time all count; re-asking a
question this device already has an answer to does not, because the next
run would face exactly what this one faced. A question can be on file as
an alias awaiting confirmation *or* as an ambiguity, and both have to be
read, or a book the server cannot tell from another would pass for
progress every run for ever. That is what makes the chain terminate.

A refusal does not cancel the debt. If it is worth retrying, the backed
off retry picks the shortfall up with everything else and the run stays
a failure. If it is not — one stale book among six hundred — the rest of
the library still gets its follow-up, and the reader is told what failed
by the status report rather than by the outcome.

Carrying on lives in `PositionSyncCoordinator`, not in the worker. A
fresh connection is synced from app start, pull to refresh, connecting
an account and Settings' sync now, and only one of those is the worker;
every one of them would otherwise have to remember, and one of them
eventually would not. `CompositePositionSync` folds `Incomplete` over a
peer that had nothing to do, or the shortfall would vanish behind a
quiet neighbour, and over a peer that failed for a reason not worth
retrying: such a reason decides nothing else — a partial run nobody will
retry and a run carrying on both end the worker without a backoff — so
letting it through would leave a fresh connection half-named for as long
as some unrelated account stayed locked out. A failure that *is* worth
retrying still wins, because its retry covers the shortfall too. The follow-up is appended to its own unique work name
rather than kept, because the run reporting the shortfall is usually the
previous follow-up and WorkManager would drop a request colliding with
its own running name. `FULL_SYNC`'s backoff is untouched.

The coordinator also stops after twenty-five consecutive carry-ons. The
accounting above should make that unreachable; it is there because a
mistake in it would otherwise be a sync every fifteen seconds for as
long as the phone is on.

## Consequences

- Komga and calibre-web get the ordering fix for free: both already
  supply a remote time, and the change is in `data/db`.
- A device that has already imported in batches is repaired by the
  migration rather than needing a reconnect.
- `read_at` is a display concern. Anything that must agree across
  devices continues to go through revisions.
- A fresh device now issues one resolve per catalog book, up to 500, in
  a single run. A batch resolve route on the server would make that one
  request; the client works without it, and that is tracked separately.
