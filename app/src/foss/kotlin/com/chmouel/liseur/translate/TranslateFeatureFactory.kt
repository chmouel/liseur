package com.chmouel.liseur.translate

import android.content.Context
import com.chmouel.liseur.AppContainer

/**
 * The F-Droid build translates with the phone's own translator, which
 * sends nothing, or a server of the reader's choice, such as one they
 * host themselves.
 */
object TranslateFeatureFactory {
    fun create(context: Context, container: AppContainer): TranslateFeature = ServiceTranslate(
        settings = container.appSettings,
        connections = container.serverConnections,
        accounts = container.serviceAccounts,
        services = listOf(
            DeviceTranslationService(context.applicationContext),
            ServerTranslationService(container.serverConnections, container.appSettings),
        ),
    )
}
