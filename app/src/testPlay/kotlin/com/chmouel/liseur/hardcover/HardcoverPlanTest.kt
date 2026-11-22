package com.chmouel.liseur.hardcover

import com.chmouel.liseur.domain.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardcoverPlanTest {

    private fun local(
        status: ReadingStatus,
        chosen: Boolean = false,
        progression: Double? = null,
        startedOn: String? = null,
        finishedOn: String? = null,
        rating: Double? = null,
    ) = LocalReading(status, chosen, progression, startedOn, finishedOn, rating)

    private fun shelf(statusId: Int? = null, rating: Double? = null, read: HardcoverRead? = null) =
        HardcoverShelfState(userBookId = 7, statusId = statusId, rating = rating, read = read)

    private val absent = HardcoverShelfState(null, null, null, null)

    @Test
    fun `every status maps, and the chosen-only ones need a choice`() {
        assertEquals(HardcoverStatus.CURRENTLY_READING, HardcoverPlan.targetStatus(local(ReadingStatus.READING)))
        assertEquals(HardcoverStatus.READ, HardcoverPlan.targetStatus(local(ReadingStatus.FINISHED)))
        assertNull(HardcoverPlan.targetStatus(local(ReadingStatus.READY_TO_READ, chosen = true)))
        for ((status, id) in listOf(
            ReadingStatus.WANT_TO_READ to HardcoverStatus.WANT_TO_READ,
            ReadingStatus.PAUSED to HardcoverStatus.PAUSED,
            ReadingStatus.DID_NOT_FINISH to HardcoverStatus.DID_NOT_FINISH,
        )) {
            assertEquals(id, HardcoverPlan.targetStatus(local(status, chosen = true)))
            assertNull(HardcoverPlan.targetStatus(local(status, chosen = false)))
        }
    }

    @Test
    fun `a book not on the shelf is added with its status and rating`() {
        assertEquals(
            HardcoverEntryStep.Add(HardcoverStatus.READ, 4.5),
            HardcoverPlan.entryStep(absent, local(ReadingStatus.FINISHED, rating = 4.5), pushedRating = null),
        )
        assertNull(HardcoverPlan.entryStep(absent, local(ReadingStatus.READY_TO_READ), pushedRating = null))
    }

    @Test
    fun `a book read on hardcover stays read`() {
        assertNull(HardcoverPlan.entryStep(shelf(HardcoverStatus.READ), local(ReadingStatus.READING), null))
        assertNull(
            HardcoverPlan.entryStep(
                shelf(HardcoverStatus.READ),
                local(ReadingStatus.DID_NOT_FINISH, chosen = true),
                null,
            ),
        )
    }

    @Test
    fun `paused and did not finish move only for a chosen status`() {
        assertNull(HardcoverPlan.entryStep(shelf(HardcoverStatus.PAUSED), local(ReadingStatus.READING), null))
        assertEquals(
            HardcoverEntryStep.Update(HardcoverStatus.CURRENTLY_READING, null),
            HardcoverPlan.entryStep(
                shelf(HardcoverStatus.DID_NOT_FINISH),
                local(ReadingStatus.READING, chosen = true),
                null,
            ),
        )
    }

    @Test
    fun `an entry that already says it is left alone`() {
        assertNull(
            HardcoverPlan.entryStep(
                shelf(HardcoverStatus.CURRENTLY_READING, rating = 4.0),
                local(ReadingStatus.READING, rating = 4.0),
                pushedRating = 4.0,
            ),
        )
    }

    @Test
    fun `a rating is cleared only when this device set it`() {
        val unrated = local(ReadingStatus.READING)
        val reading = HardcoverStatus.CURRENTLY_READING
        assertEquals(
            HardcoverEntryStep.Update(null, null, clearRating = true),
            HardcoverPlan.entryStep(shelf(reading, rating = 3.0), unrated, pushedRating = 3.0),
        )
        assertNull(HardcoverPlan.entryStep(shelf(reading, rating = 3.0), unrated, pushedRating = null))
        assertNull(HardcoverPlan.entryStep(shelf(reading, rating = 3.5), unrated, pushedRating = 3.0))
    }

    @Test
    fun `progress is logged on the open read and never goes backwards`() {
        val reading = HardcoverStatus.CURRENTLY_READING
        val read = HardcoverRead(id = 3, pages = 100, startedAt = "2026-01-02", finishedAt = null)
        assertEquals(
            HardcoverReadStep(pages = 150),
            HardcoverPlan.readStep(reading, read, local(ReadingStatus.READING, progression = 0.5), 300, false),
        )
        assertNull(
            HardcoverPlan.readStep(reading, read, local(ReadingStatus.READING, progression = 0.2), 300, false),
        )
    }

    @Test
    fun `pages count in the read's own edition unless the reader chose another here`() {
        val reading = HardcoverStatus.CURRENTLY_READING
        val local = local(ReadingStatus.READING, progression = 0.5)
        val read = HardcoverRead(3, pages = 100, startedAt = "2026-01-02", finishedAt = null, editionId = 8, editionPages = 600)
        assertEquals(
            HardcoverReadStep(pages = 300),
            HardcoverPlan.readStep(reading, read, local, 300, false, editionId = 5, editionChosen = false),
        )
        assertEquals(
            HardcoverReadStep(pages = 150, editionId = 5),
            HardcoverPlan.readStep(reading, read.copy(pages = 400), local, 300, false, editionId = 5, editionChosen = true),
        )
    }

    @Test
    fun `an edition chosen here moves the entry, one found alone does not`() {
        val onOther = shelf(HardcoverStatus.CURRENTLY_READING).copy(editionId = 8)
        val local = local(ReadingStatus.READING)
        assertNull(HardcoverPlan.entryStep(onOther, local, null))
        assertEquals(
            HardcoverEntryStep.Update(null, null, editionId = 5),
            HardcoverPlan.entryStep(onOther, local, null, chosenEdition = 5),
        )
        assertNull(HardcoverPlan.entryStep(onOther.copy(editionId = 5), local, null, chosenEdition = 5))
    }

    @Test
    fun `no progress goes out while hardcover has the book paused or wanted`() {
        val read = HardcoverRead(id = 3, pages = 100, startedAt = null, finishedAt = null)
        val local = local(ReadingStatus.READING, progression = 0.9)
        assertNull(HardcoverPlan.readStep(HardcoverStatus.PAUSED, read, local, 300, false))
        assertNull(HardcoverPlan.readStep(HardcoverStatus.WANT_TO_READ, read, local, 300, false))
    }

    @Test
    fun `a paused read still moves to the edition chosen here, with nothing else`() {
        val read = HardcoverRead(id = 3, pages = 100, startedAt = null, finishedAt = null, editionId = 8)
        val local = local(ReadingStatus.READING, progression = 0.9)
        assertEquals(
            HardcoverReadStep(editionId = 5),
            HardcoverPlan.readStep(HardcoverStatus.PAUSED, read, local, 300, false, editionId = 5, editionChosen = true),
        )
    }

    @Test
    fun `an unfinished book never reports its last page`() {
        assertEquals(299, HardcoverPlan.pagesFor(0.999, 300, finished = false))
        assertEquals(300, HardcoverPlan.pagesFor(0.5, 300, finished = true))
        assertNull(HardcoverPlan.pagesFor(0.5, null, finished = false))
        assertNull(HardcoverPlan.pagesFor(0.5, 0, finished = false))
    }

    @Test
    fun `a read hardcover started today gets the real start`() {
        val read = HardcoverRead(id = 3, pages = null, startedAt = "2026-03-10", finishedAt = null)
        assertEquals(
            HardcoverReadStep(startedAt = "2026-03-01"),
            HardcoverPlan.readStep(
                HardcoverStatus.CURRENTLY_READING,
                read,
                local(ReadingStatus.READING, startedOn = "2026-03-01"),
                editionPages = null,
                justFinished = false,
            ),
        )
        // A read that began earlier than anything here keeps its own start.
        assertNull(
            HardcoverPlan.readStep(
                HardcoverStatus.CURRENTLY_READING,
                read.copy(startedAt = "2026-02-01"),
                local(ReadingStatus.READING, startedOn = "2026-03-01"),
                editionPages = null,
                justFinished = false,
            ),
        )
    }

    @Test
    fun `a book just finished gets its real finish and start dates`() {
        val stamped = HardcoverRead(id = 3, pages = null, startedAt = "2026-04-20", finishedAt = "2026-04-20")
        assertEquals(
            HardcoverReadStep(startedAt = "2026-03-01", finishedAt = "2026-04-18"),
            HardcoverPlan.readStep(
                HardcoverStatus.READ,
                stamped,
                local(ReadingStatus.FINISHED, startedOn = "2026-03-01", finishedOn = "2026-04-18"),
                editionPages = 300,
                justFinished = true,
            ),
        )
    }

    @Test
    fun `a book read before keeps its dates`() {
        val old = HardcoverRead(id = 3, pages = null, startedAt = "2020-01-01", finishedAt = "2020-02-01")
        assertNull(
            HardcoverPlan.readStep(
                HardcoverStatus.READ,
                old,
                local(ReadingStatus.FINISHED, startedOn = "2026-03-01", finishedOn = "2026-04-18"),
                editionPages = 300,
                justFinished = false,
            ),
        )
        assertEquals(
            HardcoverReadStep(finishedAt = "2026-04-18"),
            HardcoverPlan.readStep(
                HardcoverStatus.READ,
                old.copy(finishedAt = null),
                local(ReadingStatus.FINISHED, finishedOn = "2026-04-18"),
                editionPages = 300,
                justFinished = false,
            ),
        )
    }

    @Test
    fun `half stars become hardcover ratings`() {
        assertEquals(1.0, HardcoverPlan.ratingFromLocal("2")!!, 0.0)
        assertEquals(3.5, HardcoverPlan.ratingFromLocal("7")!!, 0.0)
        assertEquals(5.0, HardcoverPlan.ratingFromLocal("10")!!, 0.0)
        assertNull(HardcoverPlan.ratingFromLocal("1"))
        assertNull(HardcoverPlan.ratingFromLocal("11"))
        assertNull(HardcoverPlan.ratingFromLocal(null))
    }
}
