package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechProviderSelectionTest {
    @Test
    fun deviceSpeechIsTheDefaultWhenOffered() {
        assertEquals(
            DEVICE_SPEECH_PROVIDER_ID,
            speechProviderId(listOf("gemini", DEVICE_SPEECH_PROVIDER_ID, "openai"), saved = null),
        )
    }

    @Test
    fun aSavedProviderChoiceIsPreserved() {
        assertEquals(
            "gemini",
            speechProviderId(listOf(DEVICE_SPEECH_PROVIDER_ID, "gemini", "openai"), saved = "gemini"),
        )
    }

    @Test
    fun buildsWithoutDeviceSpeechFallBackToTheirFirstProvider() {
        assertEquals("openai", speechProviderId(listOf("openai", "gemini"), saved = null))
    }
}
