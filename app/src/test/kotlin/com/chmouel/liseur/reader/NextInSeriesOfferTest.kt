package com.chmouel.liseur.reader

import com.chmouel.liseur.reader.progress.ReaderProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextInSeriesOfferTest {

    @Test
    fun `a manually finished book does not offer the next volume before its last page`() {
        assertFalse(shouldOfferNextInSeries(finished = true, progress = progress(position = 20)))
    }

    @Test
    fun `a finished book offers the next volume on its last page`() {
        assertTrue(shouldOfferNextInSeries(finished = true, progress = progress(position = 100)))
    }

    @Test
    fun `the next volume is not offered before reader position is known`() {
        assertFalse(shouldOfferNextInSeries(finished = true, progress = null))
    }

    private fun progress(position: Int) = ReaderProgress(
        position = position,
        totalPositions = 100,
        totalProgression = position / 100f,
        chapterTitle = null,
        minutesLeftInChapter = 0,
        minutesLeftInBook = 0,
        isSpeedMeasured = false,
    )
}
