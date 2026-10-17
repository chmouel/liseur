package com.chmouel.liseur.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class StoryTest {
    private val map = ChapterMap.of(
        (1..5).map { "ch$it.xhtml" },
        (1..5).map { TocNode("ch$it.xhtml", title = "Chapter $it title") },
    )

    private fun saved(unit: Int, json: String, key: String = map.unitKey(unit)) = SavedRecap(RecapUnit(unit, key), json)

    @Test
    fun `each earlier chapter shows its summary or says why it has none`() {
        val earlier = listOf(4, 3, 2, 1, 0).map { EarlierChapter(it, "start-$it") }
        val rows = Story.chapters(
            map,
            earlier,
            listOf(
                saved(0, ChapterRecap(listOf("Mira leaves.", " ", "She returns."), emptyList()).encode()),
                saved(1, ChapterRecap.EMPTY.encode()),
                saved(2, "not json"),
                // Saved when the chapters were cut differently.
                saved(3, ChapterRecap(listOf("Stale."), emptyList()).encode(), key = "3:old"),
            ),
        )
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.unit })
        assertEquals(
            listOf(
                StoryRecap.Summary(listOf("Mira leaves.", "She returns.")),
                StoryRecap.NoSummary,
                StoryRecap.Unreadable,
                StoryRecap.Missing,
                StoryRecap.Missing,
            ),
            rows.map { it.recap },
        )
        assertEquals("Chapter 1 title", rows[0].title)
        assertEquals("start-0", rows[0].start)
        assertEquals(listOf(map.unitKey(0), map.unitKey(1)), Story.shown(map, rows))
    }

    @Test
    fun `recaps of chapters not listed are never shown`() {
        val rows = Story.chapters(map, listOf(EarlierChapter(0, null)), listOf(saved(3, ChapterRecap(listOf("Later."), emptyList()).encode())))
        assertEquals(listOf(StoryChapter(0, "Chapter 1 title", StoryRecap.Missing, null)), rows)
    }
}
