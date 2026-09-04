# 22. Pinch on the page

Status: accepted
GitHub issues: [#139](https://github.com/chmouel/liseur/issues/139),
[#110](https://github.com/chmouel/liseur/issues/110)

## Context

Two requests want the same two fingers on the same surface, so neither
can be answered on its own.

**Font size is a round trip.** It lives on the Size slider in the
typography sheet and on Settings -> Reading appearance, and both of them
cover the text in order to offer the control that resizes it. A reader
who thinks the page is slightly too small has to notice, open the sheet,
drag a slider they cannot see the effect of, close the sheet, find their
place again, and then decide whether it was enough. Often it was not.

**Images cannot be enlarged at all.** An EPUB renders a map, a diagram
or a plate at whatever width the column gives it, and on a phone that is
frequently too small to read. There is nothing to tap and nothing to
stretch.

Both are the pinch. Which means the first question is not how to resize
text or how to zoom an image, it is what a pinch on a page of a book
*means* — and that has one answer, not two.

The constraint that shapes everything after it: Readium 3.3.0 has no
pinch. This cannot be a navigator listener, so it is a Compose gesture
intercepted ahead of the web view, which is a sharper instrument than
this codebase usually reaches for.

## Decision

A two-finger pinch on the page is a reading gesture, and there are two
of them.

1. **What is under the fingers when the gesture begins decides which
   one it is.** If the gesture starts on an image, it zooms that image.
   Anything else resizes the text.
2. The rule is settled when the second finger lands and is **not
   revisited** while the fingers move.
3. The text size snaps to the positions the Size slider already offers,
   and commits **once**, when the fingers lift.
4. During the gesture a small pill shows "Aa" drawn at the size being
   landed on.
5. On a fixed-layout book the resize half **refuses out loud**. The
   image half works there in full.
6. An image also opens from a long-press on it.
7. The resize half can be switched off in Settings -> Reading &
   navigation. The image half cannot.

## What Readium actually does

Read off the shipped Readium 3.3.0 classes and off a running emulator
rather than off the documentation, the way [ADR 2](0002-typography-fine-tuning.md)
and [ADR 20](0020-fixed-layout-reading-settings.md) were.

**`InputListener` is three methods.** `onTap(TapEvent)`,
`onDrag(DragEvent)`, `onKey(KeyEvent)`. There is no pinch and no long
press, and `EpubNavigatorFragment.Listener` extends only
`OverflowableNavigator.Listener` and `HyperlinkNavigator.Listener` —
links and jumps, nothing about images. So neither gesture can be a third
listener beside `ReaderTapZones` and `ScrollEdgeTurner`, and the Compose
`pointerInput` over the reading box is the only place a second finger is
visible at all. `evaluateJavascript` is public and suspending, which is
the door `WideContentFit` already goes through to ask the book's own
document a question.

**Readium turns the web view's own zoom on, and then swallows it.**
`R2EpubPageFragment` calls `setSupportZoom(true)`,
`setBuiltInZoomControls(true)` and `setDisplayZoomControls(false)`, so
on paper the pinch is already claimed by the platform. It is not. A
genuine two-finger pinch injected on an emulator — multitouch protocol B
through `sendevent`, twenty-four steps, fingers travelling from 200px
apart to 680px apart across the middle of a page — left the screen
byte-identical, with a single-finger tap injected the same way as the
control, and that one turned the page. `R2WebView.onTouchEvent` consumes
the multi-touch stream before the zoom manager ever sees it.

The gesture is therefore unclaimed and free to take. That is *behaviour*
rather than a contract, so a Readium upgrade is a thing to re-test, not
a thing to assume.

**`fontSize.isEffective` is gated on `layout == REFLOWABLE`.** The row
already in ADR 20's table, and the reason point 5 exists: on a
fixed-layout book, committing a new size would change nothing at all.

## Design

### The rule is settled at the second finger

A gesture whose meaning changed while the fingers moved is a gesture
nobody can aim. So the page is asked what is under the centroid once, as
the pinch begins, and whatever it answers holds until the fingers lift —
even if they wander off the image, even if the text they wander onto
would have resized.

### Where the gesture lives, and what it consumes

The existing `pointerInput` at `PointerEventPass.Initial`
(`ReaderScreen.kt`), which already tracks `fingerDown` for auto-scroll
and `lastTouchY` for the footnote card, and already bails out while a
note is showing. `Initial` is ahead of the web view, which is what makes
the interception possible and also what makes it dangerous: events are
consumed **only once a second pointer is down**. Consuming earlier would
take the ordinary tap away from the page, and the ordinary tap is how
the book is read.

### The size commits when the fingers lift

`ReflowScope` serialises reflows behind a mutex and restores the
reader's place around each one, which is the machinery that came out of
#63. Preferences reach it through a `distinctUntilChanged().collect`,
and the comment there already explains why a slider dragged through its
notches must not commit per notch: every intermediate value is a reflow
and an anchor-and-restore of a book the reader is holding still.

A pinch is that slider with no `onValueChangeFinished`, so the commit
has to be put back by hand. One gesture, one commit, one reflow, one
anchor. Everything before the lift is a preview drawn by Liseur, and the
book underneath does not move.

### It snaps to the slider's own positions

The eighteen positions are derived once from `ReaderPrefs.MIN_FONT_SIZE`,
`ReaderPrefs.MAX_FONT_SIZE` and the slider's step count, in one place
that the sheet and the gesture both read, so the two cannot drift into
offering different sizes for the same book. Snapping also bounds how far
a single gesture can travel and gives the pill something discrete to
show, instead of a continuously sliding sample that never settles.

A dead zone at the start keeps a two-finger rest — a thumb and a
finger holding the phone — from being a resize.

### The pill is the text, not a number

"Aa" drawn at the size being landed on, so the reader sees the answer to
the question they are actually asking, which is not "what multiplier is
this" but "can I read that". Painted in the reading theme rather than in
Material colours, for the reason `FootnoteCard` is: a white card over a
black page at night is a lamp in the face. Animation-free under
`LocalEInk`.

### A fixed-layout book refuses out loud

ADR 20's argument applied to a gesture. A control that silently does
nothing does not read as "this book carries its own layout", it reads as
broken — and a gesture is worse than a slider here, because the reader
cannot even tell whether the app saw the pinch. So the same pill appears
carrying the same line the typography sheet already shows, and nothing
is committed. A pinch on an image in that book still zooms, because
nothing about a fixed layout stops an image being too small.

### Asking the page what is under the fingers, without stalling them

`document.elementFromPoint` at the gesture's centroid, walking up for an
`<img>` or an SVG `<image>`, answered as JSON — the shape `WideContentFit`
established. The point is CSS pixels in the web view's viewport, so the
Compose position is offset by the web view's screen rect and divided by
the display density.

`evaluateJavascript` is asynchronous and the fingers are already moving,
so the answer has to arrive before it is needed. Two things make that
true. First, a `document.images.length` probe run **once per resource**:
on a page of plain text, which is most pages of most books, no script
runs on touch at all and the pinch goes straight to resize. Second,
where the probe says the resource does have images, the hit test is
fired on the **first** pointer down, so the answer is usually already in
hand when the second finger arrives. Only if it is not does the gesture
hold undecided — consumed, committing nothing — for a short budget, and
if the budget runs out it falls into resize with the span measured from
that moment, so the size does not jump.

### Which images count

Two filters, because either alone gets it wrong.

A **minimum rendered size**, which catches the unlabelled ornaments:
drop caps, inline rules, the 16px dingbat between scenes. And a short
vocabulary of **decorative `epub:type` roles**, which catches the
labelled ones. The second exists because of the imprint page of every
Standard Ebook:

```html
<img alt="The Standard Ebooks logo."
     src="../images/logo.png"
     srcset="../images/logo-2x.png 2x, ../images/logo.png 1x"
     epub:type="z3998:publisher-logo"/>
```

That logo renders around 76dp wide — comfortably above any threshold
meant to exclude an ornament, and still not something anyone wants a
full-screen viewer for. The markup says outright what it is. Judging an
element by what it *is* rather than by how big it happens to be is the
same move `FootnoteResolver` already makes for notes.

The same snippet settles the source attribute. On any screen above 1x
the browser resolves `srcset` and displays `logo-2x.png`; reading `src`
would hand the viewer `logo.png`, the *lower*-resolution file, which is
the exact opposite of what zooming is for. The script reads
`currentSrc` — the URL the page actually resolved — and falls back to
`src` only when there is none.

`alt` is a caption the book has already written, so the viewer shows it.

### The viewer is the app's page, not the book's

A full-screen overlay, not an in-place zoom of the web view. An in-place
zoom fights the paginated column layout, has nothing to dismiss it, and
leaves the reader pinching their way back to exactly 1.0 before the page
turns properly again. The overlay is also the only place `BackHandler`
means anything.

The bytes come from `publication.get(href)?.read()` on
`Dispatchers.IO`, inside the suspending function that blocks, and Coil
does the downsampling. `ResourceAddress` gains a public `href()`
extracted from its existing private `path()`, so the one spelling of
"turn a `readium_package` URL into a publication href" is shared rather
than copied.

### Long-press, and why not the web view's own

Detected in the same pointer loop — one pointer, no travel, past
`viewConfiguration.longPressTimeoutMillis` — rather than through
`setOnLongClickListener`, which would fight whatever Readium has on the
web view and would sit in front of text selection. The pointer is
consumed only once the answer says image, so a long press on text still
selects it.

### What the other two gestures must not do

`ReaderTapZones` and `ScrollEdgeTurner` read an `isPinching` lambda,
the same trick [ADR 9](0009-tap-zone-customization.md) used so that
changing a preference does not tear down and rebuild the navigator's
input listeners. A second finger arriving a beat late must not turn a
page on the way in, and lifting out of a pinch must not leave a stray
tap behind.

### The opt-out covers the resize half only

It is for a grip that produces stray two-finger touches, and the two
halves fail differently there. A stray resize silently changes how every
page looks from then on, and the reader has to work out what happened. A
stray viewer is one visible thing, dismissed with a tap. So the switch
is named for what it does — "Pinch to resize text" — and sits in
Settings -> Reading & navigation beside the other rows about how the
book is turned and held, not in Reading appearance: it is not how the
page looks, it is what the hands do (ADR 1, ADR 9).

## Ruled out

**Zooming the page of a fixed-layout book.** A pinch there could
plausibly magnify the publisher's page. That is a different feature
wearing the same gesture, with its own questions about panning, page
turns and where the zoom resets, and taking it now would mean the
gesture means three things instead of two.

**In-place web view zoom for images**, for the reasons above.

**Leaving it on the slider.** It works and it is discoverable. It also
costs the round trip that is the complaint. The slider stays either way;
the gesture is an addition, not a replacement.

**A vertical edge drag for size.** Collides with `ScrollEdgeTurner` and
with scroll mode, and is not the gesture readers reach for.

**Double-tap cycling through preset sizes.** Double-tap is wanted
elsewhere, and cycling suits a three-way choice, not eighteen steps.

**A per-book pinch preference.** The gesture is about the hand, not the
book. Per-book typography stays where it is.

## Consequences

A gesture is not discoverable. That is why the slider stays, and why the
pill has to be legible the first time it appears.

Someone's grip will produce a stray pinch. That is what the opt-out is
for, and it is also why the dead zone exists.

Consuming at `PointerEventPass.Initial` is ahead of the web view, so a
mistake there costs ordinary taps rather than degrading gracefully. The
"only once a second pointer is down" rule is load-bearing.

The undecided window is the one place this can feel like lag, on a
picture-heavy page where the hit test has not answered yet.

The minimum size will be wrong for somebody's book in one direction or
the other, and the decorative vocabulary only knows the roles it has
been told about.

Readium's swallowing of multi-touch is behaviour, not contract. A
toolkit upgrade is a thing to re-test.

*Where:* `reader/ReaderScreen.kt`, `reader/chrome/PinchResize.kt`,
`reader/chrome/FontSizeHud.kt`, `reader/chrome/ImageViewer.kt`,
`reader/ImageAtPoint.kt`, `reader/ResourceAddress.kt`,
`reader/chrome/ReaderTapZones.kt`, `reader/chrome/ScrollEdgeTurner.kt`,
`reader/ReaderViewModel.kt`, `data/settings/AppSettings.kt`,
`ui/settings/ReadingNavigationScreen.kt`.
