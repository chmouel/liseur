# 9. Tap-zone presets

Status: accepted
GitHub issue: [#46](https://github.com/chmouel/liseur/issues/46)

## Context

The tap zones are fixed: the edge the book came from goes back, the
opposite edge goes forward, middle for chrome. On a book that reads
left to right, that is back on the left and forward on the right. A
left-handed reader holding a phone in one hand has the forward turn
under the wrong thumb every single page. The fix most readers actually
want is not a zone editor, it is "swap the edges".

## Decision

One two-way preference: **Standard** (today's layout, whichever way the
book reads) and **Swapped** (the other thumb: the sides the other way
round from whatever Standard gives that book).

Two, not the three this ADR first proposed. The dropped one was "both
edges forward", with going back left to the volume keys and the
scrubber. It is a real way to read, but it is a different kind of
answer: the other two say which hand is holding the phone, while that
one takes an action off the page and asks the reader to find it
somewhere else.

Fit with Liseur's simplicity: one chip row in **Settings -> Reading**,
directly under "Volume keys turn pages". Not the typography sheet this
ADR first named, and not Reading appearance: a tap zone is not how the
page looks, it is how the page is turned, and the switch it belongs
beside is the one that decides what the volume keys do. It is also set
once, when the app is set up, which is what the reading sheet is
explicitly not for ([ADR 1](0001-advanced-reading-menu.md)).

No draggable zone editor, no per-zone action picker. That is a
settings hobby, and the two presets cover the readers who exist.

## Design

`ReaderTapZones.zoneAt` resolves a tap position to a *side of the
screen*, and stays that way: the ceilings it applies to the chrome zones
are geometry, and a preset that reorders the sides has nothing to say
about them. The mapping from a side to a direction moves into one pure
function, `ReaderTapZones.forward(zone, rtl, swapped)`, so the reading
page and the endpaper cannot come to different answers about the same
tap; the endpaper is a page, and had its own copy of that `when`.

The preset composes with reading direction rather than overruling it. An
RTL book turns forward on the left because that is where the next page
is; Swapped means "the other thumb" whatever the book does, so on an RTL
book it puts forward back on the right. Both reorderings at once are the
standard layout again, which is why the function is an equality and not
a pair of branches.

It lives in `AppSettings` beside `volumeKeyMode` and `scrollMode`,
reaches the reader as a `StateFlow` on `ReaderViewModel`, and is read
through a lambda so that changing it does not tear down and rebuild the
navigator's input listeners.

The volume keys are untouched: they already give a physical
back/forward pair whatever the thumbs do. Scroll mode keeps its own
scroll-aware tap behaviour: the whole page is the chrome zone there, so
a scrolled book never resolves to a side and the preset has nothing to
reinterpret.

The endpaper is the exception, and deliberately. It is the app's own
page rather than the book's, is not scrolled by the navigator, and its
chrome zone does nothing at all, so its sides stay live whatever the
reading mode, exactly as they did before this preset existed. Taking
them away in scroll mode would leave a reader who scrolls no tap at all
to get back into the book. The preset therefore reaches the endpaper
unconditionally, and the settings text's "no sides to tap" is about the
book's pages.

## Consequences

Two presets instead of a matrix means someone will ask for a third. The
bar for adding one is the same as for these: a hand position that
actually occurs, not a preference for novelty.

The setting is only in Settings, so a reader cannot try it from the "Aa"
sheet with the book in front of them. That is the cost of keeping the
sheet to what is changed often; a preset is chosen once and then
forgotten.

*Where:* `data/settings/AppSettings.kt`,
`reader/chrome/ReaderTapZones.kt`, `reader/chrome/Endpaper.kt`,
`ui/settings/SettingsScreen.kt`.

## Update

The Reading section it landed in later moved behind a row of its own,
so the chip row is now in **Settings -> Reading & navigation**
(`ui/settings/ReadingNavigationScreen.kt`), still directly under
"Volume keys turn pages" and still not in Reading appearance. See
`docs/adr/0001-advanced-reading-menu.md`. Both rows have since moved
together into that screen's Advanced section, since a reader sets them
once when setting up the app.

## Update: Off

A third preset, **Off**, was added later for a grip that keeps landing
on the edge of the page. It is not the "both edges forward" answer this
ADR rejected: it does not move an action, it takes one off the page
entirely. A tap on either side does nothing, while the centre and the
top strip still reveal the chrome; the volume keys, swipes and the
endpaper keep behaving as before. The endpaper stays live whatever the
preset because it is the app's own page, not the book's, and its chrome
zone does nothing — the same reason it ignores scroll mode.

The first version of Off returned before working out which zone was
tapped, so the centre and top strip went dead along with the sides and
the menu could only be reached by other means. The preset and the zone
are now resolved separately (`ReaderTapZones.action`): Off drops side
taps and keeps chrome taps, as described above.

## Update: side width

Issue [#403](https://github.com/chmouel/liseur/issues/403): on the
standard layout the forward side covers roughly half the page, so a
reader reaching for the menu (to get to read aloud) kept turning the
page instead. Off fixes that by taking side taps away entirely, which
is too much for someone who still wants to tap to turn.

A **Page-turn area** slider sits under the preset row. Its stops are
10, 15, 20, 25, 30 and 35 percent per side, and a last stop, **Wide**,
which is the original layout and the default. A percentage stop makes
both sides the same narrow edge and gives the whole band between them,
top to bottom, to the chrome; the top strip still opens the chrome.
The percentage is not capped in dp on wide screens the way the centre
box is, because the reader picked it as a share of the page.

This is still not the zone editor this ADR rejected: one number, no
per-zone actions, and it composes with Standard, Swapped and reading
direction unchanged through `forward()`. The slider is greyed out under
Off. Like the preset, the width is in the settings archive but not in
liseur-sync settings backup, since it depends on how this device is
held.

## Update: one side, and a page of its own

Swapped answered "which thumb" by moving forward to the other side, but
a one-handed reader still had to reach across the page to go back. The
sides choice is now **Both**, **Left only**, **Right only** and **Off**.
With one side, that side does both turns: its upper half (below the top
strip) goes back and its lower half goes forward, and the other side
opens the menu like the middle. The split is vertical, so reading
direction does not change it; Both keeps the old direction rule.

Swapped is gone. Its stored id, `swapped`, now means Left only: on most
books Swapped put forward under the left thumb, and Left only keeps it
there. Reusing the id means no migration, and an older build reading
it back gets Swapped, the closest thing it knows. Only Right only has a
new id, `right`; an older build reads it as Both and rejects a backup
that holds it.

The width slider applies to one side as well. A percentage stop is that
edge alone; Wide is that whole half of the page outside the centre box
and the top strip, not the 30% back side of Both.

`ReaderTapZones.actionAt` now holds the whole shape for every mode, and
the reading page, the endpaper and the settings drawing all ask it. The
endpaper maps Off to Both for the reason given above: it is the way
back into the book.

Both choices moved out of Advanced into a **Page taps** screen opened
from a row there. It shows a drawing of the page, sampled from
`actionAt` at this window's size, so the picture cannot disagree with
what a tap does. It follows the slider while it is dragged; the value
is saved on release.

*Where:* `ui/settings/PageTapsScreen.kt`,
`ui/settings/PageTapsPreview.kt`.
