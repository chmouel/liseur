package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CachedLookupTest {

    private class Answer(val name: String)

    private var found: Answer? = Answer("first")
    private var lookups = 0
    private var checks = 0
    private var good = true

    private val cache = CachedLookup<Answer>(
        stillGood = {
            checks++
            good
        },
        lookup = {
            lookups++
            found
        },
    )

    @Test
    fun `the first call has to go and look`() {
        val first = found
        assertSame(first, cache.current())
        assertEquals(1, lookups)
    }

    @Test
    fun `nothing is checked when nothing is held`() {
        cache.current()
        assertEquals(0, checks)
    }

    @Test
    fun `a value that still does is handed back without looking again`() {
        val first = cache.current()
        repeat(60) { assertSame(first, cache.current()) }
        assertEquals(1, lookups)
    }

    @Test
    fun `a value that no longer does is replaced`() {
        val first = cache.current()
        good = false
        found = Answer("second")
        val second = cache.current()
        assertEquals("second", second?.name)
        assertEquals(2, lookups)
        assertEquals("first", first?.name)
    }

    @Test
    fun `invalidating sends the next call back to the lookup`() {
        cache.current()
        cache.invalidate()
        found = Answer("second")
        assertEquals("second", cache.current()?.name)
        assertEquals(2, lookups)
    }

    @Test
    fun `a value invalidated is not asked whether it still does`() {
        cache.current()
        cache.invalidate()
        cache.current()
        assertEquals(0, checks)
    }

    @Test
    fun `finding nothing is not an answer worth keeping`() {
        found = null
        assertNull(cache.current())
        found = Answer("arrived")
        assertEquals("arrived", cache.current()?.name)
        assertEquals(2, lookups)
        // The empty result was never held, so it was never checked.
        assertEquals(0, checks)
    }
}
