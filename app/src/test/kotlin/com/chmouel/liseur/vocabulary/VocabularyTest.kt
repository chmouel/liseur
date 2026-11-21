package com.chmouel.liseur.vocabulary

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chmouel.liseur.data.db.LiseurDatabase
import com.chmouel.liseur.insights.UsageEvent
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.publication.Locator
import org.json.JSONObject

@Config(sdk = [35], application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class VocabularyTest {
    @Test
    fun `normalization keeps scripts and meaningful marks`() {
        assertEquals("café", normalizeVocabularyWord("«CAFE\u0301!»", "fr"))
        assertEquals("l'été", normalizeVocabularyWord("L’été,", "fr"))
        assertEquals("ışık", normalizeVocabularyWord("IŞIK", "tr"))
        assertEquals("北京", normalizeVocabularyWord("「北京」", "zh"))
        assertEquals("𐐨", normalizeVocabularyWord("𐐀", "en"))
        assertNull(normalizeVocabularyWord("…", "fr"))
        assertNull(normalizeVocabularyWord("a".repeat(121), "en"))
    }

    @Test
    fun `context isolates the sentence around the selected word`() {
        assertEquals("It was twilight.", vocabularySentence("They left. It was ", "twilight", ". The moon rose.", "en"))
        assertEquals("喜欢读书。", vocabularySentence("你好吗？喜欢", "读书", "。再见！", "zh"))
    }

    @Test
    fun `recall preserves the original interval ladder for Good and caps long intervals`() {
        assertEquals(listOf(30, 720, 1440, 2880, 5760, 10080, 21600, 43200),
            (0..7).map { scheduleVocabulary(it, Recall.GOOD, 0).intervalMinutes })
        assertEquals(VocabularySchedule(0, 5, 300_111), scheduleVocabulary(11, Recall.AGAIN, 111))
        assertEquals(VocabularySchedule(1, 15, 900_000), scheduleVocabulary(1, Recall.HARD, 0))
        assertEquals(VocabularySchedule(2, 720, 43_200_000), scheduleVocabulary(0, Recall.EASY, 0))
        assertEquals(2_764_800, scheduleVocabulary(14, Recall.GOOD, 0).intervalMinutes)
        val instant = Instant.parse("2026-03-29T22:30:00Z").toEpochMilli()
        val midnight = Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()
        assertEquals(midnight, vocabularyDayStart(instant, ZoneId.of("Europe/Paris")))
    }

    @Test
    fun `lookups deduplicate by normalized language and retain both passages`() = runTest {
        withDatabase { db ->
            val repo = VocabularyRepository(db)
            val original = source("one")
            val first = repo.collect(VocabularyLookup("“CHAT!”", "fr", original), null, null, 10)
            assertNull(repo.collect(VocabularyLookup("dog", "en", original), null, null, 11, existingOnly = true))
            val next = repo.collect(VocabularyLookup("chat", "fr", source("two")), "Petit félin", null, 12, existingOnly = true)
            assertEquals(first, next)
            repo.collect(VocabularyLookup("Chat", "fr", original), "Conversation en ligne", null, 13)
            assertNotEquals(first, repo.collect(VocabularyLookup("chat", "en", original), "Talk", null, 14))
            val id = checkNotNull(first)
            assertEquals("Petit félin\nConversation en ligne", repo.dao.word(id)?.definition)
            assertEquals(listOf(2, 1), repo.dao.occurrences(id).first().map { it.encounters })
            assertEquals(listOf(id), repo.dao.words("", "two", 0, 0).first().map { it.wordId })
            assertEquals(setOf("one", "two"), repo.dao.words("", null, 0, 0, grouped = true).first().map { it.groupUrl }.toSet())
            repo.edit(id, "My definition", "Book club")
            repo.enrich(id, "Another definition", null)
            assertEquals("My definition", repo.dao.word(id)?.definition)
            assertEquals(listOf(id), repo.dao.words("book club", null, 0, 0).first().map { it.wordId })
        }
    }

    @Test
    fun `review and undo persist progress and enforce daily limits`() = runBlocking {
        withTimeout(15_000) {
            withDatabase { db ->
                val time = System.currentTimeMillis() - 1000
                val repo = VocabularyRepository(db)
                val firstId = checkNotNull(repo.collect(VocabularyLookup("basis", "en", null), null, null, time))
                repo.collect(VocabularyLookup("apex", "en", null), null, null, time + 1)
                val initial = repo.reviewQueue(null, 1, 0).first()
                assertEquals(1, initial.dueCount)
                assertEquals(firstId, initial.card?.id)
                val card = checkNotNull(initial.card)
                val undo = checkNotNull(repo.review(card, Recall.GOOD, 1, 1, time + 2))
                assertNull(repo.review(card, Recall.EASY, 1, 1, time + 3))
                val reopened = VocabularyRepository(db)
                assertEquals(0, reopened.reviewQueue(null, 1, 1).first().dueCount)
                assertEquals(1, reopened.reviewQueue(null, 1, 1).first().completedToday)
                assertEquals(time + 2 + 30 * 60_000, reopened.dao.word(firstId)?.dueAt)
                assertTrue(reopened.undoReview(undo))
                assertTrue(!reopened.undoReview(undo))
                assertEquals(card, reopened.reviewQueue(null, 1, 1).first().card)
                assertTrue(reopened.dao.reviews(firstId).isEmpty())
            }
        }
    }

    @Test
    fun `usage counts only new words and accepted review or undo actions`() = runBlocking {
        withDatabase { db ->
            val events = mutableListOf<UsageEvent>()
            val repo = VocabularyRepository(db, report = { events += it })
            val time = System.currentTimeMillis() - 1000
            val lookup = VocabularyLookup("basis", "en", source("one"))
            assertNull(repo.collect(lookup, null, null, time, existingOnly = true))
            assertNull(repo.collect(lookup.copy(word = "…"), null, null, time))
            assertTrue(events.isEmpty())

            val id = checkNotNull(repo.collect(lookup, "Foundation", null, time))
            assertEquals(id, repo.collect(lookup, "A base", null, time))
            repo.edit(id, "My meaning", "My note")
            val card = checkNotNull(repo.dao.word(id))
            val undo = checkNotNull(repo.review(card, Recall.GOOD, 20, 0, time + 1))
            assertNull(repo.review(card, Recall.EASY, 20, 0, time + 2))
            assertTrue(repo.undoReview(undo))
            assertFalse(repo.undoReview(undo))

            val home = checkNotNull(repo.homeCard(20, 0).first())
            assertTrue(repo.review(home, Recall.AGAIN, 20, 0) != null)
            assertNull(repo.review(home, Recall.GOOD, 20, 0))
            assertEquals(listOf(
                UsageEvent.VOCABULARY_WORD_SAVED,
                UsageEvent.VOCABULARY_REVIEWED,
                UsageEvent.VOCABULARY_REVIEW_UNDONE,
                UsageEvent.VOCABULARY_REVIEWED,
            ), events)
        }
    }

    @Test
    fun `usage runs after commit and reporting errors do not fail vocabulary actions`() = runBlocking {
        withDatabase { db ->
            val events = mutableListOf<UsageEvent>()
            val repo = VocabularyRepository(db, report = {
                assertFalse(db.inTransaction())
                events += it
                throw IllegalStateException("Reporting unavailable")
            })
            val time = System.currentTimeMillis() - 1000
            val id = checkNotNull(repo.collect(VocabularyLookup("basis", "en", null), null, null, time))
            val card = checkNotNull(repo.dao.word(id))
            val undo = checkNotNull(repo.review(card, Recall.GOOD, 20, 0, time + 1))
            assertTrue(repo.undoReview(undo))
            assertEquals(card, repo.dao.word(id))
            assertEquals(listOf(
                UsageEvent.VOCABULARY_WORD_SAVED,
                UsageEvent.VOCABULARY_REVIEWED,
                UsageEvent.VOCABULARY_REVIEW_UNDONE,
            ), events)
        }
    }

    @Test
    fun `home card shows the next due word with where it was met and leaves once reviewed`() = runBlocking {
        withTimeout(15_000) {
            withDatabase { db ->
                val repo = VocabularyRepository(db)
                assertNull(repo.homeCard(20, 0).first())
                val time = System.currentTimeMillis() - 1000
                val id = checkNotNull(repo.collect(VocabularyLookup("basis", "en", source("one")), "Foundation", null, time))
                repo.collect(VocabularyLookup("basis", "en", source("two").copy(locationKey = "later", sentence = null, lastSeenAt = 5)), null, null, time + 1)
                val card = checkNotNull(repo.homeCard(20, 0).first())
                assertEquals(HomeVocabulary(id, "basis", "en", "Foundation", "That is the word.", "Title of one", 1, 0, 0), card)
                repo.review(checkNotNull(repo.dao.word(id)), Recall.GOOD, 20, 0, time + 2)
                assertNull(repo.homeCard(20, 0).first())
            }
        }
    }

    @Test
    fun `home rates the word it shows and skips one reviewed since`() = runBlocking {
        withTimeout(15_000) {
            withDatabase { db ->
                val repo = VocabularyRepository(db)
                val id = checkNotNull(repo.collect(VocabularyLookup("basis", "en", source("one")), "Foundation", null, System.currentTimeMillis() - 1000))
                val card = checkNotNull(repo.homeCard(20, 0).first())
                val undo = checkNotNull(repo.review(card, Recall.AGAIN, 20, 0))
                assertEquals(1, checkNotNull(repo.dao.word(id)).repetitions)
                assertNull(repo.review(card, Recall.GOOD, 20, 0))
                assertEquals(1, repo.dao.reviews(id).size)
                repo.undoReview(undo)
                assertEquals(card, repo.homeCard(20, 0).first())
            }
        }
    }

    @Test
    fun `JSON preserves locators and progress while Anki CSV preserves escaped fields`() = runTest {
        withDatabase { db ->
            val repo = VocabularyRepository(db)
            val time = System.currentTimeMillis() - 100
            val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"fragments":["para"]},"text":{"highlight":"mot"}}"""
            val occurrence = source("file:book").copy(locator = locator, locationKey = locator, sentence = "Le « mot ».")
            val id = checkNotNull(repo.collect(VocabularyLookup("mot", "fr", occurrence), "Groupe, \"cité\"\r\nDeuxième ligne", null, time))
            repo.review(checkNotNull(repo.dao.word(id)), Recall.EASY, 0, 0, time + 1)
            val records = repo.export()
            val json = StringWriter().also { writeVocabularyJson(records, it) }.toString()
            val imported = readVocabulary(json.reader(), time + 5)
            assertEquals(locator, imported.single().occurrences.single().locator)
            assertEquals("chapter.xhtml", Locator.fromJSON(JSONObject(imported.single().occurrences.single().locator!!))?.href?.toString())
            assertEquals(records.single().word.copy(id = 0), imported.single().word)
            assertEquals(1, repo.import(imported))
            assertEquals(records, repo.export())

            val csv = StringWriter().also { writeVocabularyCsv(records, it) }.toString()
            val portable = readVocabulary(csv.reader(), time + 5).single()
            assertEquals(records.single().word.definition, portable.word.definition)
            assertEquals(occurrence.sentence, portable.occurrences.single().sentence)
            assertEquals(occurrence.bookTitle, portable.occurrences.single().bookTitle)
            assertNull(portable.word.reviewedAt)
            assertThrows(IllegalArgumentException::class.java) { readVocabulary("\"unterminated".reader()) }
        }
    }

    @Test
    fun `search folds Unicode in definitions notes and edited sentences`() = runTest {
        withDatabase { db ->
            val repo = VocabularyRepository(db)
            val source = source("book").copy(sentence = "Остров сокровищ")
            val id = checkNotNull(repo.collect(VocabularyLookup("sail", "en", source), "Парус", null))
            repo.edit(id, "Парус", "Запись о Пирате")
            for (text in listOf("ПАРУС", "пирате", "оСтРоВ")) {
                val matches = repo.dao.words(normalizeVocabularySearch(text), null, 0, 0).first()
                assertEquals(listOf(id), matches.map { it.wordId })
            }
            val occurrence = repo.dao.occurrences(id).first().single()
            repo.editSentence(occurrence.id, "Новый контекст")
            assertEquals(listOf(id), repo.dao.words("новый", null, 0, 0).first().map { it.wordId })
            assertTrue(repo.dao.words("остров", null, 0, 0).first().isEmpty())
        }
    }

    @Test
    fun `JSON restores manual definitions including a cleared one and keeps local edits`() = runTest {
        withDatabase { db ->
            val repo = VocabularyRepository(db)
            val id = checkNotNull(repo.collect(VocabularyLookup("boat", "en", null), "A vessel", null))
            val word = checkNotNull(repo.dao.word(id))
            val cleared = VocabularyRecord(word.copy(id = 0, definition = null, definitionEdited = true), emptyList())
            val json = StringWriter().also { writeVocabularyJson(listOf(cleared), it) }.toString()
            repo.import(readVocabulary(json.reader()))
            assertNull(repo.dao.word(id)?.definition)
            assertEquals(true, repo.dao.word(id)?.definitionEdited)
            repo.enrich(id, "A small craft", null)
            assertNull(repo.dao.word(id)?.definition)
            repo.edit(id, "My local edit", "")
            repo.import(readVocabulary(json.reader()))
            assertEquals("My local edit", repo.dao.word(id)?.definition)
        }
    }

    @Test
    fun `activity query filters time and book without duplicating multiple passages`() = runTest {
        withDatabase { db ->
            val repo = VocabularyRepository(db)
            val id = checkNotNull(repo.collect(VocabularyLookup("word", "en", source("one")), "Meaning", null, 1))
            repo.dao.insertOccurrence(
                source("one").copy(
                    wordId = id,
                    locationKey = "second",
                    occurrenceKey = com.chmouel.liseur.domain.VocabularyMerge.provisionalOccurrenceKey("one", "Book one", "second"),
                ),
            )
            repo.dao.insertOccurrence(source("two").copy(wordId = id))
            val other = checkNotNull(repo.collect(VocabularyLookup("other", "en", source("three")), null, null, 1))
            for ((word, time) in listOf(id to 10L, id to 20L, other to 30L)) {
                repo.dao.addReview(VocabularyReview(
                    wordId = word, rating = "GOOD", reviewedAt = time,
                    intervalMinutes = 30, dueAt = time + 1_800_000, firstReview = false,
                ))
            }
            assertEquals(listOf(20L, 30L), repo.dao.reviewsSince(20, null).first().map { it.reviewedAt })
            assertEquals(listOf(VocabularyReviewActivity(20, "GOOD")), repo.dao.reviewsSince(20, "one").first())
            assertEquals(listOf(10L, 20L), repo.dao.reviewsSince(0, "two").first().map { it.reviewedAt })
            assertTrue(repo.dao.reviewsSince(0, "missing").first().isEmpty())
        }
    }

    private suspend fun withDatabase(test: suspend (LiseurDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), LiseurDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            test(database)
        } finally {
            database.close()
        }
    }

    private fun source(book: String) = VocabularyOccurrence(
        bookUrl = book,
        bookTitle = "Title of $book",
        locationKey = "chapter-one",
        locator = null,
        sentence = "That is the word.",
        lastSeenAt = 0,
    )
}
