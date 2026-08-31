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
month" would be false. The same distinction applies to `LAST_90_DAYS`,
`LAST_YEAR`, and `ALL_TIME`: they remain useful spans, but they are not the
views this comparison describes.

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

`LAST_30_DAYS`, `LAST_90_DAYS`, `LAST_YEAR`, and `ALL_TIME` remain in the
range menu and show no comparison. `THIS_MONTH` is added rather than
relabeling `LAST_30_DAYS`, preserving the meaning of the stored `30d` range
id for existing installations.

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
screen already is when server insights are unavailable. Stale replies are
guarded by the existing refresh generation.

`StatsHeadline` carries an optional comparison value containing the previous
total and its period label. `BentoHero` renders it immediately beneath the
current duration and above the existing range caption. No database migration,
new dependency, setting, or server endpoint is required.

## Consequences

The default weekly view gains context that grows fairly through the week:
Wednesday is compared with Monday-to-Wednesday, not with all seven days of
last week. Month and year views follow the same rule, so a partial period is
never measured against a completed one.

The range menu gains one item and keeps two different month-like choices:
"This month" and "Last 30 days". They answer different questions, and keeping
both avoids silently changing a saved preference. Rolling ranges deliberately
have no comparison until there is a separate reason to define one.

The percentage can be striking when the previous total was small. Showing
the plain "More than …" fallback for a zero baseline avoids infinity, while
neutral colours and wording keep the result observational rather than
competitive.

*Where:* `domain/StatsRange.kt`, `domain/ReadingStats.kt`,
`ui/stats/ReadingStatsViewModel.kt`, `ui/stats/ReadingStatsScreen.kt`, and
`res/values/strings.xml`.
