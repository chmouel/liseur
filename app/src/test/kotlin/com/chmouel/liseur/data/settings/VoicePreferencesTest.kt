package com.chmouel.liseur.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePreferencesTest {
    private val english = VoicePreference("openai", "http://kokoro/v1", "kokoro", "en", "af_bella")
    private val french = VoicePreference("openai", "http://kokoro/v1", "kokoro", "fr", "ff_siwis")

    @Test
    fun `preferences survive a round trip`() {
        val stored = listOf(english, french, VoicePreference("device", "com.google.android.tts", "", "en", "en-gb-x-1"))
        assertEquals(stored, VoicePreferences.decode(VoicePreferences.encode(stored)))
    }

    @Test
    fun `a new choice replaces only its own language`() {
        val chosen = VoicePreferences.with(listOf(english, french), english.copy(voice = "bm_george"))
        assertEquals(listOf(french, english.copy(voice = "bm_george")), chosen)
        assertEquals(listOf(english, english.copy(model = "tts-1")), VoicePreferences.with(listOf(english), english.copy(model = "tts-1")))
    }

    @Test
    fun `damaged values are skipped`() {
        assertEquals(emptyList<VoicePreference>(), VoicePreferences.decode(null))
        assertEquals(emptyList<VoicePreference>(), VoicePreferences.decode("not json"))
        val mixed = """[{"provider":"openai","context":"http://kokoro/v1","model":"kokoro","language":"en","voice":"af_bella"},""" +
            """{"provider":"openai"},42,{"provider":"gemini","context":"","model":"m","language":"fr","voice":""}]"""
        assertEquals(listOf(english), VoicePreferences.decode(mixed))
    }
}
