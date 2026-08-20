package com.chmouel.liseur.reader.progress

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ExactLocatorAnchorTest {

    @Test
    fun `javascript anchor keeps exact unicode context lengths`() {
        val before = "🙂".repeat(40)
        val highlight = "界".repeat(70)
        val after = "é".repeat(40)
        val json = JSONObject()
            .put("cssSelector", "#chapter > p:nth-of-type(2)")
            .put("before", before)
            .put("highlight", highlight)
            .put("after", after)
            .toString()

        val anchor = ExactLocatorAnchor.parseJavascriptResult(JSONObject.quote(json))

        assertNotNull(anchor)
        assertEquals("🙂".repeat(32), anchor?.before)
        assertEquals("界".repeat(64), anchor?.highlight)
        assertEquals("é".repeat(32), anchor?.after)
    }

    @Test
    fun `marked locator survives json and carries stable progression`() {
        val exact = ExactLocatorAnchor.mark(
            locator(),
            ViewportTextAnchor("#chapter", "before ", "visible", " after"),
        ).let { ExactLocatorAnchor.withStableProgression(it, 0.42) }
        val restored = Locator.fromJSON(exact.toJSON())

        assertTrue(ExactLocatorAnchor.isExact(restored))
        assertTrue(ExactLocatorAnchor.isExactJson(exact.toJSON().toString()))
        assertEquals(0.42, restored?.locations?.totalProgression ?: 0.0, 0.0)
        assertEquals("visible", restored?.text?.highlight)
    }

    @Test
    fun `legacy text locator is approximate`() {
        val legacy = locator().copy(
            text = Locator.Text(before = "before", highlight = "next", after = "after"),
        )

        assertFalse(ExactLocatorAnchor.isExact(legacy))
        assertFalse(ExactLocatorAnchor.isExactJson(legacy.toJSON().toString()))
    }

    @Test
    fun `excerpt is normalized and only comes from an exact anchor`() {
        val exact = ExactLocatorAnchor.mark(
            locator(),
            ViewportTextAnchor("#chapter", "  before\n", " visible ", "\t after  "),
        )

        assertEquals(
            "before visible after",
            ExactLocatorAnchor.excerpt(exact.toJSON().toString()),
        )
        assertEquals(null, ExactLocatorAnchor.excerpt(locator().toJSON().toString()))
    }

    private fun locator(): Locator = requireNotNull(
        Locator.fromJSON(
            JSONObject()
                .put("href", "https://example.com/chapter.xhtml")
                .put("type", "application/xhtml+xml")
                .put(
                    "locations",
                    JSONObject()
                        .put("progression", 0.5)
                        .put("position", 3)
                        .put("totalProgression", 0.9),
                ),
        ),
    )
}
