package com.chmouel.liseur.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class FurthestPositionDaoTest {
    private lateinit var db: LiseurDatabase
    private lateinit var dao: FurthestPositionDao

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java,
        ).build()
        dao = db.furthestPositionDao()
    }

    @After
    fun close() = db.close()

    private fun candidate(
        fraction: Double, seq: Long, edition: String = "edition", origin: String = "",
    ) = FurthestPosition("account", "work", edition, origin, fraction, seq, "original-$seq")

    @Test
    fun `strict maxima retain complete payloads by edition and origin with deterministic ties`() = runTest {
        dao.observe(candidate(0.0, 1))
        dao.observe(candidate(0.7, 3))
        dao.observe(candidate(0.7, 2))
        dao.observe(candidate(0.31, 4))
        assertEquals("original-2", dao.forWork("account", "work").single().payload)
        dao.observe(candidate(0.700001, 5))
        dao.observe(candidate(Double.NaN, 6))
        dao.observe(candidate(2.0, 7))
        dao.observe(candidate(0.1, 8, edition = "another"))
        dao.observe(candidate(0.2, 9, origin = "legacy"))
        val rows = dao.forWork("account", "work")
        assertEquals(3, rows.size)
        assertEquals("original-5", rows.single { it.edition == "edition" && it.origin == "" }.payload)
        assertEquals(emptyList<FurthestPosition>(), dao.forWork("someone-else", "work"))
    }

    @Test
    fun `rekey and disconnect carry or remove observations and delivery acknowledgements together`() = runTest {
        db.readingProgressDao().recordLocal("book", "{}", 0.7, null, "reading", 1)
        dao.observe(candidate(0.7, 1))
        dao.acknowledge(PeakDelivery("account", "book", "work", 1, true))
        dao.rekeyPeer("account", "new-account")
        assertEquals(0, dao.countForPeer("account"))
        assertEquals(2, dao.countForPeer("new-account"))
        dao.forgetPeer("new-account")
        assertEquals(0, dao.countForPeer("new-account"))
        assertEquals(0.7, db.readingProgressDao().get("book")!!.peakProgression!!, 0.0)
    }

    @Test
    fun `forgetting a replaced local file cannot leave its peak marked delivered`() = runTest {
        db.readingProgressDao().recordLocal("book", "{}", 0.7, null, "reading", 1)
        dao.acknowledge(PeakDelivery("account", "book", "work", 1, true))
        db.readingProgressDao().forget("book")
        assertNull(dao.delivered("account", "book", "work"))
    }
}
