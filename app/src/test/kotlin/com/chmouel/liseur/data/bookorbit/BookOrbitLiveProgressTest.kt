package com.chmouel.liseur.data.bookorbit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanitized responses from the dedicated copilot account on orbit.chmouel.com. */
class BookOrbitLiveProgressTest {
    @Test
    fun `an unopened file differs from a saved zero`() {
        val unopened = BookOrbitFileProgress.parse(JSONObject(fixture("unopened")))
        val saved = BookOrbitFileProgress.parse(JSONObject(fixture("saved-zero")))

        assertFalse(unopened.isSaved)
        assertNull(unopened.displayTime)
        assertEquals(0.0, unopened.percentage, 0.0)
        assertTrue(saved.isSaved)
        assertEquals(0.0, saved.percentage, 0.0)
        assertEquals("epubcfi(/6/4!/4/2/1:3)", saved.cfi)
        assertNotNull(saved.displayTime)
    }

    @Test
    fun `a text post clears omitted engine fields but retains narration`() {
        val before = JSONObject(fixture("before-text"))
        val after = JSONObject(fixture("after-text"))
        assertEquals(25.0, before.getDouble("percentage"), 0.0)
        assertEquals(30.0, after.getDouble("percentage"), 0.0)
        for (field in listOf(
            "cfi", "pageNumber", "positionSeconds", "mediaOverlayFragment",
            "mediaOverlaySectionIndex", "koboLocationSource", "koboLocationType",
            "koboLocationValue", "koboContentSourceProgressPercent", "koreaderProgress",
        )) {
            assertFalse("seed field $field was missing", before.isNull(field))
            assertTrue("omitted field $field survived text POST", after.has(field) && after.isNull(field))
        }
        assertEquals(before.getDouble("narrationPercentage"), after.getDouble("narrationPercentage"), 0.0)
        assertEquals(before.getString("narrationUpdatedAt"), after.getString("narrationUpdatedAt"))
        assertTrue(BookOrbitFileProgress.parse(after).isSaved)
    }

    @Test
    fun `book progress lists a row for each file even when unopened`() {
        val rows = JSONArray(fixture("multifile"))
        assertEquals(3, rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            assertEquals(34 + index, row.getInt("fileId"))
            assertEquals(0.0, row.getDouble("percentage"), 0.0)
            assertTrue(row.has("updatedAt"))
            assertTrue(row.isNull("updatedAt"))
        }
    }

    @Test
    fun `book detail preserves all eight personal status values`() {
        val statuses = JSONArray(fixture("statuses"))
        assertEquals(8, statuses.length())
        val names = mutableListOf<String>()
        for (index in 0 until statuses.length()) {
            val parsed = requireNotNull(BookOrbitBooks.parseStatus(statuses.getJSONObject(index)))
            names += parsed.status
            assertEquals("manual", parsed.source)
            assertNotNull(parsed.updatedAt)
        }
        assertEquals(
            listOf("unread", "want_to_read", "reading", "on_hold", "rereading", "read", "skimmed", "abandoned"),
            names,
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/bookorbit/progress-live-$name.json")).readText()
}
