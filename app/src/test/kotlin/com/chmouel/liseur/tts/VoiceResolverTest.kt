package com.chmouel.liseur.tts

import com.chmouel.liseur.data.settings.VoicePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceResolverTest {
    private val kokoro = VoiceScope("openai", "http://kokoro:8880/v1", "kokoro")

    private fun kokoroVoices(vararg ids: String) = ids.map { CatalogueVoice(it, setOfNotNull(VoiceLabel.of(it).language)) }

    private val catalogue = VoiceCatalogue(
        kokoro,
        kokoroVoices("af_bella", "af_sky", "bf_emma", "bm_george", "ff_siwis", "jf_alpha"),
    )

    private val english = BookLanguage.Known("en")
    private val french = BookLanguage.Known("fr")

    private fun resolved(voice: String, language: String) = VoiceResolution.Resolved(VoicePick(voice, language))

    @Test
    fun `English and French books each keep their own voice`() {
        var preferences = emptyList<VoicePreference>()
        assertEquals(resolved("af_bella", "en"), VoiceResolver.resolve(english, catalogue, preferences))
        assertEquals(resolved("ff_siwis", "fr"), VoiceResolver.resolve(french, catalogue, preferences))

        preferences = preferences + kokoro.preference("en-GB", "bm_george")
        preferences = preferences + kokoro.preference("fr", "ff_siwis")
        repeat(2) {
            assertEquals(resolved("bm_george", "en"), VoiceResolver.resolve(english, catalogue, preferences))
            assertEquals(resolved("ff_siwis", "fr"), VoiceResolver.resolve(french, catalogue, preferences))
        }
    }

    @Test
    fun `a region picks its accent unless another was chosen`() {
        assertEquals(resolved("bf_emma", "en-GB"), VoiceResolver.resolve(BookLanguage.Known("en-GB"), catalogue, emptyList()))
        assertEquals(resolved("af_bella", "en-US"), VoiceResolver.resolve(BookLanguage.Known("en-US"), catalogue, emptyList()))
        val chosen = listOf(kokoro.preference("en", "af_sky"))
        assertEquals(resolved("af_sky", "en-GB"), VoiceResolver.resolve(BookLanguage.Known("en-GB"), catalogue, chosen))
        assertEquals(resolved("af_sky", "en-AU"), VoiceResolver.resolve(BookLanguage.Known("en-AU"), catalogue, chosen))
    }

    @Test
    fun `the book's aliases match the voices`() {
        mapOf("eng" to "af_bella", "en-US" to "af_bella", "en_GB" to "bf_emma").forEach { (tag, voice) ->
            val book = SpeechLanguage.ofBook(listOf(tag))
            assertEquals(voice, (VoiceResolver.resolve(book, catalogue, emptyList()) as VoiceResolution.Resolved).pick.voice)
        }
        listOf("fra", "fre", "fr-CA").forEach {
            val book = SpeechLanguage.ofBook(listOf(it))
            assertEquals("ff_siwis", (VoiceResolver.resolve(book, catalogue, emptyList()) as VoiceResolution.Resolved).pick.voice)
        }
    }

    @Test
    fun `the saved voice reads when it speaks the language, then the default`() {
        assertEquals(resolved("bm_george", "en"), VoiceResolver.resolve(english, catalogue.copy(global = "bm_george"), emptyList()))
        assertEquals(resolved("ff_siwis", "fr"), VoiceResolver.resolve(french, catalogue.copy(global = "bm_george"), emptyList()))
        assertEquals(resolved("af_sky", "en"), VoiceResolver.resolve(english, catalogue.copy(default = "af_sky"), emptyList()))
        // The exact region before the default in another accent.
        assertEquals(resolved("bf_emma", "en-GB"), VoiceResolver.resolve(BookLanguage.Known("en-GB"), catalogue.copy(default = "af_sky"), emptyList()))
    }

    @Test
    fun `a language's own default comes before the service default, after any choice`() {
        val device = VoiceScope("device", "com.google.android.tts", "")
        val voices = VoiceCatalogue(
            device,
            listOf(
                CatalogueVoice("en-us-x-iob-local", setOf("en-US")),
                CatalogueVoice("en-us-x-tpc-local", setOf("en-US")),
                CatalogueVoice("en-gb-x-gba-local", setOf("en-GB")),
                CatalogueVoice("fr-fr-x-fra-local", setOf("fr-FR")),
                CatalogueVoice("fr-fr-x-frd-local", setOf("fr-FR")),
                CatalogueVoice("fr-ca-x-caa-local", setOf("fr-CA")),
            ),
            default = "en-us-x-iob-local",
            defaults = mapOf("en" to "en-us-x-tpc-local", "fr" to "fr-fr-x-frd-local"),
        )

        assertEquals(resolved("en-us-x-tpc-local", "en"), VoiceResolver.resolve(english, voices, emptyList()))
        assertEquals(resolved("en-us-x-tpc-local", "en-US"), VoiceResolver.resolve(BookLanguage.Known("en-US"), voices, emptyList()))
        assertEquals(resolved("fr-fr-x-frd-local", "fr"), VoiceResolver.resolve(french, voices, emptyList()))
        assertEquals(resolved("fr-fr-x-frd-local", "fr-FR"), VoiceResolver.resolve(BookLanguage.Known("fr-FR"), voices, emptyList()))
        // Another region keeps its own accent.
        assertEquals(resolved("en-gb-x-gba-local", "en-GB"), VoiceResolver.resolve(BookLanguage.Known("en-GB"), voices, emptyList()))
        assertEquals(resolved("fr-ca-x-caa-local", "fr-CA"), VoiceResolver.resolve(BookLanguage.Known("fr-CA"), voices, emptyList()))
        // A remembered or saved voice still wins.
        val chosen = listOf(device.preference("en", "en-us-x-iob-local"))
        assertEquals(resolved("en-us-x-iob-local", "en"), VoiceResolver.resolve(english, voices, chosen))
        assertEquals(resolved("fr-fr-x-fra-local", "fr"), VoiceResolver.resolve(french, voices.copy(global = "fr-fr-x-fra-local"), emptyList()))
        // Without it, the service default reads.
        assertEquals(resolved("en-us-x-iob-local", "en"), VoiceResolver.resolve(english, voices.copy(defaults = emptyMap()), emptyList()))
        val tpcGone = voices.copy(voices = voices.voices.filter { it.id != "en-us-x-tpc-local" })
        assertEquals(resolved("en-us-x-iob-local", "en"), VoiceResolver.resolve(english, tpcGone, emptyList()))
    }

    @Test
    fun `an unknown, mixed or unspoken language is asked about`() {
        assertEquals(VoiceResolution.NeedsChoice(ChoiceReason.Missing), VoiceResolver.resolve(BookLanguage.Missing, catalogue, emptyList()))
        assertEquals(
            VoiceResolution.NeedsChoice(ChoiceReason.Ambiguous),
            VoiceResolver.resolve(BookLanguage.Ambiguous(listOf("en", "fr")), catalogue, emptyList()),
        )
        assertEquals(VoiceResolution.NeedsChoice(ChoiceReason.NoVoice), VoiceResolver.resolve(BookLanguage.Known("ru"), catalogue, emptyList()))
        assertEquals(
            VoiceResolution.NeedsChoice(ChoiceReason.CatalogueFailed),
            VoiceResolver.resolve(BookLanguage.Known("ru"), catalogue.copy(failed = true), emptyList()),
        )
    }

    @Test
    fun `a voice no longer offered or from elsewhere is not remembered`() {
        val gone = listOf(kokoro.preference("en", "am_adam"))
        assertEquals(resolved("af_bella", "en"), VoiceResolver.resolve(english, catalogue, gone))
        val otherServer = listOf(VoiceScope("openai", "http://other/v1", "kokoro").preference("en", "bm_george"))
        assertEquals(resolved("af_bella", "en"), VoiceResolver.resolve(english, catalogue, otherServer))
        val otherModel = listOf(kokoro.copy(model = "tts-1").preference("en", "bm_george"))
        assertEquals(resolved("af_bella", "en"), VoiceResolver.resolve(english, catalogue, otherModel))
        // Known to speak French, so never read English with.
        val wrong = listOf(kokoro.preference("en", "ff_siwis"))
        assertEquals(resolved("af_bella", "en"), VoiceResolver.resolve(english, catalogue, wrong))
    }

    @Test
    fun `an unclassified voice reads only the language it was given`() {
        val server = VoiceScope("openai", "http://speaches/v1", "tts-1")
        val voices = VoiceCatalogue(server, listOf(CatalogueVoice("alloy", null), CatalogueVoice("nova", null)), global = "alloy")
        assertEquals(VoiceResolution.NeedsChoice(ChoiceReason.NoVoice), VoiceResolver.resolve(english, voices, emptyList()))
        val assigned = listOf(server.preference("fr", "nova"))
        assertEquals(resolved("nova", "fr"), VoiceResolver.resolve(french, voices, assigned))
        assertEquals(VoiceResolution.NeedsChoice(ChoiceReason.NoVoice), VoiceResolver.resolve(english, voices, assigned))
    }

    @Test
    fun `typed voices are kept when the list fails`() {
        val server = VoiceScope("openai", "http://kokoro:8880/v1", "kokoro")
        val typed = VoiceCatalogue(server, kokoroVoices("ff_siwis"), failed = true)
        assertEquals(resolved("ff_siwis", "fr"), VoiceResolver.resolve(french, typed, emptyList()))
        assertEquals(VoiceResolution.NeedsChoice(ChoiceReason.CatalogueFailed), VoiceResolver.resolve(english, typed, emptyList()))
    }

    @Test
    fun `an uninstalled device voice gives way to an installed one`() {
        val device = VoiceScope("device", "com.google.android.tts", "")
        val installed = VoiceCatalogue(
            device,
            listOf(CatalogueVoice("en-us-x-sfg", setOf("en-US")), CatalogueVoice("fr-fr-x-frb", setOf("fr-FR"))),
            default = "en-us-x-sfg",
        )
        val remembered = listOf(device.preference("en", "en-gb-x-rjs"))
        assertEquals(resolved("en-us-x-sfg", "en"), VoiceResolver.resolve(english, installed, remembered))
        assertEquals(resolved("fr-fr-x-frb", "fr"), VoiceResolver.resolve(french, installed, remembered))
    }

    @Test
    fun `a voice picked in Settings is remembered for the language it speaks or the one being read`() {
        assertEquals("en", VoiceResolver.settingsLanguage(setOf("en-GB"), "fr"))
        assertEquals("fr", VoiceResolver.settingsLanguage(null, "fr-CA"))
        assertNull(VoiceResolver.settingsLanguage(null, null))
        assertEquals("fr", VoiceResolver.settingsLanguage(setOf("en", "fr"), "fr"))
        assertNull(VoiceResolver.settingsLanguage(setOf("en", "de"), "fr"))
    }

    @Test
    fun `the session keeps the book's region or the voice's accent`() {
        assertEquals("fr-CA", VoiceResolver.sessionTag("fr", BookLanguage.Known("fr-CA"), CatalogueVoice("ff_siwis", setOf("fr-FR"))))
        assertEquals("fr-FR", VoiceResolver.sessionTag("fr", BookLanguage.Missing, CatalogueVoice("ff_siwis", setOf("fr-FR"))))
        assertEquals("fr", VoiceResolver.sessionTag("fr", BookLanguage.Missing, CatalogueVoice("nova", null)))
    }
}
