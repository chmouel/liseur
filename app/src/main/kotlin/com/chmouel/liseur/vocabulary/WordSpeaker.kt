package com.chmouel.liseur.vocabulary

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

internal class WordSpeaker {
    var engine: TextToSpeech? = null
    var ready by mutableStateOf(false)

    private fun locale(language: String): Locale =
        if (language == "und") Locale.getDefault() else Locale.forLanguageTag(language)

    private fun voice(language: String): Voice? {
        val target = locale(language)
        return engine?.voices.orEmpty()
            .filter {
                it.locale.language == target.language && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }
            .minWithOrNull(compareBy<Voice> { it.locale != target }.thenBy { it.name })
    }

    fun supports(language: String): Boolean = ready &&
        (engine?.isLanguageAvailable(locale(language)) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= 0 && voice(language) != null

    fun speak(word: VocabularyWord) {
        val tts = engine ?: return
        if (!ready) return
        val voice = voice(word.language) ?: return
        if (tts.setLanguage(locale(word.language)) < 0 || tts.setVoice(voice) < 0) return
        if (tts.speak(word.word, TextToSpeech.QUEUE_FLUSH, null, "vocabulary-${word.id}") == TextToSpeech.ERROR) {
            ready = false
        }
    }
}

@Composable
internal fun rememberWordSpeaker(): WordSpeaker {
    val context = LocalContext.current.applicationContext
    val speaker = remember { WordSpeaker() }
    DisposableEffect(context) {
        var disposed = false
        val handler = Handler(Looper.getMainLooper())
        val engine = TextToSpeech(context) { status ->
            handler.post { if (!disposed) speaker.ready = status == TextToSpeech.SUCCESS }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = Unit
            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String) {
                handler.post { if (!disposed) speaker.ready = false }
            }
        })
        speaker.engine = engine
        onDispose {
            disposed = true
            speaker.ready = false
            speaker.engine = null
            engine.stop()
            engine.shutdown()
        }
    }
    return speaker
}
