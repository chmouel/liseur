package com.chmouel.liseur.reader

import android.graphics.PointF
import com.chmouel.liseur.translate.PageSentence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * What a tap on a translated page becomes, decided against the page's
 * own mark on the sentence being read. The page itself is asked through
 * [markedOnPage], so the decision is checked with a page that answers
 * what the test needs it to; what the real page makes of the question
 * is `PageSwapsTest`'s to check.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalReadiumApi::class)
class SpokenMarkTapsTest {

    private val sentence = PageSentence(
        text = "It was a dark and stormy night.",
        href = "/EPUB/text/ch1.xhtml",
        selector = "p.first",
        before = "Chapter One ",
    )

    private fun tap(): TapEvent = TapEvent(PointF(100f, 200f), null)

    /** What the reader did: how often the chrome was toggled, and the taps handed to the zones. */
    private class Handled {
        var toggles = 0
        val zoneTaps = mutableListOf<TapEvent>()
    }

    private fun taps(
        scope: TestScope,
        gate: Boolean = true,
        current: Boolean = true,
        pinching: Boolean = false,
        spoken: PageSentence? = sentence,
        markedOnPage: suspend (TapEvent, PageSentence) -> Boolean,
        handled: Handled,
    ): SpokenMarkTaps = SpokenMarkTaps(
        scope = scope,
        gate = { gate },
        sentence = { spoken },
        isCurrent = { current },
        isPinching = { pinching },
        onToggleChrome = { handled.toggles++ },
        onZoneTap = { handled.zoneTaps += it },
        markedOnPage = markedOnPage,
    )

    @Test
    fun `the tap is claimed only where the page owns its own mark`() {
        // Untranslated, the Readium decoration's own listener takes the
        // tap; so it does when a WebView too old to paint the mark left
        // the words to one, and neither must be doubled up on. A pinch
        // is not a tap at all.
        fun claims(pinching: Boolean, translated: Boolean, spoken: Boolean, decoration: Boolean) =
            SpokenMarkTaps.claims(
                pinching = pinching,
                pageTranslated = translated,
                sentence = if (spoken) sentence else null,
                decorationOwnsMark = decoration,
            )
        assertTrue(claims(pinching = false, translated = true, spoken = true, decoration = false))
        assertFalse(claims(pinching = true, translated = true, spoken = true, decoration = false))
        assertFalse(claims(pinching = false, translated = false, spoken = true, decoration = false))
        assertFalse(claims(pinching = false, translated = true, spoken = false, decoration = false))
        assertFalse(claims(pinching = false, translated = true, spoken = true, decoration = true))
    }

    @Test
    fun `a tap on the marked words toggles the chrome and never reaches the zones`() = runTest {
        val handled = Handled()
        val listener = taps(this, markedOnPage = { _, _ -> true }, handled = handled)
        assertTrue(listener.onTap(tap()))
        advanceUntilIdle()
        assertEquals(1, handled.toggles)
        assertEquals(0, handled.zoneTaps.size)
    }

    @Test
    fun `a tap off the marked words is handed to the zones unchanged`() = runTest {
        val handled = Handled()
        val event = tap()
        val listener = taps(this, markedOnPage = { _, _ -> false }, handled = handled)
        assertTrue(listener.onTap(event))
        advanceUntilIdle()
        assertEquals(0, handled.toggles)
        assertEquals(1, handled.zoneTaps.size)
        assertTrue(handled.zoneTaps[0] === event)
    }

    @Test
    fun `a page that does not answer in time lets the tap through`() = runTest {
        val handled = Handled()
        val listener = taps(this, markedOnPage = { _, _ -> delay(60_000); false }, handled = handled)
        assertTrue(listener.onTap(tap()))
        advanceUntilIdle()
        assertEquals(0, handled.toggles)
        assertEquals(1, handled.zoneTaps.size)
    }

    @Test
    fun `a tap the listener does not claim passes straight through`() = runTest {
        val handled = Handled()
        val listener = taps(this, gate = false, markedOnPage = { _, _ -> true }, handled = handled)
        assertFalse(listener.onTap(tap()))
        advanceUntilIdle()
        assertEquals(0, handled.toggles)
        assertEquals(0, handled.zoneTaps.size)
    }

    @Test
    fun `a second tap while one is still being decided is dropped`() = runTest {
        // Handing it to the zones instead would let one touch both turn
        // the page and toggle the chrome a blink later; queueing it would
        // act on a page the reader has stopped looking at.
        val handled = Handled()
        val listener = taps(this, markedOnPage = { _, _ -> delay(100); true }, handled = handled)
        assertTrue(listener.onTap(tap()))
        assertTrue(listener.onTap(tap()))
        advanceUntilIdle()
        assertEquals(1, handled.toggles)
        assertEquals(0, handled.zoneTaps.size)
    }

    @Test
    fun `a tap decided after the reader has left goes nowhere`() = runTest {
        val handled = Handled()
        val listener = taps(this, current = false, markedOnPage = { _, _ -> true }, handled = handled)
        assertTrue(listener.onTap(tap()))
        advanceUntilIdle()
        assertEquals(0, handled.toggles)
        assertEquals(0, handled.zoneTaps.size)
    }

    @Test
    fun `only the page saying true counts as on the mark`() {
        // Anything else — off the words, another document, no answer at
        // all — is the zones' to answer.
        assertTrue(SpokenMarkTaps.onMark("true"))
        assertFalse(SpokenMarkTaps.onMark("false"))
        assertFalse(SpokenMarkTaps.onMark("null"))
        assertFalse(SpokenMarkTaps.onMark(null))
        assertFalse(SpokenMarkTaps.onMark(""))
    }

    @Test
    fun `a point becomes a fraction of the view that holds it`() {
        assertEquals(0.25f to 0.5f, SpokenMarkTaps.fraction(250f, 400f, 150f, 200f, 400f, 400f))
    }

    @Test
    fun `a point outside the view is no view's`() {
        assertNull(SpokenMarkTaps.fraction(100f, 400f, 150f, 200f, 400f, 400f))
        assertNull(SpokenMarkTaps.fraction(250f, 601f, 150f, 200f, 400f, 400f))
        assertNull(SpokenMarkTaps.fraction(250f, 400f, 150f, 200f, 0f, 400f))
    }
}
