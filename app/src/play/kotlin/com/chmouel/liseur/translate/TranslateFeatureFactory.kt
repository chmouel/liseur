package com.chmouel.liseur.translate

import android.content.Context
import com.chmouel.liseur.AppContainer

/**
 * The Play build translates with the phone's own translator, Google's
 * ML Kit on the phone, Gemini on the reader's key, or a listed server.
 * The phone's is the default; the others are chosen explicitly.
 */
object TranslateFeatureFactory {
    fun create(context: Context, container: AppContainer): TranslateFeature = ServiceTranslate(
        settings = container.appSettings,
        connections = container.serverConnections,
        accounts = container.serviceAccounts,
        saved = lazy { container.savedTranslations },
        services = listOf(
            DeviceTranslationService(context.applicationContext),
            MlKitTranslationService(context.applicationContext),
            GeminiTranslationService(container.serviceAccounts.gemini, container.appSettings),
            ServerTranslationService(container.serverConnections, container.appSettings),
        ),
    )
}
