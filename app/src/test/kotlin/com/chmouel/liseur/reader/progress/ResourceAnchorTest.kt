package com.chmouel.liseur.reader.progress

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import com.chmouel.liseur.reader.ResourceAddress
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ResourceAnchorTest {

    @Test
    fun `the resource and its own progression survive`() {
        val sanitized = ResourceAnchor.sanitize(
            locator(progression = 0.62, totalProgression = 0.23).toJSON().toString(),
        )

        assertNotNull(sanitized)
        assertEquals("/chapter-7.xhtml", sanitized?.href.toString())
        assertEquals(0.62, sanitized?.locations?.progression ?: 0.0, 0.0)
        assertEquals(0.23, sanitized?.locations?.totalProgression ?: 0.0, 0.0)
    }

    @Test
    fun `a foreign pagination and a stale quote are dropped, not carried`() {
        // Everything here describes the *writing* client: its synthetic
        // page number, its engine's CFI, and a quote this device has no
        // way to confirm. Restoring against any of them is how a
        // fallback ends up more wrong than the percentage it replaced.
        val foreign = locator().copy(
            locations = Locator.Locations(
                fragments = listOf("epubcfi(/6/14!/4/2/2[p9]/1:0)"),
                progression = 0.62,
                position = 812,
                totalProgression = 0.23,
                otherLocations = mapOf(
                    ExactLocatorAnchor.MARKER to 1,
                    ExactLocatorAnchor.CSS_SELECTOR to "body > p:nth-of-type(9)",
                ),
            ),
            text = Locator.Text(before = "before", highlight = "moved", after = "after"),
        )

        val sanitized = requireNotNull(ResourceAnchor.sanitize(foreign.toJSON().toString()))

        assertEquals(emptyList<String>(), sanitized.locations.fragments)
        assertNull(sanitized.locations.position)
        assertEquals(emptyMap<String, Any>(), sanitized.locations.otherLocations)
        assertNull(sanitized.text.highlight)
        // What it is *for* is still there.
        assertEquals(0.62, sanitized.locations.progression ?: 0.0, 0.0)
        // And it must not read as an exact anchor afterwards, or the
        // reader would be told the passage was restored exactly.
        assertFalse(ExactLocatorAnchor.isExact(sanitized))
    }

    @Test
    fun `the top of a chapter is a place, not a missing one`() {
        val top = ResourceAnchor.sanitize(
            locator(progression = 0.0, totalProgression = 0.0).toJSON().toString(),
        )

        assertNotNull(top)
        assertEquals(0.0, top?.locations?.progression ?: -1.0, 0.0)
    }

    @Test
    fun `a progression that is not a fraction is refused rather than clamped`() {
        // Clamping would move the reader to the end of a chapter on the
        // strength of another client's bug. There is no place here.
        //
        // Written as raw JSON because that is how these actually arrive.
        // org.json refuses to *emit* a NaN, so a client whose fraction
        // went wrong sends `null` (what JSON.stringify does with one) or
        // the string "NaN" — which is just a string until something asks
        // it for a double. SyncOps guards the same door for the same
        // reason.
        val broken = listOf("1.7", "-0.2", "\"NaN\"", "null", "\"\"", "\"later\"")
        for (value in broken) {
            val json = """
                {"href":"/chapter-7.xhtml","type":"application/xhtml+xml",
                 "locations":{"progression":$value,"totalProgression":0.23}}
            """.trimIndent()
            assertNull(value, ResourceAnchor.sanitize(json))
        }
    }

    @Test
    fun `a broken whole-book percentage does not cost the chapter`() {
        // The resource progression is the part that matters here, and it
        // is sound. Dropping the whole place because the *other* number
        // is nonsense would be refusing the good half of the answer.
        val json = """
            {"href":"/chapter-7.xhtml","type":"application/xhtml+xml",
             "locations":{"progression":0.62,"totalProgression":4.2}}
        """.trimIndent()

        val sanitized = requireNotNull(ResourceAnchor.sanitize(json))
        assertEquals(0.62, sanitized.locations.progression ?: 0.0, 0.0)
        assertNull(sanitized.locations.totalProgression)
    }

    @Test
    fun `a locator with no resource progression has no resource place`() {
        val percentageOnly = JSONObject()
            .put("href", "/chapter-7.xhtml")
            .put("type", "application/xhtml+xml")
            .put("locations", JSONObject().put("totalProgression", 0.23))

        assertNull(ResourceAnchor.sanitize(percentageOnly.toString()))
        assertFalse(ResourceAnchor.isResourceJson(percentageOnly.toString()))
    }

    @Test
    fun `nothing at all is not a place`() {
        assertNull(ResourceAnchor.sanitize(null as String?))
        assertNull(ResourceAnchor.sanitize(""))
        assertNull(ResourceAnchor.sanitize("{}"))
        assertNull(ResourceAnchor.sanitize("not json"))
        assertFalse(ResourceAnchor.isResourceJson(null))
    }

    @Test
    fun `an exact anchor is also a resource place`() {
        // The ladder descends: an anchor whose quote will not resolve
        // must still leave the chapter behind it, or the fallback is the
        // percentage again.
        val exact = ExactLocatorAnchor.mark(
            locator(progression = 0.62),
            ViewportTextAnchor("#chapter", "before ", "word", " after"),
        )

        assertTrue(ExactLocatorAnchor.isExact(exact))
        val sanitized = requireNotNull(ResourceAnchor.sanitize(exact.toJSON().toString()))
        assertEquals("/chapter-7.xhtml", sanitized.href.toString())
        assertEquals(0.62, sanitized.locations.progression ?: 0.0, 0.0)
    }

    @Test
    fun `a chapter this book does not have is not a place in it`() {
        val spine = setOf("/chapter-7.xhtml")
        assertNotNull(ResourceAnchor.targetIn(locator(), spine))
        assertNull(ResourceAnchor.targetIn(locator(), setOf("/other.xhtml")))
        assertNull(ResourceAnchor.targetIn(locator(), emptySet()))
    }

    @Test
    fun `equivalent href spellings resolve to the opened spine spelling`() {
        val foreign = locator().copy(
            href = requireNotNull(Url("/OEBPS/ch2%20long.xhtml#part")),
        )
        assertEquals("OEBPS/ch2 long.xhtml", ResourceAddress.canonicalPath(foreign.href.toString()))
        assertEquals("OEBPS/ch2 long.xhtml", ResourceAddress.canonicalPath("OEBPS/ch2%20long.xhtml"))
        val target = ResourceAnchor.targetIn(foreign, setOf("OEBPS/ch2%20long.xhtml"))

        assertEquals("OEBPS/ch2%20long.xhtml", target?.href.toString())
        assertEquals(0.62, target?.locations?.progression ?: 0.0, 0.0)
    }

    @Test
    fun `duplicate spine resources are too ambiguous for the resource rung`() {
        assertNull(
            ResourceAnchor.targetIn(
                locator(),
                listOf("/chapter-7.xhtml", "/chapter-7.xhtml"),
            ),
        )
    }

    @Test
    fun `whole-book fractions must stay in range`() {
        assertTrue(ResourceAnchor.isFraction(0.0))
        assertTrue(ResourceAnchor.isFraction(1.0))
        assertFalse(ResourceAnchor.isFraction(-0.1))
        assertFalse(ResourceAnchor.isFraction(1.1))
        assertFalse(ResourceAnchor.isFraction(Double.NaN))
    }

    @Test
    fun `a fragment on the href still names its resource`() {
        // A peer's anchor may carry an id. The spine is keyed by path.
        val withFragment = requireNotNull(
            Locator.fromJSON(
                JSONObject()
                    .put("href", "/chapter-7.xhtml#p42")
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", 0.4)),
            ),
        )
        assertNotNull(ResourceAnchor.targetIn(withFragment, setOf("/chapter-7.xhtml")))
    }

    @Test
    fun `the chapter is tried before the percentage`() {
        // Issue #184: the percentage is the only rung that can land in
        // the wrong chapter, so it must be the last one tried.
        var percentageAsked = false
        val target = ResourceAnchor.resumeTarget(
            saved = locator(),
            totalProgression = 0.23,
            readingOrder = setOf("/chapter-7.xhtml"),
            byProgression = { percentageAsked = true; null },
        )
        assertEquals("/chapter-7.xhtml", target?.href.toString())
        assertEquals(0.62, target?.locations?.progression ?: 0.0, 0.0)
        assertFalse(percentageAsked)
    }

    @Test
    fun `a chapter from elsewhere gives way to the percentage`() {
        val elsewhere = requireNotNull(
            Locator.fromJSON(
                JSONObject()
                    .put("href", "/from-another-book.xhtml")
                    .put("type", "application/xhtml+xml")
                    .put("locations", JSONObject().put("progression", 0.4)),
            ),
        )
        val fromPercentage = locator(progression = 0.1, totalProgression = 0.23)
        val target = ResourceAnchor.resumeTarget(
            saved = elsewhere,
            totalProgression = 0.23,
            readingOrder = setOf("/chapter-7.xhtml"),
            byProgression = { fromPercentage },
        )
        assertEquals(fromPercentage, target)
    }

    @Test
    fun `an exact anchor is taken as it is, whole`() {
        // The ladder must not sanitise the rung it is not on: an exact
        // anchor needs its quote and selector to be verifiable.
        val exact = requireNotNull(
            Locator.fromJSON(
                JSONObject()
                    .put("href", "/chapter-7.xhtml")
                    .put("type", "application/xhtml+xml")
                    .put(
                        "locations",
                        JSONObject()
                            .put("progression", 0.62)
                            .put("cssSelector", "#p42")
                            .put("liseurAnchor", 1),
                    )
                    .put("text", JSONObject().put("highlight", "the word")),
            ),
        )
        val target = ResourceAnchor.resumeTarget(
            saved = exact,
            totalProgression = 0.23,
            readingOrder = setOf("/chapter-7.xhtml"),
            byProgression = { null },
        )
        assertEquals(exact, target)
        assertTrue(ExactLocatorAnchor.isExact(target))
    }

    @Test
    fun `nothing usable leaves the caller what it had`() {
        // Preserving the old behaviour: a locator nobody can improve on
        // is still passed through rather than dropped.
        val bare = requireNotNull(
            Locator.fromJSON(
                JSONObject()
                    .put("href", "/chapter-7.xhtml")
                    .put("type", "application/xhtml+xml"),
            ),
        )
        assertEquals(
            bare,
            ResourceAnchor.resumeTarget(bare, null, setOf("/chapter-7.xhtml")) { null },
        )
        assertNull(ResourceAnchor.resumeTarget(null, 0.5, emptySet()) { null })
    }

    @Test
    fun `a peer's own total progression is used when nothing else says`() {
        var asked: Double? = null
        ResourceAnchor.approximateTarget(
            saved = locator(totalProgression = 0.44),
            totalProgression = null,
            readingOrder = emptySet(),
            byProgression = { asked = it; null },
        )
        assertEquals(0.44, asked ?: -1.0, 0.0)
    }

    @Test
    fun `a locator written by the browser reader is read as exact here`() {
        // The literal bytes liseur-sync's reader-anchor.js emits. The two
        // readers agree on this shape or they cannot use each other's
        // anchors at all, so it is worth pinning rather than describing.
        val fromBrowser = """
            {"href":"OEBPS/ch1.xhtml","type":"application/xhtml+xml","title":"Moby-Dick",
             "locations":{"fragments":[],"progression":0.25,"totalProgression":0.1,
             "position":2,"liseurAnchor":1,"cssSelector":"body > p:nth-of-type(3)"},
             "text":{"before":"and then ","highlight":"Ishmael","after":" said nothing"}}
        """.trimIndent()
        assertTrue(ExactLocatorAnchor.isExactJson(fromBrowser))

        // And when its quote will not resolve, the chapter underneath is
        // still there to fall back to, unpolluted by the browser's own
        // spine index.
        val target = ResourceAnchor.sanitize(fromBrowser)
        assertEquals("OEBPS/ch1.xhtml", target?.href.toString())
        assertEquals(0.25, target?.locations?.progression ?: -1.0, 0.0)
        assertNull(target?.locations?.position)
        assertNull(target?.text?.highlight)
    }

    private fun locator(
        progression: Double = 0.62,
        totalProgression: Double = 0.23,
    ): Locator = requireNotNull(        Locator.fromJSON(
            JSONObject()
                .put("href", "/chapter-7.xhtml")
                .put("type", "application/xhtml+xml")
                .put(
                    "locations",
                    JSONObject()
                        .put("progression", progression)
                        .put("totalProgression", totalProgression),
                ),
        ),
    )
}
