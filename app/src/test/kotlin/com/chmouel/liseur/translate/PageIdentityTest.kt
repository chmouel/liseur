package com.chmouel.liseur.translate

import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.data.settings.ServerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PageIdentityTest {
    private val url = "https://llm.example/v1"
    private val id = checkNotNull(ServerConnection.idOf(url))

    private fun settings(name: String = "Home", model: String = "small", gemini: String? = "flash") = AppSettings(
        servers = ServerList.Readable(listOf(ServerConnection(id, name, url))),
        translationProvider = ServerSettings.SERVER_PROVIDER,
        translationServer = id,
        translationModels = mapOf(id to model),
        translationGeminiModel = gemini,
    )

    private fun server(s: AppSettings) = pageIdentity(s, ServerSettings.SERVER_PROVIDER, "fr", "en")

    @Test
    fun `a server keeps its translations through a rename and another service's model`() {
        assertEquals(server(settings()), server(settings(name = "Renamed", gemini = "pro")))
    }

    @Test
    fun `the model and the languages are part of it`() {
        assertNotEquals(server(settings()), server(settings(model = "large")))
        assertNotEquals(server(settings()), pageIdentity(settings(), ServerSettings.SERVER_PROVIDER, null, "en"))
    }

    @Test
    fun `the phone's translator ignores every model`() {
        assertEquals(
            pageIdentity(settings(), DeviceTranslationService.ID, "fr", "en"),
            pageIdentity(settings(model = "large", gemini = "pro"), DeviceTranslationService.ID, "fr", "en"),
        )
    }
}
