package com.chmouel.liseur.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bound on decoding an image chosen by its filename.
 *
 * A declared cover was at least pointed at by the book. This one was
 * picked out of an untrusted archive because of what it is called, and
 * an image header can ask for any amount of memory it likes, so the
 * bound is the thing that makes guessing safe.
 */
class CoverSampleSizeTest {
    private val max = 2048L * 2048

    /**
     * The bound exists to refuse the absurd, not to choose a display
     * size, so an ordinary cover has to come through untouched.
     */
    @Test
    fun `a cover of ordinary size is decoded whole`() {
        assertEquals(1, coverSampleSize(1600, 2400, max))
        assertEquals(1, coverSampleSize(2048, 2048, max))
    }

    @Test
    fun `an image just over the bound is halved`() {
        assertEquals(2, coverSampleSize(2048, 2049, max))
    }

    /**
     * `BitmapFactory` rounds `inSampleSize` down to a power of two, so
     * returning anything else would put the real bound somewhere other
     * than where the code says it is.
     */
    @Test
    fun `the answer is always a power of two`() {
        for (edge in listOf(2049, 3000, 5000, 9000, 20_000, 100_000)) {
            val sample = coverSampleSize(edge, edge, max)!!
            assertTrue(sample > 0 && sample and (sample - 1) == 0)
        }
    }

    /** However big the claim, the result is within the bound. */
    @Test
    fun `a decompression bomb is brought under the bound`() {
        for (edge in listOf(10_000, 65_535, Int.MAX_VALUE)) {
            val sample = coverSampleSize(edge, edge, max)!!
            assertTrue((edge / sample).toLong() * (edge / sample) <= max)
        }
    }

    /**
     * A long thin image samples its short edge to nothing long before
     * its long edge is small enough. Flooring each edge at one is what
     * stops the product reading as zero and the loop stopping early on
     * an image that is still enormous.
     */
    @Test
    fun `a long thin image is still bounded`() {
        val sample = coverSampleSize(Int.MAX_VALUE, 1, max)!!
        assertTrue(Int.MAX_VALUE / sample <= max)
    }

    /**
     * A header that could not be parsed leaves the dimensions at -1.
     * There is nothing to decode, and saying so is cheaper than letting
     * the decoder discover it.
     */
    @Test
    fun `an unreadable header is not an image`() {
        assertNull(coverSampleSize(-1, -1, max))
        assertNull(coverSampleSize(0, 0, max))
        assertNull(coverSampleSize(100, 0, max))
        assertNull(coverSampleSize(0, 100, max))
    }

    /**
     * No sample size satisfies a bound of nothing, so looking for one
     * would not end. Refusing is the only answer that terminates.
     */
    @Test
    fun `a bound of nothing is refused rather than searched for`() {
        assertNull(coverSampleSize(100, 100, 0))
        assertNull(coverSampleSize(100, 100, -1))
    }
}
