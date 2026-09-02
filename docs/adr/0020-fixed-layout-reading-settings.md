# 20. Settings a fixed-layout book cannot use

Status: proposed
GitHub issue: [#123](https://github.com/chmouel/liseur/issues/123)

## Context

A fixed-layout EPUB is placed by the publisher, page by page. Nothing in
it reflows, and Readium honours no reflowable-text setting inside one:
`EpubPreferencesEditor` gates them all behind `layout == REFLOWABLE`.

[ADR 2](0002-typography-fine-tuning.md) disabled the six rows it added
when the open book is fixed-layout, and said so in a line above them.
The rows that were already there were left alone, deliberately, to keep
that change reviewable, and its consequences say as much. So today the
reader opening a fixed-layout book gets a menu where alignment is greyed
out and font size is not, though neither one does anything. Dragging the
size slider reflows nothing, changes nothing, and gives no reason why.

That is worse than either of the two consistent answers. A menu that
greys out half of what it cannot do reads as though the other half
works, and the reader's conclusion is not "this book carries its own
layout" but "this app's font size is broken".

## Decision

Every reading setting Readium honours only in a reflowable book is
disabled when the open book is fixed-layout, showing its stored value,
under **one line per sheet** saying the book carries its own layout. The
settings that still do something stay enabled, and this ADR records
which those are and why, so they are not tidied away later.

## What Readium actually does

Read off Readium 3.3.0's `EpubPreferencesEditor` rather than its
documentation, the same way ADR 2 was:

| Preference | `getIsEffective` | Liseur's control |
|---|---|---|
| `fontSize` | `layout == REFLOWABLE` | size slider, typography sheet |
| `fontFamily` | `layout == REFLOWABLE` | font picker, typography sheet |
| `lineHeight` | `layout == REFLOWABLE && !publisherStyles && …` | line spacing, Advanced |
| `pageMargins` | `layout == REFLOWABLE` | margins, Advanced |
| `columnCount` | `layout == REFLOWABLE && !scroll` | columns, Advanced |
| `scroll` | `layout == REFLOWABLE && !verticalText` | "read by scrolling", typography sheet |
| the six of ADR 2 | `layout == REFLOWABLE && …` | Advanced — already disabled |
| `backgroundColor` | `preferences.backgroundColor != null` | reading theme |
| `spread` | `layout == FIXED` | nowhere |

Two rows in that table are worth arguing about.

**`backgroundColor` carries no layout gate at all.** Readium's `theme`
is reflowable-only, so the obvious reading is that the theme swatches
belong in the disabled set with everything else. They do not. Liseur
does not rely on `theme` for its colours; it passes explicit
`backgroundColor` and `textColor`, because Readium's own palette is
close to ours but not identical and the difference shows as bands above
and below the text, and `EpubNavigatorFragment` calls
`resourcePager.setBackgroundColor(settings.effectiveBackgroundColor)`
whatever the layout. So the swatches visibly change the letterbox around
a fixed page, in every theme, and disabling them would take away the one
typographic control that still works in the book.

**`spread` is the only preference a fixed-layout book has that Liseur
exposes nowhere.** A fixed-layout book is very often two facing pages
laid out as a spread, and Readium can render it that way. Naming the gap
here is the whole treatment it gets: adding a control for it in this
change would widen the scope again, which is exactly the move that
produced this issue, and it has an issue of its own to arrive under.

The rest need no argument. Brightness is the screen's, not the page's.
The footer, the page-turn animation and "just this book" are Liseur's
own and know nothing about layout.

## Design

### Disabled, showing its stored value

The same answer ADR 2 gave, for the same reason: the value is not lost,
it is waiting for the next book that can use it. A reader who has settled
on 120% and a wide margin should find both still set when they close the
comic and go back to the novel, and should be able to see, while the
comic is open, that the app has not forgotten them.

Hiding the rows instead would also make the two sheets change shape
between books, which is the opposite of what
[ADR 1](0001-advanced-reading-menu.md) buys: five rows or ten, it is the
same sheet and the reader learns it once.

### The line is said once per sheet

ADR 2's note lives inside `ReadingFineTypographyControls`, above the six
rows it answers for. With the size, the face, the margins, the columns
and the scrolling joining them, a per-group note would print the same
sentence three times on one sheet.

So it is hoisted to the top of each sheet, above everything it applies
to, and reworded. The current wording, "so its text cannot be reshaped",
was true of the six spacing and alignment rows; it is not what a
reader needs to be told when the size slider and the font picker are
greyed out as well.

### One gate, not a second predicate

`ReadingCss.honoursAnything` already answers this. It is false for
`Unsupported` and true for `Unknown`, which is precisely what the two
surfaces need: nothing is disabled on Settings -> Reading appearance,
where no book is open and the reader is choosing a default for every
book they will open.

Adding a second predicate for the older rows would be a copy that can
drift from the first, and drift is what the bug in this issue is. The
enum gains no member.

### Chrome that follows scroll mode is gated on the book too

`effectiveScrolling` in `ReaderScreen` is `scrollMode || verticalText`.
It asks what the reader chose and whether the book runs down the page;
it never asks whether the book can scroll at all.

Three things hang off it: the tap zones, the footer, and whether the
sheet offers auto-scroll or a page-turn animation. So a reader who left
scroll mode on and then opens a fixed-layout book gets scroll-flavoured
chrome over a page Readium is paginating regardless: tap zones that
scroll a page that turns, no footer, and an auto-scroll switch that moves
nothing. It becomes `reflowable && (scrollMode || verticalText)`, which
widens the derivation [ADR 6](0006-auto-scroll.md) named "effectively
scrolled" by the one clause it never had: it asks what the reader chose
and what the book's lines do, and now also whether the book has anything
to scroll.

What is *sent* to Readium does not change. `scroll` goes on being passed
as the reader left it, because Readium ignores it for a fixed layout on
its own, and rebuilding the navigator fragment over a value it ignores
would cost a reader their place to no effect.

### Everything is still sent

No change to `toEpubPreferences`. Every preference keeps going to Readium
exactly as before, whatever the layout; what the layout decides is
whether a value *counts*, not whether it is stored. This is the rule ADR
2 set for the stylesheet variants, and a fixed layout is the same rule
one step further.

## Consequences

- The inconsistency ADR 2 recorded closes. A fixed-layout book
  presents one state instead of two: everything that cannot work is
  greyed, and the sheet says why once.
- The reading theme and the brightness stay enabled, and the audit
  above is the reason. Anyone reading `EpubPreferencesEditor` alone will
  conclude the theme belongs in the disabled set; it does not, because
  Liseur passes its own colours and Readium paints the pager with them
  in every layout.
- A stored value survives a fixed-layout book, because nothing about
  what is sent changes.
- `spread` remains unreachable. A fixed-layout book gets a more
  honest menu out of this change and no new capability; the one setting
  it actually has is left to its own issue.
- The demo shelf has no fixed-layout book, so this cannot be checked
  by running `hack/screenshots`. Verifying it means building a
  pre-paginated EPUB by hand and side-loading it.
- No new dependency, no new stored state, no migration, and no new
  network, file, credential or permission surface.

*Where:* `ui/reading/ReadingFineTypography.kt`,
`ui/reading/ReadingAppearanceControls.kt`,
`reader/chrome/TypographySheet.kt`, `reader/chrome/AdvancedSheet.kt`,
`reader/ReaderScreen.kt`, `res/values/strings.xml`.
