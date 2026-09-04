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

**`EpubNavigatorFragment.evaluateJavascript` answers `null` on a
fixed-layout book.** Not an error and not `"false"` — nothing at all,
which reads as "this page has no images" and would refuse the viewer on
every page of a book that is very often nothing but images. So the
script is put to the web view directly, through
`WebView.evaluateJavascript`, which the same code already has in hand
because it needs the view's bounds to place the point anyway. Readium's
own method is not used here.

## Design

### The rule is settled at the second finger

A gesture whose meaning changed while the fingers moved is a gesture
nobody can aim. So the page is asked what is under the first finger
once, as the touch begins, and whatever it answers holds until the
fingers lift — even if they wander off the image, even if the text they
wander onto would have resized.

The first finger rather than the centroid, because the answer has to be
in hand *before* the second finger lands, and because a pinch that
starts on a picture starts with a finger on it: by the time there is a
centroid to measure, the fingers have already begun to spread, and the
midpoint between them may be sitting on the margin beside the very image
the reader is reaching for.

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

The count lives in `ReaderPrefs` and *both* controls read it: the
gesture derives its positions from it and the slider derives its `steps`
from it. Not one from the other and not each from a literal, because a
`Slider` counts the notches *between* its ends and a set of positions
counts the ends too, so writing the same intention twice gets it wrong
by one — and the whole point of snapping is that a book resized by the
gesture and then nudged by the slider does not jump. Snapping also
bounds how far a single gesture can travel and gives the pill something
discrete to show, instead of a continuously sliding sample that never
settles.

A dead zone at the start keeps a two-finger rest — a thumb and a
finger holding the phone — from being a resize. Fingers that spread and
then come home again have therefore asked for nothing, and the target is
*assigned* on every move rather than merged: a target kept from earlier
in the gesture, because the dead zone answered with nothing this time,
is a size the reader backed out of and would be committed on lift.

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

`document.elementsFromPoint` where the first finger landed — the whole
stack under the point, so a picture beneath a link, a caption's wrapper
or a transparent overlay is found without guessing which of them the
browser will report on top. Answered as JSON, the shape `WideContentFit`
established.

Where the stack holds no picture the script falls back to walking up a
few levels from `elementFromPoint` and looking *inside* each ancestor,
for the book that wraps its plate in something the hit stack reports
without reporting the plate. Anything found that way has to have the
point inside its own bounding box. That check is not a nicety: an
ancestor two levels up is usually the `<section>`, a `<section>`
contains the chapter's illustration wherever in the chapter that
illustration happens to be, and without it a pinch on a paragraph — or
on a plate's own caption — opens a picture from three pages away instead
of resizing the text.

The point crosses as a **fraction of the web view**, not as a pixel
count. A reflowable page is drawn at the display density and a fixed-layout
page is drawn at whatever scale it takes to fit the screen, so device
pixels over the display density are CSS pixels in one kind of book and
not the other; the script multiplies the fraction by
`window.innerWidth`, which is right in both.

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

The probe is optimistic while it is in flight: between a page turn and
its answer the resource is assumed to have images, because a wrong yes
costs one script evaluation on one touch and a wrong no is the feature
quietly not working on exactly the plate the reader has just turned to.
It is keyed by the resource as well as by the web view, since the pager
recycles a view from one chapter into the next.

The budget is asked in one place and consulted from two — the coroutine
that receives the answer, and the pointer loop on the next event after
it was written down. Checked in only one of them it is no budget at all:
the answer is stored either way, and whichever side does not check acts
on it. And it applies only to a touch a resize has already got hold of.
Fingers that take their time arriving at a picture are not late for
anything; a document that took half a second to answer, while those
fingers were already resizing, is.

"Has got hold of" is remembered on the touch, not read off the live
pinch. A pinch is forgotten the instant it drops to one finger, but the
touch runs on until the last finger leaves — so reading the live pinch
would make a resize that ran long count as unclaimed again the moment a
finger lifted, and a replacement finger landing then would open the
picture the *first* finger had been over, minutes of travel ago. The
flag is set when a resize takes the gesture and cleared only when a
genuinely new touch begins.

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

The role is read off the picture **and off the few elements above it**,
because the word that matters is often on the section rather than on the
image: a title page's `<img>` says nothing, its `<section>` says
`titlepage`. And it is read from `class` as well as from `epub:type`,
because by the time the document is on screen the attribute has been
rewritten into a class name and looking only for the attribute finds
nothing at all.

The same snippet settles the source attribute. On any screen above 1x
the browser resolves `srcset` and displays `logo-2x.png`; reading `src`
would hand the viewer `logo.png`, the *lower*-resolution file, which is
the exact opposite of what zooming is for. The script reads
`currentSrc` — the URL the page actually resolved — and falls back to
`src` only when there is none.

`alt` is a caption the book has already written, so the viewer shows it.

### Everything the book says is a size the book chose

This reader opens whatever file it is pointed at, so the document on the
other side of `evaluateJavascript` is not friendly by construction, and
three things are bounded because of it. The answer's address and caption
have ceilings, since a book can inline a plate as a `data:` URL and
would otherwise hand back megabytes across the bridge on every touch.
The picture itself is read as a **range** rather than whole: an archive
entry's declared size is the archive's word for it, and a two-finger
touch is not much to ask of a reader before it exhausts its heap. That
range is the entry's own declared length wherever the container knows
it, because Readium answers a range that runs off the end of a
*compressed* entry with a decoding failure rather than clamping it — the
useful bound and the safe one are the same number here only because the
declaration is checked against the limit first and what comes back is
measured again afterwards. The declaration bounds the request; it is
never trusted for what it says arrived. And the evaluation itself
has a timeout, because a renderer that hangs or a process that goes away
never calls the callback at all, and the touch that asked would wait for
it forever with its job still in flight when the next one starts.

None of this is about injection. The script interpolates only numbers
this app computed. It is about what comes back.

### The viewer is the app's page, not the book's

A full-screen overlay, not an in-place zoom of the web view. An in-place
zoom fights the paginated column layout, has nothing to dismiss it, and
leaves the reader pinching their way back to exactly 1.0 before the page
turns properly again. The overlay is also the only place `BackHandler`
means anything.

The bytes come from `publication.get(href)?.read(0 until limit + 1)` on
`Dispatchers.IO`, inside the suspending function that blocks, and Coil
does the downsampling. `ResourceAddress` gains a public `href()`
extracted from its existing private `path()`, so the one spelling of
"turn a `readium_package` URL into a publication href" is shared rather
than copied.

**Paper under the ink.** A great many book illustrations are black line
art on a transparent background — Standard Ebooks marks them
`se:image.color-depth.black-on-transparent` — and on a black scrim those
are not dimmed, they are gone. So a white rectangle is drawn behind the
picture's own fitted rectangle. Behind the fitted rectangle rather than
behind the screen, so a photograph covers it completely and never shows
a white border; white rather than the reading theme's paper, because the
overlay is not the page and because a transparent image in an EPUB is
ink, not chalk.

**Modal to a screen reader as well as to a finger.** Drawn as the last
child of the reading box rather than in a window of its own, so that it
inherits the reader's immersive bars instead of fighting them — and so
nothing under it is reachable by touch, because the pointer loop stops
there. A screen reader is not steered by the pointer loop. Left alone it
walks past the picture into a chapter and a row of controls that are not
on screen, which is worse than useless: it is a control the reader can
operate and cannot see. So the page and the chrome are hidden from
accessibility while the viewer is up, the way `Endpaper` already hides
what it has not revealed, and the overlay carries a pane title.

The cost of drawing it as a sibling rather than in its own window is
that being hidden has to be *applied*, once per sibling, and a window
gets it for free. The bookmark ribbon, the footer, the note card, the
selection bar and the size HUD all carry it for that reason. Anything
new added to the reading box has to carry it too; a sibling that forgets
is a control a reader cannot see and can still reach. The trade is
deliberate — a separate window would mean re-deriving the immersive bar
handling this screen is careful about — but it is the fragile half of
this decision, and the place to look first if a control ever turns up
where it should not.

### Long-press, and why not the web view's own

Detected in the same pointer loop — one pointer, no travel, past
`viewConfiguration.longPressTimeoutMillis` — rather than through
`setOnLongClickListener`, which would fight whatever Readium has on the
web view and would sit in front of text selection. The pointer is
consumed only once the answer says image, so a long press on text still
selects it.

Consumed for the *rest of that touch*, and not only for the event that
opened the viewer. A long press on an image is also a long press as far
as the web view is concerned, and the web view answers one by starting a
drag of the picture — so without the claim the viewer opens over a drag
shadow the reader never asked for.

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
elsewhere, and cycling suits a three-way choice, not nineteen steps.

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
been told about. The ceiling on how large a picture may be will be
wrong for somebody's book too — a genuine 20MB plate is refused in
silence, which is the price of not being able to tell one from a hostile
archive entry before reading it.

Requiring the point to fall inside the picture's own box costs the case
where a book draws a plate under something opaque that the hit stack
does not report. Nobody has one; a pinch on a paragraph opening an
illustration from elsewhere in the chapter is not hypothetical at all.

Readium's swallowing of multi-touch is behaviour, not contract. A
toolkit upgrade is a thing to re-test.

*Where:* `reader/ReaderScreen.kt`, `reader/chrome/PinchResize.kt`,
`reader/chrome/FontSizeHud.kt`, `reader/chrome/ImageViewer.kt`,
`reader/ImageAtPoint.kt`, `reader/ResourceAddress.kt`,
`reader/chrome/ReaderTapZones.kt`, `reader/chrome/ScrollEdgeTurner.kt`,
`reader/ReaderViewModel.kt`, `data/settings/ReaderPrefs.kt`,
`data/settings/AppSettings.kt`, `ui/reading/ReadingAppearanceControls.kt`,
`ui/settings/ReadingNavigationScreen.kt`.
