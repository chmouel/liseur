package com.chmouel.liseur.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.chmouel.liseur.data.settings.ReaderTheme

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
