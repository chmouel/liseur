package com.chmouel.liseur.tts

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceVoicesTest {
    private fun engine(name: String, language: String, network: Boolean = false, installed: Boolean = true) =
        EngineVoice(name, language, network, installed)

    @Test
    fun onlyInstalledOfflineVoicesAreOfferedNumberedWithinTheirLanguage() {
        val voices = DeviceVoices.offline(
            listOf(
                engine("en-us-x-tpf-local", "en-US"),
                engine("en-us-x-iob-local", "en-US"),
                engine("en-us-x-iob-network", "en-US", network = true),
                engine("en-us-x-sfg-local", "en-US", installed = false),
                engine("fr-fr-x-frc-local", "fr-FR"),
            ),
        )

        assertEquals(
            listOf(
                DeviceVoice("en-us-x-iob-local", "en-US", 1),
                DeviceVoice("en-us-x-tpf-local", "en-US", 2),
                DeviceVoice("fr-fr-x-frc-local", "fr-FR", 1),
            ),
            voices,
        )
    }

    @Test
    fun deviceSpeechNeedsAtLeastOneUsableOfflineVoice() {
        assertTrue(deviceSpeechConfigured(Result.success(listOf(DeviceVoice("en", "en-US", 1)))))
        assertFalse(deviceSpeechConfigured(Result.success(emptyList())))
        assertFalse(deviceSpeechConfigured(Result.failure(SpeechError.InvalidResponse("no speech engine"))))
    }

    @Test
    fun theStoredVoiceWinsThenTheEnginesDefaultThenTheReadersLanguage() {
        val en = DeviceVoice("en", "en-US", 1)
        val fr = DeviceVoice("fr", "fr-FR", 1)
        val de = DeviceVoice("de", "de-DE", 1)
        val voices = listOf(en, fr, de)

        assertEquals(fr, DeviceVoices.pick(voices, "fr", "de", Locale.ENGLISH))
        assertEquals(de, DeviceVoices.pick(voices, "gone", "de", Locale.ENGLISH))
        assertEquals(fr, DeviceVoices.pick(voices, null, "en-us-x-network", Locale.FRANCE))
        assertEquals(en, DeviceVoices.pick(voices, null, null, Locale.JAPAN))
        assertNull(DeviceVoices.pick(emptyList(), "fr", "de", Locale.FRANCE))
    }

    @Test
    fun englishAndFrenchHaveTheirOwnDefaultBeforeTheEnginesWhenOffered() {
        val tpc = DeviceVoice("en-us-x-tpc-local", "en-US", 7)
        val iob = DeviceVoice("en-us-x-iob-local", "en-US", 2)
        val frd = DeviceVoice("fr-fr-x-frd-local", "fr-FR", 5)
        val fra = DeviceVoice("fr-fr-x-fra-local", "fr-FR", 2)
        val voices = listOf(iob, tpc, fra, frd)

        assertEquals(mapOf("en" to tpc.id, "fr" to frd.id), DeviceVoices.preferred(voices))
        assertEquals(mapOf("en" to tpc.id), DeviceVoices.preferred(listOf(iob, tpc, fra)))
        assertEquals(tpc, DeviceVoices.pick(voices, null, iob.id, Locale.US))
        assertEquals(frd, DeviceVoices.pick(voices, null, fra.id, Locale.FRANCE))
        assertEquals(iob, DeviceVoices.pick(voices, iob.id, null, Locale.US))
        assertEquals(fra, DeviceVoices.pick(listOf(iob, fra), null, fra.id, Locale.FRANCE))
    }

    @Test
    fun theReadersLanguageIsListedFirst() {
        val voices = listOf(
            DeviceVoice("de", "de-DE", 1),
            DeviceVoice("en", "en-US", 1),
            DeviceVoice("fr-ca", "fr-CA", 1),
            DeviceVoice("fr", "fr-FR", 1),
        )

        assertEquals(
            listOf("fr-CA", "fr-FR", "de-DE", "en-US"),
            DeviceVoices.grouped(voices, Locale.FRANCE).map { it.first },
        )
    }

    @Test
    fun monoSixteenBitAtTheRightRateIsKeptAsItIs() {
        val pcm = shorts(1, -2, 300, -32768)

        assertArrayEquals(pcm, DeviceVoices.toSpeechPcm(pcm, 24_000, AudioFormat.ENCODING_PCM_16BIT, 1))
    }

    @Test
    fun stereoIsAveragedAndAHigherRateHalved() {
        // 48 kHz stereo: four frames become two at 24 kHz.
        val pcm = shorts(1000, 3000, 2000, 2000, 4000, 0, 0, 0)

        val out = DeviceVoices.toSpeechPcm(pcm, 48_000, AudioFormat.ENCODING_PCM_16BIT, 2)!!

        assertArrayEquals(intArrayOf(2000, 2000), shortsOf(out))
    }

    @Test
    fun aLowerRateIsInterpolated() {
        // 12 kHz to 24 kHz: a sample appears halfway between each pair.
        val out = DeviceVoices.toSpeechPcm(shorts(0, 1000), 12_000, AudioFormat.ENCODING_PCM_16BIT, 1)!!

        val samples = shortsOf(out)
        assertEquals(4, samples.size)
        assertEquals(0, samples[0])
        assertEquals(500, samples[1], 1)
        assertEquals(1000, samples[2], 1)
    }

    @Test
    fun eightBitAndFloatAreConverted() {
        val eight = DeviceVoices.toSpeechPcm(byteArrayOf(128.toByte(), 255.toByte()), 24_000, AudioFormat.ENCODING_PCM_8BIT, 1)!!
        assertEquals(0, shortsOf(eight)[0])
        assertEquals(32511, shortsOf(eight)[1], 300)

        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-2f).array()
        val out = shortsOf(DeviceVoices.toSpeechPcm(floats, 24_000, AudioFormat.ENCODING_PCM_FLOAT, 1)!!)
        assertEquals(16384, out[0], 1)
        assertEquals(-32767, out[1])
    }

    @Test
    fun anUnknownFormatGivesNothing() {
        assertNull(DeviceVoices.toSpeechPcm(ByteArray(4), 24_000, AudioFormat.ENCODING_AC3, 1))
        assertNull(DeviceVoices.toSpeechPcm(ByteArray(4), 0, AudioFormat.ENCODING_PCM_16BIT, 1))
    }

    private fun shorts(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    private fun shortsOf(pcm: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(pcm.size / 2) { buffer.getShort(it * 2).toInt() }
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) =
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
}
