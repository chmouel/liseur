package com.chmouel.liseur.readaloud

import android.app.Application
import android.content.Context
import com.chmouel.liseur.AppContainer
import com.chmouel.liseur.tts.GeminiKeyStore
import com.chmouel.liseur.tts.GeminiReadAloud

/** The Play build reads aloud with a Gemini voice, on the reader's own key. */
object ReadAloudFeatureFactory {
    fun create(context: Context, container: AppContainer): ReadAloudFeature = GeminiReadAloud(
        application = context.applicationContext as Application,
        keys = GeminiKeyStore(context),
        settings = container.appSettings,
        checkpoints = container.listeningCheckpoints,
    )
}
