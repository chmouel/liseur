package com.chmouel.liseur.reader

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.chmouel.liseur.reader.progress.ExactLocatorAnchor
import com.chmouel.liseur.reader.progress.ViewportTextAnchor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SettleCaptureTest {

    private fun exact(): Locator {
        val native = requireNotNull(
            Locator.fromJSON(
                JSONObject()
                    .put("href", "EPUB/ch1.xhtml")
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", 0.4)),
            ),
        )
        return ExactLocatorAnchor.mark(
            native,
            ViewportTextAnchor("#p2", "before ", "visible", " after"),
        )
    }

    @Test
    fun `an untranslated page keeps the captured words`() {
        val captured = exact()
        assertSame(captured, settleCapture(captured, 0, false, 0, false))
    }

    @Test
    fun `a page already translated when the capture began is coarsened`() {
        val settled = settleCapture(exact(), 0, true, 0, true)
        assertTrue(ExactLocatorAnchor.anchorIn(settled) == null)
        assertTrue(settled.text.highlight == null)
        // The place itself survives: resource and progression stay.
        assertEquals("EPUB/ch1.xhtml", settled.href.toString())
        assertEquals(0.4, settled.locations.progression ?: -1.0, 0.0)
    }

    @Test
    fun `a page that switched translation under the capture is coarsened`() {
        // Onto a translation: the words may be the translation's.
        assertTrue(ExactLocatorAnchor.anchorIn(settleCapture(exact(), 0, false, 1, true)) == null)
        // Off again: the same doubt runs the other way.
        assertTrue(ExactLocatorAnchor.anchorIn(settleCapture(exact(), 1, false, 0, false)) == null)
    }
}
