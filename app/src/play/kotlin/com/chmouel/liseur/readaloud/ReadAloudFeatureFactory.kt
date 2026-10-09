package com.chmouel.liseur.readaloud

import android.app.Application
import android.content.Context
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.tts.DeviceSpeechService
import com.chmouel.liseur.tts.GeminiSpeechService
import com.chmouel.liseur.tts.OpenAiSpeechService
import com.chmouel.liseur.tts.SpeechReadAloud

/**
 * The Play build reads aloud with a Gemini voice on the reader's own key,
 * a speech server of their choice, or the device's own voices. Device
 * voices are the default; network providers are selected explicitly.
 */
object ReadAloudFeatureFactory {
    fun create(context: Context, container: AppContainer): ReadAloudFeature = SpeechReadAloud(
        application = context.applicationContext as Application,
        settings = container.appSettings,
        checkpoints = container.listeningCheckpoints,
        connections = container.serverConnections,
        accounts = container.serviceAccounts,
    ) { control ->
        listOf(
            DeviceSpeechService(context, container.appSettings, control),
            GeminiSpeechService(container.serviceAccounts.gemini, container.appSettings, control),
            OpenAiSpeechService(container.serverConnections, container.appSettings, control),
        )
    }
}
