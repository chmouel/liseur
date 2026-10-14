package com.chmouel.liseur.companion

import com.chmouel.liseur.translate.TranslationError
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoPromptTest {
    private val messages = listOf(
        ChatMessage("system", "Answer from the passages."),
        ChatMessage("user", "Who is Ana?"),
        ChatMessage("assistant", """{"search":["Ana"]}"""),
        ChatMessage("user", "Passages: ..."),
    )

    @Test
    fun `one question goes as it is, with the instruction apart`() {
        assertEquals("Answer from the passages." to "Who is Ana?", NanoPrompt.of(messages.take(2), systemPrompt = true))
    }

    @Test
    fun `earlier turns are written out in the one prompt`() {
        val (system, prompt) = NanoPrompt.of(messages, systemPrompt = true)

        assertEquals("Answer from the passages.", system)
        assertEquals("[user]\nWho is Ana?\n\n[you]\n{\"search\":[\"Ana\"]}\n\n[user]\nPassages: ...\n\n[you]\n", prompt)
    }

    @Test
    fun `without system prompt support the instruction leads the prompt`() {
        val (system, prompt) = NanoPrompt.of(messages.take(2), systemPrompt = false)

        assertEquals(null, system)
        assertEquals("Answer from the passages.\n\nWho is Ana?", prompt)
    }

    @Test
    fun `a reply cut short, unfinished or empty is an error`() {
        assertEquals("Ana is a sailor.", NanoPrompt.answer("Ana is a sailor.", Candidate.FinishReason.STOP))
        assertEquals("Ana", NanoPrompt.answer("Ana", null))
        assertTrue(runCatching { NanoPrompt.answer("Ana is", Candidate.FinishReason.MAX_TOKENS) }.exceptionOrNull() is TranslationError.Truncated)
        assertTrue(runCatching { NanoPrompt.answer("Ana", Candidate.FinishReason.OTHER) }.exceptionOrNull() is TranslationError.Malformed)
        assertTrue(runCatching { NanoPrompt.answer(" ", Candidate.FinishReason.STOP) }.exceptionOrNull() is TranslationError.Empty)
        assertTrue(runCatching { NanoPrompt.answer(null, null) }.exceptionOrNull() is TranslationError.Empty)
    }

    @Test
    fun `a busy phone can be asked again, a missing model is not set up, too much text is too long`() {
        assertTrue(NanoPrompt.errorFor(GenAiException.ErrorCode.BUSY) is TranslationError.RateLimited)
        assertTrue(NanoPrompt.errorFor(GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED) is TranslationError.RateLimited)
        assertTrue(NanoPrompt.errorFor(GenAiException.ErrorCode.NOT_AVAILABLE) is TranslationError.NotSetUp)
        assertTrue(NanoPrompt.errorFor(GenAiException.ErrorCode.REQUEST_TOO_LARGE) is CompanionError.TooLong)
        assertTrue(NanoPrompt.errorFor(GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR) is TranslationError.Malformed)
        assertTrue(NanoPrompt.errorFor(GenAiException.ErrorCode.UNKNOWN) is TranslationError.Service)
    }
}
