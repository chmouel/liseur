# 30. Whose day it is when the stats screen adds up

Date: 2026-09-14

## Status

Draft

## Context

Which day a sitting happened on is not a question the stored rows answer
by themselves. A `ZoneId` decides it, and `readingStats()` takes one
rather than assuming, so the answer is computed against the zone in force
when it is asked.

The screen asks with two different zones, and which one it uses depends
on whether a server snapshot is in play:

- Without a usable snapshot, `LocalStats` computes with `window.zone`,
  the device's (`ReadingStatsViewModel.kt:492-498`). The screen reports
  `THIS_DEVICE`.
- With one, the locally captured sessions are recomputed with the
  snapshot's `zone` — the account's — before `uniteSnapshot` folds the
  server's buckets in (`ReadingStatsViewModel.kt:535-539`). The
  comparison does the same. The screen reports `ALL_DEVICES`.

The combined view is therefore internally consistent, and deliberately
so: ADR-0021 requires the account zone for an exact merge, and
`LiseurSyncSnapshotsTest` covers it. Both halves of that sum are filed
the same way. This ADR is not about a mismatch inside the combined
figures, because there is not one.

It is about the seam between the two views. A reader whose device zone
and account zone disagree — one who has travelled, or who set the account
zone once and moved since — gets one definition of a day when a snapshot
is usable and another when it is not. A snapshot is rejected whenever the
device is offline, the account changes, an alias set moves, or local
sessions are captured after the response came back. So the switch is
routine, not exceptional.

What that looks like: a sitting that ran across one of the two midnights
moves between days when a snapshot lands or lapses, and the streak can
gain or lose a day with it, because a streak is a run of days and the two
zones do not agree about where one ends.

Nothing here is a bug being hit today, and for a reader whose zones agree
— almost everyone, almost always — the two definitions coincide and
nothing is visible. It is a seam the design contains and does not name.

## Decision

*Not decided.* Recording the options while the server-side question
(liseur-sync ADR-0043) is open, because whatever the combined view does
has to match what the server files.

### Use the account's zone for both views once an account is connected

The two views would then agree, and figures would stop moving as
snapshots come and go. The cost is that a reader in Tokyo with a Paris
account sees their evening reading attributed to the afternoon even while
offline, and the local-only figures they saw before connecting shift
under them once they do.

### Keep the device's zone for the local-only view and say so

Cheaper, and honest about the seam: label the local-only figures the way
pace is already labelled. The disagreement stays, but it stops being
invisible. The trouble is that a streak cannot really be footnoted.
*Eleven days, approximately* is not a thing to put on a screen.

### Let the reader choose

A setting, defaulting to the device. The answer that admits there is no
right one, at the cost of a setting for something most readers will never
think about, which this repository's conventions say to resist.

## Consequences

A reader who never connects an account is unaffected: one zone, one
definition, no seam.

If liseur-sync ADR-0043 makes the account zone time-varying, the account
zone stops being a single value the client can be handed, and the first
option changes shape.

## Open questions

- Is the shift ever large enough for a reader to notice, outside an
  actual move?
- Does the comparison (ADR-0024) inherit this? It already follows the
  snapshot's zone when united and the device's otherwise, so it has the
  same seam.
- If the account zone were adopted for the local-only view, what should
  the screen show between connecting and the first snapshot arriving?
