# 39. The widget borrows the dashboard's other devices

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

When a widget draws, it adds these to this device's live sittings.
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
- Both tables are in `WIDGET_TABLES`, so saving a snapshot redraws the
  widgets.
