# 31. The reader's chrome is painted on the page

Status: accepted

## Context

With the reading theme set to sepia, long-pressing a word and tapping
Define opened a bottom sheet painted white ([#212]). The sheet is
`DefinitionSheet`, and it passes no colours to
`LiseurModalBottomSheet`, so it gets `MaterialTheme.colorScheme.surface`
— the *app* theme, which is decoupled from the reading theme by design,
because the library should follow the system while the page follows the
reader.

That was one instance of a class. Roughly half the reader's chrome was
hand-painted from `ReaderTheme` — `FootnoteCard`, `NoteSheet`,
`ImageViewer`, `Endpaper`, the contents and search screens, the toolbar —
and the other half was not: the define and typography sheets, Advanced,
`SelectionPopup`, the note editor, the Go to page and external-link
dialogs, `BookSyncDialog`, the loading and error screens. ADR 27 wrote
the rule down as it stood: `NoteSheet` is painted in the reading theme
"for the reason `FootnoteCard` is", and "every other sheet keeps
Material's". The bug is what that sentence costs on a sepia page.

Painting the remaining ten by hand would not have fixed them. The colour
that is wrong is mostly not written down anywhere: it comes from the
defaults inside them — `AlertDialog`'s container, `Slider`'s track,
`Switch`'s thumb, `OutlinedTextField`'s border, `DropdownMenu`'s
background. Overriding those one role at a time is a long diff that would
still miss some, and the next sheet written would miss them again.

## Decision

The reading theme becomes a `ColorScheme`, derived once and installed
over the whole reader activity. Every Material component inside is then
already on the page's paper, and `DefinitionSheet` — the file in the bug
report — is not touched at all.

`readingColorScheme(light, dark, page, eInk)` takes both lightnesses of
the app's palette by name, picks the one matching `ReaderTheme.isDarkPage`,
and `copy(...)`s only the page-related roles onto it:

- **Surfaces are the page.** `background`, `surface`, `surfaceBright` and
  `surfaceContainerLowest` are the page's background exactly. The raised
  containers are opaque `lerp` steps toward the page's ink.
- **Ink is the page's foreground**, with `onSurfaceVariant` a mix of it —
  or the full ink under e-ink, where a wash dithers to grey. That is the
  rule `NoteSheet` already followed by hand.
- **Accents are inherited untouched** from the lightness-matched base.
  That is what makes `error` right per page — a light page gets the light
  scheme's dark red, a Dark or Black page gets the dark scheme's pale one
  — with no per-widget override, and it keeps the Enable dictionary button
  a Material accent rather than a smudge of page ink. Copying rather than
  constructing also keeps `inversePrimary` and the M3 fixed-accent roles
  consistent with the accents they belong to, and will keep whatever
  Material adds next.
- `surfaceTint` is transparent, or M3's tonal-elevation overlay tints a
  sepia sheet lilac when something sits at elevation. `scrim` stays black:
  a scrim built from a night page's ink would lighten the page instead of
  pushing it back.

The mixes are opaque rather than alpha, so nothing stacks and e-ink has no
translucency to dither. Their exact factors are not a design opinion:
`ReadingColorSchemeTest` checks WCAG contrast for every role pairing that
can actually meet on screen, across four reading themes, two palettes and
both e-ink states, and each factor is whatever makes its worst case pass.

`ReaderTheme.isDarkPage` is an exhaustive `when`, not a luminance
threshold, so a fifth reading theme will not compile until somebody says
which side it is on.

### Wallpaper colours stop at the reader's door

> **Amended.** One exception was added later: the loading indicator. See
> "The loading indicator is not reading chrome" below.

A dynamic scheme's accents are chosen against *its own* surfaces, which
this replaces with a page Liseur picked. Nobody — not Material, not the
reader — has ever checked a wallpaper-derived primary against sepia, and a
pale dynamic accent on white paper is exactly the unreadable pairing the
contrast test exists to prevent. It is also the one dimension a JVM test
cannot cover, because the colours come from the device's wallpaper at
runtime.

So inside the reader, `LiseurTheme` uses Liseur's own brand palette, or
the monochrome one under e-ink, and ignores `dynamicColor`. The library
and settings screens keep wallpaper colour exactly as they had it. This is
a visible change for readers who use dynamic colour, and it is the part of
this worth arguing about: the reading page is not the wallpaper's to tint.

### One preference, not two

`ReaderViewModel.open()` awaits the preferences before it publishes
`Ready`, so a second, independently-scheduled collector in the activity
could still hold null while `Ready` is already rendering the saved theme —
a frame of black page under a light Material scheme. Two sources of one
answer is the bug, so there is one: the activity collects the preferences
itself, and the `Ready` branch is gated on that value and uses it as its
own reading theme rather than resolving it a second time.

### System bars

`enableEdgeToEdge()` sets the status- and navigation-bar icon appearance
from the app's darkness, not from `MaterialTheme`, so a light app with a
Black reading theme drew dark icons over a dark toolbar. That was already
wrong, in one corner; page-colouring the activity makes it wrong
everywhere. `SystemBarIcons(dark)` sits beside the `LiseurTheme` call,
keyed on the page's darkness, and restores what it found on the way out.

It is at activity level rather than inside `ImmersiveMode` because the
page colours now reach the loading screen, the error screen and
`BookSyncDialog` as well — a black loading screen with dark icons is the
same bug one scope up. `ImmersiveMode` goes on doing only what it did:
showing and hiding the bars.

Activity level is not the only level, though, because the activity's is
not the only window. A `ModalBottomSheet` is a window of its own, laid
over the bars, and Material points that window's icons at the sheet's ink
*once*, when the window is built: `updateParameters` takes a new colour
and never says it again. So the reader who changes the reading theme from
the sheet in front of them repaints the page, the sheet and nothing else —
a black clock left on a black status bar (#225). `SystemBarIcons` resolves
the window it is composed in, through `DialogWindowProvider`, and
`LiseurModalBottomSheet` says it from inside the sheet, keyed on the
sheet's paper. `ReadingColorSchemeTest` holds the two answers together:
the paper a sheet is painted in is dark exactly when the page is, so
opening or dismissing a sheet can never flip the icons on its own.

## Amendment: the loading indicator is not reading chrome

Added after [#221]: a reader with an aquamarine wallpaper accent got a
golden spinner while a book opened. The decision above is why, and it
stands for everything that is actually chrome over a page. The spinner
is not.

It is the app saying "still working" before there is a book to read at
all, on a screen with no text, no page furniture and nothing the reader
picked a paper colour for. Calling that reading chrome also produced a
sillier symptom than the wrong hue: `ReaderActivity` builds the theme with
`readingPage = null` until the preferences land, so one continuous spinner
started in the wallpaper accent and turned gold part-way through, with
nothing on screen having changed.

So the indicator keeps the app's own accent, wallpaper colours included.
The objection above — that a JVM test cannot check a wallpaper accent
against sepia — is answered by not asking a JVM test to. `loadingAccentOn`
measures the contrast at runtime against the exact page background the
indicator will sit on, and hands back the page's accent when the app's
cannot be seen. The measurement is a pure function, so what *is* testable
is tested.

Three things keep this from growing into the reversal it is not:

- The accent is resolved at the *page's* lightness, not the app's, by the
  same `isDarkPage` rule the scheme above uses. A light-scheme tone would
  clear the threshold on a black page and still look wrong.
- The threshold is 3:1, not 4.5:1. An indicator carries no glyphs, so WCAG
  1.4.11 Non-text Contrast is the rule that applies, and borrowing the
  body-text minimum would refuse accents that are perfectly visible.
- With wallpaper colour off, or below API 31, or under e-ink, the app's
  accent and the page's are the same colour from the same palette at the
  same lightness, so the exception computes today's answer exactly.
  `ReadingColorSchemeTest` asserts that, and it is the whole safety
  argument: nothing moves unless dynamic colour is on.

The window before the app's settings arrive is left as it was: there is
no page yet, so the indicator draws in whatever `MaterialTheme` is
handing out, which is the app's own theme. That is what it always did.
What goes away is the hue changing, which is what the reader in #221 saw,
and only for an accent that clears the guard. An accent too close to the
page still falls back to the page's own, and that fallback can change the
hue; refusing an invisible spinner is worth more than holding one colour.

The tone can still move too, because the app and the page do not have to
be the same lightness: a light app opening a black page takes the light
scheme's accent before preferences land and the dark scheme's after. That
is the right answer both times, since the surface underneath flips at the
same instant and the accent is following the paper it sits on.

The error screen is deliberately not included. A failure message is
content, its red is semantic, and the scheme above already gets it right
per page.

## Consequences

The existing hand-painted `theme.foreground` / `theme.background` call
sites are now redundant. They are also still correct, and rewriting them
would bury the fix in churn, so they stay.

`ui/reading/*` controls are shared between the reader's sheets and
Settings. They take their colours from whichever theme is in force, so
both surfaces stay right without a flag.

A sheet written next year is on the page by default, which is the point.
The corollary is that a sheet that genuinely wants app colours now has to
say so — but no such sheet exists, and a reader looking at a page is the
case worth defaulting to.

On the Black theme the lowest container steps round back to pure black, so
a sheet there is exactly the page. That is not a flaw to design around: it
is what `NoteSheet` and `FootnoteCard` already did by hand, on an OLED
panel it is the black the reader asked for, and the outline is sized to
hold the separation on its own.

Readium's page rendering is unaffected; it is a WebView driven by
`EpubPreferences`.

[#212]: https://github.com/chmouel/liseur/issues/212
[#221]: https://github.com/chmouel/liseur/issues/221
