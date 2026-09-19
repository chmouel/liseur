package com.chmouel.liseur.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.chmouel.liseur.data.settings.ReaderTheme
import kotlin.math.max
import kotlin.math.min

/**
 * The app's palette, re-laid on the page the reader chose.
 *
 * The reading theme and the app theme are deliberately separate: somebody
 * reading sepia at noon still wants their library the colour they set it.
 * The cost of that was that everything the reader raised *over* the page —
 * the definition sheet, the typography sheet, Go to page, the note editor —
 * came up in the app's colours, because that is what `MaterialTheme` was
 * handing out. A white sheet over a sepia page, which is what
 * [#212](https://github.com/chmouel/liseur/issues/212) reported.
 *
 * A few pieces of chrome had already been painted by hand from
 * [ReaderTheme] — the footnote card, the note sheet, the image viewer. That
 * only works for colours somebody wrote down. Most of the wrong colour here
 * was never written down anywhere: it is the default container of an
 * `AlertDialog`, the track of a `Slider`, the border of an
 * `OutlinedTextField`, the background of a `DropdownMenu`. Chasing those one
 * role at a time across ten files would have been a long diff that still
 * missed some, and the next sheet written would have missed them again.
 *
 * So the page is applied once, here, and installed over the whole reader
 * activity. Everything Material draws inside is then already on the paper,
 * and a sheet written next year is right without knowing any of this.
 *
 * ### What is kept, and what is replaced
 *
 * The accents are kept. This starts from whichever of [light] or [dark]
 * matches the page's own lightness and copies it, so `primary`, `error`,
 * their `on*` partners, their containers, `inversePrimary` and every fixed
 * accent role arrive already paired with each other by Material. That is
 * also what makes the error colour right per page for free: the lookup-failed
 * message is a dark red on a light page and a pale red on a night one,
 * because those are the two schemes' own answers, not something invented
 * here.
 *
 * The surfaces are replaced, because those are the page. The raised
 * containers are steps of ink mixed into paper so a sheet still reads as
 * lifted off the page behind it.
 *
 * ### Why the mixes are opaque
 *
 * Every derived colour is a [lerp] to a solid value rather than an alpha over
 * the page. Translucency stacks — a wash on a container on a scrim stops
 * being the colour it was reasoned about — and electronic paper renders it as
 * a dithered smear. This is the same rule `NoteSheet` and `FootnoteCard`
 * already follow by hand.
 *
 * @param light the palette at its light lightness
 * @param dark the same palette at its dark lightness
 * @param page the paper the chrome is sitting on
 * @param eInk whether this is an electronic paper panel
 */
fun readingColorScheme(
    light: ColorScheme,
    dark: ColorScheme,
    page: ReaderTheme,
    eInk: Boolean,
): ColorScheme {
    val paper = page.background
    val ink = page.foreground
    val mix = { amount: Float -> lerp(paper, ink, amount) }

    return (if (page.isDarkPage) dark else light).copy(
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        // The page itself, at the two ends of Material's surface range.
        surfaceBright = paper,
        surfaceDim = mix(DIM),
        surfaceContainerLowest = paper,
        surfaceContainerLow = mix(CONTAINER_LOW),
        surfaceContainer = mix(CONTAINER),
        surfaceContainerHigh = mix(CONTAINER_HIGH),
        surfaceContainerHighest = mix(CONTAINER_HIGHEST),
        surfaceVariant = mix(CONTAINER),
        // A wash under full ink dithers to grey on electronic paper, so
        // there the quieter text is simply the ink.
        onSurfaceVariant = if (eInk) ink else mix(QUIET_INK),
        outline = mix(OUTLINE),
        outlineVariant = mix(OUTLINE_VARIANT),
        inverseSurface = ink,
        inverseOnSurface = paper,
        // Always darkens, never tints. A scrim mixed from a night page's
        // own ink would lighten the page instead of pushing it back.
        scrim = Color.Black,
        // M3 tints a raised surface with this at elevation. Left on, a
        // sepia sheet picks up a lilac cast the moment anything sits above
        // the page.
        surfaceTint = Color.Transparent,
    )
}

/*
 * How much ink each derived colour carries.
 *
 * Settled by ReadingColorSchemeTest rather than by eye: every one of these
 * has to keep the ink readable on the surface it produces across all four
 * reading themes and both palettes. Raising one until its test passes is the
 * intended way to change it.
 *
 * On the Black page the lowest steps round back to pure black, so a sheet
 * there is exactly the page and its edge is what separates it. That is not a
 * degenerate case to design around: it is what `NoteSheet` and `FootnoteCard`
 * already do by hand on every theme, and on an OLED panel it is the black the
 * reader asked for. [OUTLINE] is sized to hold that separation on its own.
 */
private const val CONTAINER_LOW = 0.04f
private const val CONTAINER = 0.07f
private const val CONTAINER_HIGH = 0.10f
private const val CONTAINER_HIGHEST = 0.13f
private const val DIM = 0.10f
private const val QUIET_INK = 0.80f
private const val OUTLINE = 0.65f
private const val OUTLINE_VARIANT = 0.22f

/**
 * The colour the spinner is drawn in while a book is opening.
 *
 * A spinner is not reading chrome. It is the app saying "still working"
 * before there is a book to read at all, on a screen with no text, no page
 * furniture and nothing the reader picked a paper colour for. So it is the
 * one accent in the reader that is allowed to stay the app's own, wallpaper
 * colours included: the bounded exception to
 * `docs/adr/0031-the-readers-chrome-is-painted-on-the-page.md`, reported as
 * [#221](https://github.com/chmouel/liseur/issues/221).
 *
 * Reading chrome was painted on the page in the first place because nobody
 * had ever checked a wallpaper-derived accent against sepia. That is
 * checked here instead, at runtime, against the exact background the
 * indicator will sit on. When the app's accent cannot be seen on the page
 * the page's own accent is used, which is what the whole reader used before
 * this exception existed.
 *
 * [appAccent] is expected to have been resolved at the *page's* lightness
 * rather than the app's, so that a light-scheme accent never lands on a
 * night page. A tone that dark would clear [CONTROL] on black and still
 * look wrong.
 *
 * With wallpaper colour off this returns [fallback] unchanged, because the
 * app's accent and the page's are then the same colour from the same
 * palette. The exception has no effect at all until somebody turns dynamic
 * colour on.
 *
 * @param page the paper the indicator is sitting on
 * @param appAccent the app's own accent, at the page's lightness
 * @param fallback the page's accent, used when [appAccent] cannot be seen
 */
internal fun loadingAccentOn(page: ReaderTheme, appAccent: Color, fallback: Color): Color =
    if (contrastRatio(appAccent, page.background) >= CONTROL) appAccent else fallback

/**
 * WCAG 2.x minimum for a graphical control, which is what a spinner is.
 *
 * Deliberately not the 4.5 the rest of this file is tuned against: that is
 * the body-text minimum, and it applies to the accent where the accent is
 * text, as it is in the definition sheet. An indicator carries no glyphs,
 * so 1.4.11 Non-text Contrast is the rule it has to meet.
 */
private const val CONTROL = 3.0

/**
 * The WCAG contrast ratio between two colours, 1.0 to 21.0.
 *
 * Both colours must be opaque. Nothing here composites, so a translucent
 * colour would be measured as though its alpha were 1 and the answer would
 * be wrong in the safe-looking direction. Every colour this is asked about
 * comes from a [ColorScheme] or a [ReaderTheme], and those are opaque by
 * construction.
 */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}
