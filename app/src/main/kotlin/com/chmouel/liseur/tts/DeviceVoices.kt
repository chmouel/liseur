package com.chmouel.liseur.tts

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

/** A voice of the device's speech engine as offered: its engine name, its language, and its number within it. */
internal data class DeviceVoice(val id: String, val language: String, val number: Int)

/** What the engine says of one of its voices. */
internal data class EngineVoice(
    val name: String,
    /** A BCP 47 tag such as `en-US`. */
    val language: String,
    val needsNetwork: Boolean,
    val installed: Boolean,
)

internal object DeviceVoices {
    /**
     * The voice read in a language when the reader has chosen none, by
     * primary language, while the engine offers it: Google's en-US and
     * fr-FR voices that are Voice 7 and Voice 5 with the full packs
     * installed. Named rather than numbered, since numbers move as
     * voices are installed.
     */
    val PREFERRED = mapOf(
        "en" to "en-us-x-tpc-local",
        "fr" to "fr-fr-x-frd-local",
    )

    /** The [PREFERRED] voices among [voices], by primary language. */
    fun preferred(voices: List<DeviceVoice>): Map<String, String> =
        PREFERRED.filterValues { name -> voices.any { it.id == name } }

    /**
     * The [voices] that speak without a network, so no text leaves the
     * device, numbered within their language by name. Engines name voices
     * with codes nobody can read, which is why they get numbers.
     */
    fun offline(voices: Collection<EngineVoice>): List<DeviceVoice> = voices
        .filter { !it.needsNetwork && it.installed }
        .groupBy { it.language }
        .flatMap { (language, same) ->
            same.map { it.name }.distinct().sorted().mapIndexed { i, name -> DeviceVoice(name, language, i + 1) }
        }

    /**
     * The voice to read with: the [stored] one when it is offered, else
     * the [PREFERRED] one for [locale]'s language, else the engine's
     * [default], else the first in the language of [locale], else the
     * first. Null when there is none.
     */
    fun pick(voices: List<DeviceVoice>, stored: String?, default: String?, locale: Locale): DeviceVoice? =
        voices.firstOrNull { it.id == stored }
            ?: voices.firstOrNull { it.id == PREFERRED[locale.language] }
            ?: voices.firstOrNull { it.id == default }
            ?: voices.firstOrNull { Locale.forLanguageTag(it.language).language == locale.language }
            ?: voices.firstOrNull()

    /** [voices] by language, those of [locale]'s language first, then by language tag. */
    fun grouped(voices: List<DeviceVoice>, locale: Locale): List<Pair<String, List<DeviceVoice>>> =
        voices.groupBy { it.language }.toList().sortedWith(
            compareBy<Pair<String, List<DeviceVoice>>> {
                Locale.forLanguageTag(it.first).language != locale.language
            }.thenBy { it.first },
        )

    /**
     * The engine's audio as [SpeechAudio] wants it: mono 16-bit at
     * [SpeechAudio.SAMPLE_RATE]. Channels are averaged and the rate is
     * changed by linear interpolation, which is plenty for a voice.
     * Null for an [encoding] it does not know.
     */
    fun toSpeechPcm(audio: ByteArray, sampleRate: Int, encoding: Int, channels: Int): ByteArray? {
        if (sampleRate <= 0 || channels <= 0) return null
        if (encoding == AudioFormat.ENCODING_PCM_16BIT && channels == 1 && sampleRate == SpeechAudio.SAMPLE_RATE) {
            return audio.copyOf(audio.size - audio.size % 2)
        }
        val buffer = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN)
        val samples: FloatArray = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> FloatArray(audio.size / 2) { buffer.getShort(it * 2) / 32768f }
            AudioFormat.ENCODING_PCM_8BIT -> FloatArray(audio.size) { ((audio[it].toInt() and 0xFF) - 128) / 128f }
            AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(audio.size / 4) { buffer.getFloat(it * 4) }
            else -> return null
        }
        val frames = samples.size / channels
        val mono = FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += samples[f * channels + c]
            sum / channels
        }
        val outFrames = if (sampleRate == SpeechAudio.SAMPLE_RATE) {
            frames
        } else {
            (frames.toLong() * SpeechAudio.SAMPLE_RATE / sampleRate).toInt()
        }
        val out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outFrames) {
            val at = i.toDouble() * sampleRate / SpeechAudio.SAMPLE_RATE
            val left = at.toInt().coerceAtMost(frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val value = mono[left] + (mono[right] - mono[left]) * (at - left).toFloat()
            out.putShort((value.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        }
        return out.array()
    }
}
