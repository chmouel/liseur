package com.chmouel.liseur.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.data.db.ReadingProgress
import com.chmouel.liseur.data.db.recordLocal
import com.chmouel.liseur.data.remote.FurthestDestination
import com.chmouel.liseur.data.remote.ResumeConfidence
import com.chmouel.liseur.data.remote.SyncOutcome
import com.chmouel.liseur.data.remote.SyncPreview
import com.chmouel.liseur.domain.FinishedOverride
import com.chmouel.liseur.reader.progress.samePage
import com.chmouel.liseur.sync.LatestPositionSync
import com.chmouel.liseur.sync.PositionUpdate
import com.chmouel.liseur.sync.ReadingPositionPublisher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class FurthestPositionCaptureTest {
    private lateinit var db: LiseurDatabase
    private val dao get() = db.readingProgressDao()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java,
        ).build()
    }

    @After
    fun close() = db.close()

    private suspend fun original(): ReadingProgress {
        dao.recordLocal("book", ORIGINAL, 0.31, null, "reading", 1000)
        return dao.get("book")!!
    }

    private fun preview(row: ReadingProgress) = SyncPreview(
        local = row.totalProgression, remote = null, remoteAt = null,
        localRevision = row.localRevision, localLocatorJson = row.locatorJson,
        accountKey = "account", peerId = "peer", resolvable = false,
        furthest = FurthestDestination(
            "account", "work", "edition", "original-op", 0.7, FURTHEST,
            500, ResumeConfidence.EXACT,
        ),
    )

    private fun TestScope.publisher() = ReadingPositionPublisher(
        scope = backgroundScope,
        overrideFor = { FinishedOverride.NONE },
        persist = { update, status ->
            dao.recordLocal(update.bookUrl, update.locatorJson, update.progression,
                null, status, update.updatedAt)
        },
        refreshFinished = {}, markFinished = {}, scheduleClose = {},
        onError = { _, error -> throw error },
        latestSync = LatestPositionSync(
            backgroundScope, { SyncOutcome.Success }, {}, { _, error -> throw error },
        ),
    )

    private fun update(locator: String = ORIGINAL) = PositionUpdate(
        bookUrl = "book", locatorJson = locator, progression = 0.31,
        readingSecondsPerPosition = null, readingPaceSamples = null,
        readingPaceElapsedMs = null, readingPaceEvidence = null, updatedAt = 2000,
    )

    @Test
    fun `scrolled recapture flush advances the revision without invalidating the original passage`() = runTest {
        val before = original()
        val offered = preview(before)
        val publisher = publisher()
        assertTrue(publisher.publish(update()))
        assertTrue(publisher.flush("book"))
        val after = dao.get("book")!!
        assertEquals(before.localRevision + 1, after.localRevision)
        // The original implementation used the revision from before the capture.
        assertFalse(dao.applyPeerPull("book", offered.localRevision!!, 0.7, "reading", 3000, FURTHEST))
        val captured = offered.afterFurthestCapture(before, after, 1, ::samePage)!!
        assertEquals(after.localRevision, captured.localRevision)
        assertSame(offered.furthest, captured.furthest)
        assertEquals(offered.peerId, captured.peerId)
        assertTrue(dao.applyPeerPull("book", captured.localRevision!!, 0.7, "reading", 3000, FURTHEST))
        assertEquals(FURTHEST, dao.get("book")!!.locatorJson)
    }

    @Test
    fun `paginated capture that writes nothing keeps the original revision`() = runTest {
        val before = original()
        val offered = preview(before)
        assertEquals(offered, offered.afterFurthestCapture(before, before, 0, ::samePage))
    }

    @Test
    fun `a write while the dialog was open cannot be accepted as the action capture`() = runTest {
        val original = original()
        val offered = preview(original)
        val publisher = publisher()
        assertTrue(publisher.publish(update()))
        assertTrue(publisher.flush("book"))
        val before = dao.get("book")
        assertTrue(publisher.publish(update()))
        assertTrue(publisher.flush("book"))
        assertNull(offered.afterFurthestCapture(before, dao.get("book"), 1, ::samePage))
    }

    @Test
    fun `scrolling to another exact passage at the same percentage supersedes the choice`() = runTest {
        val before = original()
        val publisher = publisher()
        assertTrue(publisher.publish(update(MOVED)))
        assertTrue(publisher.flush("book"))
        assertNull(preview(before).afterFurthestCapture(before, dao.get("book"), 1, ::samePage))
        assertEquals(MOVED, dao.get("book")!!.locatorJson)
    }

    @Test
    fun `movement away and back during capture is not a single intentional write`() = runTest {
        val before = original()
        val publisher = publisher()
        assertTrue(publisher.publish(update(MOVED)))
        assertTrue(publisher.publish(update()))
        assertTrue(publisher.flush("book"))
        assertNull(preview(before).afterFurthestCapture(before, dao.get("book"), 2, ::samePage))
    }

    @Test
    fun `an unrelated same-place write is not attributed to the capture`() = runTest {
        val before = original()
        dao.applyPeerPull("book", before.localRevision, 0.31, "reading", 2000, ORIGINAL)
        assertNull(preview(before).afterFurthestCapture(before, dao.get("book"), 0, ::samePage))
    }

    @Test
    fun `unflushed reader movement cannot hide behind the capture revision`() = runTest {
        val before = original()
        val publisher = publisher()
        assertTrue(publisher.publish(update()))
        assertTrue(publisher.flush("book"))
        assertNull(preview(before).afterFurthestCapture(before, dao.get("book"), 2, ::samePage))
    }

    @Test
    fun `movement after successful capture is still rejected by the atomic adoption guard`() = runTest {
        val before = original()
        val publisher = publisher()
        assertTrue(publisher.publish(update()))
        assertTrue(publisher.flush("book"))
        val captured = preview(before).afterFurthestCapture(before, dao.get("book"), 1, ::samePage)!!
        assertTrue(publisher.publish(update(MOVED)))
        assertTrue(publisher.flush("book"))
        assertFalse(dao.applyPeerPull("book", captured.localRevision!!, 0.7, "reading", 3000, FURTHEST))
        assertEquals(MOVED, dao.get("book")!!.locatorJson)
    }

    @Test
    fun `capture metadata may change without changing its exact passage`() = runTest {
        val before = original()
        val publisher = publisher()
        assertTrue(publisher.publish(update(ORIGINAL.replace("\"progression\":0.1", "\"progression\":0.10000001"))))
        assertTrue(publisher.flush("book"))
        val captured = preview(before).afterFurthestCapture(before, dao.get("book"), 1, ::samePage)
        assertEquals(2L, captured?.localRevision)
    }

    private companion object {
        const val ORIGINAL = """{"href":"c4.xhtml","type":"application/xhtml+xml","locations":{"liseurAnchor":1,"cssSelector":"#p1","progression":0.1,"totalProgression":0.31},"text":{"highlight":"Original passage"}}"""
        const val MOVED = """{"href":"c4.xhtml","type":"application/xhtml+xml","locations":{"liseurAnchor":1,"cssSelector":"#p2","progression":0.1,"totalProgression":0.31},"text":{"highlight":"Another passage"}}"""
        const val FURTHEST = """{"href":"c8.xhtml","type":"application/xhtml+xml","locations":{"liseurAnchor":1,"cssSelector":"#p1","totalProgression":0.7},"text":{"highlight":"Furthest passage"}}"""
    }
}
