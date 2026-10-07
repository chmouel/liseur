package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceChoiceTest {

    private val qwen = listOf("Vivian", "Serena")

    @Test
    fun `no saved voice takes the model's default`() {
        assertEquals("Vivian", VoiceChoice.after("", qwen, changed = false))
    }

    @Test
    fun `a new model that lacks the voice moves to its default`() {
        assertEquals("Vivian", VoiceChoice.after("af_bella", qwen, changed = true))
        assertEquals("Serena", VoiceChoice.after("Serena", qwen, changed = true))
    }

    @Test
    fun `a refresh keeps a voice the list lacks, as it may be typed or cloned`() {
        assertEquals("my-clone", VoiceChoice.after("my-clone", qwen, changed = false))
    }

    @Test
    fun `with no preset voices the saved one stays`() {
        assertEquals("af_bella", VoiceChoice.after("af_bella", emptyList(), changed = true))
        assertEquals("", VoiceChoice.after("", null, changed = true))
    }
}
