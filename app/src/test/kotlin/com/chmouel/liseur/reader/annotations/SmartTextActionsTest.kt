package com.chmouel.liseur.reader.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTextActionsTest {

    @Test
    fun `isEligibleForSmartClassification rejects null blank and single character text`() {
        assertFalse(isEligibleForSmartClassification(null))
        assertFalse(isEligibleForSmartClassification(""))
        assertFalse(isEligibleForSmartClassification("   \n\t "))
        assertFalse(isEligibleForSmartClassification("a"))
        assertFalse(isEligibleForSmartClassification("  x  "))
    }

    @Test
    fun `isEligibleForSmartClassification accepts short one and two line selections`() {
        assertTrue(isEligibleForSmartClassification("ab"))
        assertTrue(isEligibleForSmartClassification("https://example.org/book"))
        assertTrue(isEligibleForSmartClassification("10 Downing Street\nLondon SW1A 2AA"))
        assertTrue(isEligibleForSmartClassification("Line one\r\nLine two"))
        assertTrue(isEligibleForSmartClassification("a".repeat(MAX_SMART_SELECTION_CHARS)))
        assertTrue(isEligibleForSmartClassification("  " + "a".repeat(MAX_SMART_SELECTION_CHARS) + "  "))
    }

    @Test
    fun `isEligibleForSmartClassification rejects text exceeding max length or two lines`() {
        assertFalse(isEligibleForSmartClassification("a".repeat(MAX_SMART_SELECTION_CHARS + 1)))
        assertFalse(isEligibleForSmartClassification("line 1\nline 2\nline 3"))
        assertFalse(isEligibleForSmartClassification("line 1\r\nline 2\r\nline 3"))
        assertFalse(isEligibleForSmartClassification("a\nb\nc"))
    }

    @Test
    fun `mapEntityTypeToKind maps known entity types and falls back to OTHER`() {
        assertEquals(SmartEntityKind.URL, mapEntityTypeToKind("url"))
        assertEquals(SmartEntityKind.URL, mapEntityTypeToKind(" URL "))
        assertEquals(SmartEntityKind.ADDRESS, mapEntityTypeToKind("address"))
        assertEquals(SmartEntityKind.EMAIL, mapEntityTypeToKind("email"))
        assertEquals(SmartEntityKind.PHONE, mapEntityTypeToKind("phone"))
        assertEquals(SmartEntityKind.DATETIME, mapEntityTypeToKind("date"))
        assertEquals(SmartEntityKind.DATETIME, mapEntityTypeToKind("datetime"))
        assertEquals(SmartEntityKind.FLIGHT, mapEntityTypeToKind("flight"))
        assertEquals(SmartEntityKind.OTHER, mapEntityTypeToKind("other"))
        assertEquals(SmartEntityKind.OTHER, mapEntityTypeToKind("dictionary"))
        assertEquals(SmartEntityKind.OTHER, mapEntityTypeToKind(""))
    }

    @Test
    fun `selectPrimaryEntity returns null when empty or below confidence threshold`() {
        assertNull(selectPrimaryEntity(emptyMap()))
        assertNull(selectPrimaryEntity(mapOf("url" to 0.69f, "phone" to 0.2f)))
        assertNull(selectPrimaryEntity(mapOf("url" to Float.NaN)))
    }

    @Test
    fun `selectPrimaryEntity picks single winner and highest confidence among multiple candidates`() {
        assertEquals(
            SmartEntityKind.EMAIL,
            selectPrimaryEntity(mapOf("email" to MIN_ENTITY_CONFIDENCE)),
        )
        assertEquals(
            SmartEntityKind.ADDRESS,
            selectPrimaryEntity(
                mapOf(
                    "phone" to 0.72f,
                    "address" to 0.91f,
                    "url" to 0.85f,
                ),
            ),
        )
    }

    @Test
    fun `selectPrimaryEntity breaks ties deterministically by entity type`() {
        val forward = linkedMapOf("url" to 0.85f, "address" to 0.85f, "email" to 0.85f)
        val reverse = linkedMapOf("email" to 0.85f, "url" to 0.85f, "address" to 0.85f)
        assertEquals(SmartEntityKind.ADDRESS, selectPrimaryEntity(forward))
        assertEquals(SmartEntityKind.ADDRESS, selectPrimaryEntity(reverse))
    }
}
