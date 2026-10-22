package com.chmouel.liseur.translate

import com.chmouel.liseur.data.settings.AppSettings
import com.chmouel.liseur.data.settings.ServerConnection
import com.chmouel.liseur.data.settings.ServerList
import com.chmouel.liseur.data.settings.ServerSettings
import com.chmouel.liseur.data.settings.ServerTranslationState
import com.chmouel.liseur.data.settings.TranslationContextSize
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
    fun `options that change a translation change it, and untouched ones keep the old one`() {
        val plain = settings()
        val untuned = plain.copy(translationStates = mapOf(id to ServerTranslationState(model = "small", keepAlive = "10m")))
        assertEquals(server(plain), server(untuned))
        val warmer = plain.copy(translationStates = mapOf(id to ServerTranslationState(temperature = 0.7)))
        val told = plain.copy(translationStates = mapOf(id to ServerTranslationState(instructions = "Use tu.")))
        assertNotEquals(server(plain), server(warmer))
        assertNotEquals(server(plain), server(told))
        assertNotEquals(server(warmer), server(told))
        // TranslateGemma never gets the sentence before, so turning it off changes nothing.
        val alone = plain.copy(translationStates = mapOf(id to ServerTranslationState(context = false)))
        assertEquals(server(plain, model = "translategemma:4b"), server(alone, model = "translategemma:4b"))
        assertNotEquals(server(plain), server(alone))
    }

    @Test
    fun `an untuned server keeps its identity across options that do not change replies`() {
        // The fields and their order as they were, written out, so no constant can move them.
        val legacy = listOf("1", "2", ServerSettings.SERVER_PROVIDER, id, "small", "fr", "en").joinToString("\u0000")
        assertEquals(legacy, server(settings()))
        val untuned = settings().copy(translationStates = mapOf(id to ServerTranslationState(model = "small", keepAlive = "10m")))
        assertEquals(legacy, server(untuned))
    }

    @Test
    fun `a model that refused the temperature keeps its pages`() {
        val plain = settings().copy(translationStates = mapOf(id to ServerTranslationState(model = "small")))
        val refused = settings().copy(translationStates = mapOf(id to ServerTranslationState(model = "small", noTemperature = true)))
        assertEquals(server(plain), server(refused))
        val warmer = settings().copy(translationStates = mapOf(id to ServerTranslationState(model = "small", temperature = 0.7)))
        assertEquals(server(warmer), server(warmer.copy(translationStates = mapOf(id to ServerTranslationState(model = "small", temperature = 0.7, noTemperature = true)))))
    }

    @Test
    fun `the earlier text sent joins the identity last, only when some is sent`() {
        val off = pageIdentity(settings(), "gemini", "flash", "fr", "en")
        assertEquals(off, pageIdentity(settings(), "gemini", "flash", "fr", "en", TranslationContextSize.OFF))
        val standard = pageIdentity(settings(), "gemini", "flash", "fr", "en", TranslationContextSize.STANDARD)
        assertEquals(off + "\u0000context=standard", standard)
        assertNotEquals(standard, pageIdentity(settings(), "gemini", "flash", "fr", "en", TranslationContextSize.LARGE))
        assertEquals("gemini", pageIdentityService(standard))
    }

    @Test
    fun `the book template joins the identity after the earlier text, only when one is sent`() {
        val off = pageIdentity(settings(), "gemini", "flash", "fr", "en", TranslationContextSize.STANDARD)
        val told = pageIdentity(settings(), "gemini", "flash", "fr", "en", TranslationContextSize.STANDARD, "Book: {title}")
        assertEquals(off + "\u0000book=" + TranslationBookContext.identity("Book: {title}"), told)
        assertNotEquals(told, pageIdentity(settings(), "gemini", "flash", "fr", "en", TranslationContextSize.STANDARD, "Title: {title}"))
        assertEquals("gemini", pageIdentityService(told))
    }

    @Test
    fun `another service is not tied to the chosen server`() {
        assertFalse(pageIdentity(settings(), "gemini", "flash", "fr", "en").contains(id))
    }
}
