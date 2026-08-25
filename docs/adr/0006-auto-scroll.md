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

**A place is not a distance**, and that was a bug for as long as this
existed. `firstVisibleElementLocator` answers with a selector and the
words at the top of the screen and nothing else: no progression, no
position. `BookPositions.resolve` reads a locator with neither as the
start of its resource, so every save auto-scroll made filed the reader
at the top of the chapter they were half way down — and that number is
the one the footer shows, the one the pace estimator learns from, and
the one calibre-web syncs outright and Komga and liseur-sync compare
when they disagree. Only local resume escaped it, because the anchor
saved beside the number was still exact.

So the distance is measured too, by `ScrollProgression`: the document's
own scroll offset over its own length. That is not an approximation of
Readium's convention but the inverse of it — `readium.scrollToPosition`
restores a fraction by multiplying it back out against the same span. A
page that cannot give one saves nothing that tick, because a place a
line behind is worth having and a place a chapter behind is not.

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

**A book scrolled by hand keeps a place the same way.** Its debounce
does land — a finger drag ends — but it lands after the reader was
marked inactive if they leave the moment they stop scrolling, and it is
then dropped as movement nobody made. So the same round trip runs for a
manually scrolled book, driven not by a frame loop but by the web view's
own scroll offset: a field read every couple of seconds, and the
document asked only when that offset has moved since the last look. An
offset that has not moved is a page at rest, and a page at rest has
already been answered for. The offset is sampled before the first wait
rather than after it, because the window a reader opens a book in is
exactly the window they scroll in.

That watcher runs for as long as the book is scrolled, armed or not,
because arming is not running: a finger on the page, the chrome up, a
dialog open — every one of those stops the carrying loop while leaving
the reader free to scroll by hand, and a gap between the two would be a
pause with nothing held. It only skips the round trip while the loop is
actually carrying the page, which is measuring the same thing anyway; it
keeps sampling the offset throughout, so the moment the loop stops there
is already a baseline to compare against.

One `HeldPlace` is shared between them, and both go through it: the
question of whether a measurement is still current is the same question
as whether it is worth publishing, so `hold` answers both and the two
cannot drift apart. A measurement takes several round trips into a
document the reader is still moving through, so it is held against a
mark taken before the asking began, and a mark that has moved on refuses
it. Without that, a reader who jumps *backwards* within the same chapter
can be carried forward again on the way out by a round trip that was
already in the air when they jumped; the href guard alone cannot see
that, because it is the same chapter.

Keeping a place moves the mark on too, so of two measurements begun in
the same generation the one that lands first is the one that stands. The
loser is refused rather than merged: they were asked for at different
moments and answered out of order, and nothing here can tell which the
reader is nearer to. A poll behind costs a line, and an older answer
landing last would walk the reader back.

What supersedes a held place is not the *announcement* of a location but
the capture of one, which is why the holder distinguishes invalidating
from retiring, and why that bookkeeping lives in the collector that
publishes rather than in a second one watching the same flow. A locator
Readium announces is captured before it is saved, and a capture
suspends: clear the held place when the announcement arrives and a
reader who leaves mid-capture has neither the new place — dropped as
movement by an inactive reader — nor the old one. So the announcement
only invalidates: measurements in flight are refused, the place already
held stands, and it is replaced once the new capture has actually been
taken. A reflow locator is the exception that retires outright, being
the layout moving rather than the reader, and not a place anyone should
be sent back to.

A place is retired for real when it stops being anyone's: a chapter the
reader has left, a navigator replaced underneath them, a book that has
stopped being scrolled. The holder is remembered across all three, so
each is told explicitly.

Two things are never asked at all. A fixed-layout book has no scroll
fraction that means anything — nothing reflows, and the document does
not move under the viewport — and a page being rebuilt by a preference
change is not a page the reader moved through, so an open reflow skips
the tick the way `ReflowScope` gates everything else.

**Speed** is one shared `Float` in the reader DataStore, a step from 1
to 10 mapped to dp per second and multiplied by the font size, so
*lines* per minute stay roughly constant across text sizes. It lives in
`ReaderPrefs` alongside the typography, which means the flow that maps
those to `EpubPreferences` gains a `distinctUntilChanged()` — without
it, dragging the slider would reflow the whole book once per notch for a
setting the book cannot see.

**What a frame costs.** A frame here does arithmetic on elapsed time and
one `scrollBy`, and nothing else, because everything else it used to do
had the same answer as the frame before. The pace is a curve over the
speed notch, the font size and the display density; all three are keys
of the effect that runs the loop, so it is computed on the way in and
cannot go stale without the loop being rebuilt around it. The web view
was found by walking the view tree and taking a global visible rect off
every web view in it, sixty times a second, so it is held between frames
by a `CachedLookup`.

Holding it is only safe if "current" keeps the meaning `visibleWebView`
gives it: the view covering the middle of the reader. Attachment will
not do — Readium keeps the neighbouring chapters attached to its pager,
so a chapter the reader has left stays attached and, to `isShown`,
shown. The check is therefore the cheap half of the same question, one
rect against one point. Mid-transition, when nothing covers the centre,
the search falls back to the largest visible view, which fails that
check, so the cache spends those frames looking again — the cost paid on
every frame before, now paid only while a chapter is arriving.

The held view is also dropped on a new position or a layout pass, the
pair this screen already watches for exactly this reason: Readium
reports arriving at a resource before laying it out, and a view that
does not exist yet cannot be found. Invalidating is free and idempotent,
so it can be said too often. A chapter *changing* is different news and
rides its own event — positions arrive as the reader moves within a
chapter, and the ticker's carried fraction and the place kept for the
pause may only be thrown away when the page they belong to has actually
gone.

**Someone else turning the page first.** The dwell at a chapter's end is
the one window where the loop is alive and waiting rather than moving,
and `ReaderActivity` sends volume and page keys straight to the turner
without raising the chrome — so auto-scroll stays armed and the dwell
stays alive. The dwell therefore notes which chapter it is waiting in
and does not step if it is somewhere else when the wait ends: the page
moved, which is what the wait was there to allow.

Two round trips into the document have the same shape of problem. The
position the loop saves is fetched and captured while the reader may be
leaving, so it is discarded unless the chapter it was asked of is still
open — a save dropped costs one tick, a save kept would file the old
chapter's place as the reader's place in the new one. The place kept for
the pause outlives the loop, so it is dropped on the way back in unless
it still points into the open chapter: a book moved elsewhere while the
reader was away moved with nobody watching.

## Consequences

The reading-speed estimator will see unusually steady progress; that is
genuine reading and needs no special-casing. The screen must not sleep
mid-scroll, so starting auto-scroll implies keep-screen-on for the
session regardless of the toggle.

The reader gains no new chrome: the page moving is the state, and the
switch that started it is where it stops.

*Where:* `reader/chrome/AutoScroll.kt`, `reader/chrome/CachedLookup.kt`,
`reader/chrome/HeldPlace.kt`,
`reader/chrome/ReaderWebViews.kt`, `reader/progress/ScrollProgression.kt`,
`data/settings/ReaderPrefs.kt`,
`reader/ReaderScreen.kt`, `reader/chrome/AdvancedSheet.kt`,
`reader/chrome/TypographySheet.kt`, `reader/chrome/ReaderTapZones.kt`.
