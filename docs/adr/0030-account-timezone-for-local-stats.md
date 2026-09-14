# 30. Whose day it is when the stats screen adds up

Date: 2026-09-14

## Status

Draft

## Context

`readingStats()` takes a `ZoneId` and a `today`, and the caller passes
the device's. That is deliberate and documented: which day a sitting
happened on is not a question SQL can answer without being told a zone,
the zone is the reader's, and it changes when they fly somewhere. Doing
the sums in Kotlin means the answer is computed against the zone in force
when it is asked.

The server answers the same question differently. It files a sitting into
a `(work, day, timezone)` bucket using the *account's* timezone, and it
records which zone it used. Those buckets are what a connected reader
sees on the combined stats screen, merged with locally captured reading
by `uniteSnapshot`.

So the screen adds two numbers computed under two definitions of "day".
For a reader whose device zone and account zone agree — almost everyone,
almost always — the two agree too and nothing is visible. For a reader
who has travelled, or who set the account zone once and moved since, they
do not:

- A sitting that ran across local midnight is on one day in the server's
  half of the answer and another in the local half. The day-by-day chart
  puts it in two places.
- The streak is worse, because a streak is a run of days and a
  disagreement about one day can break a run that did not break, or join
  two runs that were genuinely separate.

`serverOnlyPace` already admits one version of this problem — the
snapshot's pace covers only the reading the server holds, so it is
labelled rather than silently merged. The day attribution has no such
admission.

Nothing here is a bug being hit today. It is a disagreement the design
contains and does not name.

## Decision

*Not decided.* Recording the options while the server-side question
(liseur-sync ADR-0043) is open, because the two answers have to agree.

### Use the account's zone locally when connected

The screen would then be internally consistent: both halves filed the
same way, one definition of a day, a streak that means one thing. The
cost is that a reader in Tokyo with a Paris account sees their evening
reading attributed to the afternoon, and the local-only figures they saw
before connecting would shift under them.

It also makes `readingStats()` depend on a server setting, which is
exactly the coupling its current signature was written to avoid — though
passing a zone in from the ViewModel rather than reading a setting inside
keeps the function pure.

### Keep the device's zone and say so

Cheaper and more honest about the seam: label the combined figures the
way pace is already labelled, so the reader knows the two halves were
counted differently. The disagreement stays, but it stops being invisible.

The trouble is that a streak cannot really be footnoted. *Eleven days,
approximately* is not a thing to put on a screen.

### Let the reader choose

A setting under Settings → Reading, defaulting to the device. This is the
answer that admits there is no right one, and the cost is a setting for
something most readers will never think about — which the repository's
own conventions say to resist.

## Consequences

Whatever is chosen has to match the server's answer, because the screen
adds the two together. If liseur-sync ADR-0043 makes the account zone
time-varying, the client's choice changes shape too: "the account's zone"
stops being a single value it can be handed.

Until then, the honest position is that the combined streak is the
server's `combinedStreak` — already the case — and that the local
day-by-day chart is the device's. They are not the same question, and the
screen currently implies they are.

## Open questions

- Is the disagreement ever large enough for a reader to notice, outside
  of an actual move?
- Does the comparison feature (ADR-0024) inherit this, and does a
  period-over-period comparison across a zone change mean anything?
- If the account zone is adopted locally, what happens to the figures
  shown before an account is connected — do they change when it is?
