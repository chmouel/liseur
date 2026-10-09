package com.chmouel.liseur.translate

import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.data.settings.ServerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PageIdentityTest {
    private val url = "https://llm.example/v1"
    private val id = checkNotNull(ServerConnection.idOf(url))

    private fun settings(name: String = "Home") = AppSettings(
        servers = ServerList.Readable(listOf(ServerConnection(id, name, url))),
        translationProvider = ServerSettings.SERVER_PROVIDER,
        translationServer = id,
    )

    private fun server(s: AppSettings, model: String = "small", source: String? = "fr") =
        pageIdentity(s, ServerSettings.SERVER_PROVIDER, model, source, "en")

    @Test
    fun `a server keeps its translations through a rename`() {
        assertEquals(server(settings()), server(settings(name = "Renamed")))
    }

    @Test
    fun `the model and the languages are part of it`() {
        assertNotEquals(server(settings()), server(settings(), model = "large"))
        assertNotEquals(server(settings()), server(settings(), source = null))
    }

    @Test
    fun `another service is not tied to the chosen server`() {
        assertFalse(pageIdentity(settings(), "gemini", "flash", "fr", "en").contains(id))
    }
}
