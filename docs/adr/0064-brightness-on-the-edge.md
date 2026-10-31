# 64. Brightness on the edge of the page

Status: accepted

Related: [ADR 22](0022-pinch-on-the-page.md) (pinch to resize), which
ruled out a vertical edge drag for size.

## Context

Changing the brightness meant opening the Aa sheet, which takes the
reader off the page to make a small adjustment, often several times in
an evening (#393). Other readers answer this with a swipe up or down the
edge of the page.

The edge is already busy. A tap there turns the page, a long press
selects or opens a picture, and under Lift or None a sideways drag from
the edge curls the page (`PageTurnDrag`). In scrolled reading a vertical
drag is how the page moves, and `ScrollEdgeTurner` reads drags past the
end of a chapter.

## Decision

A one-finger swipe up or down a strip along one edge changes the
brightness. Up is brighter. The edge is a setting: Off, Left or Right,
Left by default. The physical edge is used whatever the book's reading
direction. The choice sits in the reader's Advanced sheet, beside the
page-turn style and shown only for turned pages, and under Advanced in
Reading & navigation beside pinch to resize. The brightness row in the
Aa sheet stays as it was. It is a device setting: in
the backup, not synced (see `SyncableSettings`).

Only in paginated reflowable reading, under the same conditions as the
page-curl drag (`pageGesturesFree`): no chrome, no selection, no picture,
no pinch, not scrolled, not fixed layout. A scrolled book needs vertical
drags for scrolling, and a fixed-layout page is dragged to look around.

The strip is 10% of the page's width, kept between 32 and 56 dp, and
leaves out the top and bottom system gesture bands (the shade and the
home swipe). It does not ask for gesture exclusion: that would take Back
away from the edge, and the bars are meant to stay sticky.

The touch is decided once, on the first event past the touch slop, just
before `PageTurnDrag` decides on the same event. It is a brightness swipe
when it landed in the strip, set off at least twice as steep as it went
sideways, and has not been resting for a long press. Otherwise the edge
gives up the touch for good and everything else runs as before, so taps,
long presses and sideways turns that start in the strip are untouched.

A claimed touch is consumed until every finger lifts. Consuming in the
pointer loop's Initial pass makes the web view see the touch cancelled,
so Readium raises no tap and no long press, as with the page curl and
the image viewer. A second finger is not a pinch. No timed guard is
needed after the lift.

Readium's script reports a drag start a few pixels in, before the slop.
While a touch is a candidate or a swipe, the reader's own drag listener
ignores it, so read aloud does not take a brightness swipe for the reader
moving the page.

The window follows the finger and the setting is saved once, on lift.
The preview stays until the saved value comes back, so the screen does
not drop to the old brightness for a frame. `ScreenBrightness` writes the
window only when the value changes and hands it back to the system only
when the reader closes. Brightness is not a Readium setting, so a change
never reflows the page.

A swipe crosses the whole range in 60% of the page's height. The value is
rounded to the percent the pill shows. When the reader follows the system
brightness, a swipe starts from the system setting read as 0-255. That is
approximate, since some phones use another scale and Android maps it on a
curve, but it is close enough not to jump.

## Ruled out

**A Readium `InputListener`.** Its drags start at a few pixels and it
cannot cancel the web view's long press or tap.

**Scrolled reading.** A vertical drag at the edge there would stop the
page scrolling, which is the reading itself.

**Both edges at once.** That would leave no edge for a reader who holds
the phone by it.

**Saving on every frame.** It means a DataStore write per frame, and
nothing reads the saved value until the next touch.

## Consequences

Starting a sideways page turn inside the strip only counts as the
reader's own move once the slop decides it.

The starting point from "follow system" is approximate on phones whose
brightness scale is not 0-255.

The gesture is invisible on a screen reader, which explores by touch.
The slider stays the accessible way.

*Where:* `reader/chrome/BrightnessSwipe.kt`, `reader/chrome/FontSizeHud.kt`
(`BrightnessHud`), `reader/ReaderScreen.kt`, `data/settings/AppSettings.kt`
(`EdgeBrightness`), `ui/reading/ReadingAppearanceControls.kt`,
`reader/chrome/AdvancedSheet.kt`, `ui/settings/ReadingNavigationScreen.kt`.
