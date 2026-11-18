package com.chmouel.liseur.reader.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefinitionSheetLookupTest {
    private val pack = OfflineDictionary.Pack(
        id = "pack",
        name = "English",
        language = "en",
        enabled = true,
        sizeBytes = 0,
        wordCount = 1,
        isReaderDict = true,
    )

    @Test
    fun `online lookup waits for offline result then runs on a miss`() {
        assertFalse(shouldUseOnlineLookup(offlineSelected = true, enabled = true, localResult = null))
        assertTrue(shouldUseOnlineLookup(offlineSelected = true, enabled = true, localResult = Result.success(null)))
        assertTrue(shouldUseOnlineLookup(offlineSelected = true, enabled = true, localResult = Result.failure(IllegalStateException())))
        assertFalse(
            shouldUseOnlineLookup(
                offlineSelected = true,
                enabled = true,
                localResult = Result.success(OfflineDictionary.Lookup(pack, listOf(StarDictEntry("word")))),
            ),
        )
        assertFalse(shouldUseOnlineLookup(offlineSelected = true, enabled = false, localResult = Result.success(null)))
        assertTrue(shouldUseOnlineLookup(offlineSelected = false, enabled = false, localResult = null))
    }
}
