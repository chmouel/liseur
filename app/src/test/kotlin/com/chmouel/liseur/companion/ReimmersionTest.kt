package com.chmouel.liseur.companion

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReimmersionTest {
    private val scope = AskingScope(lo = 4, count = 8, until = ReadPoint(resource = 4, element = 2))

    private val book = (0..3).flatMap { chapter -> (0..2).map { passage(chapter, it, "Chapter ${chapter + 1} line $it.") } } +
        listOf(
            passage(4, 0, "Mira reaches the gate."),
            passage(4, 1, "Mira hides the key."),
            passage(4, 2, "On screen. $SENTINEL"),
            passage(4, 3, "After the place. $SENTINEL"),
            passage(5, 0, "Chapter six. $SENTINEL"),
        )

    private val recaps = (0..7).map { RecapInput(it, "Recap of chapter ${it + 1}.") }

    private class Chat(private val replies: List<String>) : CompanionChat {
        val requests = mutableListOf<List<ChatMessage>>()

        override suspend fun reply(messages: List<ChatMessage>, beforeRequest: () -> Unit): String {
            beforeRequest()
            requests += messages
            return replies[requests.size - 1]
        }
    }

    @Test
    fun `only text before the place and recaps of earlier chapters are sent`() {
        val inputs = Reimmersion.inputs(scope, book, recaps)
        val sent = Reimmersion.user(inputs)

        assertFalse(sent.contains(SENTINEL))
        assertTrue(sent.contains("Mira hides the key."))
        assertEquals(listOf(0, 1, 2, 3), inputs.recaps.map { it.unit })
        assertFalse(sent.contains("Recap of chapter 5."))
        assertTrue(sent.contains("<summary ref=\"C4\" chapter=\"4\">Recap of chapter 4.</summary>"))
    }

    @Test
    fun `the text sent keeps the end nearest the place and the last recaps`() {
        val long = (0..19).map { passage(3, it, "x".repeat(1_000) + it) }
        val inputs = Reimmersion.inputs(AskingScope(lo = 10, count = 12), long, (0..9).map { RecapInput(it, "R$it") })

        assertTrue(inputs.passages.sumOf { it.text.length } <= Reimmersion.TEXT_CHARACTERS)
        assertEquals(long.last(), inputs.passages.last())
        assertEquals((4..9).toList(), inputs.recaps.map { it.unit })
    }

    @Test
    fun `refs map to passages and recaps and an uncited field is dropped`() {
        val inputs = Reimmersion.inputs(scope, book, recaps)
        val reply = """{"stopped":{"text":"Mira hid the key.","refs":["S${inputs.passages.size}","C2"]},
            |"objective":{"text":"Uncited.","refs":[]},"tone":null,"questions":["Who sent her?"]}""".trimMargin()

        val answer = (Reimmersion.parse(reply, inputs) as Reimmersion.Parsed.Answer).answer

        assertEquals(listOf(book[13]), answer.stopped!!.sources)
        assertEquals(listOf(1), answer.stopped.recaps)
        assertNull(answer.objective)
        assertEquals(listOf("Who sent her?"), answer.questions)
    }

    @Test
    fun `a ref never shown gets one correction`() = runTest {
        val inputs = Reimmersion.inputs(scope, book, recaps)
        val chat = Chat(
            listOf(
                """{"stopped":{"text":"Later.","refs":["S99"]}}""",
                """{"stopped":{"text":"At the gate.","refs":["S1"]}}""",
            ),
        )

        val answer = Reimmersion.ask(chat, inputs)

        assertEquals("At the gate.", answer.stopped!!.text)
        assertEquals(2, chat.requests.size)
        assertTrue(chat.requests[1].last().content.contains("bad_citation"))
    }

    private companion object {
        const val SENTINEL = "LATERSENTINEL"

        fun passage(chapter: Int, ordinal: Int, text: String) = Passage(
            ref = PassageText.ref(chapter, ordinal, null),
            hash = PassageText.hash(text),
            resource = chapter,
            text = text,
            range = ChapterRange(chapter, chapter),
            place = chapter * 100 + ordinal,
            end = ordinal,
        )
    }
}
