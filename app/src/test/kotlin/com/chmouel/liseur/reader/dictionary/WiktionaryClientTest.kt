package com.chmouel.liseur.reader.dictionary

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

        assertEquals(DictionaryState.Failed("HTTP 503"), state)
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
}
