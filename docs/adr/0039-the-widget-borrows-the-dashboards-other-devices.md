# 39. The widget borrows the dashboard's other devices

## Amendment: fixed week, month and year (2026-10-05)

There are now two providers: the unchanged current cover and a stats card.
The stats card shows the current title and progress above all-book calendar
week, month and year hours. Book and totals have separate tap targets.
There is no configuration, cover, chart or streak in the stats card.
The library and combined-cover providers are removed, not redirected.
The surviving receiver identities stay unchanged.

The shared refresher also fetches the year, using the existing snapshot
chunking and revision/overlap proof. All cache saves retain 366 days;
year data does not create an unused aggregate window. Account checks,
timezone isolation, explicit zero days and residual-only accounting remain
unchanged. A week or month refresh must not prune annual reading.

Fresh complete coverage of every displayed range needs no source footer.
Otherwise the footer is “Last synced reading” when remote activity
contributes. With only local activity, the widget shows no source footer.
Freshness still means one hour, and
missing days are never treated as proven zeros.
When book deletion or replacement removes sessions used to subtract overlap,
the app clears cached residuals in the same database transaction and queues a
new snapshot. An offline refresh leaves the widget on local totals without a
source footer.

The sections below record the earlier design.

Status: accepted

Builds on [21. Cross-device reading statistics](0021-cross-device-reading-statistics.md).

## Context

The home-screen widgets shipped reading only this device's Room data.
For someone who reads on one phone, that is the whole story. For
someone who also reads on an e-ink tablet and in the liseur-sync web
reader, it isn't. In one real week the stats screen said 12h21 across
every device, and the widget on the same phone said about 4h20: the
phone's share.

The widgets can't simply ask the server. A widget draws from a
broadcast or a worker that Android may kill at any moment, and a
home screen that waits on the network, or goes blank when offline, is
worse than one that is out of date.

## Decision

The stats screen already asks liseur-sync for a complete snapshot and
proves it against this device's sittings. When it accepts one, it now
also saves the part the server counted **on other devices**: every
figure with the server's measured overlap with this device's candidate
sittings taken out.

- `remote_stats_day` holds that residual per calendar day. Days with
  nothing read are stored too, so a missing row means "not covered"
  rather than "nothing read".
- `remote_stats_window` holds, for this week and this month, the
  residual sittings, the works read elsewhere and the combined streak.

The screen saves both this week and this month, whichever span it
shows, fetching the missing one without the comparison with the period
before. When neither reaches back to six days ago, it also fetches the
last seven days, which the Today widget draws as bars. When a widget draws, it adds these to this device's live
sittings.
Because the residual excludes this device's captured sittings, a sitting
read here after the snapshot is still counted once, whether or not it
has been uploaded since.

- Minutes and bars take other devices' reading for every covered day,
  in all three periods.
- Sittings and books take the residual only from a window for the
  same week or month. The server doesn't count sittings per day, so
  the Today widget's sittings and books stay this device's.
- The streak is the largest of this device's own, the run across days
  read on either side, and the server's combined streak for today.
- Rows are keyed by account and timezone. A widget uses them only for
  the current liseur-sync account and only when the device's zone
  matches the account's.

## Consequences

- The widgets still never touch the network. They learn about other
  devices only when the stats screen is opened, so reading done
  elsewhere since then is missing until the next visit. This was chosen
  over having the hourly worker fetch snapshots.
- Both tables are peer-keyed. `RemoteAccountRepository` moves them on
  a rekey (replacing anything under the new key, since this is derived
  data) and clears them when the account is forgotten.
- Both tables are in `WIDGET_TABLES`, with `work_alias`, so saving a
  snapshot or learning a book's work redraws the widgets.
- Each stats screen refresh can ask the server up to four times
  instead of once.
- A save checks, in the same transaction as the write, that the account
  is still the connected one, so a snapshot that arrives after a
  disconnect or a rekey leaves nothing behind.

## Amendment: independent refresh and account timezone

Opening the dashboard as the only way to refresh left the home-screen
figures stale after reading on another device. The widgets now share
`RemoteStatsRefresh` with the dashboard. Successful position syncs, live
insights events, and widget placement enqueue a coalesced background job;
the hourly redraw requests it too. That job runs only while a stats
widget is placed, requires connectivity, and respects local-network
access. Rendering continues to read Room immediately, including offline.

The exact residual accounting is unchanged. The widget attributes local
sessions in the cached account timezone instead of refusing the cache
when the phone timezone differs. Cache rows record refresh times, and
the widget distinguishes fresh complete coverage, older or partial
synced reading, and this-device figures. Account changes remain guarded
at save time, and all rows follow account rekeying and cleanup.

The dashboard saves its accepted range and the shared refresher fetches
any missing week/month range without comparison data. The simplified
widgets show time and streak; removing their charts also removes the
extra last-seven-days request. Session and book counts remain available
on the dashboard instead of exposing mixed scope in the Today widget.
