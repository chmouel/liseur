package com.chmouel.liseur.reader.progress

import com.chmouel.liseur.data.bookorbit.BookOrbitCfiContext
import com.chmouel.liseur.data.bookorbit.BookOrbitConflictPreview
import com.chmouel.liseur.data.bookorbit.BookOrbitEpubPackage
import com.chmouel.liseur.data.bookorbit.BookOrbitFileProgress
import com.chmouel.liseur.data.bookorbit.BookOrbitOpenedEpub
import com.chmouel.liseur.data.bookorbit.BookOrbitPullOffer
import com.chmouel.liseur.data.bookorbit.BookOrbitRequestContext
import com.chmouel.liseur.data.db.BookOrbitPositionAgreement
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BookOrbitOpeningTest {
    private val context = BookOrbitCfiContext(
        BookOrbitRequestContext("account", 7, "https://example.org"), "book", 12, 34, 2,
    )
    private val remote = BookOrbitFileProgress(25.0, "epubcfi(/6/2!/4/2:3)", null, null, "saved")
    private val offer = BookOrbitPullOffer(context, remote, 4, "local", "remote", "OPS/one.xhtml")
    private val preview = BookOrbitConflictPreview(
        context, remote, 4, "local", 0.1, "epubcfi(/6/2!/4/2:1)",
        BookOrbitPositionAgreement("account", "book", 12, 34, 2, 7, "https://example.org"),
    )
    private val opened = BookOrbitOpenedEpub(
        context,
        BookOrbitEpubPackage.parsePackage(
            "OPS/package.opf",
            checkNotNull(javaClass.getResourceAsStream("/bookorbit/OPS/package.opf")).use { it.readBytes() },
        ),
        File("unused.epub"), "file:///unused.epub", 0, 0,
    )

    private fun choice() = BookOrbitOpening().apply { propose(null, preview to offer) }

    @Test
    fun `failed opening removes the choice that suppresses local position writes`() {
        val opening = choice()
        assertNotNull(opening.choice)
        opening.failed()
        assertNull(opening.choice)
        assertNull(opening.verify("remote", opened))
        assertFalse(opening.choose(preview, takeRemote = true))
        assertFalse(opening.choose(preview, takeRemote = false))
    }

    @Test
    fun `failed recheck revokes the previously verified pull`() {
        val opening = BookOrbitOpening().apply { propose(offer, null) }
        opening.verify("remote", opened)
        assertEquals(offer, opening.verifiedPull)
        opening.failed()
        assertNull(opening.pull)
        assertNull(opening.verifiedPull)
        assertNull(opening.verifiedEpub)
    }

    @Test
    fun `neither choice is allowed before active view verification`() {
        val opening = choice()
        assertFalse(opening.choose(preview, takeRemote = false))
        assertFalse(opening.choose(preview, takeRemote = true))
        assertNull(opening.verify("different locator", opened))
        assertNull(opening.verify("remote", null))
        assertNull(opening.verify("remote", opened.copy(context = context.copy(fileId = 99))))
        assertNull(opening.verifiedEpub)
        assertEquals(preview, opening.verify("remote", opened))
        assertTrue(opening.choose(preview, takeRemote = false))
        assertEquals(preview to false, opening.chosen)
    }

    @Test
    fun `replacement navigator requires a fresh proof before either choice`() {
        val opening = choice()
        opening.verify("remote", opened)
        opening.navigatorLost()
        assertNotNull(opening.choice)
        assertNull(opening.verifiedEpub)
        assertFalse(opening.choose(preview, takeRemote = true))
        assertFalse(opening.choose(preview, takeRemote = false))
        assertEquals(preview, opening.verify("remote", opened))
        assertTrue(opening.choose(preview, takeRemote = true))
    }

    @Test
    fun `view teardown preserves verified automatic pull for post-close adoption`() {
        val opening = BookOrbitOpening().apply { propose(offer, null) }
        opening.verify("remote", opened)
        opening.navigatorLost()
        assertEquals(offer, opening.pull)
        assertEquals(offer, opening.verifiedPull)
        assertEquals(opened, opening.verifiedEpub)
    }

    @Test
    fun `closing after an explicit choice retains proof for the queued adoption`() {
        for (takeRemote in listOf(false, true)) {
            val opening = choice()
            opening.verify("remote", opened)
            assertTrue(opening.choose(preview, takeRemote))
            opening.navigatorLost()
            assertEquals(preview to takeRemote, opening.chosen)
            assertEquals(opened, opening.verifiedEpub)
            assertEquals(preview to offer, opening.choice)
        }
    }

    @Test
    fun `a changed preview cannot use an older proof`() {
        val opening = choice()
        opening.verify("remote", opened)
        assertFalse(opening.choose(preview.copy(localRevision = 5), takeRemote = true))
        opening.propose(null, preview.copy(localRevision = 5) to offer)
        assertNull(opening.verifiedEpub)
        assertNull(opening.chosen)
        assertFalse(opening.choose(preview.copy(localRevision = 5), takeRemote = true))
    }

    @Test
    fun `retry without a verified remote anchor only allows keeping the local place`() {
        val retry = preview.copy(baseline = preview.baseline.copy(
            outgoingBytes = byteArrayOf(1), attemptState = "RETRY_REQUIRED",
        ))
        val opening = BookOrbitOpening().apply { propose(null, retry to null) }
        assertNull(opening.verify("remote", opened))
        opening.navigatorLost()
        assertFalse(opening.choose(retry, takeRemote = true))
        assertTrue(opening.choose(retry, takeRemote = false))
        assertEquals(retry to false, opening.chosen)
        assertNull(opening.verifiedEpub)
    }

    @Test
    fun `a recreated reader has no queued choice or active view proof`() {
        val old = choice()
        old.verify("remote", opened)
        old.choose(preview, takeRemote = true)
        val restarted = choice()
        assertNull(restarted.chosen)
        assertNull(restarted.verifiedEpub)
        assertFalse(restarted.choose(preview, takeRemote = true))
    }
}
