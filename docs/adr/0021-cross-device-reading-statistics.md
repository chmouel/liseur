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

The merge takes the larger of the two figures, never their sum, because
the server's count already contains this device's uploads and adding
them would pay twice. It is the right rule, and it makes the server
invisible: for a reader with one device the two numbers are within a few
unsynced minutes of each other, so the headline is the same whether the
request succeeded, failed, or was never made.

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
one is refused. The two are merged by maximum and never by sum. A
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

Two things are deliberately not decided here. The timezone difference
between the server's day buckets, which follow the account's configured
zone, and this device's, which follow the phone's, is left for its own
change. And nothing here alters how sessions reach the server.

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

`LiseurSyncInsights` keeps returning null on every failure. Provenance
is a separate question from the figures and is answered separately:
whether an answer arrived for the window on screen, which the view model
already knows, since refusing a stale one is exactly what
`forWindow` does. Nothing in the merge needs to change to know it.

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
`ui/stats/ReadingStatsViewModel.kt`, `ui/stats/ReadingStatsScreen.kt`,
`ui/settings/ServerAccountScreen.kt`, `res/values/strings.xml`.

The server names each work in `GET /v1/insights/works`, which is what
lets a book with no local file be listed at all: liseur-sync's
`internal/api/insights.go`, `docs/openapi.yaml` and `docs/integrating.md`.
