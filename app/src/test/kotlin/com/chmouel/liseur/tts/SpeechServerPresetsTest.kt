package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechServerPresetsTest {

    @Test
    fun everyAddressIsTakenAsItIs() {
        SpeechServerPresets.all.forEach { preset ->
            // A self-hosted example is the server's own address; /v1 is added to it.
            val expected = if (preset.example) "${preset.url}/v1" else preset.url
            assertEquals(expected, OpenAiTts.baseUrl(preset.url).toString())
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
    fun theSelfHostedExamplesAreNeverShownAsInUse() {
        val examples = SpeechServerPresets.all.filter { it.example }
        assertEquals(listOf("8880", "1234", "11434"), examples.map { OpenAiTts.baseUrl(it.url)!!.port.toString() })
        examples.forEach { example ->
            assertNull(SpeechServerPresets.matching(example.url))
            assertEquals("192.168.1.10", example.url.substring(example.host))
        }
    }
}
