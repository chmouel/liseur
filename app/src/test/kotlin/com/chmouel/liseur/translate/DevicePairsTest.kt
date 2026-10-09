package com.chmouel.liseur.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevicePairsTest {

    private val pairs = DevicePairs.of(
        listOf(
            Triple("fr-FR", "en-US", PairState.NeedsDownload),
            Triple("fr", "en", PairState.Ready),
            Triple("fr", "de", PairState.NeedsDownload),
            Triple("en", "zh-TW", PairState.Ready),
            Triple("en", "ja", null),
            Triple("en-US", "en-GB", PairState.Ready),
            Triple("und", "fr", PairState.Ready),
        ),
    )

    @Test
    fun `pairs the system cannot offer, or that translate into themselves, are left out`() {
        assertEquals(4, pairs.size)
        assertNull(DevicePairs.find(pairs, "en", "ja"))
        assertNull(DevicePairs.find(pairs, "en", "en"))
    }

    @Test
    fun `a pair listed twice is as usable as its best entry, and keeps the system's own tags`() {
        val pair = DevicePairs.find(pairs, "fr", "en")!!
        assertEquals(PairState.Ready, pair.state)
        assertEquals("fr" to "en", pair.systemSource to pair.systemTarget)
    }

    @Test
    fun `targets say which need a download, and any source lists them all`() {
        assertEquals(mapOf("en" to PairState.Ready, "de" to PairState.NeedsDownload), DevicePairs.targets(pairs, "fr"))
        assertEquals(setOf("en", "de", "zh-Hant"), DevicePairs.targets(pairs, null).keys)
        assertEquals(setOf("fr", "en"), DevicePairs.sources(pairs))
    }
}
