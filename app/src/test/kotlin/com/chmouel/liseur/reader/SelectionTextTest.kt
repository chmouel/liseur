package com.chmouel.liseur.reader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionTextTest {
    @Test
    fun `untranslated actions use the original selection`() = runTest {
        assertEquals("source words", selectionActionText(
            original = "source words",
            translated = false,
            inSheet = false,
            retainedText = "old displayed words",
            readDisplayedText = { error("Untranslated selections do not need a DOM read") },
        ))
    }

    @Test
    fun `translated popup actions read the displayed selection afresh`() = runTest {
        assertEquals("new displayed words", selectionActionText(
            original = "source words",
            translated = true,
            inSheet = false,
            retainedText = "old displayed words",
            readDisplayedText = { "new displayed words" },
        ))
    }

    @Test
    fun `translated sheet actions retain displayed text after the DOM selection is gone`() = runTest {
        assertEquals("retained displayed words", selectionActionText(
            original = "source words",
            translated = true,
            inSheet = true,
            retainedText = "retained displayed words",
            readDisplayedText = { error("The sheet must use its saved selection") },
        ))
    }

    @Test
    fun `unavailable translated text never falls back to source words`() = runTest {
        for (inSheet in listOf(false, true)) {
            for (text in listOf(null, "", "  ")) {
                assertNull(selectionActionText(
                    original = "source words",
                    translated = true,
                    inSheet = inSheet,
                    retainedText = text,
                    readDisplayedText = { text },
                ))
            }
        }
    }
}
