# 9. Tap-zone presets

Status: accepted
GitHub issue: [#46](https://github.com/chmouel/liseur/issues/46)

## Context

The tap zones are fixed: the edge the book came from goes back, the
opposite edge goes forward, middle for chrome — on a book that reads
left to right, that is back on the left and forward on the right. A
left-handed reader holding a phone in one hand has the forward turn
under the wrong thumb every single page. The fix most readers actually
want is not a zone editor, it is "swap the edges".

## Decision

One two-way preference: **Standard** (today's layout, whichever way the
book reads) and **Swapped** (the other thumb — the sides the other way
round from whatever Standard gives that book).

Two, not the three this ADR first proposed. The dropped one was "both
edges forward", with going back left to the volume keys and the
scrubber. It is a real way to read, but it is a different kind of
answer: the other two say which hand is holding the phone, while that
one takes an action off the page and asks the reader to find it
somewhere else.

Fit with Liseur's simplicity: one chip row in **Settings → Reading**,
directly under "Volume keys turn pages". Not the typography sheet this
ADR first named, and not Reading appearance: a tap zone is not how the
page looks, it is how the page is turned, and the switch it belongs
beside is the one that decides what the volume keys do. It is also set
once, when the app is set up, which is what the reading sheet is
explicitly not for ([ADR 1](0001-advanced-reading-menu.md)).

No draggable zone editor, no per-zone action picker — that is a
settings hobby, and the two presets cover the readers who exist.

## Design

`ReaderTapZones.zoneAt` resolves a tap position to a *side of the
screen*, and stays that way: the ceilings it applies to the chrome zones
are geometry, and a preset that reorders the sides has nothing to say
about them. The mapping from a side to a direction moves into one pure
function, `ReaderTapZones.forward(zone, rtl, swapped)`, so the reading
page and the endpaper cannot come to different answers about the same
tap — the endpaper is a page, and had its own copy of that `when`.

The preset composes with reading direction rather than overruling it. An
RTL book turns forward on the left because that is where the next page
is; Swapped means "the other thumb" whatever the book does, so on an RTL
book it puts forward back on the right. Both reorderings at once are the
standard layout again, which is why the function is an equality and not
a pair of branches.

It lives in `AppSettings` beside `volumeKeysTurnPages` and `scrollMode`,
reaches the reader as a `StateFlow` on `ReaderViewModel`, and is read
through a lambda so that changing it does not tear down and rebuild the
navigator's input listeners.

The volume keys are untouched — they already give a physical
back/forward pair whatever the thumbs do. Scroll mode keeps its own
scroll-aware tap behaviour: the whole page is the chrome zone there, so
a scrolled book never resolves to a side and the preset has nothing to
reinterpret.

The endpaper is the exception, and deliberately. It is the app's own
page rather than the book's, is not scrolled by the navigator, and its
chrome zone does nothing at all — so its sides stay live whatever the
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
