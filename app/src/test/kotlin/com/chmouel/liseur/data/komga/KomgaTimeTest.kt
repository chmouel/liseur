package com.chmouel.liseur.data.komga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Komga writes the same kind of timestamp two different ways, and both
 * turn up in one session, so both have to read back to the same instant.
 */
class KomgaTimeTest {

    @Test
    fun `utc and a local offset for the same instant agree`() {
        assertEquals(
            KomgaTime.parse("2026-07-30T08:07:58Z"),
            KomgaTime.parse("2026-07-30T10:07:58+02:00"),
        )
    }

    @Test
    fun `milliseconds survive`() {
        assertEquals(
            250L,
            KomgaTime.parse("1970-01-01T00:00:00.250Z"),
        )
    }

    @Test
    fun `a time with no offset is taken as utc`() {
        assertEquals(0L, KomgaTime.parse("1970-01-01T00:00:00"))
    }

    @Test
    fun `nonsense is not a time`() {
        assertNull(KomgaTime.parse(null))
        assertNull(KomgaTime.parse(""))
        assertNull(KomgaTime.parse("   "))
        assertNull(KomgaTime.parse("last tuesday"))
        assertNull(KomgaTime.parse("2026-07-30"))
    }

    @Test
    fun `what we send comes back as the same instant`() {
        val now = 1_785_398_878_123L

        assertEquals(now, KomgaTime.parse(KomgaTime.format(now)))
    }
}
