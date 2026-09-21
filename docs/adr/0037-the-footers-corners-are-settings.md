# 37. The footer's corners are settings

Status: accepted

Builds on [29. What a page is in the footer](0029-what-a-page-is-in-the-footer.md)
and [36. The footer counts screens](0036-the-footer-counts-screens.md),
and amends neither.

## Context

The reading footer has three slots and until now only one of them was a
choice. The left corner printed the percentage of the book read, the
right corner printed `137/892`, and both were written into the
composable. The middle was the setting: `FooterMode`, six values, a
dropdown in two screens, a stored id, a sync key, and a tap anywhere on
the row to cycle it.

That is the wrong slot to have spent the one setting on. The middle is
the widest part of the row and usually carries the chapter's title,
which is the thing a reader is least likely to want to trade away. The
corners are where the numbers live, and the numbers are what differ
from reader to reader. One reader wants how far through the chapter
they are and how long it has left. Another wants the clock, because
they are reading before something. A third wants nothing at all on the
right and a wide title. None of them could have it.

The request that started this was narrower — pages left in the chapter
on one corner, percentage left on the other — but it does not have an
answer that is not this one. Any pair of figures picked for the corners
is somebody else's wrong pair.

## Decision

Each of the three slots is its own setting, and the three are
independent.

The middle is not touched. `FooterMode` keeps its name, its six values,
their stored ids, its sync key `reader.footer_mode`, its dropdown, its
cycle and every rule ADR-29 and ADR-36 wrote for it. What is added is a
second catalog, `FooterField`, for the two corners, and a tap that
belongs to one slot rather than to the row.

Defaults reproduce the old footer exactly — left is the book
percentage, right is the page of the book — so an existing reader's
footer does not move when they upgrade, and a reader who never opens
the setting never learns it exists.

### The catalog

Fourteen entries, each with a stored id:

| Entry | Draws | Comes from |
| --- | --- | --- |
| `PERCENT_READ` | `43%` | the reading progression |
| `PERCENT_LEFT` | `57% left` | the same, subtracted |
| `PAGE_OF_BOOK` | `137/892` | the screen count of ADR-36 |
| `PAGES_LEFT_BOOK` | `755 left` | the same, subtracted |
| `TIME_LEFT_BOOK` | `2 hrs 5 mins` | the measured pace |
| `LOCATION` | `Loc 1234` | the stable Readium position |
| `PAGES_LEFT_CHAPTER` | `9 left` | screens of the resource on screen |
| `PAGE_IN_CHAPTER` | `4/12` | the same |
| `PERCENT_READ_CHAPTER` | `33%` | the same, or the resource's share |
| `PERCENT_LEFT_CHAPTER` | `67% left` | the same, subtracted |
| `TIME_LEFT_CHAPTER` | `12 mins` | the measured pace |
| `CLOCK` | `14:32` | the device clock |
| `BATTERY` | a glyph and `78%` | the battery |
| `EMPTY` | nothing | |

Choosing between them is arithmetic on figures the reader's page
already carries, so it lives in `reader/progress/FooterFigure.kt` next
to the middle's own, knows nothing about Android, returns a small
sealed type rather than a string, and is unit tested off a device. The
composable's only job is to put the figure into the right sentence in
the right language. Nothing here is stored, sent, or counted as
reading; these are display settings and the wire is untouched.

### A slot that cannot answer says nothing

This is ADR-36's rule for the right corner and every field inherits it.
A reflowable page still laying itself out has no screens to count. A
chapter that could not be resolved has no countdown. A book whose
positions are unusable has no location. In each case the slot is blank
for the moment it takes to have an answer.

The alternative is to print something else in the meantime, and
something else is worse than a gap. A corner that briefly shows the
previous chapter's count under the current chapter's title is not a
corner that was empty for a moment; it is a corner that lied. A reader
glancing at a number does not check which number it was.

Two rules follow from this that are new.

A time figure on a corner waits for a measured pace, which the middle's
own `TIME_LEFT_BOOK` does not. The middle shows an unmeasured estimate
because the middle has room to hedge it in words and room to fall back
to the chapter's name when it has nothing. A corner has neither. Six
characters of `2 hrs` sitting where a measured figure goes will be read
as measured, so it waits until it is.

The chapter's time waits for a chapter as well. When the chapter cannot
be resolved the minutes left in it count to the end of the book, and
under a corner label saying "chapter" that reads as a very long chapter
rather than as a figure nobody could work out. It goes on the same
answer `FooterMode.PAGES_LEFT_CHAPTER` already goes on.

`PAGE_IN_CHAPTER` refuses the fallback that the chapter percentages
accept. In a paginated reflowable book the screens of the resource on
screen are measured and `4/12` is a count. Elsewhere — a fixed-layout
book, a resource not yet measured — all that is available is where the
reading progression sits between the resource's two ends, which is a
share of the resource rather than a count of its pages. That share is
honest as `33%` and dishonest as `4/12`, because the second one names
twelve pages that were never laid out. So the percentages take the
fallback and the page count does not.

The share has to be worked out carefully, because the two numbers
behind it are kept on different rulers. A resource's ends are shares of
all the book's positions, counted from the first to one past the last,
while the reading progression runs from the first position to the last.
Subtracting one from the other without converting gave a fixed-layout
book, where each resource holds a single position, the whole book's
percentage under a label saying "chapter". The position on screen is
already on the resource's own ruler, so it is what the share is
measured from, and the progression is left to the figures that speak
about the whole book. Rebuilding the position out of the progression
instead would go through a `Float` and land a hair under the position
it came from, which costs a page its last percent.

The position counts as read, as the screen on screen does when the
screens have been measured, so a chapter that has been turned through
says 100% rather than 92%. Reaching that end matters for the last
resource in the book, where the progression stops short of 1.0: one
position less and a two-position final chapter would read half done
with the book finished. A fixed-layout resource is a single position
and reads 100% for as long as it is open, which is what the measured
path says about a chapter of one screen, and a book of a single page
says the same.

`PAGES_LEFT_BOOK` is the one place a figure is clamped rather than
refused. The page is counted and the total is estimated, so a dense
chapter can walk the page past a total that has not caught up yet, and
the subtraction goes negative. Zero is true there — this is the last
page the estimate knows about — and a negative count is not. It carries
ADR-36's "about" hedge to the screen reader for the same reason the
page number does.

As in ADR-36, a chapter is the resource on screen, so a chapter split
across two files restarts at the second one.

### Taps belong to slots

The row keeps its layout: corners at their intrinsic width, the middle
taking what is left, so a long title still gets every pixel the corners
do not want. What changed is that each slot carries its own gesture. A
tap cycles that slot through the catalog and leaves the other two
alone. A long press opens a picker for that slot. Neither turns the
page, as before.

A corner is a short string and would be a thin target, so the row's
horizontal padding moved inside each corner's clickable rather than
outside it — the chain is `clickable{}.padding()`, which grows the
target instead of insetting it — and the bottom corner of the screen
belongs to the slot sitting in it. Each corner also keeps a minimum
width, because `2%` is four millimetres of text and the middle, which
takes whatever the corners do not want, would otherwise reach almost
to the screen's edge. The minimum is on the corner's content rather
than on the whole slot: put on the slot it would be swallowed by the
twenty-dp margin the corners already keep, and a corner with nothing
to draw would end up narrower than one with a figure in it.

The row's vertical padding moved into the slots along with it, and the
corners are stretched to the row's height with `IntrinsicSize.Min`. The
band is exactly as tall as it was, and the reservation the page makes
for it is unchanged, so this is nothing a reader can see. What it
settles is which pixels answer a tap. A slot with nothing in it is a
box with no content, and how tall such a box ends up is a question
about Compose's measurement rather than about the footer; measured on a
device the empty corner did answer, but a corner that can only be
refilled by tapping it should not rest on that. Growing the band
upwards was the other way to make the corners easier to hit, and it is
still refused: those pixels belong to the page-turn zones.

The two corners are named for the sides of the screen a reader can
point at, so they keep those sides in a right-to-left locale, where a
`Row` would otherwise put its first child on the physical right and
leave the setting called "left" changing the corner on the right. The
slots trade places and everything else mirrors as it should: the wide
margin, the alignment and the picker all follow the corner they belong
to.

A corner set to `EMPTY` still claims that minimum width and the full
height, so there is somewhere to tap to bring it back. `EMPTY` is in
the tap cycle, so a corner emptied by a tap is filled by the next one.

The one exception is the tap that would empty the last slot still
drawing. That state hides the footer, and the footer is what was being
tapped, so the tap would take away its own undo and leave a setting
that can only be reversed two screens away. A tap offered that entry
is given the one after it instead. The picker can still reach it,
because a reader who goes to a sheet to empty the footer knows the
sheet is where it comes back from — the same reasoning that keeps
`FooterMode.NONE` out of the cycle and in the list.

The rule is a property of the three settings rather than of the
drawing, so it lives beside the enums with `footerHasAnythingToSay`,
which the footer, the page's height reservation and both tap cycles
all ask. The corner and the note have to agree on what a tap chooses,
and there is one answer for them to agree on.

A tap is a read of the three settings followed by a write of one of
them, and both halves happen inside DataStore's own edit rather than
against the settings the footer was last drawn with. Two taps in quick
succession would otherwise read the same starting figure, choose the
same successor, and lose a step, because the second write would say
what the first had just said. The write hands its figure back, and the
note is raised from that rather than worked out at the tap, so a
reader who taps twice before the first write has come back round is
told what the second tap actually chose.

The picker is a modal sheet listing that slot's options with the
current one checked, and it serves all three — long-pressing the middle
offers the `FooterMode` values. It exists because fourteen entries is
too many to walk through: the tap is for the reader who wants the next
thing, the long press for the reader who wants a particular thing. It
is held as its own state rather than as one of the reader's sheets,
because the footer only draws while the chrome is hidden and dismissing
the picker must not bring the chrome back.

When all three slots are empty the footer draws nothing and reserves no
height, joining `FooterMode.NONE` in the check that decides whether the
page keeps room for a footer.

### A tap says what it just did

A tap hands the reader a new figure and no indication of what it
counts, which is a poor trade for a corner they could read at a glance
a moment ago. So a tap raises a line of text above the footer naming
what the slot now shows — "Percent of chapter left", "Location" — and
takes it away again after five seconds.

A second tap replaces whatever is up rather than queueing behind it.
The reader is looking at the slot they are tapping and the label that
matters is the one for the tap they have just made, so the countdown
starts again from the top each time, including when a tap lands on the
same entry as the one before it.

The note also covers the taps that appear to do nothing. A corner
waiting on a measured reading pace draws nothing at all, and a corner
set to nothing draws nothing by definition; without a word, both read
as a tap that failed. The note says so in the first case and names the
choice in the second.

It is named from the choice the tap is about to make rather than from
the setting coming back round through storage, so it is on screen
while the finger is still lifting. Asking whether that choice has
anything to draw takes a reading of its own for the clock and the
battery: the footer holds only the readings its slots are currently
showing, so a tap arriving at the clock would otherwise be told there
was no clock in the frame before the clock appeared. A tap is not a
composition and can afford to look.

It is drawn on the page's own paper in the reading theme, like the
rest of the reader's chrome, and it takes no touches of its own: it
sits over the page-turn zones, and a reader who wants the next page
while it is up should get the next page rather than spend the tap
dismissing a label. A long press raises no note, because the sheet it
opens is already the explanation.

A slot with nothing drawn in it still says what it is to a screen
reader, using the same two sentences the note uses: the figure's name
on its own when the slot was asked for nothing, and the note's
"nothing to show yet" when it has a figure it cannot answer for.
Without it the corner is a target announced by its actions alone, and
the only way to find out what it holds is to change it.

A long press takes the note down as it opens the sheet, and keeps it
down: a tap's note arrives once its write is through, so a tap
followed by a long press can have one land behind the sheet. The sheet
is the fuller explanation and it covers the note while it is up, so a
countdown left running underneath would put the old figure's name back
over a corner the reader has since set by hand.

A note goes when the footer goes, for any of the reasons the footer
goes: the chrome, the end page, a jump offered, and the settings
themselves, since the picker and a setting arriving from another device
can both empty the last slot while a note is up. Its five seconds are
counted alongside the footer, so a note interrupted this way would keep
an unfinished countdown and finish it whenever the page came back,
explaining a tap made an hour earlier. It is cleared instead, and it is
cleared whenever it is raised while the footer is away as well: the tap
names its figure once DataStore has written it, so a reader who taps
and reaches for the chrome can have a note arrive after the footer has
gone.

It floats clear of the footer rather than over it, at a height worked
out from the same line height the footer reserves its band with. A
fixed distance would clear the figure at one system font size and sit
on it at another, which is worst for exactly the reader who has turned
the font up.

### A corner outlives the page's numbers

The middle is always a figure about the book, so a reading position
the navigator cannot yet give ends it. The corners are not all in that
position: the clock and the battery are answers the device can give at
any moment, and a reader who put the time in a corner should see it
while the page is still laying itself out. So the row draws whenever
the settings ask for it, and each slot decides for itself whether it
has an answer. The page's height reservation is made from the settings
alone, so this also closes the gap where the band was held open with
nothing drawn in it.

### The clock and the battery are read, not subscribed to

Both are new to this file and both would repaint on a schedule that has
nothing to do with reading, which on electronic paper is a schedule of
ghosts.

The clock is formatted by the platform's own time format, so it follows
the device's 12- or 24-hour setting rather than choosing for itself. On
a backlit screen a ticker at the minute boundary redraws it. On
`LocalEInk` there is no ticker at all: the value is read when the
footer is drawn, so it refreshes on a page turn. A minute-stale clock
on a device that takes most of a second to repaint is the better
trade.

A page turn has to be counted for that to hold in every book. The turn
is usually visible in the position under the footer, which moves and
redraws the row, but a book whose positions could not be generated has
no position to move, and there the clock would keep the minute it was
first drawn at. The footer takes a count of turns from the navigator,
which every book has, and reads it alongside the minute ticker.

Both costs are paid only by a footer that asked for them. The count is
kept while a corner is showing the clock or the battery and the footer
is on the page, so the default footer runs no collector and redraws
for nothing. The ticker runs while the reader is resumed, since a
minute spent behind another app is a minute spent writing a figure
nobody is looking at, and it takes a reading on the way back so the
corner is current again the moment the book is.

The battery is read the same way, as a property, rather than by
registering for the battery broadcast, which fires far more often than
a footer needs and would drag the whole row through a repaint each
time. It is drawn with a small battery glyph, because two bare
percentages a hand's width apart on the same line is how a footer
starts lying about which one is which.

### Settings and sync

`reader.footer_left` and `reader.footer_right` travel the way
`reader.footer_mode` travels the middle: last write wins by when it was
changed, validated on arrival by round-tripping the stored id, with an
unrecognised id falling back to the slot's default rather than being
taken on trust. The ids are slot-agnostic, so a footer arranged on one
device arrives arranged on the other.

The single dropdown in the appearance screen and the reader's advanced
sheet became three, in the order the slots appear on the page.

## Consequences

The footer can now be configured into uselessness — three empty slots,
or the same percentage on both corners — and it is allowed to be. It is
the reader's line of text. The defaults are the old footer and the
picker is one long press away.

There are fourteen entries to translate, in six languages, and every
new field is six strings rather than one. That is the ongoing cost and
it is the right one to pay; the alternative was fewer fields.

Two figures on the same row can now disagree about what a page is.
`LOCATION` is a stable Readium position and `PAGE_OF_BOOK` is a count
of screens, so a reader who puts both on the footer will see `Loc 1234`
next to `137/892` and they will not be the same number. This is ADR-29
and ADR-36 sitting side by side, and it is now visible rather than
theoretical. The stable position remains what the scrubber, the go-to
dialog, bookmarks and annotation labels speak in, which is exactly why
`LOCATION` is in the catalog: it is the figure that lets a reader read
the footer and the scrubber in the same language.

A tap now means less than it did. It used to cycle the middle from
anywhere along the row; it now cycles whichever slot was under the
finger. A reader with the habit of tapping the right-hand end of the
footer to change the chapter countdown will find they have changed the
page number instead. The gesture is discoverable again within one tap,
and the alternative — leaving the row's tap on the middle and giving
the corners no gesture at all — makes the corners settings that can
only be reached through two levels of a settings screen.

## Alternatives considered

**Move the existing setting to the corners and leave the middle
hardcoded.** The same mistake, rotated. Somebody wants the chapter
title and somebody wants the countdown, and the middle is where that
fight happens.

**One setting that arranges all three at once, as a named layout.**
Fewer choices to explain and a shorter settings screen, and every
reader who wants a combination that is not on the list is back where
they started. Three independent slots have the layouts as a subset.

**A free-form template string.** `{page}/{pages} · {percent}` in a text
field. Endlessly flexible, impossible to translate, impossible to
validate, and impossible to offer as a tap. Not a setting for a footer.

**Let a slot fall back to something when it cannot answer.** Show the
book percentage when the chapter's screens are not measured yet, say.
It fills the gap and it makes the corner unreadable: a figure that
silently changes what it counts is worse than a figure that is
sometimes absent.

**Show unmeasured time estimates on a corner, as the middle does.** The
corner has no room for the words that make an estimate read as an
estimate. The middle earns its estimate by being able to say so.

**Subscribe to the battery broadcast and a second-resolution clock.**
Correct to the second and a repaint storm on a screen that costs a
noticeable fraction of a second to repaint. The footer is read at a
glance during a page turn; it can be as fresh as the page turn.

**A fourth slot, or a second row.** More room for figures and less room
for the book. The footer is one line for the same reason a page number
is.

**Let the note name the slot's setting as it stands rather than as the
tap leaves it.** Always eventually right, and wrong for the handful of
frames it takes the setting to be written and read back, which is
exactly the moment the reader is looking at it. The note names the
figure the write settled on instead, so it arrives a few milliseconds
later and is right from the first frame.

**A note on the long press too.** The sheet already lists every option
with the current one checked. A label repeating what the reader just
read is noise.

**Let the tap cycle reach the empty footer and rely on the settings
screen to undo it.** It is reachable there, which is not the same as
discoverable: the reader who arrived by tapping a corner has no reason
to think a settings screen two levels down is where their footer went.
A cycle that cannot destroy the surface it is on is worth one skipped
entry, and the entry is still on the list.

**Keep the note alive across the chrome and let it finish its five
seconds afterwards.** It would mean the note always gets its full say.
But the five seconds are there to connect a label to a tap, and a
label that reappears after a trip through the chrome has lost the tap
it was about.
