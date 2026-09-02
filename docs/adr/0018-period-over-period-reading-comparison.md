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

Each side's last day stops at the time of day the reader has reached, not
at midnight. Matching the day is not enough on its own: the current
period's final day is today, counted only as far as *now*, while the
baseline's is a complete twenty-four hours. Without the second bound a
Tuesday afternoon is measured against the whole of the Tuesday before,
evening included, so the sentence slides towards "less than last week" as
every day wears on and springs back at midnight. That is a change in the
arithmetic, reported to the reader as a change in their habits.

The bound is named as a wall-clock time rather than as an instant exactly
one period ago, because "as far as I have got today" is what the reader
means and an instant a week ago is an hour adrift of it on the two
weekends a year the clocks move. Naming it is not the same as comparing
it: on each side it is resolved against that side's own day to a single
moment, so the clocks moving cannot hand one side an hour the other never
had. Where an hour is struck twice the earlier of the two is taken; where
one is skipped the cutoff moves on by the length of the gap. Neither case
is likely and both are decided rather than left to whatever a wall-clock
comparison would happen to do.

A sitting still running at that moment is counted for the share of its
length that had elapsed. Everywhere else a sitting belongs whole to the
day it ended on, and midnight still divides nothing: the rule is there so
that this device, the headline above and liseur-sync all agree which day
reading happened on. The cutoff answers a different question, how much
had been read by now, and the sitting on the other side of the
comparison is the one open on the reader's screen, which is itself only
recorded as far as its last checkpoint. Counted whole it would be an
evening measured against an afternoon; dropped whole it would be an
afternoon measured against nothing.

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
resolvable forever: they are simply no longer `StatsRange` entries, and
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

Totals for both spans are reduced from the same session snapshot, in the
same timezone, by the same `readingTotals()` call with the same cutoff. The
current dashboard continues to use `readingStats()` for its books, chart,
and headline. Sessions are assigned to days by the same rule as the existing
statistics so midnight and timezone behaviour cannot disagree between the
headline and its comparison.

A sitting still running at the cutoff is counted for the share of its
length that had elapsed, and that is the one place a sitting is divided.
Midnight still divides nothing: a sitting belongs whole to the day it ended
on, here as in the headline and on liseur-sync, so the two halves of a
comparison still add up to a total over both. The proration exists because
the sitting on the other side is the one open on the reader's screen, which
is itself only recorded as far as its last checkpoint; counted whole, the
older one would be an evening against an afternoon, dropped whole it would
be an afternoon against nothing.

The clock is sampled once, as a single zoned moment, and the day and the
time of day are both read off it. Two readings could straddle midnight and
put the cutoff at the top of a day the date says is already over, which
would empty a span's last day.

When liseur-sync is connected, `LiseurSyncInsights` still supplies the
headline: the reader does not care which machine did the reading, and the
merge rule there is the larger of the local total and the server total plus
this device's locally pending time.

**The comparison beneath it is local on both sides, and does not consult
the server at all.** What the sentence claims is a *relationship* between
two spans, and a relationship only holds between two figures gathered the
same way. Both sides are therefore this device's own sittings, reduced over
spans that stop at the same time of day.

Mixing the two sources within a side cannot be made safe. A summary
aggregates whole days, so a day stopping where the clock has got to is one
no server can answer for; the only way to use a server at all would be to
add its whole days to this device's part-day, and that sum is unsound twice
over:

- Its days are the *server's* calendar days (`InsightDay` is documented as
  such), while this device splits by its own zone. An offset between the
  two leaves the server's final whole day overlapping the device's partial
  day, so uploaded reading is counted by the server and again locally; a
  westward offset leaves a gap instead. The server does not declare its
  timezone, so the app cannot align the split.
- The two requests fail independently. One succeeding and the other timing
  out would leave one side counting every device and the other counting
  one, with an evening on a laptop appearing in one half of the comparison and
  vanishing from the other, which is exactly the false report of a changed
  habit this whole decision exists to prevent. `summary()` also folds a
  genuinely empty period into the same `null` as a failure, so the two
  cannot be told apart.

Neither is a hypothetical the arithmetic can be hardened against; both
disappear when a side has one source.

Stale replies are guarded twice. The existing refresh generation refuses
an answer from a superseded request; that is not sufficient on its own,
because `combine` does not synchronise its inputs and a fresh answer can
still be paired with a local snapshot taken before the range changed. So
the span, the week's first day, the timezone and today's date are held as
one value, and every server answer is tagged with the one it was asked
about. An answer whose tag no longer matches the local snapshot is
ignored rather than merged. The time of day travels in that same value but
is deliberately left out of the tag: it bounds the comparison, which is
local, and the server is never asked about it, so a reply fetched at four
o'clock is still an answer to the question asked at five. Were it in the
tag, every visit to the screen would sample a new time, refuse the answers
already on hand, and drop the headline back to local figures until the
network answered again.

`StatsHeadline` carries an optional comparison value containing the previous
total and its period label. `BentoHero` renders it immediately beneath the
current duration and above the existing range caption. No database migration,
new dependency, setting, or server endpoint is required.

## Consequences

The default weekly view gains context that grows fairly through the week:
Wednesday is compared with Monday-to-Wednesday, not with all seven days of
last week, and Wednesday lunchtime with Monday-to-Wednesday lunchtime rather
than with a Wednesday that had its evening. Month and year views follow the
same rule, so a partial period is never measured against a completed one.

A reader with a second device is compared against themselves on this one,
and **the sentence says so**: every wording of it ends "on this device",
directly under a total captioned "on every device". The two lines sit in
one card and the contrast between them is what tells the reader which
figure is which.

Naming the scope is not politeness, it is the claim being made. Leaving
both devices out of both halves does not preserve the all-device
percentage. A laptop that did four hours last week and none this week
would still show "more than last week" on a phone that read a little more
than it did before. What the line reports is a trend *on this device*, and
that is a true and useful thing to report; reporting it as a trend in the
reader's month would not be. A test asserts the qualifier is present, so it
cannot drift out of the strings later.

The comparison costs no network request. The baseline summary this ADR
originally specified is gone.

The range menu loses three entries and gains one, which is the cost of the
comparison applying everywhere it is offered. A reader who used "Last 90
days" loses a span; the year is what they are moved to, and it is the only
survivor that does not show them less than they had. In exchange nothing
in the menu is a window whose caption has to be read arithmetically:
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
