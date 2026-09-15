package com.chmouel.liseur.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.chmouel.liseur.data.settings.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * What the reader's chrome is painted in, checked two ways.
 *
 * The exact-role tests below say the scheme is built the way it was meant
 * to be. They would all still pass if it were unreadable, so the second
 * half measures contrast instead, over every reading theme and both
 * palettes. That half is what the mix factors in `ReadingColorScheme.kt`
 * are actually tuned against.
 */
class ReadingColorSchemeTest {

    private fun scheme(
        page: ReaderTheme,
        palette: PalettePair = BrandPalette,
        eInk: Boolean = false,
    ) = readingColorScheme(
        light = palette.light,
        dark = palette.dark,
        page = page,
        eInk = eInk,
    )

    private val palettes = listOf("brand" to BrandPalette, "mono" to MonoPalette)

    // ---- the page --------------------------------------------------------

    @Test
    fun `every plain surface is the page itself`() {
        for (page in ReaderTheme.entries) {
            val s = scheme(page)
            assertEquals("background of $page", page.background, s.background)
            assertEquals("surface of $page", page.background, s.surface)
            assertEquals("surfaceBright of $page", page.background, s.surfaceBright)
            assertEquals(
                "surfaceContainerLowest of $page",
                page.background,
                s.surfaceContainerLowest,
            )
        }
    }

    @Test
    fun `ink on the page is the reading theme's own`() {
        for (page in ReaderTheme.entries) {
            val s = scheme(page)
            assertEquals("onBackground of $page", page.foreground, s.onBackground)
            assertEquals("onSurface of $page", page.foreground, s.onSurface)
        }
    }

    @Test
    fun `raised containers climb rather than jump about`() {
        // Not that each one differs from the page: on the Black theme the
        // lowest steps round back to pure black, and a sheet that is exactly
        // the page is what NoteSheet and FootnoteCard already draw. What has
        // to hold is the order, so "high" never reads as lower than "low".
        for (page in ReaderTheme.entries) {
            val s = scheme(page)
            val steps = listOf(
                s.surfaceContainerLowest,
                s.surfaceContainerLow,
                s.surfaceContainer,
                s.surfaceContainerHigh,
                s.surfaceContainerHighest,
            ).map { contrastRatio(it, page.background) }
            assertEquals(
                "raised containers on $page should get progressively more ink",
                steps.sorted(),
                steps,
            )
        }
    }

    @Test
    fun `a sheet on a light page is visibly lifted off it`() {
        // Where the page has room for it, elevation should actually show.
        // Black is excluded by its own definition, above.
        for (page in listOf(ReaderTheme.LIGHT, ReaderTheme.SEPIA, ReaderTheme.DARK)) {
            val s = scheme(page)
            assertTrue(
                "surfaceContainerHighest on $page should differ from the page",
                s.surfaceContainerHighest != page.background,
            )
        }
    }

    // ---- the accents -----------------------------------------------------

    @Test
    fun `accents come from the palette matching the page's lightness`() {
        // A night page takes the dark scheme's accents even under a light
        // app, which is the whole point: the light scheme's dark brown on
        // a black page is the pairing this prevents.
        for (page in ReaderTheme.entries) {
            val base = if (page.isDarkPage) BrandPalette.dark else BrandPalette.light
            val s = scheme(page)
            assertEquals("primary on $page", base.primary, s.primary)
            assertEquals("onPrimary on $page", base.onPrimary, s.onPrimary)
            assertEquals("primaryContainer on $page", base.primaryContainer, s.primaryContainer)
            assertEquals("secondary on $page", base.secondary, s.secondary)
            assertEquals("tertiary on $page", base.tertiary, s.tertiary)
            assertEquals("error on $page", base.error, s.error)
            assertEquals("onError on $page", base.onError, s.onError)
            assertEquals("inversePrimary on $page", base.inversePrimary, s.inversePrimary)
        }
    }

    @Test
    fun `the failed-lookup red follows the page and not the app`() {
        // The case from the bug report's neighbourhood: a reader on a black
        // page with the app still light. The message must not arrive in the
        // light scheme's dark red.
        assertEquals(BrandPalette.dark.error, scheme(ReaderTheme.BLACK).error)
        assertEquals(BrandPalette.light.error, scheme(ReaderTheme.SEPIA).error)
        assertTrue(
            "the two schemes should not agree about red, or this proves nothing",
            BrandPalette.dark.error != BrandPalette.light.error,
        )
    }

    // ---- the rest --------------------------------------------------------

    @Test
    fun `elevation cannot tint the page`() {
        for (page in ReaderTheme.entries) {
            assertEquals(
                "surfaceTint on $page",
                Color.Transparent,
                scheme(page).surfaceTint,
            )
        }
    }

    @Test
    fun `a scrim darkens, whatever the page`() {
        // Built from a night page's own ink it would lighten the page
        // instead of pushing it back.
        for (page in ReaderTheme.entries) {
            assertEquals("scrim on $page", Color.Black, scheme(page).scrim)
        }
    }

    @Test
    fun `inverse surfaces swap the page`() {
        for (page in ReaderTheme.entries) {
            val s = scheme(page)
            assertEquals(page.foreground, s.inverseSurface)
            assertEquals(page.background, s.inverseOnSurface)
        }
    }

    @Test
    fun `electronic paper gets the full ink rather than a wash`() {
        for (page in ReaderTheme.entries) {
            assertEquals(
                "onSurfaceVariant on $page under e-ink",
                page.foreground,
                scheme(page, eInk = true).onSurfaceVariant,
            )
            assertTrue(
                "a screen that can dither should still get the quieter wash on $page",
                scheme(page, eInk = false).onSurfaceVariant != page.foreground,
            )
        }
    }

    // ---- contrast --------------------------------------------------------

    /** Every surface a reader might see text or a border laid over. */
    private fun surfacesOf(s: ColorScheme) = listOf(
        "surface" to s.surface,
        "surfaceBright" to s.surfaceBright,
        "surfaceDim" to s.surfaceDim,
        "surfaceVariant" to s.surfaceVariant,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
    )

    private fun eachScheme(body: (String, ColorScheme) -> Unit) {
        for (page in ReaderTheme.entries) {
            for ((name, palette) in palettes) {
                for (eInk in listOf(false, true)) {
                    body("$page/$name/eInk=$eInk", scheme(page, palette, eInk))
                }
            }
        }
    }

    private fun assertContrast(where: String, fg: Color, bg: Color, atLeast: Double) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(
            "$where: contrast %.2f is below %.1f".format(ratio, atLeast),
            ratio >= atLeast,
        )
    }

    @Test
    fun `body text is readable on every surface it can land on`() {
        eachScheme { where, s ->
            for ((name, surface) in surfacesOf(s)) {
                assertContrast("$where onSurface on $name", s.onSurface, surface, TEXT)
            }
        }
    }

    @Test
    fun `the quieter label is readable on every surface it can land on`() {
        // It is the supporting text inside sheets and cards, not only on the
        // bare page, so a raised container has to hold it too.
        eachScheme { where, s ->
            for ((name, surface) in surfacesOf(s)) {
                assertContrast(
                    "$where onSurfaceVariant on $name",
                    s.onSurfaceVariant,
                    surface,
                    TEXT,
                )
            }
        }
    }

    @Test
    fun `accents drawn straight on the page are readable`() {
        // The definition sheet puts the part of speech in primary and the
        // lookup failure in error, both directly on the sheet's own surface.
        eachScheme { where, s ->
            assertContrast("$where primary on surface", s.primary, s.surface, TEXT)
            assertContrast("$where error on surface", s.error, s.surface, TEXT)
        }
    }

    @Test
    fun `an outline separates what it encloses`() {
        // Text-field borders, the e-ink footnote card's edge, and the
        // selection bar, which is an outline on surfaceContainerHighest.
        eachScheme { where, s ->
            assertContrast("$where outline on surface", s.outline, s.surface, NON_TEXT)
            assertContrast(
                "$where outline on surfaceContainerHighest",
                s.outline,
                s.surfaceContainerHighest,
                NON_TEXT,
            )
        }
    }

    @Test
    fun `an accent's own label is readable on it`() {
        // Inherited from Material rather than derived here, so this is a
        // tripwire for a future palette edit rather than a claim about
        // this change.
        eachScheme { where, s ->
            assertContrast("$where onPrimary on primary", s.onPrimary, s.primary, TEXT)
            assertContrast("$where onError on error", s.onError, s.error, TEXT)
        }
    }
}

/** WCAG 2.x body-text minimum. */
private const val TEXT = 4.5

/** WCAG 2.x minimum for a border or other non-text mark. */
private const val NON_TEXT = 3.0

/**
 * The WCAG contrast ratio between two opaque colours, 1.0 to 21.0.
 *
 * Written out rather than taken from a library so the test depends on
 * nothing, and because it is eight lines.
 */
private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private fun relativeLuminance(colour: Color): Double {
    fun channel(raw: Float): Double {
        val c = raw.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(colour.red) +
        0.7152 * channel(colour.green) +
        0.0722 * channel(colour.blue)
}
