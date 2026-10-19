package com.chmouel.liseur.reader.footnotes

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class SameDocumentLinksTest {

    // The script must stay plain text: the harness in
    // hack/verify-same-document-links lifts it out of the Kotlin and runs
    // the very lines the app ships, and a template would break that.
    @Test
    fun `the script carries no interpolation`() {
        assertTrue(SameDocumentLinks.SCRIPT.contains("LiseurLinks.beginClick()"))
        assertTrue(SameDocumentLinks.SCRIPT.contains("LiseurLinks.follow(fragment)"))
        assertTrue(SameDocumentLinks.SCRIPT.contains('"'.toString() + "installed" + '"'))
        assertTrue(SameDocumentLinks.SCRIPT.contains('"' + "present" + '"'))
    }

    @Test
    fun `an acknowledgment is parsed from a quoted answer`() {
        assertEquals(SameDocumentLinks.Result.INSTALLED, SameDocumentLinks.parse("\"installed\""))
        assertEquals(SameDocumentLinks.Result.PRESENT, SameDocumentLinks.parse(" \"present\" "))
        assertEquals(SameDocumentLinks.Result.FAILED, SameDocumentLinks.parse(null))
        assertEquals(SameDocumentLinks.Result.FAILED, SameDocumentLinks.parse("null"))
        assertEquals(SameDocumentLinks.Result.FAILED, SameDocumentLinks.parse("\"nonsense\""))
    }

    @Test
    fun `a fragment payload cannot leave the emitting resource`() {
        val resource = Link(href = Url("EPUB/text/ch1.xhtml")!!)
        // Whatever a book puts in its ids, the payload is used as a
        // fragment and only as a fragment: the destination either stays
        // inside the emitting resource or does not parse and is dropped.
        for (payload in listOf(
            "fn1",
            "../other.xhtml",
            "https://example.org",
            "#",
            "%",
            "?",
        )) {
            val destination = sameDocumentDestination(resource, payload) ?: continue
            assertEquals(
                payload,
                "EPUB/text/ch1.xhtml",
                destination.url().toString().substringBefore("#"),
            )
        }
    }

    @Test
    fun `a payload that cannot be a fragment is dropped`() {
        val resource = Link(href = Url("EPUB/text/ch1.xhtml")!!)
        for (payload in listOf("", "  ", "fn1#again")) {
            assertNull(payload, sameDocumentDestination(resource, payload))
        }
    }

    @Test
    fun `the destination fragment replaces rather than appends`() {
        val resource = Link(href = Url("EPUB/text/ch1.xhtml#fn9")!!)
        val destination = sameDocumentDestination(resource, "fn1")
        assertEquals("EPUB/text/ch1.xhtml#fn1", destination!!.url().toString())
    }

    @Test
    fun `two rapid clicks are judged independently`() {
        val clicks = SameDocumentClicks()
        clicks.beginClick()
        clicks.markConsumed()
        assertTrue(clicks.currentClickConsumed())
        clicks.beginClick()
        assertEquals(false, clicks.currentClickConsumed())
    }

    @Test
    fun `a bridge suppresses a click a decoration consumed`() {
        val clicks = SameDocumentClicks()
        val posted = mutableListOf<Runnable>()
        var received: Pair<Link, String>? = null
        val bridge = SameDocumentLinkBridge(
            resource = Link(href = Url("EPUB/text/ch1.xhtml")!!),
            clicks = clicks,
            onSameDocumentLink = { resource, fragment -> received = resource to fragment },
            post = { posted.add(it) },
        )
        // The capture-phase token, then the decoration's mark, then the
        // follow — one click dispatch, in order, on one thread.
        bridge.beginClick()
        clicks.markConsumed()
        bridge.follow("fn1")
        assertTrue(posted.isEmpty())
        assertNull(received)
    }

    @Test
    fun `the verdict is decided on the bridge thread, before the hop`() {
        val clicks = SameDocumentClicks()
        val posted = mutableListOf<Runnable>()
        var received: Pair<Link, String>? = null
        val bridge = SameDocumentLinkBridge(
            resource = Link(href = Url("EPUB/text/ch1.xhtml")!!),
            clicks = clicks,
            onSameDocumentLink = { resource, fragment -> received = resource to fragment },
            post = { posted.add(it) },
        )
        bridge.beginClick()
        bridge.follow("fn1")
        // The click's verdict was taken when follow ran; a decoration
        // marking afterwards belongs to a later click and cannot un-follow
        // this one, nor can a delay on the main thread outpace it.
        assertEquals(1, posted.size)
        clicks.markConsumed()
        posted.single().run()
        assertNotNull(received)
        assertEquals("fn1", received!!.second)
    }

    @Test
    fun `a blank fragment never reaches the callback`() {
        val clicks = SameDocumentClicks()
        val posted = mutableListOf<Runnable>()
        val bridge = SameDocumentLinkBridge(
            resource = Link(href = Url("EPUB/text/ch1.xhtml")!!),
            clicks = clicks,
            onSameDocumentLink = { _, _ -> },
            post = { posted.add(it) },
        )
        bridge.beginClick()
        bridge.follow("")
        assertTrue(posted.isEmpty())
    }
}
