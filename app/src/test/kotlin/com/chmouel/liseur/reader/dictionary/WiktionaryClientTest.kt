package com.chmouel.liseur.reader.dictionary

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.chmouel.liseur.reader.dictionary.DictionaryInflectionTest.Companion.englishDefinition
import com.chmouel.liseur.reader.dictionary.DictionaryInflectionTest.Companion.englishForms
import com.chmouel.liseur.reader.dictionary.DictionaryInflectionTest.Companion.formLink
import com.chmouel.liseur.reader.dictionary.DictionaryInflectionTest.Companion.htmlForms
import com.chmouel.liseur.reader.dictionary.DictionaryInflectionTest.Companion.page
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * The definition client against a real socket.
 *
 * What matters here is where the request goes: F-Droid asked for the
 * dictionary host to stop being something baked into the app, so the
 * test that earns its keep is the one proving the client follows the
 * site it is given.
 */
class WiktionaryClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun baseUrl() = "http://127.0.0.1:${server.port}"

    @Test
    fun `asks the configured site, not a built-in one`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"en":[{"partOfSpeech":"Noun","definitions":[{"definition":"A cat."}]}]}""",
            ),
        )

        val state = WiktionaryClient().define("chat", listOf("en"), baseUrl())

        val request = server.takeRequest()
        assertEquals("/api/rest_v1/page/definition/chat", request.target)
        assertTrue(state is DictionaryState.Found)
        assertEquals("A cat.", (state as DictionaryState.Found).senses.single().definitions.single())
    }

    @Test
    fun `identifies itself, because Wikimedia rejects anonymous agents`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "{}"))

        WiktionaryClient().define("chat", listOf("en"), baseUrl())

        val agent = server.takeRequest().headers["User-Agent"].orEmpty()
        assertTrue(agent, agent.startsWith("Liseur/"))
        assertTrue(agent, agent.contains("github.com/chmouel/liseur"))
    }

    @Test
    fun `a missing word is an answer, not a failure`() = runBlocking {
        server.enqueue(MockResponse(code = 404))

        assertEquals(
            DictionaryState.NotFound,
            WiktionaryClient().define("qwertyuiop", listOf("en"), baseUrl()),
        )
    }

    @Test
    fun `retries a capitalised word in lowercase`() = runBlocking {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"en":[{"partOfSpeech":"Noun","definitions":[{"definition":"A group."}]}]}""",
            ),
        )

        val state = WiktionaryClient().define("Antipathies", listOf("en"), baseUrl())

        assertEquals("/api/rest_v1/page/definition/Antipathies", server.takeRequest().target)
        assertEquals("/api/rest_v1/page/definition/antipathies", server.takeRequest().target)
        assertTrue(state is DictionaryState.Found)
    }

    @Test
    fun `an entry with no senses in the wanted languages counts as missing`() = runBlocking {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"de":[{"partOfSpeech":"Substantiv","definitions":[{"definition":"Katze."}]}]}""",
            ),
        )

        assertEquals(
            DictionaryState.NotFound,
            WiktionaryClient().define("chat", listOf("en"), baseUrl()),
        )
    }

    @Test
    fun `a server error is reported rather than swallowed`() = runBlocking {
        server.enqueue(MockResponse(code = 503))

        val state = WiktionaryClient().define("chat", listOf("en"), baseUrl())

        assertEquals(DictionaryState.Failed("HTTP 503", "127.0.0.1:${server.port}"), state)
    }

    /**
     * Only en.wiktionary.org implements the definition endpoint; other
     * wikis and bare mirrors answer it with a 501. The entry page is the
     * fallback, so those hosts still define words.
     */
    @Test
    fun `falls back to the entry page when the definition endpoint is a 501`() = runBlocking {
        server.enqueue(MockResponse(code = 501, body = """{"status":501}"""))
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                    <html><body><section><h2>Français</h2>
                    <section><h3>Nom commun</h3><ol><li>Un félin.</li></ol></section>
                    </section></body></html>
                """.trimIndent(),
            ),
        )

        val state = WiktionaryClient().define("chat", listOf("fr"), baseUrl())

        assertEquals("/api/rest_v1/page/definition/chat", server.takeRequest().target)
        assertEquals("/api/rest_v1/page/html/chat", server.takeRequest().target)
        assertTrue(state is DictionaryState.Found)
        assertEquals("Un félin.", (state as DictionaryState.Found).senses.single().definitions.single())
    }

    @Test
    fun `a probe passes when the site answers, even with nothing found`() = runBlocking {
        server.enqueue(MockResponse(code = 404))

        assertNull(WiktionaryClient().probe(baseUrl()))
    }

    @Test
    fun `a probe fails with the reason when the site does not answer`() = runBlocking {
        server.enqueue(MockResponse(code = 503))

        assertEquals("HTTP 503", WiktionaryClient().probe(baseUrl()))
    }

    @Test
    fun `an empty selection never reaches the network`() = runBlocking {
        assertEquals(
            DictionaryState.NotFound,
            WiktionaryClient().define("  ", listOf("en"), baseUrl()),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a book's language is asked for first, with English behind it`() {
        assertEquals(listOf("fr", "en"), WiktionaryClient.languagesFor("fr-FR"))
        assertEquals(listOf("en"), WiktionaryClient.languagesFor("en"))
        assertEquals(listOf("en"), WiktionaryClient.languagesFor(null))
    }

    @Test
    fun `harnesses gets noun and verb meanings from one base entry request`() = runBlocking {
        server.enqueue(MockResponse(body = englishForms()))
        server.enqueue(MockResponse(body = """
            {"en":[
                {"partOfSpeech":"Noun","definitions":[{"definition":"A set of straps."}]},
                {"partOfSpeech":"Verb","definitions":[{"definition":"To put to use."}]}
            ],"fr":[{"partOfSpeech":"Noun","definitions":[{"definition":"Wrong language."}]}]}
        """.trimIndent()))

        val found = WiktionaryClient().define("harnesses", listOf("en"), baseUrl()) as DictionaryState.Found

        assertEquals("plural of harness", found.senses[0].definitions.single())
        assertEquals(listOf("A set of straps."), found.senses[0].resolved[0]!!.senses.single().definitions)
        assertEquals(listOf("To put to use."), found.senses[1].resolved[0]!!.senses.single().definitions)
        assertTrue(found.senses.all { it.resolved[0]!!.problem == null })
        assertEquals("/api/rest_v1/page/definition/harnesses", server.takeRequest().target)
        assertEquals("/api/rest_v1/page/definition/harness", server.takeRequest().target)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `HTML editions resolve plurals and conjugations through mirror fallback`() = runBlocking {
        for (fixture in htmlForms) {
            val before = server.requestCount
            server.enqueue(MockResponse(code = 501))
            server.enqueue(MockResponse(body = fixture.html))
            server.enqueue(MockResponse(code = 501))
            val heading = when (fixture.language) {
                "fr" -> "Français"
                "es" -> "Español"
                else -> "Nederlands"
            }
            val part = when (fixture.language to fixture.part) {
                "fr" to "noun" -> "Nom commun"
                "fr" to "verb" -> "Verbe"
                "es" to "noun" -> "Sustantivo masculino"
                "es" to "verb" -> "Verbo intransitivo"
                "nl" to "noun" -> "Zelfstandig naamwoord"
                else -> "Werkwoord"
            }
            server.enqueue(MockResponse(body = page(fixture.language, heading, part,
                "<ol><li>Meaning of ${fixture.base}.</li></ol>")))
            val state = WiktionaryClient().define(fixture.word, listOf(fixture.language), baseUrl()) as DictionaryState.Found
            val resolved = state.senses.single().resolved[0]!!
            assertNull(fixture.word, resolved.problem)
            assertEquals(listOf("Meaning of ${fixture.base}."), resolved.senses.single().definitions)
            assertEquals(before + 4, server.requestCount)
            repeat(4) { server.takeRequest() }
        }
    }

    @Test
    fun `mixed senses survive and base references are not followed again`() = runBlocking {
        server.enqueue(MockResponse(body = englishDefinition(formLink("child")).replace(
            "\"definitions\":[", "\"definitions\":[{\"definition\":\"An independent meaning.\"},",
        )))
        server.enqueue(MockResponse(body = englishDefinition(formLink("person"))))
        val state = WiktionaryClient().define("children", listOf("en"), baseUrl()) as DictionaryState.Found
        assertEquals("An independent meaning.", state.senses.single().definitions[0])
        assertEquals(DefinitionProblem.UNRESOLVED, state.senses.single().resolved[1]!!.problem)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `missing and failed base lookups preserve the original answer`() = runBlocking {
        for ((code, problem) in listOf(404 to DefinitionProblem.MISSING, 503 to DefinitionProblem.FAILED)) {
            server.enqueue(MockResponse(body = englishDefinition(formLink("harness"))))
            server.enqueue(MockResponse(code = code))
            val found = WiktionaryClient().define("harnesses", listOf("en"), baseUrl()) as DictionaryState.Found
            assertEquals("plural of harness", found.senses.single().definitions.single())
            assertEquals(problem, found.senses.single().resolved[0]!!.problem)
        }
    }

    @Test
    fun `self references and probe do not cause follow-up requests`() = runBlocking {
        server.enqueue(MockResponse(body = englishDefinition(formLink("harnesses"))))
        val found = WiktionaryClient().define("harnesses", listOf("en"), baseUrl()) as DictionaryState.Found
        assertEquals(DefinitionProblem.UNRESOLVED, found.senses.single().resolved[0]!!.problem)
        server.enqueue(MockResponse(body = englishDefinition(formLink("volume"))))
        assertNull(WiktionaryClient().probe(baseUrl()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `follow-ups have a four request budget including endpoint fallback`() = runBlocking {
        val definitions = (1..4).joinToString(",") {
            """{"definition":${JSONObject.quote(formLink("base$it"))}}"""
        }
        server.enqueue(MockResponse(body = """{"en":[{"partOfSpeech":"Noun","definitions":[$definitions]}]}"""))
        repeat(2) {
            server.enqueue(MockResponse(code = 501))
            server.enqueue(MockResponse(body = page("en", "English", "Noun", "<ol><li>A meaning.</li></ol>")))
        }
        val found = WiktionaryClient().define("forms", listOf("en"), baseUrl()) as DictionaryState.Found
        assertEquals(5, server.requestCount)
        val resolved = found.senses.single().resolved.values.toList()
        assertTrue(resolved.take(2).all { it.problem == null })
        assertTrue(resolved.drop(2).all { it.problem == DefinitionProblem.LIMIT })
    }

    @Test
    fun `base lookup redirects cannot send words to another origin`() = runBlocking {
        MockWebServer().use { other ->
            other.start(InetAddress.getByName("127.0.0.1"), 0)
            other.enqueue(MockResponse(body = englishDefinition("Unexpected request.")))
            server.enqueue(MockResponse(body = englishDefinition(formLink("harness"))))
            server.enqueue(MockResponse.Builder().code(302)
                .addHeader("Location", other.url("/leaked-word").toString()).build())
            val found = WiktionaryClient().define("harnesses", listOf("en"), baseUrl()) as DictionaryState.Found
            assertEquals(DefinitionProblem.FAILED, found.senses.single().resolved[0]!!.problem)
            assertEquals(0, other.requestCount)
        }
    }

    @Test
    fun `automatic HTTP retries also consume the follow-up budget`() = runBlocking {
        val definitions = (1..3).joinToString(",") {
            """{"definition":${JSONObject.quote(formLink("base$it"))}}"""
        }
        server.enqueue(MockResponse(body = """{"en":[{"partOfSpeech":"Noun","definitions":[$definitions]}]}"""))
        repeat(4) {
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
        }
        val found = WiktionaryClient().define("forms", listOf("en"), baseUrl()) as DictionaryState.Found
        assertEquals(5, server.requestCount)
        assertEquals(DefinitionProblem.LIMIT, found.senses.single().resolved[2]!!.problem)
    }

    @Test
    fun `cancelling a lookup cancels its base-word HTTP call`() = runBlocking {
        val client = OkHttpClient()
        server.enqueue(MockResponse(body = englishDefinition(formLink("harness"))))
        server.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).body("{}").build())
        val lookup = async(Dispatchers.IO) { WiktionaryClient(client).define("harnesses", listOf("en"), baseUrl()) }
        assertEquals("/api/rest_v1/page/definition/harnesses", server.takeRequest(5, TimeUnit.SECONDS)?.target)
        assertEquals("/api/rest_v1/page/definition/harness", server.takeRequest(5, TimeUnit.SECONDS)?.target)
        withTimeout(2_000) { lookup.cancelAndJoin() }
        assertTrue(lookup.isCancelled)
        assertTrue(client.dispatcher.runningCalls().all { it.isCanceled() })
    }

    @Test
    fun `the initial definition is published before any base-word request`() = runBlocking {
        server.enqueue(MockResponse(body = englishDefinition(formLink("harness"))))
        server.enqueue(MockResponse(body = englishDefinition("A set of straps.")))
        var published = false
        val found = WiktionaryClient().define("harnesses", listOf("en"), baseUrl()) { initial ->
            assertEquals(1, server.requestCount)
            assertEquals("plural of harness", initial.senses.single().definitions.single())
            assertTrue(initial.senses.single().resolved.isEmpty())
            published = true
        } as DictionaryState.Found
        assertTrue(published)
        assertEquals("A set of straps.", found.senses.single().resolved[0]!!.senses.single().definitions.single())
    }

    @Test
    fun `hidden definitions never consume requests or display unrelated base meanings`() = runBlocking {
        val visible = (1..4).joinToString(",") { """{"definition":"Meaning $it."}""" }
        val hidden = JSONObject.quote(formLink("harness"))
        server.enqueue(MockResponse(body =
            """{"en":[{"partOfSpeech":"Noun","definitions":[$visible,{"definition":$hidden}]}]}"""))
        val found = WiktionaryClient().define("harnesses", listOf("en"), baseUrl()) as DictionaryState.Found
        assertEquals(1, server.requestCount)
        assertEquals(5, found.senses.single().definitions.size)
        assertTrue(found.senses.single().resolved.isEmpty())
    }
}
