# 25. Three page turns, and an Advanced section to keep them behind

Status: accepted
GitHub issue: [#156](https://github.com/chmouel/liseur/issues/156)

## Context

A reader coming from Libby asked for its page turn: the two pages travel
together, edge to edge, the way a photograph slides under a finger.
Liseur's turn lifts the finished page off the one underneath instead,
which is Kindle's motion and was chosen deliberately.

The answer was already in the app twice over. Dragging the page across
with a finger gives exactly the movement asked for — that is Readium's
own animated move, and the reporter said so: they wanted it *on a tap*,
not only on a drag. And `PageTurner` already performed all three
motions, because the lifted page falls back to the plain slide whenever
the snapshot cannot be taken, and to no motion at all when the reader
had turned the animation off. What was missing was a way to ask for one.

So the setting was a boolean with three behaviours behind it, two of
which nobody could choose.

## Decision

`ReaderPrefs.pageTurnAnimation: Boolean` becomes
`pageTurnStyle: PageTurnStyle`, with three values named for what the
reader sees:

- **Lift** — the finished page comes off the stack. The default, and
  what the app has always done.
- **Slide** — the navigator's own move: both pages travel together, the
  motion a drag already gives.
- **None** — the next page is simply there.

`PageTurner` takes a `style: () -> PageTurnStyle` in place of its
`isAnimated: () -> Boolean`. Nothing else about it changes: the lifted
page keeps every fallback it had, since a snapshot that cannot be taken
is still a turn that has to happen.

Electronic paper overrules the choice with **None** in that one lambda,
where `&& !eInkNow` used to sit. A trail of half-erased pages is what a
photograph dragged across such a screen actually looks like, and that is
a fact about the panel, not a preference.

The endpaper animates only under **Lift**. It is drawn over the book
rather than navigated to, so there is no navigator move for **Slide** to
animate — it would have to be faked, and a faked slide onto a screen
that is not a page is worse than no slide.

A scrolled book has no page to lift, so it reads the one question it can
answer: **None** jumps, the other two glide.

### The drag answers to the setting as well

Readium's reply to a sideways drag is the slide, always: the columns
follow the finger and snap when it lifts. That is one of the three
styles, so a reader who asked for the lifted page or the instant jump
got the slide back the moment they used a thumb instead of tapping —
the same book turning two different ways depending on how it was
touched.

`PageTurnDrag` claims the gesture under **Lift** and **None** and hands
it to the same `PageTurner.turn` a tap uses.

Claiming it means taking the touches themselves, in Compose. Readium's
gesture script does ask before it moves anything, and a `true` from an
`InputListener` becomes a `preventDefault()` — but that governs only the
JavaScript. The columns are moved by `R2WebView`'s own native gesture
code, with its own slop, velocity tracker and scroller, which answers to
nothing the page or the navigator can say; a drag refused in JS still
turned the page, and at a resource edge turned two. The one thing above
it is the pointer loop `ReaderScreen` already runs over the reader —
`awaitPointerEvent(PointerEventPass.Initial)`, the same loop the image
viewer uses to swallow a pinch. Consuming there cancels the touch on the
way down, so neither the web view nor the pager ever sees it. That makes
`PageTurnDrag` a plain state machine — `offer`, `release`, `reset` — with
no Android or Readium types in it at all, and testable as arithmetic.

What is lost under those two styles is the finger tracking: the page no
longer travels with the thumb and cannot be pulled halfway and put back.
It becomes a swipe, committed on release after 48dp. That is the honest
reading of the setting — a reader who asked for no motion cannot also
have the page follow their finger — and **Slide**, which is that
tracking, is one tap away.

#### Amended: the page follows the finger after all

> Amended by [#176](https://github.com/chmouel/liseur/issues/176). The
> paragraph above was wrong about what the setting says. A reader who
> chose the lifted page chose what a *tap* does with it; they did not
> ask for a thumb that pushes nothing. Losing the tracking was a cost
> of how the claim was built, and it read on the phone as the gesture
> having stopped working — the page sits still under the finger and
> then jumps when it leaves.

So under **Lift** and **None** the drag now curls the departing page off
the book, and the curl follows the finger: it can be pulled halfway,
held there, and put back. **Slide** is unchanged and still Readium's
own drag, which is that tracking already.

The curl is a page turn that has already happened underneath. The
machinery the rejected alternative below was refused for turns out to
be the machinery already written for the lift: `PageTurner` photographs
the page with `PixelCopy` and jumps the navigator with `animated =
false`, and the snapshot is drawn over the live page. The drag does the
same, and the difference is only what moves the snapshot — a finger
rather than a tween — and that it can be put back. Nothing is held at
touch-down: the snapshot is taken when the drag is claimed, which is
after the slop and after the direction is read, so a selection or a
pinch never causes one to be taken at all.

`reader/chrome/PageCurl.kt` is the geometry, and it is arithmetic:
vertices and per-vertex colours for `Canvas.drawBitmapMesh`, which is
hardware-accelerated back to API 18 where `drawVertices` only arrives at
29. The page wraps a cylinder whose radius broadens as the turn opens
and tightens as it completes, the fold tilts with the row the finger
grabbed and with how far down it has since travelled, and the back face
is a second pass of a plain paper tile so the text shows faintly through
it the way thin paper does. `PageCurlOverlay.kt` draws it and
`PageTurnDrag` is still the state machine, now with the curl behind an
interface so that all of it stays testable as numbers.

Releasing commits the turn past about 40% of the width, or on a fling
toward it; anything less puts the page back, and a fling back cancels
from anywhere.

**Electronic paper keeps Readium's drag**, as it had before this ADR.
The reasoning that gives it **None** for a tap is exactly the reasoning
that refuses it a curl: a photograph dragged across such a panel is a
trail of half-erased pages, and a curled one is a worse trail. But the
swipe that stood in for it here was no better a fit — a gesture that
does nothing until it is released, on the screen least able to explain
why — and Readium's own drag is what e-paper had before and handles
itself. Since `turnStyle` overrules e-ink to **None** in one lambda and
a chosen **None** cannot be told from an imposed one, the claim reads a
separate `interactive` predicate rather than the style.

The swipe does not go away. It is what a refused curl falls back to:
the last page, whose turn finishes the book and must not be tentative,
so it is probed for first; the first page going back; a snapshot that
could not be taken; anything drawn over the book.

The claim is decided once, on the first move past the touch slop, and
only for a drag that sets off across the page. That decision then stands
for the rest of the gesture: a drag that set off downwards stays the web
view's however it curves later, because taking it over halfway through
would cancel a touch the web view is already acting on. A second finger
settles it the same way, so a pinch is untouched, as is a selection being
stretched and a scrolled book being scrolled. A fixed-layout page, which
is dragged to look around rather than to turn, is left to Readium
entirely, and so is anything dragged while the chrome is up, where a
sideways drag belongs to the progress scrubber.

Storage is a new `page_turn_style` key. When it is absent the old
`page_turn_animation` boolean is read once — `false` means **None** — so
a reader who turned the animation off does not find pages lifting again
after an update. A stored style always wins, including one this build
has never heard of, so an older build cannot quietly overwrite a newer
choice.

### An Advanced section on Settings -> Reading & navigation

The reader's own sheet has had one since [ADR 1](0001-advanced-reading-menu.md):
the short list stays short and everything rarer lives a tap further in.
The settings screen had the same problem and no such answer — the page
that opens with "Volume keys turn pages" was eight rows deep in things
nobody changes twice.

So it gets a collapsible group at the bottom, closed on arrival, holding
the three settings a reader sets once if ever: the page turn, the
page-turn sides ([ADR 9](0009-tap-zone-customization.md)), and pinch to
resize ([ADR 22](0022-pinch-on-the-page.md)). Whether it is open is
screen-local state and is deliberately not remembered: a section that
stays open is not an advanced section, only a long one.

The page turn moves off Settings -> Reading appearance to get there.
That screen is how the page *looks*; how it gets out of the way when it
is turned is what the hands do, which is the split ADR 1 and ADR 9 draw
between the two screens. It stays in the reader's Advanced sheet, where
it always was.

## Alternatives

**A drag-to-turn gesture instead of a setting.** It already exists —
that is what the reporter was doing. The request was for the tap.

**Leaving the drag as Readium's under every style.** Simplest, and what
the first cut did. It also meant the setting quietly described only half
of how the book is turned.

**Animating the lift under the finger.** A page that tracks the thumb
and lifts on release, so nothing is lost. It needs the snapshot taken at
touch-down, held for the length of an open-ended gesture, and thrown
away if the drag turns into a selection or a pinch — a lot of machinery
for a style whose point is that the page comes off the stack, not that
it follows anything.

> Amended by [#176](https://github.com/chmouel/liseur/issues/176):
> built, as a curl rather than a lift. The objection was answered by
> not taking the snapshot at touch-down. It is taken when the drag is
> claimed, by which time a selection or a pinch has already gone
> elsewhere, so there is nothing to throw away.

**Interpolating the slide ourselves.** Readium animates its own move; a
second animation over the top of it would have to agree with the first
about duration, easing and direction, and would drift the moment the
toolkit changed either.

**Keeping the boolean and adding a second setting for the style.** Two
overlapping settings where one has three answers, and a state
("animation on, style none") that means nothing.

## Consequences

Three names have to describe three motions to someone who has not seen
them. Lift, Slide and None are the best short ones available, and each
carries a line of its own underneath.

The Advanced section hides three settings that were findable by
scrolling. The Settings landing page still names the current page-turn
side in its subtitle, which is what makes the section worth opening.

Pinch to resize moving into Advanced compounds its default going off
(ADR 22, amended): a reader who had it and liked it has to go looking.
It only ever shipped in release candidates, which is what made that
affordable.

#156 also asks for pages-left-in-chapter progress and a partial progress
bar. Not decided here.

> Amended by [#176](https://github.com/chmouel/liseur/issues/176): a
> curl that is put back is, underneath, a page turn and a turn back.
> The navigator has already moved by the time the finger is halfway,
> and the cancel is an exact `go` to the locator the turn started from
> — exact rather than a step the other way, so that a resource boundary
> the tentative turn crossed is crossed back to the same spot and not
> merely to the same side of it. Everything downstream sees both moves:
> the position is published twice, the pace estimator drops the
> backward one, a catch-up offer is dismissed. This is what a tap
> followed by a tap back already does, and it was accepted rather than
> given a tentative-move channel of its own, which would have had to
> reach every reader of a position. The tentative turn is kept off the
> paths that cannot be undone: `turn` and `stepChapter` do nothing
> while one is live, and the last page is probed before the page is
> ever photographed, so the endpaper is never reached by a turn that
> might yet be put back.
>
> A turn still in the hand when the reader leaves is put back at
> `ON_PAUSE`, and only there. That is the last moment the navigator can
> be driven at all: disposal is too late, because a rotation swaps the
> navigator and the one being let go of has already lost its fragments'
> views, so a `go` from a teardown throws. Disposal therefore drops the
> held page and forgets the turn without driving anything. The origin is
> published as a `LOCAL_JUMP` as well as navigated to, since the
> navigator's own answer arrives on a later frame that a closing reader
> is not obliged to give it — the same reasoning, and the same observer,
> as the held scroll position published there already.

*Where:* `data/settings/ReaderPrefs.kt`,
`data/settings/ReaderPreferencesRepository.kt`,
`data/settings/AppSettings.kt`, `reader/chrome/PageTurnEffect.kt`,
`reader/chrome/PageTurnDrag.kt`, `reader/chrome/PageCurl.kt`,
`reader/chrome/PageCurlOverlay.kt`, `reader/ReaderScreen.kt`,
`reader/chrome/AdvancedSheet.kt`,
`ui/reading/ReadingAppearanceControls.kt`, `ui/settings/SettingsRows.kt`,
`ui/settings/ReadingNavigationScreen.kt`,
`ui/settings/ReadingAppearanceScreen.kt`.
