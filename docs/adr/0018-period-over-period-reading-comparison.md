# 18. Period-over-period reading comparison

Status: accepted
GitHub issue: [#120](https://github.com/chmouel/liseur/issues/120)

## Context

The statistics screen says how long the reader has read in the selected
span, but it gives that number no reference point. Two hours by Wednesday
can feel like a lot or a little; the useful question is whether it is more
or less than the same point last week. Activity apps such as Strava make
that comparison legible without turning the whole history into a report.

Liseur already has a calendar-aligned `THIS_WEEK` range and a
calendar-aligned `THIS_YEAR` range. Its nearest month range is instead
`LAST_30_DAYS`. A rolling window has no previous calendar month to compare
with and shifts both endpoints every day, so calling its predecessor "last
month" would be false. The same distinction applies to `LAST_90_DAYS` and
`LAST_YEAR`: they are useful spans, but they are not views this comparison
can describe.

A menu that mixes the two kinds also asks the reader to hold the
difference in their head. "This month" and "Last 30 days" sit next to each
other answering almost the same question with different arithmetic, and
only one of them can carry a comparison. Rather than explain that, the
menu is reduced to calendar spans.

The headline may combine local sessions with liseur-sync insights. Any
comparison has to follow the same rule for both periods, or reading on a
second device could appear in the current figure and disappear from the
baseline. Server statistics remain decoration: a failed comparison request
must leave a complete local statistics screen, not an error.

## Decision

Add a `THIS_MONTH` statistics range and show a period-over-period reading
time comparison for `THIS_WEEK`, `THIS_MONTH`, and `THIS_YEAR`.

Each current period ends today. Its baseline is the matching elapsed portion
of the immediately preceding calendar period:

- This week compares the locale's week start through today with the same
  weekdays in the preceding week.
- This month compares the first of this month through today's day number with
  the first of the previous month through the same day number. If that day
  does not exist in the previous month, the baseline ends on its last day.
- This year compares 1 January through today with 1 January through the same
  month and day in the previous year. A leap-day comparison ends on 28
  February in a non-leap year.

Only active reading time is compared. Books, sessions, streak, pace, and
finished counts continue to describe the selected current span and do not
gain deltas. A single comparison under the large reading-time figure keeps
the hierarchy clear and avoids turning every tile into a scorecard.

The copy is descriptive: "25% more than last week", "12% less than last
month", or "Same as last year". The direction is reinforced by an arrow but
not by success/failure colours; reading more is not graded as good and
reading less is not graded as bad. Accessibility text states the direction,
percentage, and baseline period without relying on the icon.

Percentage is `abs(current - previous) / previous`, rounded to the nearest
whole percent. Equal rounded durations read "Same as …". If the previous
period is zero, no percentage is invented: a non-zero current period reads
"More than …", while two zero periods read "Same as …". The comparison is
hidden when no trustworthy baseline can be produced.

The rolling spans are withdrawn. The range menu becomes **This week ·
This month · This year · All time**: every entry is a calendar period, and
every entry but "All time" carries a comparison. `ALL_TIME` has no period
before it and shows none.

A saved range id is what is on disk, so the three withdrawn ids stay
resolvable forever — they are simply no longer `StatsRange` entries, and
nothing can select them again. `StatsRange.fromId()` maps each to the
nearest surviving span rather than dropping the reader to the current week
without telling them:

| stored id | resolves to | why |
| --- | --- | --- |
| `30d` | `THIS_MONTH` | the closest surviving span, and the question it was asking |
| `90d` | `THIS_YEAR` | no quarter survives; the year is the only span that does not shrink the view |
| `365d` | `THIS_YEAR` | the calendar spelling of what they had |

The migration is a lookup in `fromId`, not a rewrite of the stored
setting: rewriting would need a migration path for a value the reader may
never look at again. `THIS_WEEK` keeps the id `7d` for the same reason.

## Design

A pure domain value describes two inclusive date spans: the selected
current span and its previous-period baseline. It owns the month-end and
leap-year clamping rules and is tested independently of Compose and Android.
Ranges that are not calendar-to-date return no comparison span.

Local totals for both spans are reduced from the same session snapshot and
timezone. The current dashboard continues to use `readingStats()` for its
books, chart, and headline; the comparison only needs the total duration for
the baseline. Sessions are assigned to days by the same rule as the existing
statistics so midnight and timezone behaviour cannot disagree between the
headline and its comparison.

When liseur-sync is connected, `LiseurSyncInsights` requests summaries by
explicit `from` and `to` dates for both periods. Each displayed total uses
the existing merge rule: the larger of the local total and the server total
plus locally pending time for that exact span. The two server answers are
accepted independently. If the previous-period answer fails, the app uses
the local baseline; if local history cannot represent a trustworthy
cross-device baseline, the comparison is simply local, as the rest of the
screen already is when server insights are unavailable.

Stale replies are guarded twice. The existing refresh generation refuses
an answer from a superseded request; that is not sufficient on its own,
because `combine` does not synchronise its inputs and a fresh answer can
still be paired with a local snapshot taken before the range changed. So
the span, the week's first day, the timezone and today's date are held as
one value, and every server answer is tagged with the one it was asked
about. An answer whose tag no longer matches the local snapshot is
ignored rather than merged.

`StatsHeadline` carries an optional comparison value containing the previous
total and its period label. `BentoHero` renders it immediately beneath the
current duration and above the existing range caption. No database migration,
new dependency, setting, or server endpoint is required.

## Consequences

The default weekly view gains context that grows fairly through the week:
Wednesday is compared with Monday-to-Wednesday, not with all seven days of
last week. Month and year views follow the same rule, so a partial period is
never measured against a completed one.

The range menu loses three entries and gains one, which is the cost of the
comparison applying everywhere it is offered. A reader who used "Last 90
days" loses a span; the year is what they are moved to, and it is the only
survivor that does not show them less than they had. In exchange nothing
in the menu is a window whose caption has to be read arithmetically —
"This year" now says so rather than reading "In the last 227 days".

`THIS_YEAR` and `THIS_MONTH` also gain proper captions, which fixes a
pre-existing wart: the caption was built from a day count, so mid-August
"This year" was captioned "In the last 227 days, on every device".

The percentage can be striking when the previous total was small. Showing
the plain "More than …" fallback for a zero baseline avoids infinity, while
neutral colours and wording keep the result observational rather than
competitive.

*Where:* `domain/StatsRange.kt`, `domain/ReadingStats.kt`,
`data/liseursync/LiseurSyncInsights.kt`, `ui/stats/ReadingStatsViewModel.kt`,
`ui/stats/ReadingStatsScreen.kt`, `ui/stats/BookReadingStatsScreen.kt`, and
`res/values/strings.xml`.
