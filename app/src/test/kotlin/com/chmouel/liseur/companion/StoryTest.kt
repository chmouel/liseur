package com.chmouel.liseur.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.shared.publication.Locator

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

    private fun rows(vararg missing: Int) = (0 until 9).map { unit ->
        StoryChapter(unit, null, if (unit in missing) StoryRecap.Missing else StoryRecap.NoSummary, null)
    }

    @Test
    fun `nothing is picked before the reader allows it and allowing picks the chapter before the place once`() {
        val events = StoryEvents()
        val scope = AskingScope(lo = 9, count = 10)
        val chapters = rows(0, 1, 2, 3, 4, 5, 7, 8)
        assertEquals(emptyList<Int>(), events.shown(scope, allowed = false, chapters))
        assertEquals(StoryEvents.Stage.ASKING, events.stage(scope))
        assertEquals(false, events.automatic(scope, allowed = false))
        assertEquals(listOf(8), events.allow(scope, chapters))
        // The stored leave arriving, showing the tab again, a new recap: nothing is picked again.
        assertEquals(emptyList<Int>(), events.shown(scope, allowed = true, chapters))
        assertEquals(emptyList<Int>(), events.allow(scope, chapters))
        assertEquals(true, events.automatic(scope, allowed = true))
    }

    @Test
    fun `scopes sharing a chapter are separate and stop and not now keep Story from acting on its own`() {
        val events = StoryEvents()
        val inChapter = AskingScope(lo = 9, count = 10, until = ReadPoint(9, 4))
        val atStart = AskingScope(lo = 9, count = 10)
        // The chapter before the place has a recap: earlier missing ones are left to the reader.
        assertEquals(emptyList<Int>(), events.shown(inChapter, allowed = true, rows(0, 1)))
        assertEquals(listOf(8), events.shown(atStart, allowed = true, rows(0, 1, 8)))
        events.stop(inChapter)
        assertEquals(false, events.automatic(inChapter, allowed = true))
        assertEquals(emptyList<Int>(), events.shown(inChapter, allowed = true, rows(0, 1)))

        val declined = AskingScope(lo = 5, count = 10)
        events.shown(declined, allowed = false, rows(0))
        events.decline(declined)
        assertEquals(StoryEvents.Stage.DECLINED, events.stage(declined))
        assertEquals(emptyList<Int>(), events.allow(declined, rows(0)))
        assertEquals(emptyList<Int>(), events.shown(declined, allowed = true, rows(0)))
        assertEquals(false, events.automatic(declined, allowed = true))
    }

    @Test
    fun `chapters already asked for are not picked and failed ones can be asked again`() {
        val activity = RecapActivity(waiting = listOf(2), working = 3, batch = 1, batches = 4,
            failed = mapOf(1 to RecapFailure(CompanionFailure.NETWORK, null)))
        val chapters = Story.progress(rows(0, 1, 2, 3), activity) { CompanionView.Failed(it.failure) }
        assertEquals(StoryProgress.Working(1, 4), chapters[3].progress)
        assertEquals(StoryProgress.Queued, chapters[2].progress)
        assertEquals(listOf(true, true, false, false), chapters.take(4).map { it.askable })
        assertEquals(emptyList<Int>(), Story.previous(chapters.take(4)))
        // Recap missing asks for the failed one too, as many as it offers.
        assertEquals(listOf(1, 0), Story.missing(chapters))
        assertEquals(2, StoryView(chapters = chapters.map { StoryChapter<Locator>(it.unit, it.title, it.recap, null, it.progress) }).missing)
    }

    @Test
    fun `a chapter lists each of its recap's characters once`() {
        fun named(name: String) = RecapCharacter(name, emptyList(), emptyList(), emptyList())
        val recap = ChapterRecap(listOf("They sail."), listOf(named(" Jim "), named("Silver"), named("JIM’s"), named(" ")))

        assertEquals(listOf("Jim", "Silver"), Story.characters(saved(0, recap.encode())))
        assertEquals(emptyList<String>(), Story.characters(saved(0, "not json")))
    }
}
