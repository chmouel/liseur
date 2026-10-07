package com.chmouel.liseur.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLabelTest {
    @Test
    fun `a Kokoro id gives a name, a language and a gender`() {
        assertEquals(VoiceLabel("af_bella", "Bella", "en-US", VoiceLabel.Gender.FEMALE), VoiceLabel.of("af_bella"))
        assertEquals(VoiceLabel("bm_george", "George", "en-GB", VoiceLabel.Gender.MALE), VoiceLabel.of("bm_george"))
        assertEquals("fr-FR", VoiceLabel.of("ff_siwis").language)
    }

    @Test
    fun `any other id is shown as it is`() {
        assertEquals(VoiceLabel("alloy", "Alloy", null, null), VoiceLabel.of("alloy"))
        assertEquals(VoiceLabel("af_bella+af_sky", "Af_bella+af_sky", null, null), VoiceLabel.of("af_bella+af_sky"))
        assertEquals(VoiceLabel("xf_nope", "Xf_nope", null, null), VoiceLabel.of("xf_nope"))
    }

    @Test
    fun `voices are grouped by language, those without one last`() {
        val groups = VoiceLabel.grouped(listOf("alloy", "bf_emma", "af_heart", "bm_lewis"))
        assertEquals(listOf("en-GB", "en-US", null), groups.map { it.first })
        assertEquals(listOf("bf_emma", "bm_lewis"), groups[0].second.map { it.id })
        assertEquals(listOf("alloy"), groups[2].second.map { it.id })
    }
}
