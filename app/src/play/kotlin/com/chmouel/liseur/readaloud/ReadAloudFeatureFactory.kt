package com.chmouel.liseur.readaloud

import android.app.Application
import android.content.Context
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.tts.ApiKeyStore
import com.chmouel.liseur.tts.SpeechReadAloud

/** The Play build reads aloud with a Gemini voice on the reader's own key, or a Kokoro server of theirs. */
object ReadAloudFeatureFactory {
    fun create(context: Context, container: AppContainer): ReadAloudFeature = SpeechReadAloud(
        application = context.applicationContext as Application,
        geminiKeys = ApiKeyStore.gemini(context),
        kokoroKeys = ApiKeyStore.kokoro(context),
        settings = container.appSettings,
        checkpoints = container.listeningCheckpoints,
    )
}
