package com.chmouel.liseur.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextInSeriesOfferTest {

    @Test
    fun `a finished book does not offer the next volume before the endpaper`() {
        assertFalse(shouldOfferEndpaperContinuation(finished = true, endpaperReached = false))
    }

    @Test
    fun `a finished book offers the next volume on the endpaper`() {
        assertTrue(shouldOfferEndpaperContinuation(finished = true, endpaperReached = true))
    }

    @Test
    fun `an unfinished book does not offer the next volume from the endpaper`() {
        assertFalse(shouldOfferEndpaperContinuation(finished = false, endpaperReached = true))
    }
}
