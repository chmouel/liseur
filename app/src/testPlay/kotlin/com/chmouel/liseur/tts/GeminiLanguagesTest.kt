package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLanguagesTest {
    @Test
    fun `the bundled models read English and French`() {
        listOf(GeminiTts.DEFAULT_MODEL, "gemini-3.8-flash-tts", "models/gemini-3.8-flash-tts").forEach { model ->
            val languages = GeminiLanguages.of(model)!!
            assertTrue("en" in languages)
            assertTrue("fr" in languages)
        }
        assertFalse("sv" in GeminiLanguages.of(GeminiTts.DEFAULT_MODEL)!!)
        assertTrue("sv" in GeminiLanguages.of("gemini-3.8-flash-tts")!!)
    }

    @Test
    fun `every listed language is already in its normalized form`() {
        listOf(GeminiTts.DEFAULT_MODEL, "gemini-3.8-flash-tts").forEach { model ->
            GeminiLanguages.of(model)!!.forEach { assertEquals(it, SpeechLanguage.normalize(it)) }
        }
    }

    @Test
    fun `an unknown model says nothing of its languages`() {
        assertNull(GeminiLanguages.of("gemini-9-tts"))
    }
}
