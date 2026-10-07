package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ReadAloudSpeedTest {
    @Test
    fun `a stored speed plays at the nearest one offered`() {
        assertEquals(1.25f, ReadAloudSpeed.of(1.25f))
        assertEquals(1.25f, ReadAloudSpeed.of(1.3f))
        assertEquals(2f, ReadAloudSpeed.of(5f))
        assertEquals(0.75f, ReadAloudSpeed.of(0f))
        assertEquals(1f, ReadAloudSpeed.of(Float.NaN))
    }

    @Test
    fun `a speed is written as the reader's language writes numbers`() {
        assertEquals("1", ReadAloudSpeed.number(1f, Locale.US))
        assertEquals("1.25", ReadAloudSpeed.number(1.25f, Locale.US))
        assertEquals("1,5", ReadAloudSpeed.number(1.5f, Locale.FRANCE))
    }
}
