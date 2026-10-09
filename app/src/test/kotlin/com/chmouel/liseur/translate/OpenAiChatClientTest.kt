package com.chmouel.liseur.translate

import com.chmouel.liseur.tts.OpenAiTts
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiChatClientTest {

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

    private val base: HttpUrl get() = OpenAiTts.baseUrl(server.url("/v1").toString())!!
    private val client = OpenAiChatClient()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun completion(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(content)}},"finish_reason":"stop"}]}"""

    private inline fun <reified E : TranslationError> expect(block: () -> Unit): E {
        try {
            block()
        } catch (e: TranslationError) {
            if (e is E) return e
            fail("expected ${E::class.simpleName}, got $e")
        }
        fail("expected ${E::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun `asks the chat endpoint with the task apart from the passage`(): Unit = runBlocking {
        server.enqueue(json(completion("The sun was setting.")))

        val text = client.translate(base, "secret", "gpt-4o-mini", "fr", "en", "Le soleil se couchait.")

        assertEquals("The sun was setting.", text)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("Bearer secret", request.headers["Authorization"])
        val body = JSONObject(request.body!!.utf8())
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("<passage>\nLe soleil se couchait.\n</passage>", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `no key, no authorization header`(): Unit = runBlocking {
        server.enqueue(json(completion("ok")))
        client.translate(base, null, "m", null, "en", "x")
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a redirect is not followed`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere").toString()).build())
        expect<TranslationError.Service> { runBlocking { client.translate(base, "secret", "m", "fr", "en", "x") } }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an oversized reply is not read`(): Unit = runBlocking {
        server.enqueue(json(completion("a".repeat(TranslationHttp.MAX_REPLY_BYTES.toInt() + 10))))
        expect<TranslationError.Malformed> { runBlocking { client.translate(base, null, "m", "fr", "en", "x") } }
    }

    @Test
    fun `refused keys and quotas say so`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad key"}}""", 401))
        expect<TranslationError.InvalidKey> { runBlocking { client.translate(base, "k", "m", "fr", "en", "x") } }
        server.enqueue(json("""{"error":{"message":"slow down"}}""", 429))
        expect<TranslationError.RateLimited> { runBlocking { client.translate(base, "k", "m", "fr", "en", "x") } }
        server.enqueue(json("{}", 500))
        assertEquals(500, expect<TranslationError.Service> { runBlocking { client.translate(base, "k", "m", "fr", "en", "x") } }.code)
    }

    @Test
    fun `a server that cannot list models is not an error`(): Unit = runBlocking {
        server.enqueue(json("", 404))
        assertEquals(emptyList<String>(), client.textModels(base, null))
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    // -- Parsing ------------------------------------------------------------

    @Test
    fun `content comes as a string or as parts`() {
        assertEquals("Hello", OpenAiChat.translation(completion(" Hello ")))
        val parts = """{"choices":[{"message":{"content":[{"type":"text","text":"Hel"},{"type":"image"},{"type":"text","text":"lo"}]}}]}"""
        assertEquals("Hello", OpenAiChat.translation(parts))
    }

    @Test
    fun `a refusal or a filtered answer is a refusal`() {
        expect<TranslationError.Refused> {
            OpenAiChat.translation("""{"choices":[{"message":{"content":null,"refusal":"I can't help with that."}}]}""")
        }
        expect<TranslationError.Refused> {
            OpenAiChat.translation("""{"choices":[{"message":{"content":""},"finish_reason":"content_filter"}]}""")
        }
    }

    @Test
    fun `an answer cut at the length limit is not a translation`() {
        expect<TranslationError.Truncated> {
            OpenAiChat.translation("""{"choices":[{"message":{"content":"The sun was"},"finish_reason":"length"}]}""")
        }
    }

    @Test
    fun `an empty answer is not a translation`() {
        expect<TranslationError.Empty> { OpenAiChat.translation(completion("  ")) }
        expect<TranslationError.Empty> { OpenAiChat.translation(completion("<passage></passage>")) }
        expect<TranslationError.Empty> { OpenAiChat.translation("""{"choices":[{"message":{"content":null}}]}""") }
    }

    @Test
    fun `anything else is malformed, and an error sent with a 200 is an error`() {
        expect<TranslationError.Malformed> { OpenAiChat.translation("<html>") }
        expect<TranslationError.Malformed> { OpenAiChat.translation("""{"choices":[]}""") }
        expect<TranslationError.Malformed> { OpenAiChat.translation("""{"choices":[{"message":{"content":3}}]}""") }
        expect<TranslationError.RateLimited> { OpenAiChat.translation("""{"error":{"code":429,"message":"busy"}}""") }
    }

    @Test
    fun `models that say they write text are kept, and the rest by name`() {
        val list = """{"data":[
            {"id":"gpt-4o-mini","architecture":{"output_modalities":["text"]}},
            {"id":"gpt-4o-mini-tts","architecture":{"output_modalities":["audio"]}},
            {"id":"tts-is-in-the-name-but-writes-text","output_modalities":["text"]},
            {"id":"llama-3"},
            {"id":"kokoro"},
            {"id":"whisper-1"},
            {"id":"text-embedding-3-small"},
            {"id":"llama-3"}
        ]}"""
        assertEquals(listOf("gpt-4o-mini", "tts-is-in-the-name-but-writes-text", "llama-3"), OpenAiChat.textModels(list))
        assertEquals(listOf("a", "b"), OpenAiChat.textModels("""["a", {"name":"b"}]"""))
        expect<TranslationError.Malformed> { OpenAiChat.textModels("nope") }
    }

    @Test
    fun `errors map to what the reader can do`() {
        assertTrue(OpenAiChat.errorFor(403, "") is TranslationError.InvalidKey)
        assertTrue(OpenAiChat.errorFor(402, "") is TranslationError.RateLimited)
        assertTrue(OpenAiChat.errorFor(503, "") is TranslationError.Service)
    }
}
