package com.chmouel.liseur.companion

import com.chmouel.liseur.translate.TranslationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CompanionLookupsTest {

    // Places are chapter * 100 + ordinal; a negative place cannot be told
    // apart, and UNREADABLE_PLACE throws while the book is read.
    private val book: List<Passage<Int>> = (0 until 8).flatMap { chapter ->
        val later = if (chapter >= 4) " LATER$chapter" else ""
        listOf(
            passage(chapter, 0, "Chapter ${chapter + 1}. Mira walks to the mill.$later"),
            passage(chapter, 1, "Tobin the elder, Mira's uncle, sails away.$later"),
            passage(chapter, 2, "Tobin, her brother, mends nets.$later"),
        )
    }

    private val catalog = object : BookCatalog<Int> {
        val asked = mutableListOf<AskingScope>()

        override val map: ChapterMap get() = throw UnsupportedOperationException()

        override suspend fun scopeOf(place: Int, translated: Boolean): AskingScope? {
            if (place == UNREADABLE_PLACE) throw IOException("unreadable")
            if (place < 0) return null
            return AskingScope(lo = place / 100, count = 8)
        }

        override suspend fun passagesBefore(scope: AskingScope): List<Passage<Int>> {
            asked += scope
            return book.filter { Eligibility.allows(scope, it.range) }
        }

        override suspend fun passagesOf(unit: Int): List<Passage<Int>> = book.filter { it.range.hi == unit }
    }

    private var choice: CompanionChoice? = CompanionChoice("s1", "https://ai.example/v1", "m", "https://ai.example", 0)
    private val chats = mutableListOf<CompanionChoice>()
    private var replies: suspend (List<ChatMessage>) -> String = { profileFor(it) }
    private val requests = mutableListOf<List<ChatMessage>>()

    private fun TestScope.lookups() = CompanionLookups(
        scope = this,
        catalog = { catalog },
        choice = { choice },
        chatFor = { chosen ->
            chats += chosen
            CompanionChat { messages, before ->
                before()
                requests += messages
                replies(messages)
            }
        },
        hints = { _, _, _ -> emptyList() },
        aside = { it() },
    )

    private fun refs(messages: List<ChatMessage>): List<String> =
        Regex("ref=\"(S[0-9]+)\"").findAll(messages.joinToString("\n") { it.content }).map { it.groupValues[1] }.distinct().toList()

    /** The first ref shown whose own passage contains [words]. */
    private fun refHolding(messages: List<ChatMessage>, words: String): String =
        Regex("ref=\"(S[0-9]+)\"[^>]*>\n(.*?)\n</book>", RegexOption.DOT_MATCHES_ALL)
            .findAll(messages.joinToString("\n") { it.content })
            .first { it.groupValues[2].contains(words) }.groupValues[1]

    private fun profileFor(messages: List<ChatMessage>) = JSONObject()
        .put("answer", "profile")
        .put("identity", JSONArray().put(JSONObject().put("text", "A miller's daughter.").put("refs", JSONArray(refs(messages).take(1)))))
        .put("backstory", JSONArray())
        .put("aliases", JSONArray())
        .put("relationships", JSONArray())
        .toString()

    private fun view(lookups: CompanionLookups<Int>) = lookups.panel.value?.view

    @Test
    fun `a name asked in chapter 5 is answered from chapters 1 to 4 only`() = runTest {
        val lookups = lookups()
        lookups.open("Mira", place = 400, translated = false)
        advanceUntilIdle()

        val profile = (view(lookups) as CompanionView.Profile).profile
        assertTrue(profile.identity.single().sources.all { it.range.hi < 4 })
        assertEquals(AskingScope(4, 8), lookups.panel.value?.scope)
        val sent = requests.flatten().joinToString("\n") { it.content }
        assertFalse(sent.contains("LATER"))
        assertTrue(sent.contains("Chapter 4."))
    }

    @Test
    fun `the first chapter, an unknown chapter and a long selection ask nobody`() = runTest {
        val lookups = lookups()

        lookups.open("Mira", place = 5, translated = false)
        advanceUntilIdle()
        assertEquals(CompanionView.FirstChapter, view(lookups))

        lookups.open("Mira", place = -1, translated = false)
        advanceUntilIdle()
        assertEquals(CompanionView.Unplaced, view(lookups))

        lookups.open("Mira walked all the way to the old mill by the river", place = 400, translated = false)
        advanceUntilIdle()
        assertEquals(CompanionView.NotAName, view(lookups))

        lookups.open("Mira", place = UNREADABLE_PLACE, translated = false)
        advanceUntilIdle()
        assertEquals(CompanionView.Failed(CompanionFailure.UNREADABLE), view(lookups))

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `without a server the panel asks for setup, and asks once one is chosen`() = runTest {
        choice = null
        val lookups = lookups()
        lookups.open("Mira", place = 400, translated = false)
        advanceUntilIdle()
        assertEquals(CompanionView.Setup, view(lookups))

        choice = CompanionChoice("s1", "https://ai.example/v1", "m", "https://ai.example", 0)
        lookups.setUp()
        advanceUntilIdle()
        assertTrue(view(lookups) is CompanionView.Profile)
    }

    @Test
    fun `cancelling stops the request, and retry asks again in the same chapters`() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        replies = {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val lookups = lookups()
        lookups.open("Mira", place = 400, translated = false)
        runCurrent()
        started.await()
        assertEquals(CompanionView.Loading(CompanionView.Stage.ASKING), view(lookups))

        lookups.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(CompanionView.Failed(CompanionFailure.CANCELLED), view(lookups))

        replies = { profileFor(it) }
        lookups.retry()
        advanceUntilIdle()
        assertTrue(view(lookups) is CompanionView.Profile)
        assertEquals(listOf(AskingScope(4, 8), AskingScope(4, 8)), catalog.asked)
    }

    @Test
    fun `choosing a candidate asks afresh about it, in the chapters first asked about`() = runTest {
        replies = { messages ->
            if (messages.any { it.content.contains("<name>Tobin the elder</name>") }) {
                profileFor(messages)
            } else {
                val brother = refHolding(messages, "her brother")
                val uncle = refHolding(messages, "uncle")
                JSONObject().put("answer", "ambiguous").put(
                    "candidates",
                    JSONArray()
                        .put(JSONObject().put("name", "Tobin").put("description", "Her brother.").put("refs", JSONArray(listOf(brother))))
                        .put(JSONObject().put("name", "Tobin the elder").put("description", "Her uncle.").put("refs", JSONArray(listOf(uncle)))),
                ).toString()
            }
        }
        val lookups = lookups()
        lookups.open("Tobin", place = 300, translated = false)
        advanceUntilIdle()
        val ambiguous = view(lookups) as CompanionView.Ambiguous
        assertEquals(listOf("Tobin", "Tobin the elder"), ambiguous.candidates.map { it.name })

        val before = requests.size
        lookups.choose(ambiguous.candidates[1])
        advanceUntilIdle()

        assertTrue(view(lookups) is CompanionView.Profile)
        assertEquals("Tobin the elder", lookups.panel.value?.name)
        assertEquals("Tobin", lookups.panel.value?.also)
        assertEquals(AskingScope(3, 8), lookups.panel.value?.scope)
        // A fresh history: the system prompt and the seed only.
        assertEquals(2, requests[before].size)
    }

    @Test
    fun `a server changed mid-lookup starts over once under the new one`() = runTest {
        var changes = 1
        replies = { messages ->
            if (changes-- > 0) throw TranslationError.Changed()
            profileFor(messages)
        }
        val lookups = lookups()
        lookups.open("Mira", place = 400, translated = false)
        advanceUntilIdle()
        assertTrue(view(lookups) is CompanionView.Profile)
        assertEquals(2, chats.size)

        changes = 2
        lookups.retry()
        advanceUntilIdle()
        assertEquals(CompanionView.Failed(CompanionFailure.SERVER), view(lookups))
    }

    @Test
    fun `server failures are told apart and can be retried`() = runTest {
        val cases = listOf(
            TranslationError.InvalidKey(401) to CompanionFailure.KEY,
            TranslationError.RateLimited(429) to CompanionFailure.RATE_LIMITED,
            TranslationError.LocalNetworkBlocked() to CompanionFailure.LOCAL_NETWORK,
            TranslationError.Network(IOException("down")) to CompanionFailure.NETWORK,
            TranslationError.ModelNotFound("m") to CompanionFailure.MODEL_MISSING,
            TranslationError.Service(500) to CompanionFailure.SERVER,
            TranslationError.Truncated() to CompanionFailure.MALFORMED,
        )
        val lookups = lookups()
        for ((error, failure) in cases) {
            replies = { throw error }
            lookups.open("Mira", place = 400, translated = false)
            advanceUntilIdle()
            assertEquals(CompanionView.Failed(failure), view(lookups))
        }
        replies = { "not json at all" }
        lookups.retry()
        advanceUntilIdle()
        assertEquals(CompanionView.Failed(CompanionFailure.MALFORMED), view(lookups))
    }

    @Test
    fun `hiding for a source keeps the answer, closing drops it`() = runTest {
        val lookups = lookups()
        lookups.open("Mira", place = 400, translated = false)
        advanceUntilIdle()
        val shown = lookups.panel.value

        lookups.hide()
        assertEquals(false, lookups.panel.value?.visible)
        lookups.reveal()
        assertEquals(shown, lookups.panel.value)

        lookups.close()
        assertNull(lookups.panel.value)
    }

    @Test
    fun `asking for the same character in the same chapter reuses the answer until retry or a different chapter`() = runTest {
        val lookups = lookups()
        lookups.open("Mira", place = 400, translated = false)
        advanceUntilIdle()
        assertEquals(1, requests.size)

        // Same chapter, case/whitespace variant: served from the cache without another server request.
        lookups.close()
        lookups.open("  mira ", place = 402, translated = false)
        advanceUntilIdle()
        assertTrue(view(lookups) is CompanionView.Profile)
        assertEquals(1, requests.size)

        // Different chapter (chapter 6 instead of chapter 5): asks afresh because chapter 5 is now eligible.
        lookups.open("Mira", place = 500, translated = false)
        advanceUntilIdle()
        assertEquals(2, requests.size)

        // Explicit retry bypasses the cache.
        lookups.retry()
        advanceUntilIdle()
        assertEquals(3, requests.size)
    }

    private companion object {
        const val UNREADABLE_PLACE = 999

        fun passage(chapter: Int, ordinal: Int, text: String) = Passage(
            ref = PassageText.ref(chapter, ordinal, null),
            hash = PassageText.hash(text),
            resource = chapter,
            text = text,
            range = ChapterRange(chapter, chapter),
            place = chapter * 100 + ordinal,
        )
    }
}
