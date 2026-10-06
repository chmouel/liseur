package com.chmouel.liseur.readaloud

import com.chmouel.liseur.data.bookorbit.BookOrbitCfiContext
import com.chmouel.liseur.data.bookorbit.BookOrbitFileProgress
import com.chmouel.liseur.data.bookorbit.BookOrbitPullOffer
import com.chmouel.liseur.data.bookorbit.BookOrbitRequestContext
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.reader.ListeningSettlement
import com.chmouel.liseur.reader.OpenBookHandle
import com.chmouel.liseur.reader.progress.BookPositions
import com.chmouel.liseur.sync.LatestPositionSync
import com.chmouel.liseur.sync.PositionUpdate
import com.chmouel.liseur.sync.ReadingPositionPublisher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class ListeningCheckpointsTest {

    private fun locator(href: String, progression: Double, position: Int? = null, total: Double? = null): Locator {
        val locations = JSONObject().put("progression", progression)
        position?.let { locations.put("position", it) }
        total?.let { locations.put("totalProgression", it) }
        return requireNotNull(
            Locator.fromJSON(
                JSONObject()
                    .put("href", "https://example.com/$href")
                    .put("type", "application/xhtml+xml")
                    .put("locations", locations),
            ),
        )
    }

    private val positions = listOf(
        listOf(locator("one.xhtml", 0.0, 1), locator("one.xhtml", 0.5, 2)),
        listOf(locator("two.xhtml", 0.0, 3), locator("two.xhtml", 0.25, 4), locator("two.xhtml", 0.75, 5)),
    ).let { BookPositions(it.flatten(), emptyList(), emptyMap(), it) }

    private val publication =
        Publication(Manifest(metadata = Metadata(localizedTitle = LocalizedString("Book"))))

    private fun handle(withPositions: Boolean = true) =
        OpenBookHandle("book", publication, positions.takeIf { withPositions }) {}

    private val written = mutableListOf<PositionUpdate>()
    private val synced = mutableListOf<String>()

    private fun TestScope.checkpoints(): ListeningCheckpoints {
        val sync = LatestPositionSync(
            scope = backgroundScope,
            request = {
                synced += it
                SyncOutcome.Success
            },
            scheduleRetry = {},
            onError = { _, error -> throw error },
        )
        val publisher = ReadingPositionPublisher(
            scope = backgroundScope,
            overrideFor = { FinishedOverride.NONE },
            persist = { update, _ -> written += update },
            refreshFinished = {},
            markFinished = {},
            latestSync = sync,
            scheduleClose = {},
            onError = { _, error -> throw error },
        )
        return ListeningCheckpoints(publisher, now = { 9_000L })
    }

    @Test
    fun `a sentence heard is saved where turning to it would save it`() = runTest {
        val handle = handle()
        // Readium's content iterator guesses a whole-book progression of
        // its own and carries no position.
        val heard = locator("two.xhtml", 0.5, total = 0.99)

        assertTrue(checkpoints().save(handle, handle.claimForListening(), heard, spokenAt = 1_000, share = true))
        runCurrent()

        val pageTurn = positions.resolve(handle.prepareLocator(heard))!!.progression
        val saved = written.single()
        assertEquals(pageTurn, saved.progression!!, 0.0)
        val stored = Locator.fromJSON(JSONObject(saved.locatorJson))!!
        assertEquals(pageTurn, stored.locations.totalProgression!!, 0.0)
        assertTrue(handle.listened)
        assertTrue(handle.awaitingPageCapture)
        assertSame(heard, handle.lastHeard)
    }

    @Test
    fun `a checkpoint keeps when the sentence was heard`() = runTest {
        val handle = handle()
        checkpoints().save(handle, handle.claimForListening(), locator("one.xhtml", 0.2), spokenAt = 1_000, share = true)
        runCurrent()

        assertEquals(1_000L, written.single().readAt)
        assertEquals(9_000L, written.single().updatedAt)
    }

    @Test
    fun `checkpoints while playing stay local and the last one syncs`() = runTest {
        val handle = handle()
        val checkpoints = checkpoints()
        val held = handle.claimForListening()

        checkpoints.save(handle, held, locator("one.xhtml", 0.2), spokenAt = 1_000, share = false)
        runCurrent()
        assertEquals(1, written.size)
        assertEquals(emptyList<String>(), synced)

        checkpoints.save(handle, held, locator("one.xhtml", 0.6), spokenAt = 2_000, share = true)
        runCurrent()
        assertEquals(2, written.size)
        assertEquals(listOf("book"), synced)
    }

    @Test
    fun `a session that handed the place back saves nothing`() = runTest {
        val handle = handle()
        val held = handle.claimForListening()
        handle.handBack(held)

        assertFalse(checkpoints().save(handle, held, locator("one.xhtml", 0.2), spokenAt = 1_000, share = true))
        assertFalse(
            checkpoints().save(handle, held - 1, locator("one.xhtml", 0.2), spokenAt = 1_000, share = true),
        )
        runCurrent()

        assertEquals(emptyList<PositionUpdate>(), written)
        assertFalse(handle.listened)
    }

    @Test
    fun `a sentence that does not resolve to a position is skipped`() = runTest {
        val handle = handle(withPositions = false)

        assertFalse(
            checkpoints().save(handle, handle.claimForListening(), locator("one.xhtml", 0.2), spokenAt = 1_000, share = true),
        )
        runCurrent()

        assertEquals(emptyList<PositionUpdate>(), written)
    }

    @Test
    fun `a declined server place is declined once, an accepted one each time`() = runTest {
        val context = BookOrbitCfiContext(BookOrbitRequestContext("account", 7, "https://example.org"), "book", 12, 34, 2)
        val remote = BookOrbitFileProgress(25.0, "epubcfi(/6/2!/4/2:3)", null, null, "saved")
        val pull = BookOrbitPullOffer(context, remote, 4, "local", "remote", "OPS/one.xhtml")
        val declined = pull.copy(expectedRevision = 5)
        val handle = handle()
        handle.listeningSettles = ListeningSettlement(pull = pull, declined = declined)
        val checkpoints = checkpoints()
        val held = handle.claimForListening()

        checkpoints.save(handle, held, locator("one.xhtml", 0.2), spokenAt = 1_000, share = false)
        checkpoints.save(handle, held, locator("one.xhtml", 0.6), spokenAt = 2_000, share = false)
        runCurrent()

        assertEquals(listOf(pull, pull), written.map { it.bookOrbitPull })
        assertEquals(declined, written[0].bookOrbitDeclined)
        assertNull(written[1].bookOrbitDeclined)
    }
}
