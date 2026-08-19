# 5. Warm light

Status: proposed

## Context

Night reading on the Dark and Black themes is easier on the eyes than
white, but the light is still blue. Android's own Night Light does not
reach every device, and a reader should not have to leave the book to
find a system toggle. Every serious reading app ends up with a warmth
control for this reason.

## Decision

One warmth slider that draws an amber tint over the page, from nothing
to distinctly candle-lit, remembered like brightness.

Fit with Liseur's simplicity: the slider sits directly under the
brightness slider in the typography sheet — the two answer the same
question, "how does the light feel" — and there is no schedule, no
automation, no per-theme value.

## Design

The tint is a Compose overlay in `ReaderScreen`, a full-size box over
the navigator filled with an amber whose alpha the slider sets, drawn
under the chrome so sheets and dialogs stay true-colour. Zero alpha
draws nothing. This is the same shape as the existing brightness
override: a `Float?` on `ReaderPrefs`, null meaning off, one DataStore
key, one row in the sheet.

An overlay dims slightly as it warms; that is acceptable and matches
what an amber gel does to a real lamp. Tinting through the WebView or
Readium's colours instead would keep luminance but would also recolour
images and fight the theme palette. Not worth it.

## Consequences

The scrim covers the page but not the system bars, which are already
hidden in the reader. On e-ink (the existing monochrome mode) the tint
becomes a grey veil with no benefit, so the slider is ignored there.

*Where:* `data/settings/ReaderPrefs.kt`, `reader/ReaderScreen.kt`,
`reader/chrome/TypographySheet.kt`.
