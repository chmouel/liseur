# 6. Auto-scroll

Status: accepted
GitHub issue: [#45](https://github.com/chmouel/liseur/issues/45)

## Context

Scroll mode exists, but the thumb still has to move the page. Reading
over lunch, on a treadmill, or with one hand in a strap on the train,
a page that carries itself at reading pace is the difference between
reading and not. Paginated mode has no equivalent need — the tap zones
and volume keys already turn pages without reaching.

## Decision

Auto-scroll for scroll mode only: a start row, a speed slider, and any
touch on the page pauses it. Turning it on in a paginated book is not
possible because the row only appears when the book is scrolled.

Fit with Liseur's simplicity: one row in the Advanced sheet, visible
only in scroll mode. While scrolling, the reader chrome is the pause
control it already is — a tap shows the chrome and stops the movement.

## Design

**Where it lives.** Not `ReaderViewModel`, as first proposed: the view
model has no navigator. `ReaderScreen` owns the navigator and the
`PageTurner` that knows how to cross a chapter, so the loop lives there,
driving a pure `reader/chrome/AutoScroll.kt` that knows nothing of web
views, densities or frames. A `withFrameNanos` loop turns elapsed time
into whole pixels — carrying the remainder, so a slow pace still moves —
and calls `scrollBy` on the visible web view.

The notch and the pace are split, and the seam is the direction the
dependency has to run. `AutoScrollPreference` — the range, the default,
`snap` and `sanitize` — is in `data/settings/ReaderPrefs.kt` beside
`ReaderFont`, `FooterMode` and `ColumnMode`, because those bounds
describe the stored setting: what the slider may show and what the
preference store may hold. `AutoScrollSpeed` and `AutoScrollTicker` stay
in the reader, because how fast a page moves is the reader's business.
The reader then reads the setting, which is the way round every other
reading preference already goes. Having the DataStore repository import
the reader instead would have been the layering backwards, and no
tidier for being pointed at a pure file.

The curve normalises every step through `AutoScrollPreference.sanitize`
rather than keeping its own copy of the range, so the slider and the
pace cannot drift apart. `sanitize` is also the only door the stored
value comes through, and it is where a step that is not a number is
stopped: `Float.roundToInt` throws on NaN, and a NaN pace otherwise
multiplies out to a NaN distance, which the ticker would carry forever
as a page that silently never moves again. The ticker refuses a
non-finite pace too, so that invariant does not rest on one caller.

**Whether it moves.** Readium's scroll mode scrolls the web view itself,
which is why `ScrollEdgeTurner` reads `canScrollVertically` off it and
why `R2BasicWebView` hangs its progression notification on
`onScrollChanged`. A book set in vertical lines is scrolled sideways and
runs right to left, so it is scrolled by a *negative* horizontal step —
the same convention `scrollScreenfulScript` and `ScrollEdgeTurner`
already read.

**When it runs** is one predicate, `canAutoScroll`, stated once:

> armed, and the book is effectively scrolled, and the chrome is hidden,
> and no finger is down, and no overlay is up, and the endpaper is not
> showing, and the lifecycle is `RESUMED`.

That is the pause, rather than a separate mechanism for it. In a
scrolled book every tap is a chrome tap, so a tap raises the chrome and
the page stops; the next tap hides it and the page carries on. A drag is
a finger down, so the text goes where the reader puts it and picks up
from there. "Overlay" is every one of them — both sheets, contents,
search, the footnote card, the selection popup, the note dialog, the
definition sheet, the jump-back and catch-up pills, and the activity's
own dialogs: an offer nobody has answered is about the page as it was.

**Effectively scrolled** is `scrollMode || verticalText`, not
`scrollMode`: Readium cannot paginate lines that run down the page, so
it scrolls such a book whatever the preference says. The same derivation
feeds `ReaderTapZones`, `ScrollEdgeTurner` and `PageTurner`, which read
the preference alone before — without that, a tap on a vertical book
turns a page instead of pausing, and this pause does not work at all.

**Chapter boundaries.** Reaching the bottom is not the moment to leave;
the last lines have only just arrived. The loop dwells there for one
screen's worth of reading time at the reader's pace, then asks
`PageTurner.stepChapter`, which is what a hand drag past the edge asks
too. It *reads the answer*: false means there was nowhere to go, and the
loop disarms rather than sitting against the edge. On true it waits for
the resource to actually change — not a fixed delay, which would step a
slow chapter twice and skip one shorter than a screen — and that wait,
like every other, is bounded and cancellable.

**Position saving does need something new**, contrary to the first
draft. Readium answers a scroll with a *debounced* location notification
— a hundred milliseconds of stillness. A finger drag always ends, so
that debounce always lands. A page that never stops never lands it, and
the reader's place would stay where they last lifted a finger. So the
loop asks the navigator itself, every couple of seconds, through
`firstVisibleElementLocator` — the same question the debounce would have
asked, asked on time, and asked off the frame loop so the page does not
hitch. Those go in as `READER_MOVEMENT`: auto-scroll is reading, so it
should teach the pace estimator and count as time spent.

Leaving the book is the one case that needs its own note.
`ReaderViewModel` drops a `READER_MOVEMENT` locator once the reader is
inactive, and `ReaderActivity.onPause` clears that flag before
`super.onPause()`, so neither Readium's late debounce nor anything the
loop publishes on `ON_PAUSE` can arrive as movement. The last position
the loop fetched is therefore republished from an `ON_PAUSE` observer as
`LOCAL_JUMP`, which persists, is not dropped by that guard, and does not
count the same reading twice. It asks the page nothing and waits for
nothing, so it is a synchronous call that has returned before `onStop`
can begin to close the position queue.

**Speed** is one shared `Float` in the reader DataStore, a step from 1
to 10 mapped to dp per second and multiplied by the font size, so
*lines* per minute stay roughly constant across text sizes. It lives in
`ReaderPrefs` alongside the typography, which means the flow that maps
those to `EpubPreferences` gains a `distinctUntilChanged()` — without
it, dragging the slider would reflow the whole book once per notch for a
setting the book cannot see.

## Consequences

The reading-speed estimator will see unusually steady progress; that is
genuine reading and needs no special-casing. The screen must not sleep
mid-scroll, so starting auto-scroll implies keep-screen-on for the
session regardless of the toggle.

The reader gains no new chrome: the page moving is the state, and the
switch that started it is where it stops.

*Where:* `reader/chrome/AutoScroll.kt`, `data/settings/ReaderPrefs.kt`,
`reader/ReaderScreen.kt`, `reader/chrome/AdvancedSheet.kt`,
`reader/chrome/TypographySheet.kt`, `reader/chrome/ReaderTapZones.kt`.
