package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechServerPresetsTest {

    @Test
    fun everyAddressIsTakenAsItIs() {
        SpeechServerPresets.all.forEach { preset ->
            assertEquals(preset.url, OpenAiTts.baseUrl(preset.url).toString())
        }
    }

    @Test
    fun aHostedServiceIsKnownByItsServerWhateverThePath() {
        val deepInfra = SpeechServerPresets.all.single { it.url.contains("deepinfra") }
        assertEquals(deepInfra, SpeechServerPresets.matching("https://API.deepinfra.com:443/v1/openai/"))
        assertEquals(deepInfra, SpeechServerPresets.matching("https://api.deepinfra.com/v1/other"))
        assertNull(SpeechServerPresets.matching("http://api.deepinfra.com/v1/openai"))
        assertNull(SpeechServerPresets.matching("not an address"))
    }

    @Test
    fun theSelfHostedExampleIsNeverShownAsInUse() {
        val example = SpeechServerPresets.all.single { it.example }
        assertNull(SpeechServerPresets.matching(example.url))
        assertEquals("192.168.1.10", example.url.substring(example.host))
        assertTrue(SpeechServerPresets.all.count { it.example } == 1)
    }
}
