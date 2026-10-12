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
import org.junit.Assert.assertFalse
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
        assertEquals(0, body.getInt("temperature"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("<passage>\nLe soleil se couchait.\n</passage>", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `TranslateGemma gets its own single prompt, greedy and without context`(): Unit = runBlocking {
        server.enqueue(json(completion("Il pleut.")))

        val text = client.translate(base, null, "translategemma:4b", "en", "fr", "It rains.", context = "It was grey.")

        assertEquals("Il pleut.", text)
        val body = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals(0, body.getInt("temperature"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val content = messages.getJSONObject(0).getString("content")
        assertTrue(content, content.endsWith("into French:\n\n\nIt rains."))
        assertTrue("It was grey" !in content)
    }

    @Test
    fun `the reader's options go with the request, and only once set`(): Unit = runBlocking {
        server.enqueue(json(completion("a")))
        client.translate(base, null, "m", "en", "fr", "x")
        val plain = JSONObject(server.takeRequest().body!!.utf8())
        assertFalse(plain.has("max_tokens"))
        assertFalse(plain.has("keep_alive"))

        server.enqueue(json(completion("b")))
        client.translate(
            base, null, "m", "en", "fr", "x",
            temperature = 0.3, instructions = "Use tu.", maxTokens = 200, keepAlive = "600",
        )
        val tuned = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals(0.3, tuned.getDouble("temperature"), 0.0)
        assertEquals(200, tuned.getInt("max_tokens"))
        // Ollama reads a number as seconds but refuses a string without a unit.
        assertEquals(600, tuned.get("keep_alive"))
        val system = tuned.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system, "Use tu." in system)

        server.enqueue(json(completion("c")))
        client.translate(base, null, "translategemma:4b", "en", "fr", "x", instructions = "Use tu.", keepAlive = "10m")
        val gemma = JSONObject(server.takeRequest().body!!.utf8())
        assertEquals("10m", gemma.get("keep_alive"))
        val prompt = gemma.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt, prompt.indexOf("Use tu.") in 0 until prompt.indexOf("Please translate"))
    }

    @Test
    fun `a server that refuses the temperature is asked once more without it, and says so`(): Unit = runBlocking {
        server.enqueue(
            json(
                """{"error":{"message":"Unsupported value: 'temperature' does not support 0 with this model. Only the default (1) value is supported.","type":"invalid_request_error","param":"temperature","code":"unsupported_value"}}""",
                400,
            ),
        )
        server.enqueue(json(completion("Il pleut.")))
        var refused = 0

        val text = client.translate(base, "k", "o4-mini", "en", "fr", "It rains.", onTemperatureRefused = { refused++ })

        assertEquals("Il pleut.", text)
        assertEquals(1, refused)
        assertEquals(2, server.requestCount)
        assertEquals(0, JSONObject(server.takeRequest().body!!.utf8()).getInt("temperature"))
        val retry = JSONObject(server.takeRequest().body!!.utf8())
        assertFalse(retry.has("temperature"))
        assertEquals("o4-mini", retry.getString("model"))
    }

    @Test
    fun `a refusal that is not about the temperature is not asked again`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"messages too long"}}""", 400))
        var refused = 0
        val error = expect<TranslationError.Service> {
            runBlocking { client.translate(base, "k", "m", "en", "fr", "x", onTemperatureRefused = { refused++ }) }
        }
        assertEquals(400, error.code)
        assertEquals(1, server.requestCount)
        assertEquals(0, refused)
    }

    @Test
    fun `a retry without the temperature that fails too is not taken as the refusal`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"bad temperature"}}""", 400))
        server.enqueue(json("""{"error":{"message":"busy"}}""", 503))
        var refused = 0
        expect<TranslationError.Service> {
            runBlocking { client.translate(base, "k", "m", "en", "fr", "x", onTemperatureRefused = { refused++ }) }
        }
        assertEquals(2, server.requestCount)
        assertEquals(0, refused)
    }

    @Test
    fun `no temperature is sent when there is none, and a refusal then is not retried`(): Unit = runBlocking {
        server.enqueue(json("""{"error":{"message":"temperature is required"}}""", 400))
        expect<TranslationError.Service> { runBlocking { client.translate(base, "k", "m", "en", "fr", "x", temperature = null) } }
        assertEquals(1, server.requestCount)
        assertFalse(JSONObject(server.takeRequest().body!!.utf8()).has("temperature"))
    }

    @Test
    fun `a model the server does not have is named, and a missing route is not taken for one`(): Unit = runBlocking {
        // Ollama's.
        server.enqueue(json("""{"error":{"message":"model \"qwen9\" not found, try pulling it first","type":"api_error","param":null,"code":null}}""", 404))
        assertEquals("qwen9", expect<TranslationError.ModelNotFound> { runBlocking { client.translate(base, null, "qwen9", "en", "fr", "x") } }.model)
        // OpenAI's.
        server.enqueue(
            json(
                """{"error":{"message":"The model `gpt-9` does not exist or you do not have access to it.","type":"invalid_request_error","param":null,"code":"model_not_found"}}""",
                404,
            ),
        )
        assertEquals("gpt-9", expect<TranslationError.ModelNotFound> { runBlocking { client.translate(base, "k", "gpt-9", "en", "fr", "x") } }.model)
        // A server with no chat endpoint there.
        server.enqueue(MockResponse.Builder().code(404).body("404 page not found").build())
        assertEquals(404, expect<TranslationError.Service> { runBlocking { client.translate(base, null, "m", "en", "fr", "x") } }.code)
        // OpenRouter's 404 for a data policy names no missing model.
        server.enqueue(json("""{"error":{"message":"No endpoints found matching your data policy (Free model publication)","code":404}}""", 404))
        assertEquals(404, expect<TranslationError.Service> { runBlocking { client.translate(base, "k", "m", "en", "fr", "x") } }.code)
    }

    @Test
    fun `no key, no authorization header`(): Unit = runBlocking {
        server.enqueue(json(completion("ok")))
        client.translate(base, null, "m", null, "en", "x")
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `model discovery sends the configured key as a bearer token`(): Unit = runBlocking {
        server.enqueue(json("""{"data":[{"id":"gpt-4o-mini"}]}"""))

        assertEquals(listOf(TextModel("gpt-4o-mini")), client.textModels(base, "discovery-key"))

        val request = server.takeRequest()
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals("Bearer discovery-key", request.headers["Authorization"])
    }

    @Test
    fun `a key that cannot be a header is rejected without repeating it`(): Unit = runBlocking {
        val error = expect<TranslationError.InvalidKey> {
            runBlocking { client.translate(base, "secret\nkey", "m", "fr", "en", "x") }
        }
        assertFalse(error.message.orEmpty().contains("secret"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect does not send the key or passage to another server`(): Unit = runBlocking {
        MockWebServer().use { elsewhere ->
            elsewhere.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(
                MockResponse.Builder()
                    .code(307)
                    .addHeader("Location", elsewhere.url("/elsewhere").toString())
                    .build(),
            )

            expect<TranslationError.Service> {
                runBlocking { client.translate(base, "secret", "m", "fr", "en", "private passage") }
            }

            assertEquals(1, server.requestCount)
            assertEquals(0, elsewhere.requestCount)
        }
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
        assertEquals(emptyList<TextModel>(), client.textModels(base, null))
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
        assertEquals(
            listOf("gpt-4o-mini", "llama-3", "tts-is-in-the-name-but-writes-text"),
            OpenAiChat.textModels(list).map { it.id },
        )
        assertEquals(listOf("A", "b"), OpenAiChat.textModels("""[{"name":"b"}, "A"]""").map { it.id })
        expect<TranslationError.Malformed> { OpenAiChat.textModels("nope") }
    }

    @Test
    fun `a listed price is kept per million tokens, and a partial or varying one is dropped`() {
        val list = """{"data":[
            {"id":"paid","pricing":{"prompt":"0.00000015","completion":"0.0000006"}},
            {"id":"free","pricing":{"prompt":"0","completion":"0"}},
            {"id":"router","pricing":{"prompt":"-1","completion":"-1"}},
            {"id":"half","pricing":{"prompt":"0.000001"}},
            {"id":"plain"}
        ]}"""
        val prices = OpenAiChat.textModels(list).associate { it.id to it.price }
        val paid = prices.getValue("paid")!!
        assertEquals(0.15, paid.input, 1e-9)
        assertEquals(0.6, paid.output, 1e-9)
        assertEquals(TextPrice(0.0, 0.0), prices["free"])
        assertEquals(null, prices["router"])
        assertEquals(null, prices["half"])
        assertEquals(null, prices["plain"])
    }

    @Test
    fun `errors map to what the reader can do`() {
        assertTrue(OpenAiChat.errorFor(403, "") is TranslationError.InvalidKey)
        assertTrue(OpenAiChat.errorFor(402, "") is TranslationError.RateLimited)
        assertTrue(OpenAiChat.errorFor(503, "") is TranslationError.Service)
        // Without the model asked for, a 404 cannot name it.
        assertTrue(OpenAiChat.errorFor(404, """{"error":{"code":"model_not_found"}}""") is TranslationError.Service)
        assertTrue(OpenAiChat.errorFor(404, """{"error":{"code":"model_not_found"}}""", "m") is TranslationError.ModelNotFound)
    }
}
