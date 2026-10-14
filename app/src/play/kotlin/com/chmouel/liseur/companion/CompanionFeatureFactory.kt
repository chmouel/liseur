package com.chmouel.liseur.companion

import android.content.Context
import com.chmouel.liseur.AppContainer

/**
 * The Play build's reading companion asks a listed server or Gemini on
 * the reader's key. A listed server stays the default.
 */
internal object CompanionFeatureFactory {
    fun create(context: Context, container: AppContainer): CompanionServer = CompanionServer(
        settings = container.appSettings,
        server = ServerCompanionService(container.serverConnections, container.appSettings),
        others = listOf(
            GeminiCompanionService(container.serviceAccounts.gemini, container.appSettings),
        ),
    )
}
