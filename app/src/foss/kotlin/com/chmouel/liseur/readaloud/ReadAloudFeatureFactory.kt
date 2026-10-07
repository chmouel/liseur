package com.chmouel.liseur.readaloud

import android.app.Application
import android.content.Context
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.tts.ApiKeyStore
import com.chmouel.liseur.tts.DeviceSpeechService
import com.chmouel.liseur.tts.OpenAiSpeechService
import com.chmouel.liseur.tts.SpeechReadAloud

/**
 * The F-Droid build reads aloud with the device's own voices, which need
 * no setup and send nothing, or a speech server of the reader's choice,
 * such as one they host themselves.
 */
object ReadAloudFeatureFactory {
    fun create(context: Context, container: AppContainer): ReadAloudFeature = SpeechReadAloud(
        application = context.applicationContext as Application,
        settings = container.appSettings,
        checkpoints = container.listeningCheckpoints,
    ) { control ->
        listOf(
            DeviceSpeechService(context, container.appSettings, control),
            OpenAiSpeechService(ApiKeyStore.openAi(context), container.appSettings, control),
        )
    }
}
