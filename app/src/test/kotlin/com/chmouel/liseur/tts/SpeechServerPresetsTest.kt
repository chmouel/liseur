package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechServerPresetsTest {

    @Test
    fun everyAddressIsTakenAsItIs() {
        SpeechServerPresets.all.forEach { preset ->
            // A self-hosted example is the server's own address; /v1 is added to it.
            val expected = if (preset.example) "${preset.url}/v1" else preset.url
            assertEquals(expected, OpenAiTts.baseUrl(preset.url).toString())
            if (preset.example) {
                assertNull(preset.keyUrl)
            } else {
                assertNotNull(preset.keyUrl)
                assertTrue(preset.keyUrl!!.startsWith("https://"))
            }
        }
    }

    @Test
    fun aHostedServiceIsKnownByItsServerWhateverThePath() {
        val deepInfra = SpeechServerPresets.all.single { it.url.contains("deepinfra") }
        assertEquals(deepInfra, SpeechServerPresets.matching("https://API.deepinfra.com:443/v1/openai/"))
        assertEquals(deepInfra, SpeechServerPresets.matching("https://api.deepinfra.com/v1/other"))
        val together = SpeechServerPresets.all.single { it.url == "https://api.together.ai/v1" }
        assertEquals(together, SpeechServerPresets.matching("https://API.together.ai:443/v1/"))
        assertNull(SpeechServerPresets.matching("http://api.together.ai/v1"))
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

    @Test
    fun gradiumsRegionsAreEachTheirOwnServerAndKeepTheirApiRoot() {
        val regions = SpeechServerPresets.all.filter { it.url.contains("gradium") }
        assertEquals(
            listOf("https://api.gradium.ai/api", "https://eu.api.gradium.ai/api", "https://us.api.gradium.ai/api"),
            regions.map { it.url },
        )
        assertEquals(3, regions.map { it.origin }.distinct().size)
        assertEquals("https://eu.api.gradium.ai/api", OpenAiTts.baseUrl("https://eu.api.gradium.ai/api").toString())
        assertEquals("https://eu.api.gradium.ai/api/v1", OpenAiTts.identityUrl("https://eu.api.gradium.ai/api/").toString())
        assertEquals(regions[1], SpeechServerPresets.matching("https://EU.api.gradium.ai/api"))
        assertNull(SpeechServerPresets.matching("http://api.gradium.ai/api"))
    }
}
