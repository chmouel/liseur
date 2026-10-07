package com.chmouel.liseur.readaloud

import android.app.Application
import android.content.Context
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.tts.ApiKeyStore
import com.chmouel.liseur.tts.GeminiSpeechService
import com.chmouel.liseur.tts.OpenAiSpeechService
import com.chmouel.liseur.tts.SpeechReadAloud

/**
 * The Play build reads aloud with a Gemini voice on the reader's own key,
 * or an OpenAI-compatible service of their choice. Gemini comes first, so
 * it stays the choice of a reader who never made one.
 */
object ReadAloudFeatureFactory {
    fun create(context: Context, container: AppContainer): ReadAloudFeature = SpeechReadAloud(
        application = context.applicationContext as Application,
        settings = container.appSettings,
        checkpoints = container.listeningCheckpoints,
    ) { control ->
        listOf(
            GeminiSpeechService(ApiKeyStore.gemini(context), container.appSettings, control),
            OpenAiSpeechService(ApiKeyStore.openAi(context), container.appSettings, control),
        )
    }
}
